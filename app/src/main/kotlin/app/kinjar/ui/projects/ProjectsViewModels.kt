package app.kinjar.ui.projects

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinjar.data.BudgetRepository
import app.kinjar.data.Clock
import app.kinjar.data.ProjectBook
import app.kinjar.data.ProjectRepository
import app.kinjar.domain.Account
import app.kinjar.domain.Category
import app.kinjar.domain.Currency
import app.kinjar.domain.Money
import app.kinjar.domain.Project
import app.kinjar.domain.ProjectCategory
import app.kinjar.domain.ProjectCost
import app.kinjar.domain.Transaction
import app.kinjar.ui.WriteOperation
import app.kinjar.ui.settings.Loadable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.YearMonth
import javax.inject.Inject

data class CategoryTotal(val category: ProjectCategory?, val total: Money)

data class ProjectSummary(val project: Project, val total: Money, val byCategory: List<CategoryTotal>, val unpaid: Money)

fun sum(costs: List<ProjectCost>, currency: Currency): Money =
    costs.filter { it.converted.currency == currency }.fold(Money.zero(currency)) { total, cost -> total + cost.converted }

/** Largest first; a cost whose category is gone counts as uncategorized. */
fun categoryTotals(costs: List<ProjectCost>, categories: List<ProjectCategory>, currency: Currency): List<CategoryTotal> {
    val byId = categories.associateBy { it.id }
    return costs.groupBy { it.categoryId?.let(byId::get) }
        .map { (category, list) -> CategoryTotal(category, sum(list, currency)) }
        .filter { !it.total.isZero }
        .sortedByDescending { it.total.amount }
}

/** A fixed 12 month window ending at the latest cost; older months stay in the totals and the category view. */
fun monthTotals(costs: List<ProjectCost>, currency: Currency, today: YearMonth): List<Pair<YearMonth, Money>> {
    val last = costs.maxOfOrNull { YearMonth.from(it.date) } ?: today
    val byMonth = costs.groupBy { YearMonth.from(it.date) }
    return (11 downTo 0).map { back -> last.minusMonths(back.toLong()).let { it to sum(byMonth[it].orEmpty(), currency) } }
}

fun ProjectBook.summary(project: Project): ProjectSummary {
    val costs = costsFor(project.id)
    return ProjectSummary(project, sum(costs, project.currency), categoryTotals(costs, categories.filter { it.projectId == project.id }, project.currency),
        sum(costs.filter { it.unpaid }, project.currency))
}

data class ProjectData(val book: ProjectBook, val household: List<Category>, val accounts: List<Account>) {
    val householdById: Map<String, Category> by lazy { household.associateBy { it.id } }
    val transactionsById: Map<String, Transaction> by lazy { book.transactions.associateBy { it.id } }

    fun project(id: String): Project? = book.projects.find { it.id == id }
    fun categories(projectId: String): List<ProjectCategory> = book.categories.filter { it.projectId == projectId }
}

@HiltViewModel
class ProjectsViewModel @Inject constructor(projects: ProjectRepository) : ViewModel() {
    private val load = Loadable(viewModelScope, projects.book.map { book ->
        book.projects.sortedBy { it.name.lowercase() }.map { book.summary(it) }
    }.flowOn(Dispatchers.Default))
    val state: StateFlow<List<ProjectSummary>?> = load.state
    val loadFailed: StateFlow<Boolean> = load.failed
    fun retry() = load.retry()
}

@HiltViewModel
class ProjectViewModel @Inject constructor(
    private val projects: ProjectRepository, repo: BudgetRepository, val clock: Clock, saved: SavedStateHandle,
) : ViewModel() {
    val id: String = requireNotNull(saved["id"])
    val writes = WriteOperation(viewModelScope)
    private val load = Loadable(viewModelScope, combine(projects.book, repo.allCategories, repo.allAccounts, ::ProjectData))
    val data: StateFlow<ProjectData?> = load.state
    val loadFailed: StateFlow<Boolean> = load.failed
    fun retry() = load.retry()

    fun removeCost(cost: ProjectCost) = writes.run(finish = false) { projects.removeCost(cost.id, cost) }
}
