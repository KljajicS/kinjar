package app.kinjar.ui.loans

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import app.kinjar.R
import app.kinjar.data.BudgetRepository
import app.kinjar.data.Clock
import app.kinjar.data.LoanBook
import app.kinjar.data.TransactionAccountStore
import app.kinjar.domain.*
import app.kinjar.ui.WriteOperation
import app.kinjar.ui.parseMoneyInput
import app.kinjar.ui.settings.Loadable
import java.time.LocalDate
import javax.inject.Inject

enum class LoanIssue(@StringRes val message: Int) {
    DATE(R.string.loan_issue_date),
    FUTURE_START(R.string.loan_issue_future_start),
    PRINCIPAL_OVER_TOTAL(R.string.loan_issue_principal_total),
    EXCEEDS_BALANCE(R.string.loan_issue_exceeds),
    HISTORY_CONFLICT(R.string.loan_issue_conflict),
    INCOMPLETE_SPLITS(R.string.loan_issue_incomplete),
    ARCHIVED(R.string.loan_issue_archived),
    ACCOUNT_UNAVAILABLE(R.string.loan_issue_account),
}

/** A value ready to save, or the reason it cannot be saved; both null while the form is simply unfinished. */
data class Checked<T>(val value: T? = null, val issue: LoanIssue? = null)

@HiltViewModel
class LoanListViewModel @Inject constructor(repo: BudgetRepository, val clock: Clock) : ViewModel() {
    private val load = Loadable(viewModelScope, repo.loans.book)
    val book: StateFlow<LoanBook?> = load.state
    val loadFailed: StateFlow<Boolean> = load.failed
    fun retry() = load.retry()
}

@Serializable
data class LoanForm(
    val name: String = "",
    val opening: String = "",
    val currency: Currency = Currency.EUR,
    @Serializable(with = LocalDateSerializer::class) val start: LocalDate,
    val usual: String = "",
    @Serializable(with = LocalDateSerializer::class) val due: LocalDate? = null,
    val receiving: Boolean = false,
    val accountId: String? = null,
    val deposit: String = "",
) {
    fun value(id: String): Loan? {
        val openingMoney = parseMoneyInput(opening, currency) ?: return null
        if (name.isBlank()) return null
        val usualMoney = if (usual.isBlank()) null else parseMoneyInput(usual, currency)?.takeUnless { it.isZero } ?: return null
        return Loan(id, name.trim(), openingMoney, start, usualMoney, due)
    }
}

/** [loan] is null for a new loan, or when the edited one no longer exists. */
data class LoanEditorData(val loan: Loan?, val accounts: List<Account>)

data class LoanDetailData(val book: LoanBook, val accounts: List<Account>, val categories: List<Category>)

private const val FORM = "form"
private const val ACTION = "action"
private const val ADJUSTMENT = "adjustment"

// A draft saved by another app version is dropped rather than crashing restoration.
private inline fun <reified T> SavedStateHandle.draft(key: String): T? =
    get<String>(key)?.let { runCatching { EventJson.decodeFromString<T>(it) }.getOrNull() }

