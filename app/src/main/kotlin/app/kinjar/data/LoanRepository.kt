package app.kinjar.data

import app.cash.sqldelight.coroutines.asFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import app.kinjar.data.db.KinjarDb
import app.kinjar.domain.*
import java.time.LocalDate

data class LoanBook(
    val loans: List<Loan>,
    val payments: List<LoanPayment>,
    val adjustments: List<LoanAdjustment>,
    val transactions: List<Transaction>,
    val receipts: Map<String, String> = emptyMap(),
) {
    val transactionsById: Map<String, Transaction> by lazy { transactions.associateBy { it.id } }

    fun balance(loan: Loan, today: LocalDate): LoanBalance = LoanCalculator.balance(loan, payments, adjustments, transactionsById, today)

    /** Newest first; links whose expense no longer exists come first because they need removal. */
    fun history(loanId: String): List<Pair<LoanPayment, Transaction?>> =
        payments.filter { it.loanId == loanId }.map { it to transactionsById[it.transactionId] }
            .sortedWith(compareBy<Pair<LoanPayment, Transaction?>> { it.second != null }.thenByDescending { it.second?.date })

    fun linkCandidates(loan: Loan, today: LocalDate): List<Transaction> {
        val linked = payments.mapTo(HashSet()) { it.transactionId }
        return transactions.filter { it.type == TransactionType.EXPENSE && it.id !in linked && it.date >= loan.start && it.date <= today }
            .sortedByDescending { it.date }
    }

    fun receipt(loanId: String): Transaction? = receipts[loanId]?.let(transactionsById::get)
}

class LoanRepository(private val db: KinjarDb, private val store: EventStore, private val clock: Clock) {
    val book: Flow<LoanBook> = db.loanQueries.observe().asFlow().map {
        db.transactionWithResult { readBook() }
    }.flowOn(Dispatchers.IO)

    private suspend fun <T> read(block: () -> T): T = withContext(Dispatchers.IO) { store.read(block) }

    private fun readBook() = LoanBook(
        db.loanQueries.all().executeAsList().map { EventJson.decodeFromString<Loan>(it.payload).copy(archived = it.archived != 0L) },
        db.loanPaymentQueries.all().executeAsList().mapNotNull { it.payload?.let { json -> EventJson.decodeFromString<LoanPayment>(json) } },
        db.loanAdjustmentQueries.all().executeAsList().map { EventJson.decodeFromString<LoanAdjustment>(it.payload) },
        db.txQueries.all().executeAsList().map { row ->
            Transaction(row.id, TransactionType.valueOf(row.type), Money.of(row.amount, Currency.valueOf(row.currency)), row.account_id,
                row.to_account_id, row.category_id, LocalDate.parse(row.date), row.note, row.merchant, row.paid_by, TransactionSource.valueOf(row.source))
        },
        db.loanReceiptQueries.all().executeAsList().associate { it.loan_id to it.tx_id },
    )

    private fun loan(id: String): Loan = db.loanQueries.byId(id).executeAsOneOrNull()?.let {
        EventJson.decodeFromString<Loan>(it.payload).copy(archived = it.archived != 0L)
    } ?: error("Loan not found")

    private fun activePayment(id: String): LoanPayment? = db.loanPaymentQueries.byId(id).executeAsOneOrNull()
        ?.takeIf { it.removed == 0L }?.payload?.let { EventJson.decodeFromString<LoanPayment>(it) }

    internal fun requireUnlinked(transactionId: String) {
        require(db.loanPaymentQueries.forTransaction(transactionId).executeAsOneOrNull() == null &&
            db.loanReceiptQueries.forTransaction(transactionId).executeAsOneOrNull() == null) { "Use loan payment actions" }
    }

    private fun validateCash(tx: Transaction) {
        val account = db.accountQueries.byId(tx.accountId).executeAsOneOrNull()
        require(account != null && account.archived == 0L && account.currency == tx.money.currency.name) { "Account no longer available" }
        require(tx.date <= clock.today()) { "Payment has not happened yet" }
        if (tx.categoryId != null) require(db.categoryQueries.active().executeAsList().any { it.id == tx.categoryId })
    }

    /** True when an identical create already landed, so a retry appends nothing; different content under the same IDs is rejected. */
    private fun created(loan: Loan, receipt: Transaction?): Boolean {
        db.loanQueries.byId(loan.id).executeAsOneOrNull() ?: return false
        require(loan(loan.id) == loan)
        val link = db.loanReceiptQueries.all().executeAsList().find { it.loan_id == loan.id }
        require(link?.tx_id == receipt?.id)
        if (receipt != null) require(readBook().transactionsById[receipt.id] == receipt)
        return true
    }

    suspend fun create(loan: Loan, receipt: Transaction? = null) {
        if (read { created(loan, receipt) }) return
        val events = listOf(LoanCreated(loan)) + if (receipt == null) emptyList() else listOf(
            TransactionAdded(receipt), LoanReceiptLinked(loan.id, loan.id, receipt.id),
        )
        store.appendAll(events, skipIf = { created(loan, receipt) }) {
            require(!loan.archived && loan.start <= clock.today())
            if (receipt != null) {
                validateCash(receipt)
                require(receipt.type == TransactionType.LOAN_RECEIPT && receipt.source == TransactionSource.MANUAL && receipt.date == loan.start)
                require(!loan.opening.isZero)
                requireUnlinked(receipt.id)
                require(readBook().transactionsById[receipt.id] == null)
            }
        }
    }

