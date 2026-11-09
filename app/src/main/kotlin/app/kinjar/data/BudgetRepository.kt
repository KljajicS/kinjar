package app.kinjar.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import app.kinjar.data.db.Account as AccountRow
import app.kinjar.data.db.KinjarDb
import app.kinjar.data.db.Category as CategoryRow
import app.kinjar.data.db.Member as MemberRow
import app.kinjar.data.db.Goal as GoalRow
import app.kinjar.data.db.Recent
import app.kinjar.data.db.Recurring_rule as RuleRow
import app.kinjar.data.db.Tx as TxRow
import app.kinjar.domain.Account
import app.kinjar.domain.AccountArchived
import app.kinjar.domain.AccountCreated
import app.kinjar.domain.AccountKind
import app.kinjar.domain.AccountUpdated
import app.kinjar.domain.AccountsReordered
import app.kinjar.domain.Bill
import app.kinjar.domain.BillCalculator
import app.kinjar.domain.BillSkipped
import app.kinjar.domain.Cadence
import app.kinjar.domain.Goal
import app.kinjar.domain.GoalArchived
import app.kinjar.domain.GoalCalculator
import app.kinjar.domain.GoalSet
import app.kinjar.domain.GoalsReordered
import app.kinjar.domain.RecurringRule
import app.kinjar.domain.RecurringRuleArchived
import app.kinjar.domain.RecurringRuleSet
import app.kinjar.domain.CategoryArchived
import app.kinjar.domain.CategoryUpdated
import app.kinjar.domain.MemberRenamed
import app.kinjar.domain.Category
import app.kinjar.domain.CategoryCreated
import app.kinjar.domain.CategoryKind
import app.kinjar.domain.Currency
import app.kinjar.domain.HomeCalculator
import app.kinjar.domain.HomeSummary
import app.kinjar.domain.Member
import app.kinjar.domain.MemberCreated
import app.kinjar.domain.Money
import app.kinjar.domain.ExchangeRateSnapshot
import app.kinjar.data.rates.RateRepository
import app.kinjar.domain.Receipt
import app.kinjar.domain.Transaction
import app.kinjar.domain.TransactionAdded
import app.kinjar.domain.TransactionDeleted
import app.kinjar.domain.TransactionEdited
import app.kinjar.domain.TransactionSource
import app.kinjar.domain.TransactionType
import app.kinjar.domain.newId
import java.time.LocalDate

data class RecentTx(val transaction: Transaction, val categoryName: String?, val paidByName: String?, val categoryIcon: String? = null)
/** Another active category of the same kind already has this name, ignoring case and surrounding spaces. */
class DuplicateCategoryName : IllegalArgumentException("Category name already used")
class DuplicateReceipt : IllegalStateException("Receipt already saved")
class InvalidReceiptAccount : IllegalStateException("Receipt requires an active RSD account")
class InvalidReceiptCategory : IllegalStateException("Category no longer available")
/** The stored transaction no longer matches the one the editor showed; saving would overwrite a change it never saw. */
class StaleTransaction : IllegalStateException("Transaction changed; reload it")