@HiltViewModel
class LoanEditorViewModel @Inject constructor(
    private val repo: BudgetRepository, val clock: Clock, private val saved: SavedStateHandle,
) : ViewModel() {
    val editingId: String? = saved["id"]
    private val id = editingId ?: saved.get<String>("draftId") ?: newId().also { saved["draftId"] = it }
    private val receiptId = saved.get<String>("receiptId") ?: newId().also { saved["receiptId"] = it }
    val writes = WriteOperation(viewModelScope)
    private val restored = saved.draft<LoanForm>(FORM)
    private val mutableForm = MutableStateFlow(restored ?: LoanForm(start = clock.today()))
    val form: StateFlow<LoanForm> = mutableForm
    private var prefilled = restored != null
    private val editing: Flow<Loan?> = editingId?.let { id -> repo.loans.book.map { book -> book.loans.find { it.id == id } } } ?: flowOf(null)
    private val load = Loadable(viewModelScope, combine(editing, repo.accounts, ::LoanEditorData).onEach { prefill(it.loan) })
    val data: StateFlow<LoanEditorData?> = load.state
    val loadFailed: StateFlow<Boolean> = load.failed
    fun retry() = load.retry()

    private fun prefill(loan: Loan?) {
        if (prefilled || loan == null) return
        prefilled = true
        mutableForm.value = LoanForm(loan.name, loan.opening.amount.toPlainString(), loan.opening.currency, loan.start,
            loan.usualPayment?.amount?.toPlainString().orEmpty(), loan.nextDue)
    }

    fun edit(value: LoanForm) {
        mutableForm.value = value
        saved[FORM] = EventJson.encodeToString(value)
    }

    fun issue(): LoanIssue? = when {
        data.value?.loan?.archived == true -> LoanIssue.ARCHIVED
        form.value.start > clock.today() -> LoanIssue.FUTURE_START
        else -> null
    }

    fun canSave(): Boolean {
        val d = data.value ?: return false
        val s = form.value
        val loan = s.value(id) ?: return false
        if ((editingId != null && d.loan == null) || issue() != null) return false
        if (!s.receiving) return true
        val account = d.accounts.find { it.id == s.accountId } ?: return false
        return !loan.opening.isZero && parseMoneyInput(s.deposit, account.currency)?.let { !it.isZero } == true
    }

    fun save() {
        if (!canSave()) return
        val accounts = data.value?.accounts ?: return
        val s = form.value
        val value = s.value(id) ?: return
        writes.run {
            if (editingId != null) repo.loans.update(value) else {
                val receipt = if (!s.receiving) null else {
                    val account = accounts.first { it.id == s.accountId }
                    Transaction(receiptId, TransactionType.LOAN_RECEIPT, requireNotNull(parseMoneyInput(s.deposit, account.currency)),
                        account.id, null, null, s.start, "", s.name.trim(), requireNotNull(repo.currentMember()).id, TransactionSource.MANUAL)
                }
                repo.loans.create(value, receipt)
            }
        }
    }
}

enum class LoanAction { NONE, PAYMENT, LINK, SPLIT, ADJUST, RECEIPT }

@Serializable
data class PaymentForm(
    val id: String,
    val txId: String,
    @Serializable(with = LocalDateSerializer::class) val date: LocalDate,
    val accountId: String? = null,
    val amount: String = "",
    val total: String = "",
    val principal: String = "",
    val categoryId: String? = null,
    val linked: Transaction? = null,
) {
    fun loanTotal(loan: Loan, account: Account?): Money? {
        val accountMoney = linked?.money ?: account?.let { parseMoneyInput(amount, it.currency) } ?: return null
        if (accountMoney.isZero) return null
        return if (accountMoney.currency == loan.opening.currency) accountMoney
            else parseMoneyInput(total, loan.opening.currency)?.takeUnless { it.isZero }
    }

    fun payment(loan: Loan, account: Account?): LoanPayment? {
        val totalMoney = loanTotal(loan, account) ?: return null
        val principalMoney = if (principal.isBlank()) null else parseMoneyInput(principal, loan.opening.currency) ?: return null
        if (principalMoney != null && principalMoney.amount > totalMoney.amount) return null
        return LoanPayment(id, loan.id, linked?.id ?: txId, totalMoney, principalMoney)
    }

    fun transaction(account: Account, merchant: String, paidBy: String): Transaction? = linked
        ?: parseMoneyInput(amount, account.currency)?.takeUnless { it.isZero }?.let {
            Transaction(txId, TransactionType.EXPENSE, it, account.id, null, categoryId, date, "", merchant, paidBy, TransactionSource.MANUAL)
        }
}

@Serializable
data class AdjustmentForm(val id: String, val amount: String = "", val reason: String = "", val increase: Boolean = false)

/** Mirrors LoanRepository's checks so the user sees why a save is impossible instead of a generic failure. */
fun validatePayment(book: LoanBook, loan: Loan, form: PaymentForm, mode: LoanAction, accounts: List<Account>, today: LocalDate): Checked<LoanPayment> {
    val split = mode == LoanAction.SPLIT
    if (split && book.payments.none { it.id == form.id }) return Checked()
    if (loan.archived && !split) return Checked(issue = LoanIssue.ARCHIVED)
    if (!split && (form.date < loan.start || form.date > today)) return Checked(issue = LoanIssue.DATE)
    if (mode == LoanAction.LINK && form.linked == null) return Checked()
    val account = accounts.find { it.id == form.accountId }
    if (form.linked == null && account?.archived == true) return Checked(issue = LoanIssue.ACCOUNT_UNAVAILABLE)
    val payment = form.payment(loan, account) ?: run {
        val principal = parseMoneyInput(form.principal, loan.opening.currency)
        val total = form.loanTotal(loan, account)
        return Checked(issue = LoanIssue.PRINCIPAL_OVER_TOTAL.takeIf { principal != null && total != null && principal.amount > total.amount })
    }
    val tx = form.linked ?: account?.let { form.transaction(it, "", "") } ?: return Checked()
    val before = book.balance(loan, today)
    // Replacing by ID keeps a retry of an already saved draft from counting the payment twice.
    val after = LoanCalculator.balance(loan, book.payments.filterNot { it.id == payment.id } + payment, book.adjustments,
        book.transactionsById + (tx.id to tx), today)
    if (after.invalidHistory) return Checked(issue = if (before.invalidHistory) LoanIssue.HISTORY_CONFLICT else LoanIssue.EXCEEDS_BALANCE)
    return Checked(payment)
}