    suspend fun update(value: Loan) {
        if (read { loan(value.id) } == value) return
        store.append(LoanUpdated(value)) {
            val old = loan(value.id)
            require(!old.archived && old.opening == value.opening && old.start == value.start && !value.archived)
        }
    }

    suspend fun archive(id: String) {
        if (read { loan(id).archived }) return
        store.append(LoanArchived(id)) { loan(id) }
    }

    private fun recorded(payment: LoanPayment, transaction: Transaction): Boolean {
        val prior = db.loanPaymentQueries.byId(payment.id).executeAsOneOrNull() ?: return false
        require(prior.removed == 0L && prior.payload?.let { EventJson.decodeFromString<LoanPayment>(it) } == payment &&
            readBook().transactionsById[transaction.id] == transaction)
        return true
    }

    suspend fun record(payment: LoanPayment, transaction: Transaction, linkExisting: Boolean = false) {
        if (read { recorded(payment, transaction) }) return
        val events = (if (linkExisting) emptyList() else listOf(TransactionAdded(transaction))) + LoanPaymentAdded(payment)
        store.appendAll(events, skipIf = { recorded(payment, transaction) }) {
            val target = loan(payment.loanId)
            require(!target.archived && transaction.type == TransactionType.EXPENSE && transaction.id == payment.transactionId)
            require(transaction.date >= target.start && transaction.date <= clock.today())
            requireUnlinked(transaction.id)
            val book = readBook()
            val existingTx = book.transactionsById[transaction.id]
            if (linkExisting) require(existingTx == transaction) else {
                require(existingTx == null)
                validateCash(transaction)
            }
            validateBalance(target, book.copy(payments = book.payments + payment,
                transactions = book.transactions.filterNot { it.id == transaction.id } + transaction))
        }
    }

    /** Allowed on archived loans: fixing a split corrects history without moving money. */
    suspend fun split(paymentId: String, principal: Money?) {
        val old = read { activePayment(paymentId) } ?: error("Payment not found")
        val changed = old.copy(principal = principal)
        if (changed == old) return
        store.append(LoanPaymentUpdated(changed)) {
            require(activePayment(old.id) == old) { "Payment changed" }
            val current = readBook()
            validateBalance(loan(old.loanId), current.copy(payments = current.payments.map { if (it.id == old.id) changed else it }))
        }
    }

    // Removing a link only raises debt, so it can never introduce a conflict and stays possible while history is invalid.
    suspend fun removePayment(paymentId: String, deleteExpense: Boolean) {
        val row = read { db.loanPaymentQueries.byId(paymentId).executeAsOneOrNull() } ?: error("Payment not found")
        if (row.removed != 0L) return
        val old = EventJson.decodeFromString<LoanPayment>(requireNotNull(row.payload))
        val events = listOf(LoanPaymentRemoved(paymentId)) + if (deleteExpense) listOf(TransactionDeleted(old.transactionId)) else emptyList()
        store.appendAll(events) {
            require(activePayment(old.id) == old) { "Payment changed" }
            require(!loan(old.loanId).archived) { "Archived loan history is read-only" }
        }
    }

    private fun adjusted(adjustment: LoanAdjustment): Boolean {
        val prior = db.loanAdjustmentQueries.byId(adjustment.id).executeAsOneOrNull() ?: return false
        require(EventJson.decodeFromString<LoanAdjustment>(prior.payload) == adjustment)
        return true
    }

    suspend fun adjust(adjustment: LoanAdjustment) {
        if (read { adjusted(adjustment) }) return
        store.appendAll(listOf(LoanPrincipalAdjusted(adjustment)), skipIf = { adjusted(adjustment) }) {
            val target = loan(adjustment.loanId)
            require(!target.archived && adjustment.date >= target.start && adjustment.date <= clock.today())
            val current = readBook()
            require(current.balance(target, clock.today()).complete) { "Complete payment splits first" }
            validateBalance(target, current.copy(adjustments = current.adjustments + adjustment))
        }
    }

    private fun receipt(loanId: String): Transaction {
        val link = db.loanReceiptQueries.all().executeAsList().find { it.loan_id == loanId } ?: error("Loan has no receipt")
        return readBook().transactionsById[link.tx_id] ?: error("Receipt missing")
    }

    /**
     * Fixes the account or actual amount credited when borrowing was entered wrong. Opening principal is the
     * debt and stays as entered; a wrong principal is fixed with a balance correction instead.
     */
    suspend fun correctReceipt(loanId: String, accountId: String, amount: Money) {
        require(!amount.isZero && !amount.isNegative) { "Receipt amount must be positive" }
        val old = read { receipt(loanId) }
        val corrected = old.copy(accountId = accountId, money = amount)
        if (corrected == old) return
        store.append(TransactionEdited(corrected)) {
            require(!loan(loanId).archived) { "Archived loan history is read-only" }
            require(receipt(loanId) == old) { "Receipt changed" }
            validateCash(corrected)
        }
    }

    private fun validateBalance(loan: Loan, book: LoanBook) {
        require(!book.balance(loan, clock.today()).invalidHistory) { "Payment exceeds principal or conflicts with loan history" }
    }
}
