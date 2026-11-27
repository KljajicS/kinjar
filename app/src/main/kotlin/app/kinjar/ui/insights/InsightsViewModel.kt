package app.kinjar.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import app.kinjar.data.BudgetRepository
import app.kinjar.data.Clock
import app.kinjar.domain.Category
import app.kinjar.domain.InsightsCalculator
import app.kinjar.domain.InsightsSummary
import app.kinjar.domain.Slice
import app.kinjar.domain.Transaction
import app.kinjar.domain.WeeklyBudget
import app.kinjar.domain.WeeklyBudgetProgress
import app.kinjar.domain.WeeklyBudgetCalculator
import app.kinjar.domain.weekStart
import app.kinjar.ui.RateStatus
import app.kinjar.ui.settings.Loadable
import java.time.YearMonth
import java.time.LocalDate
import javax.inject.Inject

data class InsightsState(
    val summary: InsightsSummary,
    val categories: Map<String, Category>,
    val hasData: Boolean,
    val today: LocalDate,
    val selected: Slice? = null,
    val drilldown: List<Transaction> = emptyList(),
    val accountNames: Map<String, String> = emptyMap(),
    val rateStatus: RateStatus = RateStatus(),
    val budgets: List<WeeklyBudgetProgress> = emptyList(),
    val upcomingBudgets: List<WeeklyBudget> = emptyList(),
) {
    val canGoBack: Boolean get() = summary.month > summary.range.start
    val canGoForward: Boolean get() = summary.month < summary.range.endInclusive
}

@HiltViewModel
class InsightsViewModel @Inject constructor(repo: BudgetRepository, private val clock: Clock) : ViewModel() {
    private data class Selection(val categoryId: String?)

    private val month = MutableStateFlow(YearMonth.from(clock.today()))
    private val selection = MutableStateFlow<Selection?>(null)

    private val load = Loadable(viewModelScope, combine(
        month, selection, repo.financial, repo.allCategories,
        combine(repo.weeklyBudgets, repo.expenseCategories) { schedule, categories ->
            val activeIds = categories.map { it.id }.toSet()
            schedule.filter { it.categoryId in activeIds }
        },
    ) { m, chosen, financial, categories, budgets ->
        val summary = InsightsCalculator.summarize(financial.day, m, financial.transactions, financial.exchange.rates)
        val slice = chosen?.let { s -> summary.slices.firstOrNull { it.categoryId == s.categoryId } }
        InsightsState(
            summary,
            categories.associateBy { it.id },
            hasData = financial.transactions.isNotEmpty(),
            selected = slice,
            drilldown = slice?.let { InsightsCalculator.drilldown(financial.day, summary, it, financial.transactions) }.orEmpty(),
            accountNames = financial.accounts.associate { it.id to it.name },
            rateStatus = RateStatus.from(financial),
            today = financial.day,
            budgets = WeeklyBudgetCalculator.progress(financial.day, financial.day, budgets, financial.transactions, financial.exchange.rates),
            upcomingBudgets = budgets.filter { it.effectiveFrom > weekStart(financial.day) },
        )
    }.flowOn(Dispatchers.Default))
    val state: StateFlow<InsightsState?> = load.state
    val loadFailed: StateFlow<Boolean> = load.failed

    fun retryLoad() = load.retry()

    fun previousMonth() = step(-1)
    fun nextMonth() = step(1)

    /** Tapping the open category again closes it. */
    fun select(slice: Slice) = selection.update { if (it != null && it.categoryId == slice.categoryId) null else Selection(slice.categoryId) }
    fun closeCategory() = selection.update { null }

    private fun step(months: Long) {
        val range = state.value?.summary?.range ?: return
        selection.value = null
        month.update { it.plusMonths(months).coerceIn(range.start, range.endInclusive) }
    }
}
