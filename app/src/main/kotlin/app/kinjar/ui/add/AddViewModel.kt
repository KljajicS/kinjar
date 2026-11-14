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
import app.kinjar.data.receipt.PaymentSlip
import app.kinjar.domain.Account
import app.kinjar.domain.Bill
import app.kinjar.domain.BillCalculator
import app.kinjar.domain.billTxId
import app.kinjar.domain.Category
import app.kinjar.domain.CategoryKind
import app.kinjar.domain.Currency
import app.kinjar.domain.EventJson
import app.kinjar.domain.ExchangeRateSnapshot
import app.kinjar.ui.parseMoneyInput
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
    /** The new transaction's id once stored, so a project flow can offer to link it; null after edits and deletes. */
    val savedId: String? = null,
    val saving: Boolean = false,
    @StringRes val saveError: Int? = null,
    val receipt: Receipt? = null,
    val paymentSlip: PaymentSlip? = null,
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
    /** Archived rows included, so a stored entry still shows the name of a category that was retired. */
    val allCategories: List<Category> = emptyList(),
    val rates: ExchangeRateSnapshot = ExchangeRateSnapshot(),
    /** What the user typed for a cross-currency transfer; ignored while [receivedManual] is false. */
    val receivedText: String = "",
    val receivedManual: Boolean = false,
    /** The sent and received currencies [receivedText] was entered for. */
    val receivedPair: Pair<Currency, Currency>? = null,
    /** A typed received amount was dropped because the currencies changed. */
    val receivedReset: Boolean = false,
) {
    val scanned: Boolean get() = receipt != null || paymentSlip != null
    val typeLocked: Boolean get() = bill != null || scanned || (editing != null && editing.source != TransactionSource.MANUAL)
    val rsdOnly: Boolean get() = scanned || editing?.source == TransactionSource.RECEIPT
    val billInFuture: Boolean get() = editing?.source == TransactionSource.RECURRING && date > today

    /** Active categories the current type can use. */
    val pickableCategories: List<Category> get() = type.categoryKind?.let { kind -> categories.filter { it.kind == kind } } ?: emptyList()
    val toCurrency: Currency? get() = accounts.firstOrNull { it.id == toAccountId }?.currency
    val crossCurrency: Boolean get() = type == TransactionType.TRANSFER && toCurrency.let { it != null && it != currency }
    val suggestion: ReceivedSuggestion? get() {
        val target = toCurrency ?: return null
        val sent = amount.value ?: return null
        return if (crossCurrency) suggestReceived(Money.of(sent, currency), target, rates) else null
    }
    val received: Money? get() {
        if (!crossCurrency) return null
        val target = requireNotNull(toCurrency)
        return if (receivedManual) parseMoneyInput(receivedText, target)?.takeIf { !it.isZero } else suggestion?.money
    }
    val receivedSource: ReceivedSource? get() = when {
        !crossCurrency -> null
        receivedManual && editing?.received != null && received == editing.received -> ReceivedSource.SAVED
        receivedManual -> ReceivedSource.MANUAL
        suggestion?.nbsDate != null -> ReceivedSource.NBS
        suggestion != null -> ReceivedSource.OVERRIDE
        rates.rates.rate(currency) == null || rates.rates.rate(requireNotNull(toCurrency)) == null -> ReceivedSource.MISSING
        else -> null
    }

    val canSave: Boolean get() = !loading && !loadFailed && !saving && !saved && !missing && !loanLocked && !stale && amount.value != null && accountId != null && error == null &&
        accounts.any { it.id == accountId && !it.archived && it.currency == currency } &&
        (!rsdOnly || currency == Currency.RSD) && !billInFuture &&
        (!scanned || merchant.isNotBlank()) &&
        (type.categoryKind == null || categoryId == null || pickableCategories.any { it.id == categoryId }) &&
        (type != TransactionType.TRANSFER || accounts.any { it.id == toAccountId && !it.archived }) &&
        (!crossCurrency || received != null)
}

enum class ReceivedSource { NBS, OVERRIDE, MANUAL, SAVED, MISSING }

private data class Inputs(val accounts: List<Account>, val categories: List<Category>, val all: List<Category>, val rates: ExchangeRateSnapshot)

