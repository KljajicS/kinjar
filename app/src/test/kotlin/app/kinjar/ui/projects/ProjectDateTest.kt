package app.kinjar.ui.projects

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.kinjar.data.*
import app.kinjar.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectDateTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = FixedClock(Instant.parse("2026-12-31T23:59:30Z"), ZoneId.of("UTC"))
    private val db = testDb()
    private val store = EventStore(db, "local", clock, Projector(db))
    private val repo = BudgetRepository(db, store, clock, "local")
    private lateinit var vm: ProjectViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = ProjectViewModel(ProjectRepository(db, store, clock), repo, clock, SavedStateHandle(mapOf("id" to "p")))
    }

    @After fun tearDown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun `open project refreshes checklist month without expense changes`() = runTest(dispatcher) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.today.collect {} }
        runCurrent()
        val project = Project("p", "Bills")
        val category = ProjectCategory("water", "p", "Water", "#123456", monthlyExpenseExpected = true)
        val money = Money.of("1", Currency.EUR)
        val cost = ProjectCost("cost", "p", category.id, money, money, clock.today(), "")
        assertEquals(emptyList<ProjectCategory>(), missingMonthlyCategories(project, listOf(category), listOf(cost), YearMonth.from(vm.today.value)))
        clock.instant = clock.instant.plusSeconds(60)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(YearMonth.of(2027, 1), YearMonth.from(vm.today.value))
        assertEquals(listOf(category), missingMonthlyCategories(project, listOf(category), listOf(cost), YearMonth.from(vm.today.value)))
    }
}