fun validateAdjustment(book: LoanBook, loan: Loan, form: AdjustmentForm, today: LocalDate): Checked<LoanAdjustment> {
    if (loan.archived) return Checked(issue = LoanIssue.ARCHIVED)
    val adjustments = book.adjustments.filterNot { it.id == form.id }
    val before = LoanCalculator.balance(loan, book.payments, adjustments, book.transactionsById, today)
    if (before.invalidHistory) return Checked(issue = LoanIssue.HISTORY_CONFLICT)
    if (!before.complete) return Checked(issue = LoanIssue.INCOMPLETE_SPLITS)
    val amount = parseMoneyInput(form.amount, loan.opening.currency)?.takeUnless { it.isZero } ?: return Checked()
    if (form.reason.isBlank()) return Checked()
    val adjustment = LoanAdjustment(form.id, loan.id, if (form.increase) amount else -amount, today, form.reason.trim())
    if (LoanCalculator.balance(loan, book.payments, adjustments + adjustment, book.transactionsById, today).invalidHistory) {
        return Checked(issue = LoanIssue.EXCEEDS_BALANCE)
    }
    return Checked(adjustment)
}

fun validateReceipt(receipt: Transaction?, loan: Loan, form: PaymentForm, accounts: List<Account>): Checked<Transaction> {
    if (loan.archived) return Checked(issue = LoanIssue.ARCHIVED)
    if (receipt == null) return Checked()
    val account = accounts.find { it.id == form.accountId } ?: return Checked()
    if (account.archived) return Checked(issue = LoanIssue.ACCOUNT_UNAVAILABLE)
    val money = parseMoneyInput(form.amount, account.currency)?.takeUnless { it.isZero } ?: return Checked()
    return Checked(receipt.copy(accountId = account.id, money = money).takeUnless { it == receipt })
}

