package app.kinjar.domain

import java.time.LocalDate

class FinancialIntegrity private constructor(
    private val accounts: MutableMap<String, Account>,
    private val transactions: MutableMap<String, Transaction>,
    private val loans: MutableMap<String, Loan>,
    private val payments: MutableMap<String, LoanPayment>,
    private val adjustments: MutableMap<String, LoanAdjustment>,
    private val receipts: MutableMap<String, Receipt>,
    private val removedPayments: MutableSet<String>,
    private val deletions: MutableMap<String, String>,
    private var projectIntegrity: ProjectIntegrity = ProjectIntegrity(),
) {
    constructor() : this(mutableMapOf(), mutableMapOf(), mutableMapOf(), mutableMapOf(), mutableMapOf(), mutableMapOf(), mutableSetOf(), mutableMapOf())

    fun applying(events: List<Event>, strict: Boolean): FinancialIntegrity {
        val next = FinancialIntegrity(accounts.toMutableMap(), transactions.toMutableMap(), loans.toMutableMap(), payments.toMutableMap(),
            adjustments.toMutableMap(), receipts.toMutableMap(), removedPayments.toMutableSet(), deletions.toMutableMap(), projectIntegrity.copy())
        events.forEach { next.apply(it, strict) }
        next.projectIntegrity.apply(events, strict, next.transactions)
        if (strict) next.validate()
        return next
    }

    /** Rollback only touched entries when a command fails, rather than copying the entire financial history. */
    fun applyAtomically(events: List<Event>, strict: Boolean) {
        applyAtomically(events, strict, emptyMap())
    }

    fun applyRecords(records: List<OperationEvent>, strict: Boolean) {
        applyAtomically(records.map { it.event }, strict, records.indices.associateWith { records[it].id })
    }

    private fun applyAtomically(events: List<Event>, strict: Boolean, ids: Map<Int, String>) {
        val undo = mutableListOf<() -> Unit>()
        fun <T> remember(map: MutableMap<String, T>, id: String) {
            val before = map[id]
            undo += { if (before == null) map.remove(id) else map[id] = before; Unit }
        }
        val changedTransactions = mutableSetOf<String>()
        var changedAccounts = false
        events.forEach { event ->
            when (event) {
                is AccountCreated -> { remember(accounts, event.account.id); changedAccounts = true }
                is AccountUpdated -> { remember(accounts, event.account.id); changedAccounts = true }
                is AccountArchived -> { remember(accounts, event.accountId); changedAccounts = true }
                is TransactionAdded -> {
                    remember(transactions, event.transaction.id)
                    changedTransactions += event.transaction.id
                    event.receipt?.let { remember(receipts, it.id) }
                }
                is TransactionEdited -> { remember(transactions, event.transaction.id); changedTransactions += event.transaction.id }
                is TransactionDeleted -> {
                    remember(transactions, event.transactionId)
                    remember(deletions, event.transactionId)
                    changedTransactions += event.transactionId
                }
                is LoanCreated -> remember(loans, event.loan.id)
                is LoanUpdated -> remember(loans, event.loan.id)
                is LoanArchived -> remember(loans, event.loanId)
                is LoanPaymentAdded -> remember(payments, event.payment.id)
                is LoanPaymentUpdated -> remember(payments, event.payment.id)
                is LoanPaymentRemoved -> {
                    remember(payments, event.paymentId)
                    val wasRemoved = event.paymentId in removedPayments
                    undo += { if (!wasRemoved) removedPayments.remove(event.paymentId); Unit }
                }
                is LoanPrincipalAdjusted -> remember(adjustments, event.adjustment.id)
                else -> Unit
            }
        }
        try {
            events.forEachIndexed { index, event -> apply(event, strict, ids[index]) }
            if (strict) {
                validateTransactions(if (changedAccounts) transactions.values else changedTransactions.mapNotNull(transactions::get))
                validateLoans()
            }
            if (events.any { it is ProjectSaved || it is ProjectCategorySaved || it is ProjectCostSaved || it is ProjectCostRemoved }) {
                val updated = projectIntegrity.copy()
                updated.apply(events, strict, transactions)
                projectIntegrity = updated
            }
        } catch (failure: Throwable) {
            undo.asReversed().forEach { it() }
            throw failure
        }
    }

    fun validate() {
        validateTransactions(transactions.values)
        validateLoans()
        projectIntegrity.validate()
    }

    private fun validateTransactions(values: Collection<Transaction>) {
        values.forEach { tx ->
            require(accounts[tx.accountId]?.currency == tx.money.currency) { "Transaction account currency mismatch" }
            tx.toAccountId?.let { require(accounts[it]?.currency == (tx.received ?: tx.money).currency) { "Transfer account currency mismatch" } }
        }
    }

    private fun validateLoans() {
        payments.values.forEach { require(it.loanId in loans) { "Unknown loan" } }
        adjustments.values.forEach { require(it.loanId in loans) { "Unknown loan adjustment" } }
        loans.values.forEach { loan ->
            require(!LoanCalculator.balance(loan, payments.values.toList(), adjustments.values.toList(), transactions, LocalDate.MAX).invalidHistory) { "Invalid loan history" }
        }
    }

    private fun apply(event: Event, strict: Boolean, id: String? = null) {
        when (event) {
            is AccountCreated -> accounts.putIfAbsent(event.account.id, event.account)
            is AccountUpdated -> accounts[event.account.id]?.let { accounts[it.id] = event.account.copy(archived = it.archived) }
            is AccountArchived -> accounts[event.accountId]?.let { accounts[it.id] = it.copy(archived = true) }
            is TransactionAdded -> {
                if (strict) require(transactions[event.transaction.id]?.let { it == event.transaction } != false) { "Conflicting duplicate transaction" }
                val prior = event.receipt?.let { receipts[it.id] }
                if (strict && prior != null) require(prior == event.receipt) { "Conflicting duplicate receipt" }
                val restoring = event.receiptDeletionId != null && event.receiptDeletionId == deletions[event.transaction.id] &&
                    event.transaction.id !in transactions
                if (prior == null || restoring) {
                    if (strict) require(accounts[event.transaction.accountId]?.archived == false) { "Archived transaction account" }
                    event.receipt?.let { receipts[it.id] = it }
                    transactions.putIfAbsent(event.transaction.id, event.transaction)
                }
            }
            is TransactionEdited -> {
                if (strict) require(event.transaction.id in transactions) { "Transaction was deleted" }
                if (event.transaction.id in transactions) transactions[event.transaction.id] = event.transaction
            }
            is TransactionDeleted -> {
                transactions.remove(event.transactionId)
                if (id != null) deletions[event.transactionId] = id else deletions.remove(event.transactionId)
            }
            is LoanCreated -> loans.putIfAbsent(event.loan.id, event.loan)
            is LoanUpdated -> loans[event.loan.id]?.let {
                if (strict) require(event.loan.opening == it.opening && event.loan.start == it.start)
                loans[it.id] = event.loan.copy(archived = it.archived)
            }
            is LoanArchived -> loans[event.loanId]?.let { loans[it.id] = it.copy(archived = true) }
            is LoanPaymentAdded -> if (event.payment.id !in removedPayments) {
                if (strict) require(payments.values.none { it.transactionId == event.payment.transactionId && it.id != event.payment.id })
                payments.putIfAbsent(event.payment.id, event.payment)
            }
            is LoanPaymentUpdated -> payments[event.payment.id]?.let {
                if (strict) require(it.loanId == event.payment.loanId && it.transactionId == event.payment.transactionId && it.total == event.payment.total)
                payments[event.payment.id] = event.payment
            }
            is LoanPaymentRemoved -> { payments.remove(event.paymentId); removedPayments += event.paymentId }
            is LoanPrincipalAdjusted -> adjustments.putIfAbsent(event.adjustment.id, event.adjustment)
            else -> Unit
        }
    }
}
