package app.kinjar.ui.insights

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import app.kinjar.data.BudgetRepository
import app.kinjar.data.EventStore
import app.kinjar.data.FixedClock
import app.kinjar.data.Projector
import app.kinjar.data.testDb
import app.kinjar.domain.Account
import app.kinjar.domain.AccountKind
import app.kinjar.domain.Currency
import app.kinjar.domain.Money
import app.kinjar.domain.Transaction
import app.kinjar.domain.TransactionSource
import app.kinjar.domain.TransactionType
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class InsightsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = FixedClock(Instant.parse("2026-10-14T10:00:00Z"), ZoneId.of("Europe/Belgrade"))
    private val db = testDb()
    private val repo = BudgetRepository(db, EventStore(db, "dev-1", clock, Projector(db)), clock, "dev-1")

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    private val models = mutableListOf<InsightsViewModel>()
    @After fun tearDown() {
        runTest(dispatcher) { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        Dispatchers.resetMain()
    }

    @Test
    fun `weekly budgets remain current when viewing a previous month`() = runTest(dispatcher) {
        repo.setUp("S", listOf(Account("cash", "Cash", Currency.RSD, AccountKind.SPENDING)))
        val member = repo.currentMember()!!.id
        repo.add(Transaction("old", TransactionType.EXPENSE, Money.of("100", Currency.RSD), "cash", null, "cat-fun", LocalDate.of(2026, 9, 1), "", "", member, TransactionSource.MANUAL))
        repo.add(Transaction("current", TransactionType.EXPENSE, Money.of("250", Currency.RSD), "cash", null, "cat-fun", clock.today(), "", "", member, TransactionSource.MANUAL))
        repo.setWeeklyBudget(app.kinjar.domain.WeeklyBudget("cat-fun", app.kinjar.domain.weekStart(clock.today()), Money.of("500", Currency.RSD)), emptyList())
        val vm = InsightsViewModel(repo, clock).also { models += it }
        val current = vm.state.filterNotNull().first()
        assertEquals("cat-fun", current.budgets.single().budget.categoryId)
        assertEquals(Money.of("250", Currency.RSD), current.budgets.single().spent)
        assertEquals(Money.of("250", Currency.RSD), current.budgets.single().remaining)
        vm.previousMonth()
        val previous = vm.state.filterNotNull().first { it.summary.month == YearMonth.of(2026, 9) }
        assertEquals(current.budgets, previous.budgets)
        assertEquals(clock.today(), previous.today)
    }

    @Test
    fun `month arrows stop at the first transaction and at today, archived categories keep their name`() = runTest(dispatcher) {
        repo.setUp("S", listOf(Account("cash", "Cash", Currency.RSD, AccountKind.SPENDING)))
        val m = repo.currentMember()!!.id
        repo.add(Transaction("t1", TransactionType.EXPENSE, Money.of("500", Currency.RSD), "cash", null, "cat-fun", LocalDate.of(2026, 8, 3), "", "", m, TransactionSource.MANUAL))
        repo.archiveCategory("cat-fun")

        val vm = InsightsViewModel(repo, clock).also { models += it }
        var s = vm.state.filterNotNull().first { it.hasData }
        assertEquals(YearMonth.of(2026, 10), s.summary.month)
        assertTrue(s.canGoBack)
        assertFalse(s.canGoForward)
        assertEquals("Fun", s.categories["cat-fun"]?.name)

        vm.previousMonth()
        vm.previousMonth()
        vm.previousMonth()
        s = vm.state.filterNotNull().first { it.summary.month == YearMonth.of(2026, 8) }
        assertFalse(s.canGoBack)
        assertEquals(listOf("cat-fun"), s.summary.slices.map { it.categoryId })

        vm.select(s.summary.slices.single())
        s = vm.state.filterNotNull().first { it.selected != null }
        assertEquals(listOf("t1"), s.drilldown.map { it.id })
        assertEquals("Cash", s.accountNames["cash"])
        vm.select(s.summary.slices.single())
        vm.state.filterNotNull().first { it.selected == null }
        vm.select(s.summary.slices.single())
        vm.state.filterNotNull().first { it.selected != null }

        vm.nextMonth()
        vm.nextMonth()
        vm.nextMonth()
        s = vm.state.filterNotNull().first { it.summary.month == YearMonth.of(2026, 10) }
        assertFalse(s.canGoForward)
        assertEquals(null, s.selected)
    }
}