class BudgetRepository(
    private val db: KinjarDb,
    private val store: EventStore,
    private val clock: Clock,
    private val deviceId: String = "",
    sharingScope: CoroutineScope? = null,
    val exchangeRates: RateRepository = RateRepository(db, store, clock),
) {
    val loans = LoanRepository(db, store, clock)
    /** Active accounts in the family's saved order; pickers and Home use the same order. */
    val accounts: Flow<List<Account>> =
        db.accountQueries.active().asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map { it.toDomain() } }.flowOn(Dispatchers.Default)

    /** Active categories of both kinds. */
    val categories: Flow<List<Category>> =
        db.categoryQueries.active().asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map { it.toDomain() } }.flowOn(Dispatchers.Default)
    val expenseCategories: Flow<List<Category>> = categories.map { all -> all.filter { it.kind == CategoryKind.EXPENSE } }
    val incomeCategories: Flow<List<Category>> = categories.map { all -> all.filter { it.kind == CategoryKind.INCOME } }

    /** Archived rows included: history screens need the names and balances of things that no longer exist. */
    val allAccounts: Flow<List<Account>> =
        db.accountQueries.all().asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map { it.toDomain() } }.flowOn(Dispatchers.Default)

    val allCategories: Flow<List<Category>> =
        db.categoryQueries.all().asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map { it.toDomain() } }.flowOn(Dispatchers.Default)

    val members: Flow<List<Member>> =
        db.memberQueries.all().asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map { it.toDomain() } }.flowOn(Dispatchers.Default)

    val transactions: Flow<List<Transaction>> =
        db.txQueries.all().asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map { it.toDomain() } }.flowOn(Dispatchers.Default)
            .let { source -> if (sharingScope == null) source else source.shareReads(sharingScope) }

    fun recent(limit: Int): Flow<List<RecentTx>> =
        db.txQueries.recent(limit.toLong()).asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map { it.toRecent() } }.flowOn(Dispatchers.Default)

    /** Re-reads the date once a minute so a screen left open over midnight on the 1st moves to the new month. */
    private val today: Flow<LocalDate> = flow {
        while (true) {
            emit(clock.today())
            delay(60_000)
        }
    }.distinctUntilChanged()

    val rules: Flow<List<RecurringRule>> =
        db.recurringRuleQueries.active().asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map { it.toDomain() } }.flowOn(Dispatchers.Default)

    private val skips: Flow<Set<Pair<String, String>>> =
        db.billSkipQueries.all().asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map { it.rule_id to it.due_period }.toSet() }

    /** Active goals in priority order: savings fill the first one before the next. */
    val goals: Flow<List<Goal>> =
        db.goalQueries.active().asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map { it.toDomain() } }.flowOn(Dispatchers.Default)

    private val financialInputs = combine(today, allAccounts, transactions, exchangeRates.snapshots) { day, accs, txs, rates ->
        FinancialInputs(day, accs, txs, rates)
    }
    private val commitments = combine(rules, skips, goals) { rs, ss, gs -> Commitments(rs, ss, gs) }

    val financial: Flow<FinancialSnapshot> = financialInputs.combine(commitments) { input, commitments ->
        val (day, allAccs, txs, exchange) = input
        val rates = exchange.rates
        val active = allAccs.filterNot { it.archived }
        val bills = BillCalculator.upcoming(day, commitments.rules, active.map { it.id }.toSet(), txs, commitments.skips)
        val home = HomeCalculator.summarize(day, allAccs, txs, rates)
        val goals = GoalCalculator.progress(commitments.goals, home, rates)
        val balanceCurrencies = home.balances.values.groupBy { it.currency }
            .filterValues { amounts -> amounts.sumOf { it.amount }.signum() != 0 }.keys
        val missing = (balanceCurrencies + txs.map { it.money.currency } + bills.map { it.rule.money.currency } +
            commitments.goals.map { it.target.currency }).filter { rates.rate(it) == null }.toSet()
        FinancialSnapshot(day, allAccs, txs, bills, goals, home, exchange, missing)
    }.flowOn(Dispatchers.Default).let { source ->
        if (sharingScope == null) source else source.shareReads(sharingScope)
    }

    val home: Flow<HomeSummary> = financial.map { it.home }
    val upcomingBills: Flow<List<Bill>> = financial.map { it.bills }

    private data class FinancialInputs(val day: LocalDate, val accounts: List<Account>, val transactions: List<Transaction>, val rates: ExchangeRateSnapshot)
    private data class Commitments(val rules: List<RecurringRule>, val skips: Set<Pair<String, String>>, val goals: List<Goal>)

    suspend fun goal(id: String): Goal? = withContext(Dispatchers.IO) { db.goalQueries.byId(id).executeAsOneOrNull()?.toDomain() }
    suspend fun setGoal(goal: Goal) { store.append(GoalSet(goal)) }
    suspend fun archiveGoal(id: String) { store.append(GoalArchived(id)) }
    suspend fun reorderGoals(ids: List<String>) { store.append(GoalsReordered(ids)) }

    suspend fun rule(id: String): RecurringRule? = withContext(Dispatchers.IO) { db.recurringRuleQueries.byId(id).executeAsOneOrNull()?.toDomain() }
    suspend fun setRule(rule: RecurringRule) {
        store.append(RecurringRuleSet(rule)) {
            // Bills are paid as expenses; a rule whose category was archived may still be edited.
            require(db.categoryQueries.byId(rule.categoryId).executeAsOneOrNull()?.kind != CategoryKind.INCOME.name) { "Bills need an expense category" }
            val previous = db.recurringRuleQueries.byId(rule.id).executeAsOneOrNull()
            require(previous == null || (previous.cadence == rule.cadence.name && previous.first_due == rule.firstDue.toString())) {
                "Existing recurring schedules cannot change"
            }
        }
    }
    suspend fun archiveRule(id: String) { store.append(RecurringRuleArchived(id)) }
    suspend fun skipBill(ruleId: String, period: String) { store.append(BillSkipped(ruleId, period)) }

    suspend fun currentMember(): Member? = withContext(Dispatchers.IO) {
        db.syncSessionQueries.current().executeAsOneOrNull()?.let { session ->
            return@withContext db.memberQueries.all().executeAsList().find { it.id == session.member_id }?.toDomain()
        }
        db.memberQueries.byDevice(deviceId).executeAsOneOrNull()?.toDomain()
            ?: db.memberQueries.all().executeAsList().firstOrNull()?.toDomain()
    }

    suspend fun setUp(displayName: String, accounts: List<Account>, incomeCategories: List<Category> = StarterCategories.income) {
        require(incomeCategories.all { it.kind == CategoryKind.INCOME && !it.archived })
        store.appendAll(
            listOf(MemberCreated(Member(newId(), displayName, deviceId))) +
                (StarterCategories.all + incomeCategories).map { CategoryCreated(it) } + accounts.map { AccountCreated(it) },
        ) { check(db.memberQueries.all().executeAsList().isEmpty()) { "Setup already completed" } }
    }

    suspend fun addCategory(name: String, color: String, icon: String? = null, kind: CategoryKind = CategoryKind.EXPENSE) {
        val category = Category(newId(), name.trim(), color, kind = kind, icon = icon)
        store.append(CategoryCreated(category)) { requireUniqueName(category) }
    }

    /** The kind is fixed at creation; an update without an icon keeps the stored one. */
    suspend fun updateCategory(category: Category) {
        val trimmed = category.copy(name = category.name.trim())
        store.append(CategoryUpdated(trimmed)) {
            val stored = requireNotNull(db.categoryQueries.byId(trimmed.id).executeAsOneOrNull()) { "Category no longer available" }
            require(stored.kind == trimmed.kind.name) { "Category type cannot change" }
            // Duplicates from before this check stay editable as long as the name does not change.
            if (stored.name.normalizedName() != trimmed.name.normalizedName()) requireUniqueName(trimmed)
        }
    }

    private fun requireUniqueName(category: Category) {
        require(category.name.isNotEmpty()) { "Category needs a name" }
        val wanted = category.name.normalizedName()
        if (db.categoryQueries.active().executeAsList().any { it.id != category.id && it.kind == category.kind.name && it.name.normalizedName() == wanted }) {
            throw DuplicateCategoryName()
        }
    }

    suspend fun archiveCategory(id: String) { store.append(CategoryArchived(id)) }

    suspend fun addAccount(account: Account) { store.append(AccountCreated(account)) }
    suspend fun accountInUse(id: String): Boolean = withContext(Dispatchers.IO) { hasAccountReferences(id) }

    private fun hasAccountReferences(id: String): Boolean =
        db.txQueries.all().executeAsList().any { it.account_id == id || it.to_account_id == id } ||
            db.recurringRuleQueries.active().executeAsList().any { it.account_id == id }

    suspend fun updateAccount(account: Account) {
        store.append(AccountUpdated(account)) {
            val previous = db.accountQueries.byId(account.id).executeAsOneOrNull()
            require(previous == null || previous.currency == account.currency.name || !hasAccountReferences(account.id)) {
                "Used account currency cannot change"
            }
        }
    }

    /** Allowed with money still in it: the balance leaves savings and goals, its transactions stay in reports. */
    suspend fun archiveAccount(id: String) { store.append(AccountArchived(id)) }
    suspend fun reorderAccounts(ids: List<String>) { store.append(AccountsReordered(ids)) }

    suspend fun renameMember(memberId: String, displayName: String) { store.append(MemberRenamed(memberId, displayName.trim())) }

    suspend fun add(t: Transaction) {
        store.append(TransactionAdded(t)) {
            loans.requireUnlinked(t.id)
            require(t.type != TransactionType.LOAN_RECEIPT)
            storedTransaction(t.id)?.let { requireUnchanged(t, it) }
            validateTransaction(t)
        }
    }

    suspend fun hasReceipt(id: String): Boolean = withContext(Dispatchers.IO) {
        db.receiptQueries.activeById(id).executeAsOneOrNull() != null
    }

    suspend fun receiptForTransaction(transactionId: String): Receipt? = withContext(Dispatchers.IO) {
        db.receiptQueries.byTransaction(transactionId).executeAsOneOrNull()?.let {
            Receipt(it.fiscal_url, it.issuer, it.invoice_number, it.merchant, LocalDate.parse(it.date),
                Money.of(it.total, Currency.RSD), app.kinjar.domain.EventJson.decodeFromString(it.items_json))
        }
    }

    suspend fun receiptCategory(issuer: String): String? = withContext(Dispatchers.IO) {
        db.receiptQueries.suggestCategory(issuer).executeAsOneOrNull()?.category_id
    }

    suspend fun addReceipt(t: Transaction, receipt: Receipt) {
        require(t.id == receipt.transactionId && t.source == TransactionSource.RECEIPT && t.type == TransactionType.EXPENSE)
        require(t.money.currency == Currency.RSD && receipt.total.currency == Currency.RSD)
        val deletionId = withContext(Dispatchers.IO) { latestReceiptDeletion(db, t.id) }
        store.append(TransactionAdded(t, receipt, deletionId)) {
            loans.requireUnlinked(t.id)
            if (db.receiptQueries.activeById(receipt.id).executeAsOneOrNull() != null) throw DuplicateReceipt()
            require(deletionId == latestReceiptDeletion(db, t.id)) { "Receipt deletion changed; scan again" }
            val account = db.accountQueries.byId(t.accountId).executeAsOneOrNull()
            if (account == null || account.archived != 0L || account.currency != "RSD") throw InvalidReceiptAccount()
            if (t.categoryId != null && runCatching { db.requireCategoryFor(t) }.isFailure) throw InvalidReceiptCategory()
        }
    }
    suspend fun transaction(id: String): Transaction? = withContext(Dispatchers.IO) {
        db.txQueries.all().executeAsList().firstOrNull { it.id == id }?.toDomain()
    }

    suspend fun isLoanLinked(id: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { loans.requireUnlinked(id) }.isFailure
    }

    /**
     * Corrections keep the identity a receipt or paid bill is matched by, so neither can be recorded twice.
     * With [expected], the write is refused with [StaleTransaction] unless the stored row still equals it.
     */
    suspend fun edit(t: Transaction, expected: Transaction? = null) {
        store.append(TransactionEdited(t)) {
            loans.requireUnlinked(t.id)
            require(t.type != TransactionType.LOAN_RECEIPT)
            val previous = storedTransaction(t.id)
            if (expected != null) requireUnchanged(expected, previous)
            requireNotNull(previous) { "Transaction no longer exists" }
            require(previous.type != TransactionType.LOAN_RECEIPT && previous.source == t.source && previous.paidBy == t.paidBy)
            if (t.source != TransactionSource.MANUAL) require(t.type == TransactionType.EXPENSE)
            if (t.source == TransactionSource.RECEIPT) require(t.money.currency == Currency.RSD)
            // A future-dated bill payment reads as unpaid, and paying it again would be dropped as the same transaction.
            if (t.source == TransactionSource.RECURRING) require(t.date <= clock.today()) { "Paid bill cannot move into the future" }
            validateTransaction(t)
        }
    }

    private fun validateTransaction(t: Transaction) {
        val account = db.accountQueries.byId(t.accountId).executeAsOneOrNull()
        require(account != null && account.archived == 0L && account.currency == t.money.currency.name) { "Account no longer available" }
        db.requireCategoryFor(t)
        if (t.type == TransactionType.TRANSFER) {
            val destination = t.toAccountId?.let { db.accountQueries.byId(it).executeAsOneOrNull() }
            require(destination != null && destination.archived == 0L && destination.id != account.id) {
                "Transfer account no longer available"
            }
            require(destination.currency == (t.received ?: t.money).currency.name) { "Transfer needs the amount the destination receives" }
        }
    }

    /** With [expected], the delete is refused with [StaleTransaction] unless the stored row still equals it. */
    suspend fun delete(id: String, expected: Transaction? = null) {
        require(expected == null || expected.id == id)
        store.append(TransactionDeleted(id)) {
            loans.requireUnlinked(id)
            if (expected != null) requireUnchanged(expected, storedTransaction(id))
        }
    }

    private fun storedTransaction(id: String): Transaction? = db.txQueries.all().executeAsList().firstOrNull { it.id == id }?.toDomain()

    private fun requireUnchanged(expected: Transaction, stored: Transaction?) {
        if (stored != expected) throw StaleTransaction()
    }

    private fun String.normalizedName() = trim().lowercase()

    private fun GoalRow.toDomain() = Goal(id, name, Money.of(target, Currency.valueOf(currency)), Money.of(monthly, Currency.valueOf(currency)), archived == 1L)
    private fun RuleRow.toDomain() = RecurringRule(
        id, name, Money.of(amount, Currency.valueOf(currency)), account_id, category_id, Cadence.valueOf(cadence), LocalDate.parse(first_due), archived == 1L,
    )
    private fun AccountRow.toDomain() = Account(id, name, Currency.valueOf(currency), AccountKind.valueOf(kind), archived == 1L)
    private fun CategoryRow.toDomain() = Category(id, name, color, archived == 1L, CategoryKind.valueOf(kind), icon)
    private fun MemberRow.toDomain() = Member(id, display_name, device_id)
    private fun Recent.toRecent() = RecentTx(
        transaction(id, type, amount, currency, account_id, to_account_id, category_id, date, note, merchant, paid_by, source,
            received_amount, received_currency),
        category_name, paid_by_name, category_icon,
    )
}

