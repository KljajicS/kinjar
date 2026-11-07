package app.kinjar.ui.add

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import app.kinjar.R
import app.kinjar.data.BudgetRepository
import app.kinjar.data.Clock
import app.kinjar.data.DuplicateReceipt
import app.kinjar.data.InvalidReceiptAccount
import app.kinjar.data.InvalidReceiptCategory
import app.kinjar.data.StaleTransaction
import app.kinjar.data.DiagnosticOperation
import app.kinjar.data.Diagnostics
import app.kinjar.data.StarterCategories
import app.kinjar.data.TransactionAccountStore
import app.kinjar.domain.Account
import app.kinjar.domain.Bill
import app.kinjar.domain.BillCalculator
import app.kinjar.domain.billTxId
import app.kinjar.domain.Category
import app.kinjar.domain.Currency
import app.kinjar.domain.EventJson
import app.kinjar.domain.Money
import app.kinjar.domain.Transaction
import app.kinjar.domain.Receipt
import app.kinjar.domain.TransactionSource
import app.kinjar.domain.TransactionType
import app.kinjar.domain.newId
import java.time.LocalDate
import javax.inject.Inject

data class AddState(
    val type: TransactionType = TransactionType.EXPENSE,
    val amount: AmountInput = AmountInput(),
    val currency: Currency = Currency.RSD,
    val categoryId: String? = "cat-groceries",
    val accountId: String? = null,
    val toAccountId: String? = null,
    val date: LocalDate,
    val merchant: String = "",
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    @StringRes val error: Int? = null,
    val saved: Boolean = false,
    val saving: Boolean = false,
    @StringRes val saveError: Int? = null,
    val receipt: Receipt? = null,
    val savedReceipt: Receipt? = null,
    val paidByName: String = "",
    val bill: Bill? = null,
    /** The stored transaction being corrected; its id, source, payer and note survive the edit. */
    val editing: Transaction? = null,
    val missing: Boolean = false,
    val loanLocked: Boolean = false,
    val loadFailed: Boolean = false,
    val loading: Boolean = false,
    val today: LocalDate = date,
    /** [editing] changed after this form was filled; only a reload, never a save or delete, is allowed. */
    val stale: Boolean = false,
) {
    val typeLocked: Boolean get() = bill != null || receipt != null || (editing != null && editing.source != TransactionSource.MANUAL)
    val rsdOnly: Boolean get() = receipt != null || editing?.source == TransactionSource.RECEIPT
    val billInFuture: Boolean get() = editing?.source == TransactionSource.RECURRING && date > today

    val canSave: Boolean get() = !loading && !loadFailed && !saving && !saved && !missing && !loanLocked && !stale && amount.value != null && accountId != null && error == null &&
        accounts.any { it.id == accountId && !it.archived && it.currency == currency } &&
        (!rsdOnly || currency == Currency.RSD) && !billInFuture &&
        (type != TransactionType.EXPENSE || categoryId == null || categories.any { it.id == categoryId }) &&
        (type != TransactionType.TRANSFER || accounts.any { it.id == toAccountId && !it.archived })
}