@HiltViewModel
class LoanDetailViewModel @Inject constructor(
    private val repo: BudgetRepository, val clock: Clock, private val saved: SavedStateHandle,
    private val accountStore: TransactionAccountStore,
) : ViewModel() {
    val id: String = requireNotNull(saved["id"])
    val writes = WriteOperation(viewModelScope)
    private val load = Loadable(viewModelScope, combine(repo.loans.book, repo.allAccounts, repo.expenseCategories, ::LoanDetailData))
    /** Null while loading or after a failed read, so no check or write runs on stale inputs. */
    val data: StateFlow<LoanDetailData?> = load.state
    val loadFailed: StateFlow<Boolean> = load.failed
    private val mutableForm = MutableStateFlow(saved.draft<PaymentForm>(FORM) ?: newPayment())
    val form: StateFlow<PaymentForm> = mutableForm
    private val mutableAction = MutableStateFlow(saved.get<String>(ACTION)?.let { name -> LoanAction.entries.find { it.name == name } } ?: LoanAction.NONE)
    val action: StateFlow<LoanAction> = mutableAction
    private val mutableAdjustment = MutableStateFlow(saved.draft<AdjustmentForm>(ADJUSTMENT) ?: AdjustmentForm(newId()))
    val adjustment: StateFlow<AdjustmentForm> = mutableAdjustment

    private fun newPayment() = PaymentForm(newId(), newId(), clock.today())
    fun loan(): Loan? = data.value?.book?.loans?.find { it.id == id }
    fun retry() = load.retry()

    private fun PaymentForm.knownCategory(d: LoanDetailData) = copy(categoryId = categoryId?.takeIf { c -> d.categories.any { it.id == c } })

    fun editForm(value: PaymentForm) {
        mutableForm.value = value
        saved[FORM] = EventJson.encodeToString(value)
    }

    fun editAdjustment(value: AdjustmentForm) {
        mutableAdjustment.value = value
        saved[ADJUSTMENT] = EventJson.encodeToString(value)
    }

    private fun setAction(value: LoanAction) {
        mutableAction.value = value
        saved[ACTION] = value.name
    }

    fun close() { if (!writes.state.value.busy) setAction(LoanAction.NONE) }

    fun begin(mode: LoanAction) {
        val d = data.value ?: return
        if (writes.state.value.busy) return
        val active = d.accounts.filterNot { it.archived }
        val account = active.find { it.id == accountStore.lastUsed(TransactionType.EXPENSE) } ?: active.firstOrNull()
        val b = d.book
        val lastCategory = b.payments.filter { it.loanId == id }.mapNotNull { b.transactionsById[it.transactionId] }.maxByOrNull { it.date }?.categoryId
        editForm(newPayment().copy(accountId = account?.id, categoryId = lastCategory).knownCategory(d))
        editAdjustment(AdjustmentForm(newId()))
        setAction(mode)
    }

    fun beginReceipt() {
        if (writes.state.value.busy) return
        val receipt = data.value?.book?.receipt(id) ?: return
        editForm(newPayment().copy(accountId = receipt.accountId, amount = receipt.money.amount.toPlainString()))
        setAction(LoanAction.RECEIPT)
    }

    fun link(tx: Transaction) {
        editForm(form.value.copy(linked = tx, date = tx.date, accountId = tx.accountId, amount = tx.money.amount.toPlainString()))
    }

    fun editSplit(payment: LoanPayment) {
        if (writes.state.value.busy) return
        val tx = data.value?.book?.transactionsById?.get(payment.transactionId) ?: return
        editForm(PaymentForm(payment.id, tx.id, tx.date, tx.accountId, tx.money.amount.toPlainString(),
            payment.total.amount.toPlainString(), payment.principal?.amount?.toPlainString().orEmpty(), tx.categoryId, tx))
        setAction(LoanAction.SPLIT)
    }

    fun paymentCheck(): Checked<LoanPayment> {
        val d = data.value ?: return Checked()
        val loan = loan() ?: return Checked()
        return validatePayment(d.book, loan, form.value.knownCategory(d), action.value, d.accounts, clock.today())
    }

    fun savePayment() {
        val d = data.value ?: return
        val payment = paymentCheck().value ?: return
        val loan = loan() ?: return
        val s = form.value.knownCategory(d)
        val mode = action.value
        writes.run(finish = false) {
            if (mode == LoanAction.SPLIT) repo.loans.split(payment.id, payment.principal) else {
                val tx = s.linked ?: run {
                    val account = d.accounts.first { it.id == s.accountId }
                    requireNotNull(s.transaction(account, loan.name, requireNotNull(repo.currentMember()).id))
                }
                repo.loans.record(payment, tx, s.linked != null)
                if (s.linked == null) accountStore.remember(TransactionType.EXPENSE, tx.accountId)
            }
            setAction(LoanAction.NONE)
        }
    }

    fun adjustmentCheck(): Checked<LoanAdjustment> {
        val d = data.value ?: return Checked()
        val loan = loan() ?: return Checked()
        return validateAdjustment(d.book, loan, adjustment.value, clock.today())
    }

    fun adjust() {
        val value = adjustmentCheck().value ?: return
        writes.run(finish = false) { repo.loans.adjust(value); setAction(LoanAction.NONE) }
    }

    fun receiptCheck(): Checked<Transaction> {
        val d = data.value ?: return Checked()
        val loan = loan() ?: return Checked()
        return validateReceipt(d.book.receipt(id), loan, form.value, d.accounts)
    }

    fun correctReceipt() {
        val value = receiptCheck().value ?: return
        writes.run(finish = false) { repo.loans.correctReceipt(id, value.accountId, value.money); setAction(LoanAction.NONE) }
    }

    fun remove(payment: LoanPayment, deleteExpense: Boolean) {
        if (loan()?.archived != false) return
        writes.run(finish = false) { repo.loans.removePayment(payment.id, deleteExpense) }
    }

    fun archive() {
        if (loan()?.archived != false) return
        writes.run { repo.loans.archive(id) }
    }
}