internal fun TxRow.toDomain(): Transaction = transaction(id, type, amount, currency, account_id, to_account_id, category_id, date, note,
    merchant, paid_by, source, received_amount, received_currency)

private fun transaction(
    id: String, type: String, amount: String, currency: String, accountId: String, toAccountId: String?, categoryId: String?,
    date: String, note: String, merchant: String, paidBy: String, source: String, receivedAmount: String?, receivedCurrency: String?,
) = Transaction(
    id, TransactionType.valueOf(type), Money.of(amount, Currency.valueOf(currency)), accountId, toAccountId,
    categoryId, LocalDate.parse(date), note, merchant, paidBy, TransactionSource.valueOf(source),
    receivedAmount?.let { Money.of(it, Currency.valueOf(requireNotNull(receivedCurrency))) },
)

/**
 * New writes only: expenses take expense categories and income takes income categories, while transfers and loan
 * receipts take none. Replay never checks this, so older history keeps loading.
 */
internal fun KinjarDb.requireCategoryFor(t: Transaction) {
    val id = t.categoryId ?: return
    val kind = when (t.type) {
        TransactionType.EXPENSE -> CategoryKind.EXPENSE
        TransactionType.INCOME -> CategoryKind.INCOME
        TransactionType.TRANSFER, TransactionType.LOAN_RECEIPT -> throw IllegalArgumentException("Only income and expenses have categories")
    }
    val category = categoryQueries.byId(id).executeAsOneOrNull()
    require(category != null && category.archived == 0L) { "Category no longer available" }
    require(category.kind == kind.name) { "Category does not match the transaction type" }
}
