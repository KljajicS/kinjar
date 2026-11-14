package app.kinjar.ui.projects

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.kinjar.data.BudgetRepository
import app.kinjar.data.EventStore
import app.kinjar.data.FixedClock
import app.kinjar.data.ProjectRepository
import app.kinjar.data.Projector
import app.kinjar.data.rates.ProjectRateLookup
import app.kinjar.data.testDb
import app.kinjar.domain.Account
import app.kinjar.domain.Currency
import app.kinjar.domain.Money
import app.kinjar.domain.Project
import app.kinjar.domain.ProjectCost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class PastCostViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = FixedClock(Instant.parse("2026-10-14T10:00:00Z"), ZoneId.of("Europe/Belgrade"))
    private val db = testDb()
    private val store = EventStore(db, "dev-1", clock, Projector(db))
    private val repo = BudgetRepository(db, store, clock, "dev-1")
    private val projects = ProjectRepository(db, store, clock)
    private val models = mutableListOf<PastCostViewModel>()
    private val day = clock.today().minusDays(100)
    private val cost = ProjectCost("cost", "project", null, Money.of("100", Currency.EUR), Money.of("11700", Currency.RSD),
        day, "Materials", ratesToRsd = mapOf(Currency.EUR to "117"), rateDate = day, rateSource = "manual", unpaid = true)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() {
        runTest(dispatcher) { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        Dispatchers.resetMain()
    }

    private suspend fun seed() {
        repo.setUp("S", listOf(Account("cash", "Cash", Currency.RSD)))
        projects.save(Project("project", "Kitchen", Currency.RSD))
        projects.record(listOf(cost))
    }

    private suspend fun editor(handle: SavedStateHandle = SavedStateHandle(mapOf("id" to "project", "costId" to "cost"))): PastCostViewModel =
        PastCostViewModel(projects, repo, ProjectRateLookup { error("Offline") }, clock, handle)
            .also { models += it }
            .also { vm -> vm.form.first { it.base != null }; vm.data.first { it != null } }

    @Test fun `editing historical cost updates same entry with saved rate and preserves household finances`() = runTest(dispatcher) {
        seed()
        val before = repo.financial.first()
        val vm = editor()
        assertEquals("100", vm.form.value.amount)
        assertEquals(day, vm.form.value.date)
        vm.edit { it.copy(amount = "200", note = " Updated ", unpaid = false) }
        vm.save()
        vm.writes.state.first { it.finished || it.failed }.also { assertTrue(it.finished) }
        assertEquals(listOf(cost.copy(original = Money.of("200", Currency.EUR), converted = Money.of("23400", Currency.RSD),
            note = "Updated", unpaid = false)), projects.readBook().costs)
        assertEquals(before, repo.financial.first())
    }

    @Test fun `restored draft retains baseline and refuses concurrent correction`() = runTest(dispatcher) {
        seed()
        val handle = SavedStateHandle(mapOf("id" to "project", "costId" to "cost"))
        val first = editor(handle)
        first.edit { it.copy(note = "My draft") }
        first.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        val corrected = cost.copy(note = "Other device")
        projects.record(listOf(corrected), expected = listOf(cost))
        val restored = editor(SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        assertEquals("My draft", restored.form.value.note)
        restored.save()
        assertTrue(restored.writes.state.first { it.finished || it.failed }.failed)
        assertEquals(listOf(corrected), projects.readBook().costs)
    }

    @Test fun `changing historical date uses new conversion and rejects future dates`() = runTest(dispatcher) {
        seed()
        val vm = editor()
        vm.edit { it.copy(date = clock.today().plusDays(1), currency = Currency.RSD) }
        vm.save()
        assertFalse(vm.writes.state.value.busy)
        assertEquals(listOf(cost), projects.readBook().costs)
        vm.edit { it.copy(date = day.minusDays(1), currency = Currency.RSD, amount = "500") }
        vm.save()
        assertTrue(vm.writes.state.first { it.finished || it.failed }.finished)
        val updated = projects.readBook().costs.single()
        assertEquals(day.minusDays(1), updated.date)
        assertEquals(Money.of("500", Currency.RSD), updated.original)
        assertEquals(updated.original, updated.converted)
        assertTrue(updated.ratesToRsd.isEmpty())
    }
}
