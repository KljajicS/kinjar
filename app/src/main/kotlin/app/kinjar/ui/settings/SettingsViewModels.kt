package app.kinjar.ui.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.kinjar.data.BudgetRepository
import app.kinjar.data.Clock
import app.kinjar.data.DuplicateCategoryName
import app.kinjar.data.ThemeMode
import app.kinjar.data.ThemeStore
import app.kinjar.data.security.AppLock
import app.kinjar.data.security.LockPurpose
import app.kinjar.data.security.LockState
import app.kinjar.domain.Account
import app.kinjar.domain.AccountKind
import app.kinjar.domain.Cadence
import app.kinjar.domain.Category
import app.kinjar.domain.CategoryKind
import app.kinjar.domain.Currency
import app.kinjar.domain.Money
import app.kinjar.domain.RecurringRule
import app.kinjar.domain.newId
import java.time.LocalDate
import app.kinjar.ui.CategoryPalette
import app.kinjar.ui.isDuplicateCategoryName
import app.kinjar.ui.starterRank
import app.kinjar.ui.unusedPaletteColor
import app.kinjar.ui.RateStatus
import app.kinjar.ui.home.AccountBalance
import app.kinjar.ui.WriteOperation
import app.kinjar.ui.parseMoneyInput
import javax.inject.Inject

data class SettingsState(
    val memberId: String? = null,
    val name: String = "",
    val saved: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val loadFailed: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(private val repo: BudgetRepository, private val themeStore: ThemeStore) : ViewModel() {
    val writes = WriteOperation(viewModelScope)
    private val _state = MutableStateFlow(SettingsState(theme = themeStore.mode.value))
    val state: StateFlow<SettingsState> = _state
    private var nameEdited = false

    private val load = EditorLoad(viewModelScope, { _state.update { it.copy(loadFailed = true) } }) {
        val m = repo.currentMember()
        _state.update { it.copy(memberId = m?.id, name = if (nameEdited) it.name else m?.displayName ?: "", loadFailed = false) }
    }

    init { load.start() }

    fun retryLoad() = load.start()

    fun setName(name: String) {
        nameEdited = true
        _state.update { it.copy(name = name, saved = false) }
    }

    fun setTheme(mode: ThemeMode) {
        themeStore.set(mode)
        _state.update { it.copy(theme = mode) }
    }

    fun saveName() {
        val s = _state.value
        val id = s.memberId ?: return
        if (s.name.isBlank()) return
        writes.run(finish = false) {
            repo.renameMember(id, s.name)
            _state.update { it.copy(saved = it.name == s.name) }
        }
    }
}

@HiltViewModel
class AppLockViewModel @Inject constructor(private val lock: AppLock) : ViewModel() {
    val state: StateFlow<LockState> = lock.state

    fun toggle() {
        lock.request(if (lock.state.value.enabled) LockPurpose.DISABLE else LockPurpose.ENABLE)
    }
}

data class CategoryGroups(val expense: List<Category>, val income: List<Category>)

@HiltViewModel
class CategoriesViewModel @Inject constructor(repo: BudgetRepository) : ViewModel() {
    private val load = Loadable(viewModelScope, repo.categories.map { list ->
        val (expense, income) = list.sortedBy { starterRank(it.id) }.partition { it.kind == CategoryKind.EXPENSE }
        CategoryGroups(expense, income)
    })
    val categories: StateFlow<CategoryGroups?> = load.state
    val failed: StateFlow<Boolean> = load.failed
    fun retry() = load.retry()
}

/** [others] are the other active categories; [duplicate] records a name the repository rejected. */
data class EditCategoryState(
    val editing: Boolean = false,
    val id: String? = null,
    val name: String = "",
    val color: String = CategoryPalette.first(),
    val kind: CategoryKind = CategoryKind.EXPENSE,
    val icon: String? = null,
    val others: List<Category> = emptyList(),
    val duplicate: Boolean = false,
    val loaded: Boolean = true,
    val loadFailed: Boolean = false,
    val done: Boolean = false,
) {
    val sameKind: List<Category> get() = others.filter { it.kind == kind }
    val nameTaken: Boolean get() = duplicate || isDuplicateCategoryName(name, sameKind.map { it.name })
    val canSave: Boolean get() = name.isNotBlank() && loaded && !nameTaken && !done
}

@HiltViewModel
class EditCategoryViewModel @Inject constructor(private val repo: BudgetRepository, savedState: SavedStateHandle) : ViewModel() {
    val writes = WriteOperation(viewModelScope)
    private val _state = MutableStateFlow(savedState.get<String>("id").let { id ->
        val kind = CategoryKind.entries.firstOrNull { it.name == savedState.get<String>("kind") } ?: CategoryKind.EXPENSE
        EditCategoryState(editing = id != null, id = id, kind = kind, loaded = id == null)
    })
    val state: StateFlow<EditCategoryState> = _state
    private var original: Category? = null
    private var colorPicked = false

    /** A new category stays savable when this read fails; the repository still rejects duplicates. */
    private val load = EditorLoad(viewModelScope, { _state.update { if (it.editing) it.copy(loadFailed = true) else it } }) {
        val id = _state.value.id
        val all = repo.categories.first()
        val c = if (id == null) null else requireNotNull(all.firstOrNull { it.id == id })
        val others = all.filter { it.id != id }
        original = c
        _state.update { s ->
            if (c == null) s.copy(others = others, color = if (colorPicked) s.color else unusedPaletteColor(others.map { it.color }))
            else s.copy(name = c.name, color = c.color, kind = c.kind, icon = c.icon, others = others, loaded = true, loadFailed = false)
        }
    }

    init { load.start() }

    fun retryLoad() { if (!_state.value.loaded) load.start() }

    fun setName(name: String) = _state.update { it.copy(name = name, duplicate = false) }
    fun setIcon(key: String) = _state.update { it.copy(icon = key) }
    fun setKind(kind: CategoryKind) = _state.update { if (it.editing) it else it.copy(kind = kind, duplicate = false) }

    fun setColor(color: String) {
        colorPicked = true
        _state.update { it.copy(color = color) }
    }

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        writes.run(finish = false) {
            try {
                if (s.id == null) repo.addCategory(s.name, s.color, s.icon, s.kind)
                else repo.updateCategory(requireNotNull(original).copy(name = s.name.trim(), color = s.color, icon = s.icon))
                _state.update { it.copy(done = true) }
            } catch (_: DuplicateCategoryName) {
                _state.update { it.copy(duplicate = true) }
            }
        }
    }

    fun delete() {
        if (!_state.value.loaded) return
        val id = _state.value.id ?: return
        writes.run {
            repo.archiveCategory(id)
            _state.update { it.copy(done = true) }
        }
    }
}

