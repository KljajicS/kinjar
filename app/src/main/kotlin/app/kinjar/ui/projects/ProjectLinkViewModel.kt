package app.kinjar.ui.projects

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinjar.data.BudgetRepository
import app.kinjar.data.Clock
import app.kinjar.data.ProjectRepository
import app.kinjar.data.rates.ProjectRateLookup
import app.kinjar.domain.EventJson
import app.kinjar.domain.Project
import app.kinjar.domain.ProjectCost
import app.kinjar.domain.Transaction
import app.kinjar.domain.TransactionType
import app.kinjar.domain.newId
import app.kinjar.ui.WriteOperation
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

const val MAX_BATCH = 200

@Serializable
data class LinkForm(val query: String = "", val selected: Set<String> = emptySet(), val categoryId: String? = null, val limited: Boolean = false)

data class LinkPlan(val items: List<Pair<Transaction, Conversion?>>, val missing: List<RateKey>, val loading: Boolean) {
    val ready: Boolean get() = items.isNotEmpty() && items.size <= MAX_BATCH && items.all { it.second != null }
}

/** Expenses from the project's enabled categories that no project, archived ones included, links yet. */
fun ProjectData.candidates(project: Project, today: LocalDate): List<Transaction> {
    val linked = book.costs.mapNotNull { it.transactionId }.toSet()
    return book.transactions.filter {
        it.type == TransactionType.EXPENSE && it.categoryId in project.sourceCategoryIds && it.id !in linked && it.date <= today
    }.sortedWith(compareByDescending<Transaction> { it.date }.thenByDescending { it.id })
}

fun linkPlan(chosen: List<Transaction>, project: Project, quotes: Map<LocalDate, QuoteResult>, manual: Map<RateKey, String>): LinkPlan {
    var loading = false
    val missing = mutableListOf<RateKey>()
    val items = chosen.map { tx ->
        val needs = tx.money.currency != project.currency
        val result = quotes[tx.date]
        if (needs && result == null) loading = true
        val conversion = if (needs && result == null) null else convert(tx.money, project.currency, tx.date, result?.quote, manual)
        if (needs && result != null) missing += missingRates(tx.money, project.currency, tx.date, result.quote)
        tx to conversion
    }
    return LinkPlan(items, missing.distinct().sortedWith(compareBy({ it.date }, { it.currency })), loading)
}

@HiltViewModel
class ProjectLinkViewModel @Inject constructor(
    private val projects: ProjectRepository, repo: BudgetRepository, lookup: ProjectRateLookup, val clock: Clock, private val saved: SavedStateHandle,
) : ViewModel() {
    val id: String = requireNotNull(saved["id"])
    /** The expense just saved from this project's "New expense" flow; it is selected but not linked until [save]. */
    val fresh: String? = saved["select"]
    val writes = WriteOperation(viewModelScope)
    private val quoteCache = QuoteCache(viewModelScope, lookup)
    val quotes: StateFlow<Map<LocalDate, QuoteResult>> = quoteCache.quotes
    private val load = Loadable(viewModelScope, combine(projects.book, repo.allCategories, repo.allAccounts, ::ProjectData))
    val data: StateFlow<ProjectData?> = load.state
    val loadFailed: StateFlow<Boolean> = load.failed
    private val mutableForm = MutableStateFlow(saved.get<String>(FORM)?.let { runCatching { EventJson.decodeFromString(LinkForm.serializer(), it) }.getOrNull() } ?: LinkForm())
    val form: StateFlow<LinkForm> = mutableForm
    private val mutableManual = MutableStateFlow<Map<RateKey, String>>(emptyMap())
    val manual: StateFlow<Map<RateKey, String>> = mutableManual

    init {
        viewModelScope.launch {
            val first = data.filterNotNull().first()
            val project = first.project(id) ?: return@launch
            if (fresh != null && saved.get<Boolean>(FRESH_DONE) != true) {
                saved[FRESH_DONE] = true
                if (first.candidates(project, clock.today()).any { it.id == fresh }) edit { it.copy(selected = it.selected + fresh) }
            }
        }
        viewModelScope.launch {
            combine(data.filterNotNull(), form) { d, f ->
                val project = d.project(id) ?: return@combine emptyList<LocalDate>()
                d.book.transactions.filter { it.id in f.selected && it.money.currency != project.currency }.map { it.date }.distinct()
            }.collect { quoteCache.ensure(it) }
        }
    }

    fun retry() = load.retry()
    fun retryRates() = quoteCache.retryFailed()
    fun setRate(key: RateKey, text: String) = mutableManual.update { it + (key to text) }

    private fun edit(change: (LinkForm) -> LinkForm) {
        mutableForm.update(change)
        saved[FORM] = EventJson.encodeToString(LinkForm.serializer(), mutableForm.value)
    }

    fun setQuery(text: String) = edit { it.copy(query = text.take(100)) }
    fun setCategory(id: String?) = edit { it.copy(categoryId = id) }

    fun toggle(txId: String) = edit {
        when {
            txId in it.selected -> it.copy(selected = it.selected - txId, limited = false)
            it.selected.size >= MAX_BATCH -> it.copy(limited = true)
            else -> it.copy(selected = it.selected + txId)
        }
    }

    fun selectAll(visible: List<String>) = edit {
        val room = MAX_BATCH - it.selected.size
        val added = visible.filterNot(it.selected::contains)
        it.copy(selected = it.selected + added.take(room.coerceAtLeast(0)), limited = added.size > room)
    }

    fun clear() = edit { it.copy(selected = emptySet(), limited = false) }

    fun chosen(d: ProjectData, project: Project): List<Transaction> {
        val selected = form.value.selected
        return d.candidates(project, clock.today()).filter { it.id in selected }
    }

    fun save() {
        val d = data.value ?: return
        val project = d.project(id)?.takeIf { !it.archived } ?: return
        val plan = linkPlan(chosen(d, project), project, quotes.value, manual.value)
        if (!plan.ready) return
        val category = form.value.categoryId?.takeIf { c -> d.categories(id).any { it.id == c && !it.archived } }
        val costs = plan.items.map { (tx, conversion) ->
            val conv = requireNotNull(conversion)
            ProjectCost(newId(), id, category, tx.money, conv.converted, tx.date, "", tx.id, conv.ratesToRsd, conv.rateDate, conv.source)
        }
        writes.run { projects.record(costs) }
    }

    private companion object {
        const val FORM = "link.form"
        const val FRESH_DONE = "link.freshDone"
    }
}
