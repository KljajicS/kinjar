package app.kinjar.ui.projects

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinjar.data.BudgetRepository
import app.kinjar.data.Clock
import app.kinjar.data.ProjectRepository
import app.kinjar.data.rates.ProjectRateLookup
import app.kinjar.domain.Currency
import app.kinjar.domain.EventJson
import app.kinjar.domain.Money
import app.kinjar.domain.Project
import app.kinjar.domain.ProjectCategory
import app.kinjar.domain.ProjectCost
import app.kinjar.domain.newId
import app.kinjar.ui.WriteOperation
import app.kinjar.ui.parseMoneyInput
import app.kinjar.ui.settings.Loadable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.LocalDate
import javax.inject.Inject

/** Blank means no budget; anything else must parse to a positive amount. */
fun budgetOf(text: String, currency: Currency): Result<Money?> = when {
    text.isBlank() -> Result.success(null)
    else -> parseMoneyInput(text, currency)?.takeIf { !it.isZero && !it.isNegative }?.let { Result.success(it) }
        ?: Result.failure(IllegalArgumentException("budget"))
}

fun Money.inputText(): String = amount.stripTrailingZeros().toPlainString()

@Serializable
data class NewProjectForm(val id: String, val name: String = "", val currency: Currency = Currency.EUR, val budget: String = "")

@HiltViewModel
class NewProjectViewModel @Inject constructor(
    private val projects: ProjectRepository, private val repo: BudgetRepository, private val saved: SavedStateHandle,
) : ViewModel() {
    val writes = WriteOperation(viewModelScope)
    private val mutableForm = MutableStateFlow(
        saved.get<String>(FORM)?.let { runCatching { EventJson.decodeFromString(NewProjectForm.serializer(), it) }.getOrNull() }
            ?: NewProjectForm(newId()),
    )
    val form: StateFlow<NewProjectForm> = mutableForm

    fun edit(change: (NewProjectForm) -> NewProjectForm) {
        if (writes.state.value.busy) return
        mutableForm.update(change)
        saved[FORM] = EventJson.encodeToString(NewProjectForm.serializer(), mutableForm.value)
    }

    fun canSave(f: NewProjectForm = form.value) = f.name.isNotBlank() && budgetOf(f.budget, f.currency).isSuccess

    /** Every active expense category starts enabled so the first link list is never empty; settings narrow it later. */
    fun save() {
        val f = form.value
        if (!canSave(f)) return
        writes.run {
            val sources = repo.expenseCategories.first().map { it.id }.toSet()
            projects.save(Project(f.id, f.name.trim(), f.currency, budgetOf(f.budget, f.currency).getOrNull(), sources))
        }
    }

    private companion object {
        const val FORM = "project.new"
    }
}

@Serializable
data class SettingsForm(val base: Project, val name: String, val budget: String, val sources: Set<String>)

@HiltViewModel
class ProjectSettingsViewModel @Inject constructor(
    private val projects: ProjectRepository, repo: BudgetRepository, private val saved: SavedStateHandle,
) : ViewModel() {
    val id: String = requireNotNull(saved["id"])
    val writes = WriteOperation(viewModelScope)
    val categoryWrites = WriteOperation(viewModelScope)
    private val load = Loadable(viewModelScope, combine(projects.book, repo.allCategories, repo.allAccounts, ::ProjectData))
    val data: StateFlow<ProjectData?> = load.state
    val loadFailed: StateFlow<Boolean> = load.failed
    private val mutableForm = MutableStateFlow(
        saved.get<String>(FORM)?.let { runCatching { EventJson.decodeFromString(SettingsForm.serializer(), it) }.getOrNull() },
    )
    val form: StateFlow<SettingsForm?> = mutableForm

    init {
        if (mutableForm.value == null) viewModelScope.launch {
            val project = data.filterNotNull().first().project(id) ?: return@launch
            store(SettingsForm(project, project.name, project.budget?.inputText().orEmpty(), project.sourceCategoryIds))
        }
    }

    fun retry() = load.retry()

    private fun store(value: SettingsForm) {
        mutableForm.value = value
        saved[FORM] = EventJson.encodeToString(SettingsForm.serializer(), value)
    }

    fun edit(change: (SettingsForm) -> SettingsForm) {
        if (writes.state.value.busy) return
        mutableForm.value?.let { store(change(it)) }
    }

    fun canSave(f: SettingsForm) = f.name.isNotBlank() && budgetOf(f.budget, f.base.currency).isSuccess

    /** Saved against the project as it was when the form opened, so a change from another phone is never overwritten. */
    fun save() {
        val f = form.value ?: return
        if (!canSave(f) || f.base.archived) return
        val next = f.base.copy(name = f.name.trim(), budget = budgetOf(f.budget, f.base.currency).getOrNull(), sourceCategoryIds = f.sources)
        writes.run { projects.save(next, f.base) }
    }

    fun archive(archived: Boolean) = writes.run { projects.archive(id, archived) }

    fun saveCategory(category: ProjectCategory, expected: ProjectCategory? = null) =
        categoryWrites.run(finish = false) { projects.saveCategory(category, expected) }

    fun newCategory(name: String, color: String) = saveCategory(ProjectCategory(newId(), id, name.trim(), color))

    private companion object {
        const val FORM = "project.settings"
    }
}