data class AccountsState(
    val accounts: List<AccountBalance> = emptyList(),
    val rateStatus: RateStatus = RateStatus(),
    val reorderFailures: Int = 0,
)

@HiltViewModel
class AccountsViewModel @Inject constructor(private val repo: BudgetRepository) : ViewModel() {
    val writes = WriteOperation(viewModelScope)
    private val failures = MutableStateFlow(0)

    private val load = Loadable(viewModelScope, combine(repo.financial, failures) { financial, failed ->
        AccountsState(
            financial.activeAccounts.mapNotNull { a -> financial.home.balances[a.id]?.let { AccountBalance(a, it) } },
            RateStatus.from(financial),
            failed,
        )
    }.flowOn(Dispatchers.Default))
    val state: StateFlow<AccountsState?> = load.state
    val loadFailed: StateFlow<Boolean> = load.failed

    fun retryLoad() = load.retry()

    /** A drop while the previous order is still saving is undone rather than silently lost. */
    fun reorder(ids: List<String>) {
        if (writes.state.value.busy) {
            failures.update { it + 1 }
            return
        }
        writes.run(finish = false) {
            try {
                repo.reorderAccounts(ids)
            } catch (e: Exception) {
                failures.update { it + 1 }
                throw e
            }
        }
    }
}

/** [balanceRsd] is null without a rate; [kind] is legacy and only carried through edits; [bills] names active rules paid from this account. */
data class EditAccountState(
    val editing: Boolean = false,
    val id: String? = null,
    val name: String = "",
    val currency: Currency = Currency.RSD,
    val kind: AccountKind = AccountKind.SPENDING,
    val excludedFromGoals: Boolean = false,
    val currencyLocked: Boolean = false,
    val balance: Money? = null,
    val balanceRsd: Money? = null,
    val bills: List<String> = emptyList(),
    val loadFailed: Boolean = false,
    val done: Boolean = false,
) {
    val canSave: Boolean get() = name.isNotBlank() && !loadFailed
}

@HiltViewModel
class EditAccountViewModel @Inject constructor(private val repo: BudgetRepository, savedState: SavedStateHandle) : ViewModel() {
    val writes = WriteOperation(viewModelScope)
    private val _state = MutableStateFlow(EditAccountState(editing = savedState.get<String>("id") != null, id = savedState.get<String>("id")))
    val state: StateFlow<EditAccountState> = _state

    private var loading: Job? = null
    private var initialized = false
    init { retryLoad() }