@HiltViewModel
class AddViewModel @Inject constructor(
    private val repo: BudgetRepository,
    private val clock: Clock,
    private val savedState: SavedStateHandle,
    private val transactionAccounts: TransactionAccountStore = TransactionAccountStore({ null }, { _, _ -> }),
) : ViewModel() {
    private val _state = MutableStateFlow(AddState(date = clock.today(), loading = true))
    val state: StateFlow<AddState> = _state
    private val accountChoices = mutableMapOf<TransactionType, String>()
    private var prefilled = false

    init {
        viewModelScope.launch { _state.collect { if (prefilled) persist(it) } }
        load()
    }

    fun retryLoad() {
        if (!_state.value.loadFailed) return
        _state.update { it.copy(loadFailed = false, loading = true) }
        load()
    }

    private fun load() = viewModelScope.launch {
        try {
            if (!prefilled) {
                prefill()
                _state.update { restoreDraft(it) }
                prefilled = true
            }
            combine(repo.accounts, repo.categories) { a, c -> a to c }.collect { (accounts, categories) ->
                val memberName = repo.currentMember()?.displayName.orEmpty()
                val sortedCategories = categories.sortedBy { c -> StarterCategories.all.indexOfFirst { it.id == c.id }.let { if (it < 0) Int.MAX_VALUE else it } }
                _state.update { s ->
                    val keepCategory = s.receipt != null || s.bill != null || s.editing != null || s.categoryId == null || categories.any { it.id == s.categoryId }
                    selectAccount(s.copy(categoryId = if (keepCategory) s.categoryId else sortedCategories.firstOrNull()?.id,
                        paidByName = memberName, accounts = accounts, categories = sortedCategories, loading = false))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diagnostics.report(DiagnosticOperation.TRANSACTION_READ, e)
            _state.update { it.copy(loadFailed = true, loading = false) }
        }
    }

    private suspend fun prefill() {
        val editId = savedState.get<String>("id")
        if (editId != null) {
            val t = repo.transaction(editId)
            if (t == null) {
                _state.update { it.copy(missing = true) }
                return
            }
            showEditing(t)
            return
        }
        val ruleId = savedState.get<String>("rule")
        val due = savedState.get<String>("due")?.let(LocalDate::parse)
        val rule = ruleId?.let { repo.rule(it) }
        if (rule != null && due != null) {
            val period = BillCalculator.period(rule.cadence, due)
            _state.update {
                it.copy(
                    type = TransactionType.EXPENSE,
                    amount = AmountInput(rule.money.amount.stripTrailingZeros().toPlainString()),
                    categoryId = rule.categoryId,
                    accountId = rule.accountId,
                    currency = rule.money.currency,
                    date = clock.today(),
                    merchant = rule.name,
                    bill = Bill(rule, due, period, billTxId(rule.id, period), overdue = due < clock.today()),
                )
            }
        }
    }

    private suspend fun showEditing(t: Transaction) {
        val locked = t.type == TransactionType.LOAN_RECEIPT || repo.isLoanLinked(t.id)
        val savedReceipt = repo.receiptForTransaction(t.id)
        _state.update {
            it.copy(
                type = t.type, amount = AmountInput(t.money.amount.stripTrailingZeros().toPlainString()), currency = t.money.currency,
                categoryId = t.categoryId, accountId = t.accountId, toAccountId = t.toAccountId, date = t.date, merchant = t.merchant,
                editing = t, loanLocked = locked, savedReceipt = savedReceipt, stale = false, saveError = null,
            )
        }
    }

    /** Replaces the form with the stored transaction, dropping what was typed over the outdated one. */
    fun reload() {
        val s = _state.value
        val id = s.editing?.id ?: return
        if (!s.stale || s.loading || s.saving || s.saved) return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val t = repo.transaction(id)
                if (t == null) _state.update { it.copy(missing = true, stale = false, saveError = null) } else showEditing(t)
                _state.update { selectAccount(it.copy(loading = false)) }
            } catch (e: CancellationException) {
                _state.update { it.copy(loading = false) }
                throw e
            } catch (e: Exception) {
                Diagnostics.report(DiagnosticOperation.TRANSACTION_READ, e)
                _state.update { it.copy(loading = false, saveError = R.string.audit_finance_load_failed) }
            }
        }
    }

    /** Receipts are never persisted: the scan screen re-reads them, and the fiscal URL should not sit in saved state. */
    private fun persist(s: AddState) {
        if (s.loading || s.loadFailed) return
        if (s.receipt != null || s.missing || s.loanLocked || s.saved || (!s.stale && s.editing?.let { matches(s, it) } == true)) {
            DRAFT_KEYS.forEach { savedState.remove<Any>(it) }
            return
        }
        savedState[DRAFT_TYPE] = s.type.name
        savedState[DRAFT_AMOUNT] = s.amount.text
        savedState[DRAFT_CATEGORY] = s.categoryId
        savedState[DRAFT_ACCOUNT] = s.accountId
        savedState[DRAFT_TO] = s.toAccountId
        savedState[DRAFT_DATE] = s.date.toString()
        savedState[DRAFT_MERCHANT] = s.merchant
        // A stale form keeps the version it was typed over, so recreation cannot pass the draft off as current.
        if (!s.stale) savedState[DRAFT_BASE] = s.editing?.let { encode(it) }
    }

    /**
     * Restored values pass the same rules as typed ones: the amount is re-entered key by key and locked fields stay locked.
     * A correction drafted over a different stored version is restored as stale.
     */
    private fun restoreDraft(s: AddState): AddState {
        if (!savedState.contains(DRAFT_AMOUNT) || s.missing || s.loanLocked) return s
        val stale = s.editing != null && savedState.get<String>(DRAFT_BASE) != encode(s.editing)
        val type = savedState.get<String>(DRAFT_TYPE)
            ?.let { name -> TransactionType.entries.firstOrNull { it.name == name } }
            ?.takeIf { it != TransactionType.LOAN_RECEIPT && !s.typeLocked } ?: s.type
        val date = if (s.bill != null) s.date
            else savedState.get<String>(DRAFT_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: s.date
        val restored = s.copy(
            type = type,
            amount = savedState.get<String>(DRAFT_AMOUNT).orEmpty().fold(AmountInput()) { input, key -> input.press(key) },
            categoryId = savedState.get<String>(DRAFT_CATEGORY),
            accountId = savedState.get<String>(DRAFT_ACCOUNT),
            toAccountId = if (type == TransactionType.TRANSFER) savedState.get<String>(DRAFT_TO) else null,
            date = date,
            merchant = savedState.get<String>(DRAFT_MERCHANT) ?: s.merchant,
            stale = stale,
            saveError = if (stale) R.string.audit_edit_stale else s.saveError,
        )
        val base = savedState.get<String>(DRAFT_BASE)?.let {
            runCatching { EventJson.decodeFromString(Transaction.serializer(), it) }.getOrNull()
        }
        return if (base != null && matches(restored, base)) s else restored
    }

    private fun matches(s: AddState, transaction: Transaction): Boolean = s.type == transaction.type &&
        s.currency == transaction.money.currency && s.amount.value?.compareTo(transaction.money.amount) == 0 &&
        s.categoryId == transaction.categoryId && s.accountId == transaction.accountId && s.toAccountId == transaction.toAccountId &&
        s.date == transaction.date && s.merchant == transaction.merchant

    fun setType(type: TransactionType) = _state.update { s ->
        if (s.typeLocked || s.missing || s.loanLocked || s.type == type) s else selectAccount(
            s.copy(type = type, toAccountId = null),
            if (type == TransactionType.TRANSFER) s.accountId else accountChoices[type] ?: s.editing?.accountId,
        )
    }

    fun prefillReceipt(receipt: Receipt, categoryId: String?) = _state.update { s ->
        if (s.receipt?.id == receipt.id) s else selectAccount(s.copy(
            type = TransactionType.EXPENSE, amount = AmountInput(receipt.total.amount.toPlainString()),
            currency = Currency.RSD, categoryId = categoryId,
            accountId = null, toAccountId = null,
            date = receipt.date, merchant = receipt.merchant, receipt = receipt, bill = null,
            saved = false, saving = false, error = null, saveError = null,
        ), accountChoices[TransactionType.EXPENSE])
    }

    fun resetReceipt() {
        if (_state.value.saving) return
        accountChoices.clear()
        _state.update { s ->
            selectAccount(AddState(date = clock.today(), accounts = s.accounts, categories = s.categories, paidByName = s.paidByName))
        }
    }

    fun skip() {
        if (_state.value.loading || _state.value.loadFailed || _state.value.saving || _state.value.saved) return
        val bill = _state.value.bill ?: return
        _state.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            try {
                repo.skipBill(bill.rule.id, bill.period)
                _state.update { it.copy(saved = true, saving = false) }
            } catch (e: CancellationException) {
                _state.update { it.copy(saving = false) }
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(saving = false, saveError = R.string.add_save_failed) }
            }
        }
    }
    fun press(key: Char) = _state.update { it.copy(amount = it.amount.press(key)) }
    fun setCategory(id: String?) = _state.update { it.copy(categoryId = id) }
    fun setDate(date: LocalDate) = _state.update { if (it.bill != null) it else it.copy(date = date) }
    fun setMerchant(text: String) = _state.update { it.copy(merchant = text) }

    fun setAccount(id: String) {
        accountChoices[_state.value.type] = id
        _state.update { s ->
            validate(s.copy(accountId = id, currency = s.accounts.firstOrNull { it.id == id }?.currency ?: s.currency))
        }
    }

    fun setToAccount(id: String) = _state.update { validate(it.copy(toAccountId = id)) }

    fun save() {
        val s = _state.value
        val amount = s.amount.value ?: return
        if (!s.canSave) return
        val accountId = requireNotNull(s.accountId)
        _state.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            try {
                val toAccountId = if (s.type == TransactionType.TRANSFER) s.toAccountId else null
                val categoryId = if (s.type == TransactionType.EXPENSE) s.categoryId else null
                val original = s.editing
                if (original != null) {
                    repo.edit(original.copy(
                        type = s.type, money = Money.of(amount, s.currency), accountId = accountId, toAccountId = toAccountId,
                        categoryId = categoryId, date = s.date, merchant = s.merchant.trim(),
                    ), expected = original)
                } else {
                    val member = requireNotNull(repo.currentMember())
                    val transaction = Transaction(
                        id = s.receipt?.transactionId ?: s.bill?.txId ?: newId(),
                        type = s.type,
                        money = Money.of(amount, s.currency),
                        accountId = accountId,
                        toAccountId = toAccountId,
                        categoryId = categoryId,
                        date = if (s.bill != null) clock.today() else s.date,
                        note = "",
                        merchant = s.merchant.trim(),
                        paidBy = member.id,
                        source = when {
                            s.receipt != null -> TransactionSource.RECEIPT
                            s.bill != null -> TransactionSource.RECURRING
                            else -> TransactionSource.MANUAL
                        },
                    )
                    if (s.receipt != null) repo.addReceipt(transaction, s.receipt) else repo.add(transaction)
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(saving = false) }
                throw e
            } catch (e: Exception) {
                if (e is StaleTransaction) {
                    _state.update { it.copy(saving = false, stale = true, saveError = R.string.audit_edit_stale) }
                    return@launch
                }
                Diagnostics.report(DiagnosticOperation.TRANSACTION_WRITE, e)
                val message = when (e) {
                    is DuplicateReceipt -> R.string.scan_duplicate
                    is InvalidReceiptAccount -> R.string.scan_rsd_account
                    is InvalidReceiptCategory -> R.string.scan_category_changed
                    else -> R.string.add_save_failed
                }
                _state.update { it.copy(saving = false, saveError = message) }
                return@launch
            }
            _state.update { it.copy(saved = true, saving = false) }
            // A correction is not a fresh choice, so it does not change which account new entries default to.
            if (s.editing != null) return@launch
            try {
                transactionAccounts.remember(s.type, accountId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Retrying a persisted transaction would duplicate it when only the device preference failed.
            }
        }
    }

    fun delete() {
        val s = _state.value
        val original = s.editing ?: return
        if (s.loading || s.loadFailed || s.saving || s.saved || s.loanLocked || s.stale) return
        _state.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            try {
                repo.delete(original.id, expected = original)
                _state.update { it.copy(saved = true, saving = false) }
            } catch (e: CancellationException) {
                _state.update { it.copy(saving = false) }
                throw e
            } catch (_: StaleTransaction) {
                _state.update { it.copy(saving = false, stale = true, saveError = R.string.audit_edit_stale) }
            } catch (e: Exception) {
                Diagnostics.report(DiagnosticOperation.TRANSACTION_WRITE, e)
                _state.update { it.copy(saving = false, saveError = R.string.audit_finance_delete_failed) }
            }
        }
    }

    private fun selectAccount(s: AddState, preferredId: String? = s.accountId): AddState {
        val eligible = s.accounts.filter { !it.archived && (!s.rsdOnly || it.currency == Currency.RSD) }
        val account = eligible.firstOrNull { it.id == preferredId }
            // Bills and corrections name their account; silently moving them to another one would misstate a balance.
            ?: if (s.bill != null || s.editing != null) null else {
                transactionAccounts.lastUsed(s.type)?.let { id -> eligible.firstOrNull { it.id == id } }
                    ?: eligible.firstOrNull()
            }
        return validate(s.copy(accountId = account?.id, currency = account?.currency ?: s.currency))
    }

    private fun validate(s: AddState): AddState {
        if (s.type != TransactionType.TRANSFER || s.toAccountId == null) return s.copy(error = null)
        val from = s.accounts.firstOrNull { it.id == s.accountId }
        val to = s.accounts.firstOrNull { it.id == s.toAccountId }
        return s.copy(
            error = when {
                s.accountId == s.toAccountId -> R.string.add_same_account
                from != null && to != null && from.currency != to.currency -> R.string.add_currency_mismatch
                else -> null
            }
        )
    }

    private companion object {
        const val DRAFT_TYPE = "draft.type"
        const val DRAFT_AMOUNT = "draft.amount"
        const val DRAFT_CATEGORY = "draft.category"
        const val DRAFT_ACCOUNT = "draft.account"
        const val DRAFT_TO = "draft.to"
        const val DRAFT_DATE = "draft.date"
        const val DRAFT_MERCHANT = "draft.merchant"
        const val DRAFT_BASE = "draft.base"
        val DRAFT_KEYS = listOf(DRAFT_TYPE, DRAFT_AMOUNT, DRAFT_CATEGORY, DRAFT_ACCOUNT, DRAFT_TO, DRAFT_DATE, DRAFT_MERCHANT, DRAFT_BASE)

        fun encode(t: Transaction): String = EventJson.encodeToString(Transaction.serializer(), t)
    }
}