data class CurrencyPlan(val target: Currency, val costs: List<Pair<ProjectCost, Conversion?>>, val missing: List<RateKey>, val loading: Boolean) {
    val ready: Boolean get() = !loading && costs.all { it.second != null }
}

/** Converts every stored cost from its original amount, reusing its saved rate snapshot whenever it covers [target]. */
fun currencyPlan(costs: List<ProjectCost>, target: Currency, quotes: Map<LocalDate, QuoteResult>, manual: Map<RateKey, String>): CurrencyPlan {
    var loading = false
    val missing = mutableListOf<RateKey>()
    val items = costs.map { cost ->
        val snapshot = savedQuote(cost, target)
        val conversion = when {
            cost.original.currency == target -> convert(cost.original, target, cost.date, null, emptyMap())
            snapshot != null -> convert(cost.original, target, cost.date, snapshot, emptyMap())
            else -> {
                val result = quotes[cost.date]
                if (result == null) loading = true
                else missing += missingRates(cost.original, target, cost.date, result.quote)
                result?.let { convert(cost.original, target, cost.date, it.quote, manual) }
            }
        }
        cost to conversion
    }
    return CurrencyPlan(target, items, missing.distinct().sortedWith(compareBy({ it.date }, { it.currency })), loading)
}

fun fetchDates(costs: List<ProjectCost>, target: Currency): List<LocalDate> =
    costs.filter { it.original.currency != target && savedQuote(it, target) == null }.map { it.date }.distinct()

@HiltViewModel
class CurrencyChangeViewModel @Inject constructor(
    private val projects: ProjectRepository, repo: BudgetRepository, lookup: ProjectRateLookup, val clock: Clock, saved: SavedStateHandle,
) : ViewModel() {
    val id: String = requireNotNull(saved["id"])
    val writes = WriteOperation(viewModelScope)
    private val mutableTooLarge = MutableStateFlow(false)
    val tooLarge: StateFlow<Boolean> = mutableTooLarge
    private val quoteCache = QuoteCache(viewModelScope, lookup)
    val quotes: StateFlow<Map<LocalDate, QuoteResult>> = quoteCache.quotes
    private val load = Loadable(viewModelScope, combine(projects.book, repo.allCategories, repo.allAccounts, ::ProjectData))
    val data: StateFlow<ProjectData?> = load.state
    val loadFailed: StateFlow<Boolean> = load.failed
    private val mutableManual = MutableStateFlow<Map<RateKey, String>>(emptyMap())
    val manual: StateFlow<Map<RateKey, String>> = mutableManual
    private val mutableBudget = MutableStateFlow<String?>(null)
    /** Null until the user types; until then the suggestion from today's quote fills the field. */
    val budget: StateFlow<String?> = mutableBudget

    init {
        viewModelScope.launch {
            data.filterNotNull().collect { d ->
                val project = d.project(id) ?: return@collect
                val target = target(project)
                quoteCache.ensure(fetchDates(costs(d), target) + listOfNotNull(clock.today().takeIf { project.budget != null }))
            }
        }
    }

    fun target(project: Project) = if (project.currency == Currency.EUR) Currency.RSD else Currency.EUR
    fun costs(d: ProjectData) = d.book.costs.filter { it.projectId == id }
    fun retry() = load.retry()
    fun retryRates() = quoteCache.retryFailed()
    fun setRate(key: RateKey, text: String) = mutableManual.update { it + (key to text) }
    fun setBudget(text: String) { if (!writes.state.value.busy && text.length <= 15) mutableBudget.value = text }

    fun suggestedBudget(project: Project, quotes: Map<LocalDate, QuoteResult>): Money? {
        val budget = project.budget ?: return null
        val quote = quotes[clock.today()]?.quote ?: return null
        return convert(budget, target(project), clock.today(), quote, emptyMap())?.converted
    }

    fun budgetText(project: Project, quotes: Map<LocalDate, QuoteResult>): String =
        budget.value ?: suggestedBudget(project, quotes)?.inputText().orEmpty()

    /** An untouched budget field must not save as "no budget" just because today's quote has not arrived yet. */
    fun budgetLoading(project: Project, typed: String?, quotes: Map<LocalDate, QuoteResult>) =
        project.budget != null && typed == null && quotes[clock.today()] == null

    fun apply() {
        val d = data.value ?: return
        val project = d.project(id)?.takeIf { !it.archived } ?: return
        if (budgetLoading(project, budget.value, quotes.value)) return
        val target = target(project)
        val plan = currencyPlan(costs(d), target, quotes.value, manual.value)
        if (!plan.ready) return
        val newBudget = budgetOf(budgetText(project, quotes.value), target).getOrElse { return }
        val converted = plan.costs.map { (cost, conversion) -> cost.withConversion(cost.original, cost.date, requireNotNull(conversion)) }
        writes.run {
            mutableTooLarge.value = false
            try {
                projects.changeCurrency(project.copy(currency = target, budget = newBudget), converted, expected = project)
            } catch (e: IllegalArgumentException) {
                mutableTooLarge.value = e.message in setOf("Sync operation too large", "Project currency change too large")
                throw e
            }
        }
    }
}