    fun retryLoad() {
        val id = _state.value.id ?: return
        loading?.cancel()
        loading = viewModelScope.launch {
            try {
                if (!initialized) {
                    val used = repo.accountInUse(id)
                    val a = requireNotNull(repo.accounts.first().firstOrNull { it.id == id })
                    _state.update { it.copy(name = a.name, currency = a.currency, kind = a.kind, currencyLocked = used, excludedFromGoals = a.excludedFromGoals) }
                    initialized = true
                }
                combine(repo.financial, repo.rules) { financial, rules -> financial to rules }.collect { (f, rules) ->
                    val balance = f.home.balances[id]
                    _state.update { it.copy(balance = balance, balanceRsd = balance?.let { f.exchange.rates.convert(it) },
                        bills = rules.filter { it.accountId == id }.map { it.name }, loadFailed = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(loadFailed = true, balance = null, balanceRsd = null) }
            }
        }
    }

    fun setName(name: String) = _state.update { it.copy(name = name) }
    fun setCurrency(currency: Currency) = _state.update { if (it.currencyLocked) it else it.copy(currency = currency) }
    fun setExcludedFromGoals(excluded: Boolean) = _state.update { it.copy(excludedFromGoals = excluded) }

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        writes.run {
            val account = Account(s.id ?: newId(), s.name.trim(), s.currency, s.kind, excludedFromGoals = s.excludedFromGoals)
            if (s.id == null) repo.addAccount(account) else repo.updateAccount(account)
            _state.update { it.copy(done = true) }
        }
    }

    fun delete() {
        val id = _state.value.id ?: return
        writes.run {
            repo.archiveAccount(id)
            _state.update { it.copy(done = true) }
        }
    }
}

@HiltViewModel
class RecurringViewModel @Inject constructor(repo: BudgetRepository) : ViewModel() {
    private val load = Loadable(viewModelScope, combine(repo.upcomingBills, repo.rules, repo.accounts) { bills, rules, accounts ->
        RecurringState(bills, rules, accounts)
    })
    val state = load.state
    val failed = load.failed
    fun retry() = load.retry()
}

data class RecurringState(val bills: List<app.kinjar.domain.Bill>, val rules: List<RecurringRule>, val accounts: List<Account>)

data class EditRecurringState(
    val editing: Boolean = false,
    val id: String? = null,
    val name: String = "",
    val amountText: String = "",
    val accountId: String? = null,
    val categoryId: String? = null,
    val cadence: Cadence = Cadence.MONTHLY,
    val firstDue: LocalDate,
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val loaded: Boolean = true,
    val loadFailed: Boolean = false,
    val done: Boolean = false,
) {
    val currency: Currency? get() = accounts.firstOrNull { it.id == accountId }?.currency
    val amount: Money? get() = currency?.let { c -> parseMoneyInput(amountText, c) }?.takeIf { !it.isZero && !it.isNegative }
    val canSave: Boolean get() = name.isNotBlank() && amount != null && categoryId != null && loaded
}

@HiltViewModel
class EditRecurringViewModel @Inject constructor(private val repo: BudgetRepository, clock: Clock, savedState: SavedStateHandle) : ViewModel() {
    val writes = WriteOperation(viewModelScope)
    private val _state = MutableStateFlow(EditRecurringState(editing = savedState.get<String>("id") != null, id = savedState.get<String>("id"), firstDue = clock.today(), loaded = false))
    val state: StateFlow<EditRecurringState> = _state

    private val load = EditorLoad(viewModelScope, { _state.update { it.copy(loadFailed = true) } }) {
        val accounts = repo.accounts.first()
        val categories = repo.expenseCategories.first().sortedBy { starterRank(it.id) }
        val existing = _state.value.id?.let { requireNotNull(repo.rule(it)) }
        _state.update { s ->
            s.copy(
                accounts = accounts,
                categories = categories,
                name = existing?.name ?: s.name,
                amountText = existing?.money?.amount?.stripTrailingZeros()?.toPlainString() ?: s.amountText,
                accountId = existing?.accountId ?: accounts.firstOrNull()?.id,
                categoryId = existing?.categoryId ?: categories.firstOrNull { it.id == "cat-bills" }?.id ?: categories.firstOrNull()?.id,
                cadence = existing?.cadence ?: s.cadence,
                firstDue = existing?.firstDue ?: s.firstDue,
                loaded = true,
                loadFailed = false,
            )
        }
    }

    init { retryLoad() }

    fun retryLoad() { if (!_state.value.loaded) load.start() }

    fun setName(name: String) = _state.update { it.copy(name = name) }
    fun setAccount(id: String) = _state.update { it.copy(accountId = id) }
    fun setCategory(id: String) = _state.update { it.copy(categoryId = id) }
    fun setCadence(cadence: Cadence) = _state.update { if (it.editing) it else it.copy(cadence = cadence) }
    fun setFirstDue(date: LocalDate) = _state.update { if (it.editing) it else it.copy(firstDue = date) }

    fun setAmount(text: String) {
        if (text.length > 64) return
        _state.update { it.copy(amountText = text) }
    }

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        writes.run {
            repo.setRule(RecurringRule(s.id ?: newId(), s.name.trim(), s.amount!!, s.accountId!!, s.categoryId!!, s.cadence, s.firstDue))
            _state.update { it.copy(done = true) }
        }
    }

    fun delete() {
        if (!_state.value.loaded) return
        val id = _state.value.id ?: return
        writes.run {
            repo.archiveRule(id)
            _state.update { it.copy(done = true) }
        }
    }
}