/** [nbsDate] is null when a saved rate override took part in the conversion. */
data class ReceivedSuggestion(val money: Money, val nbsDate: LocalDate?)

internal val TransactionType.categoryKind: CategoryKind?
    get() = when (this) {
        TransactionType.EXPENSE -> CategoryKind.EXPENSE
        TransactionType.INCOME -> CategoryKind.INCOME
        TransactionType.TRANSFER, TransactionType.LOAN_RECEIPT -> null
    }

/** Converts through RSD with the same rates totals use; an override wins over the fetched NBS rate and is never labelled NBS. */
internal fun suggestReceived(sent: Money, target: Currency, rates: ExchangeRateSnapshot): ReceivedSuggestion? {
    val money = rates.rates.convert(sent, target)?.takeIf { !it.isZero } ?: return null
    val foreign = listOf(sent.currency, target).filter { it != Currency.RSD }
    val nbsDate = if (foreign.any { it in rates.overrides }) null else foreign.mapNotNull { rates.cached[it]?.effectiveDate }.minOrNull()
    return ReceivedSuggestion(money, nbsDate)
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
    /** The category last picked per type in this form; a remembered null means uncategorized was chosen. */
    private val categoryChoices = mutableMapOf<TransactionType, String?>()
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
            combine(repo.accounts, repo.categories, repo.allCategories, repo.exchangeRates.snapshots, ::Inputs).collect { (accounts, categories, all, rates) ->
                val memberName = repo.currentMember()?.displayName.orEmpty()
                val sortedCategories = categories.sortedBy { c -> StarterCategories.all.indexOfFirst { it.id == c.id }.let { if (it < 0) Int.MAX_VALUE else it } }
                _state.update { s ->
                    val pickable = s.type.categoryKind?.let { kind -> sortedCategories.filter { it.kind == kind } }.orEmpty()
                    val keepCategory = s.scanned || s.bill != null || s.editing != null || s.categoryId == null || pickable.any { it.id == s.categoryId }
                    val replacement = if (s.type == TransactionType.EXPENSE) pickable.firstOrNull()?.id else null
                    selectAccount(s.copy(categoryId = if (keepCategory) s.categoryId else replacement,
                        paidByName = memberName, accounts = accounts, categories = sortedCategories, allCategories = all, rates = rates, loading = false))
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
                editing = t, bill = null, loanLocked = locked, savedReceipt = savedReceipt, stale = false, saveError = null,
                // The stored amount is what arrived; recomputing it from today's rate would rewrite history.
                receivedText = t.received?.amount?.stripTrailingZeros()?.toPlainString().orEmpty(), receivedManual = t.received != null,
                receivedPair = t.received?.let { t.money.currency to it.currency }, receivedReset = false,
            )
        }
        categoryChoices.clear()
    }

    /** Replaces the form with the stored transaction, dropping what was typed over the outdated one. */
    fun reload() {
        val s = _state.value
        val id = s.editing?.id ?: s.bill?.txId ?: return
        if (!s.stale || s.loading || s.saving || s.saved) return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val t = repo.transaction(id)
                if (t == null) {
                    _state.update { it.copy(missing = true, stale = false, saveError = null) }
                } else {
                    savedState["id"] = t.id
                    showEditing(t)
                }
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

    /** Scans are reviewed again after recreation; payment instructions must not become a partial manual draft. */
    private fun persist(s: AddState) {
        if (s.scanned) {
            DRAFT_KEYS.forEach { savedState.remove<Any>(it) }
            return
        }
        if (s.loading || s.loadFailed) return
        if (s.missing || s.loanLocked || s.saved || (!s.stale && s.editing?.let { matches(s, it) } == true)) {
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
        savedState[DRAFT_RECEIVED] = if (s.receivedManual) s.receivedText else null
        savedState[DRAFT_RECEIVED_PAIR] = if (s.receivedManual) s.receivedPair?.let { "${it.first.name}>${it.second.name}" } else null
        // A stale form keeps the version it was typed over, so recreation cannot pass the draft off as current.
        if (!s.stale) savedState[DRAFT_BASE] = s.editing?.let { encode(it) }
    }

    /**
     * Restored values pass the same rules as typed ones: the amount is re-entered key by key and locked fields stay locked.
     * A correction drafted over a different stored version is restored as stale.
     */
    private fun restoreDraft(s: AddState): AddState {
        if (!savedState.contains(DRAFT_AMOUNT) || s.scanned || s.missing || s.loanLocked) return s
        val stale = s.editing != null && savedState.get<String>(DRAFT_BASE) != encode(s.editing)
        val type = savedState.get<String>(DRAFT_TYPE)
            ?.let { name -> TransactionType.entries.firstOrNull { it.name == name } }
            ?.takeIf { it != TransactionType.LOAN_RECEIPT && !s.typeLocked } ?: s.type
        val date = if (s.bill != null) s.date
            else savedState.get<String>(DRAFT_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: s.date
        val receivedPair = savedState.get<String>(DRAFT_RECEIVED_PAIR)?.split('>')?.takeIf { it.size == 2 }?.let { (from, to) ->
            val currencies = Currency.entries.associateBy { it.name }
            currencies[from]?.let { f -> currencies[to]?.let { t -> (f to t).takeIf { f != t } } }
        }
        val receivedText = savedState.get<String>(DRAFT_RECEIVED)
            ?.takeIf { type == TransactionType.TRANSFER && receivedPair != null && parseMoneyInput(it, receivedPair.second)?.isZero == false }
        val restored = s.copy(
            type = type,
            amount = savedState.get<String>(DRAFT_AMOUNT).orEmpty().fold(AmountInput()) { input, key -> input.press(key) },
            categoryId = savedState.get<String>(DRAFT_CATEGORY),
            accountId = savedState.get<String>(DRAFT_ACCOUNT),
            toAccountId = if (type == TransactionType.TRANSFER) savedState.get<String>(DRAFT_TO) else null,
            date = date,
            merchant = savedState.get<String>(DRAFT_MERCHANT) ?: s.merchant,
            receivedText = receivedText.orEmpty(), receivedManual = receivedText != null,
            receivedPair = if (receivedText != null) receivedPair else null, receivedReset = false,
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
        s.date == transaction.date && s.merchant == transaction.merchant && sameReceived(s, transaction.received)

    /** Compared without account currencies, so it also holds while a restored draft waits for accounts to load. */
    private fun sameReceived(s: AddState, received: Money?): Boolean =
        if (received == null) !s.receivedManual || s.type != TransactionType.TRANSFER
        else s.receivedManual && s.receivedPair?.second == received.currency &&
            parseMoneyInput(s.receivedText, received.currency)?.amount?.compareTo(received.amount) == 0

    fun setType(type: TransactionType) = _state.update { s ->
        if (s.typeLocked || s.missing || s.loanLocked || s.type == type) return@update s
        if (s.type.categoryKind != null) categoryChoices[s.type] = s.categoryId
        val categoryId = when {
            type.categoryKind == null -> null
            categoryChoices.containsKey(type) -> categoryChoices[type]
            s.editing != null -> s.editing.categoryId.takeIf { s.editing.type == type }
            type == TransactionType.EXPENSE -> s.categories.filter { it.kind == CategoryKind.EXPENSE }
                .let { expense -> expense.firstOrNull { it.id == DEFAULT_EXPENSE } ?: expense.firstOrNull() }?.id
            // Income stays uncategorized until chosen, so no entry is filed under a guessed source.
            else -> null
        }
        selectAccount(
            s.copy(type = type, toAccountId = null, categoryId = categoryId,
                receivedText = "", receivedManual = false, receivedPair = null, receivedReset = false),
            if (type == TransactionType.TRANSFER) s.accountId else accountChoices[type] ?: s.editing?.accountId,
        )
    }

    /** Any edit, even one that matches the suggestion, pins the value so later rate refreshes leave it alone. */
    fun setReceived(text: String) = _state.update { s ->
        val target = s.toCurrency
        if (!s.crossCurrency || target == null || text.length > MAX_RECEIVED) s
        else s.copy(receivedText = text.trim().replace(',', '.'), receivedManual = true, receivedPair = s.currency to target, receivedReset = false)
    }

    fun useSuggestedReceived() = _state.update { it.copy(receivedText = "", receivedManual = false, receivedPair = null, receivedReset = false) }

    fun prefillReceipt(receipt: Receipt, categoryId: String?) = _state.update { s ->
        if (s.receipt?.id == receipt.id) s else selectAccount(s.copy(
            type = TransactionType.EXPENSE, amount = AmountInput(receipt.total.amount.toPlainString()),
            currency = Currency.RSD, categoryId = categoryId,
            accountId = null, toAccountId = null,
            date = receipt.date, merchant = receipt.merchant, receipt = receipt, paymentSlip = null, bill = null,
            saved = false, saving = false, error = null, saveError = null,
        ), accountChoices[TransactionType.EXPENSE])
    }

    fun prefillPaymentSlip(slip: PaymentSlip) {
        DRAFT_KEYS.forEach { savedState.remove<Any>(it) }
        _state.update { s ->
            if (s.paymentSlip == slip) s else selectAccount(s.copy(
                type = TransactionType.EXPENSE, amount = AmountInput(slip.amount.amount.toPlainString()),
                currency = Currency.RSD, categoryId = null, accountId = null, toAccountId = null,
                date = clock.today(), merchant = slip.merchant, paymentSlip = slip, receipt = null, bill = null,
                saved = false, saving = false, error = null, saveError = null,
            ), accountChoices[TransactionType.EXPENSE])
        }
    }

    fun resetReceipt() {
        if (_state.value.saving) return
        accountChoices.clear()
        categoryChoices.clear()
        _state.update { s ->
            selectAccount(AddState(date = clock.today(), accounts = s.accounts, categories = s.categories, allCategories = s.allCategories,
                rates = s.rates, paidByName = s.paidByName))
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
    fun setAmount(text: String) = _state.update { it.copy(amount = it.amount.edit(text)) }
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
            var savedId: String? = null
            try {
                val toAccountId = if (s.type == TransactionType.TRANSFER) s.toAccountId else null
                val categoryId = if (s.type.categoryKind != null) s.categoryId else null
                val original = s.editing
                if (original != null) {
                    repo.edit(original.copy(
                        type = s.type, money = Money.of(amount, s.currency), accountId = accountId, toAccountId = toAccountId,
                        categoryId = categoryId, date = s.date, merchant = s.merchant.trim(), received = s.received,
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
                        note = s.paymentSlip?.note.orEmpty(),
                        merchant = s.merchant.trim(),
                        paidBy = member.id,
                        source = when {
                            s.receipt != null -> TransactionSource.RECEIPT
                            s.bill != null -> TransactionSource.RECURRING
                            else -> TransactionSource.MANUAL
                        },
                        received = s.received,
                    )
                    if (s.receipt != null) repo.addReceipt(transaction, s.receipt) else repo.add(transaction)
                    savedId = transaction.id
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
            _state.update { it.copy(saved = true, saving = false, savedId = savedId) }
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

    /**
     * A typed received amount only means something for the currency pair it was typed for; a new cross-currency pair drops it
     * with a notice. Same-currency or unknown destinations keep it untouched, since the field is hidden and ignored there.
     */
    private fun validate(s: AddState): AddState {
        if (s.type != TransactionType.TRANSFER || s.toAccountId == null) return s.copy(error = null)
        val pairChanged = s.receivedManual && s.crossCurrency && s.receivedPair != (s.currency to s.toCurrency)
        val checked = if (pairChanged) s.copy(receivedText = "", receivedManual = false, receivedPair = null, receivedReset = true) else s
        return checked.copy(error = if (s.accountId == s.toAccountId) R.string.add_same_account else null)
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
        const val DRAFT_RECEIVED = "draft.received"
        const val DRAFT_RECEIVED_PAIR = "draft.receivedPair"
        val DRAFT_KEYS = listOf(DRAFT_TYPE, DRAFT_AMOUNT, DRAFT_CATEGORY, DRAFT_ACCOUNT, DRAFT_TO, DRAFT_DATE, DRAFT_MERCHANT, DRAFT_BASE,
            DRAFT_RECEIVED, DRAFT_RECEIVED_PAIR)
        const val DEFAULT_EXPENSE = "cat-groceries"
        const val MAX_RECEIVED = 15

        fun encode(t: Transaction): String = EventJson.encodeToString(Transaction.serializer(), t)
    }
}
