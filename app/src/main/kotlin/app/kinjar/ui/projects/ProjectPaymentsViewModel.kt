package app.kinjar.ui.projects

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinjar.data.BudgetRepository
import app.kinjar.data.Clock
import app.kinjar.data.ProjectRepository
import app.kinjar.data.rates.ProjectRateLookup
import app.kinjar.domain.*
import app.kinjar.ui.WriteOperation
import app.kinjar.ui.parseMoneyInput
import app.kinjar.ui.settings.Loadable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class ProjectPaymentsViewModel @Inject constructor(
    private val projects: ProjectRepository, repo: BudgetRepository, lookup: ProjectRateLookup, val clock: Clock, saved: SavedStateHandle,
) : ViewModel() {
    val id: String = requireNotNull(saved["id"])
    val mode: String = saved["mode"] ?: "history"
    val selected: List<String> = EventJson.decodeFromString(saved.get<String>("selected") ?: "[]")
    val writes = WriteOperation(viewModelScope)
    private val load = Loadable(viewModelScope, combine(projects.book, repo.allCategories, repo.allAccounts, ::ProjectData))
    val data = load.state
    val failed = load.failed
    private val cache = QuoteCache(viewModelScope, lookup)
    val quotes = cache.quotes
    val manual = MutableStateFlow<Map<RateKey, String>>(emptyMap())
    fun retry() = load.retry()
    fun ratesFor(tx: Transaction) = cache.ensure(listOf(tx.date))
    fun retryRates() = cache.retryFailed()
    fun setRate(key: RateKey, text: String) = manual.update { it + (key to text) }
    fun save(value: ProjectFinance, expected: ProjectFinance, costs: List<ProjectCost> = emptyList(), finish: Boolean = true) =
        writes.run(finish) { projects.saveFinance(value, expected, costs) }
}

fun paymentSnapshot(tx: Transaction, conversion: Conversion) = ProjectPayment(tx.money, conversion.converted, tx.date, tx.id,
    conversion.ratesToRsd, conversion.rateDate, conversion.source)

fun signedProjectMoney(text: String, currency: Currency): Money? {
    val input = text.trim()
    val unsigned = if (input.startsWith("-") || input.startsWith("+")) input.drop(1) else input
    val value = parseMoneyInput(unsigned, currency) ?: return null
    return if (input.startsWith("-")) -value else value
}
