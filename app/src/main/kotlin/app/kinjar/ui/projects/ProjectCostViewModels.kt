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
import app.kinjar.domain.LocalDateSerializer
import app.kinjar.domain.Money
import app.kinjar.domain.Project
import app.kinjar.domain.ProjectCost
import app.kinjar.domain.Transaction
import app.kinjar.domain.TransactionType
import app.kinjar.domain.newId
import app.kinjar.ui.WriteOperation
import app.kinjar.ui.parseMoneyInput
import app.kinjar.ui.settings.Loadable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.LocalDate
import javax.inject.Inject

@Serializable
data class PastCostForm(
    val costId: String,
    val amount: String = "",
    val currency: Currency? = null,
    @Serializable(with = LocalDateSerializer::class) val date: LocalDate,
    val categoryId: String? = null,
    val note: String = "",
    val unpaid: Boolean = false,
    val base: ProjectCost? = null,
) {
    fun original(project: Project): Money? =
        parseMoneyInput(amount, currency ?: project.currency)?.takeIf { !it.isZero && !it.isNegative }
}

/** Shared by both forms: a rate is fetched for one historical day, and a typed one only fills that same day. */
abstract class ConversionViewModel(projects: ProjectRepository, repo: BudgetRepository, lookup: ProjectRateLookup) : ViewModel() {
    val writes = WriteOperation(viewModelScope)
    private val quoteCache = QuoteCache(viewModelScope, lookup)
    val quotes: StateFlow<Map<LocalDate, QuoteResult>> = quoteCache.quotes
    private val load = Loadable(viewModelScope, combine(projects.book, repo.allCategories, repo.allAccounts, ::ProjectData))
    val data: StateFlow<ProjectData?> = load.state
    val loadFailed: StateFlow<Boolean> = load.failed
    private val mutableManual = MutableStateFlow<Map<RateKey, String>>(emptyMap())
    val manual: StateFlow<Map<RateKey, String>> = mutableManual

    fun retry() = load.retry()
    fun retryRates() = quoteCache.retryFailed()
    fun setRate(key: RateKey, text: String) = mutableManual.update { it + (key to text) }
    protected fun ensure(date: LocalDate) = quoteCache.ensure(listOf(date))

    /** Null result while waiting for the day's quote; same-currency amounts never wait. */
    fun conversion(
        original: Money, target: Currency, date: LocalDate,
        quotes: Map<LocalDate, QuoteResult> = this.quotes.value, manual: Map<RateKey, String> = this.manual.value,
    ): Pair<Conversion?, List<RateKey>> {
        if (original.currency == target) return convert(original, target, date, null, emptyMap()) to emptyList()
        val result = quotes[date] ?: return null to emptyList()
        return convert(original, target, date, result.quote, manual) to missingRates(original, target, date, result.quote)
    }
}

@HiltViewModel
class PastCostViewModel @Inject constructor(
    private val projects: ProjectRepository, repo: BudgetRepository, lookup: ProjectRateLookup, val clock: Clock, private val saved: SavedStateHandle,
) : ConversionViewModel(projects, repo, lookup) {
    val id: String = requireNotNull(saved["id"])
    val costId: String? = saved["costId"]
    private val mutableForm = MutableStateFlow(
        saved.get<String>(FORM)?.let { runCatching { EventJson.decodeFromString(PastCostForm.serializer(), it) }.getOrNull() }
            ?: PastCostForm(newId(), date = clock.today()),
    )
    val form: StateFlow<PastCostForm> = mutableForm

    init {
        viewModelScope.launch {
            data.filterNotNull().collect { d ->
                if (costId != null && form.value.base == null) {
                    d.book.costs.find { it.id == costId && it.projectId == id && it.transactionId == null }?.let { cost ->
                        edit { PastCostForm(cost.id, cost.original.inputText(), cost.original.currency, cost.date,
                            cost.categoryId, cost.note, cost.unpaid, cost) }
                    }
                }
            }
        }
        viewModelScope.launch {
            combine(data.filterNotNull(), form) { d, f -> f.date.takeIf { d.project(id)?.let { p -> (f.currency ?: p.currency) != p.currency } == true } }
                .collect { date -> if (date != null && date <= clock.today()) ensure(date) }
        }
    }

    fun edit(change: (PastCostForm) -> PastCostForm) {
        if (writes.state.value.busy) return
        mutableForm.update(change)
        saved[FORM] = EventJson.encodeToString(PastCostForm.serializer(), mutableForm.value)
    }

    fun save() {
        val d = data.value ?: return
        val project = d.project(id)?.takeIf { !it.archived } ?: return
        val f = form.value
        if (costId != null && f.base == null) return
        val original = f.original(project) ?: return
        if (f.date > clock.today()) return
        val conv = costConversion(original, project.currency, f.date).first ?: return
        val category = f.categoryId?.takeIf { c -> d.categories(id).any { it.id == c && (!it.archived || f.base?.categoryId == c) } }
        // The draft keeps its id, so a retry after an unclear failure cannot add the cost twice.
        val cost = f.base?.withConversion(original, f.date, conv)?.copy(categoryId = category, note = f.note.trim(), unpaid = f.unpaid)
            ?: ProjectCost(f.costId, id, category, original, conv.converted, f.date, f.note.trim(), null, conv.ratesToRsd, conv.rateDate, conv.source, unpaid = f.unpaid)
        writes.run { projects.record(listOf(cost), expected = listOfNotNull(f.base)) }
    }

    fun costConversion(
        original: Money, target: Currency, date: LocalDate,
        quotes: Map<LocalDate, QuoteResult> = this.quotes.value, manual: Map<RateKey, String> = this.manual.value,
    ): Pair<Conversion?, List<RateKey>> {
        val base = form.value.base
        if (base != null && base.date == date && base.original.currency == original.currency) {
            convert(original, target, date, savedQuote(base, target), emptyMap())?.let { return it to emptyList() }
        }
        return conversion(original, target, date, quotes, manual)
    }

    private companion object {
        const val FORM = "past.form"
    }
}

data class Review(val project: Project, val cost: ProjectCost, val transaction: Transaction)

@HiltViewModel
class CostReviewViewModel @Inject constructor(
    private val projects: ProjectRepository, repo: BudgetRepository, lookup: ProjectRateLookup, saved: SavedStateHandle,
) : ConversionViewModel(projects, repo, lookup) {
    val id: String = requireNotNull(saved["id"])
    val costId: String = requireNotNull(saved["costId"])

    init {
        viewModelScope.launch {
            data.filterNotNull().collect { d -> review(d)?.let { if (it.transaction.money.currency != it.project.currency) ensure(it.transaction.date) } }
        }
    }

    fun review(d: ProjectData): Review? {
        val project = d.project(id) ?: return null
        val cost = d.book.costs.find { it.id == costId && it.projectId == id } ?: return null
        val tx = cost.transactionId?.let(d.transactionsById::get)?.takeIf { it.type == TransactionType.EXPENSE } ?: return null
        return Review(project, cost, tx)
    }

    fun save() {
        val r = data.value?.let(::review)?.takeIf { !it.project.archived } ?: return
        val conv = conversion(r.transaction.money, r.project.currency, r.transaction.date).first ?: return
        writes.run { projects.record(listOf(r.cost.withConversion(r.transaction.money, r.transaction.date, conv)), expected = listOf(r.cost)) }
    }
}
