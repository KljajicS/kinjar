package app.kinjar.ui.projects

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.kinjar.data.BudgetRepository
import app.kinjar.data.EventStore
import app.kinjar.data.FixedClock
import app.kinjar.data.ProjectRepository
import app.kinjar.data.Projector
import app.kinjar.data.testDb
import app.kinjar.domain.Currency
import app.kinjar.domain.Money
import app.kinjar.domain.Project
import app.kinjar.domain.ProjectCategory
import app.kinjar.domain.ProjectFinance
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
class ProjectSettingsSaveTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = FixedClock(Instant.parse("2026-11-17T10:00:00Z"), ZoneId.of("UTC"))
    private val db = testDb()
    private val store = EventStore(db, "local", clock, Projector(db))
    private val projects = ProjectRepository(db, store, clock)
    private val repo = BudgetRepository(db, store, clock, "local")
    private lateinit var vm: ProjectSettingsViewModel
    private val category = ProjectCategory("water", "p", "Water", "#123456")

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = ProjectSettingsViewModel(projects, repo, SavedStateHandle(mapOf("id" to "p")))
    }

    @After fun tearDown() {
        runTest(dispatcher) { vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
        Dispatchers.resetMain()
    }

    private suspend fun seed() {
        projects.save(Project("p", "Bills"))
        projects.saveCategory(category)
    }

    @Test fun `category completion remains observable after saving has already finished`() = runTest(dispatcher) {
        seed()
        val updated = category.copy(monthlyExpenseExpected = true)
        vm.saveCategory(updated, category)
        vm.categoryWrites.state.first { !it.busy }
        assertEquals(updated, projects.readBook().categories.single())
        assertTrue(vm.categoryWrites.state.value.finished)
        val eventCount = db.eventQueries.count().executeAsOne()
        vm.saveCategory(updated, category)
        assertEquals(eventCount, db.eventQueries.count().executeAsOne())
        assertFalse(vm.categoryWrites.state.value.failed)
    }

    @Test fun `new category signals editor completion`() = runTest(dispatcher) {
        projects.save(Project("p", "Bills"))
        vm.newCategory("Water", "#123456", monthlyExpenseExpected = true)
        vm.categoryWrites.state.first { !it.busy }
        assertTrue(projects.readBook().categories.single().monthlyExpenseExpected)
        assertTrue(vm.categoryWrites.state.value.finished)
    }

    @Test fun `category deletion finishes editor and stale deletion leaves it open`() = runTest(dispatcher) {
        seed()
        val renamed = category.copy(name = "Water bill")
        projects.saveCategory(renamed, category)
        vm.removeCategory(category)
        assertTrue(vm.categoryWrites.state.first { !it.busy }.failed)
        assertFalse(vm.categoryWrites.state.value.finished)
        vm.categoryWrites.reset()
        vm.removeCategory(renamed)
        assertTrue(vm.categoryWrites.state.first { !it.busy }.finished)
        assertTrue(projects.readBook().categories.isEmpty())
    }

    @Test fun `stale category save keeps editor open and preserves stored version`() = runTest(dispatcher) {
        seed()
        val changedElsewhere = category.copy(name = "Water bill")
        projects.saveCategory(changedElsewhere, category)
        vm.saveCategory(category.copy(monthlyExpenseExpected = true), category)
        val state = vm.categoryWrites.state.first { !it.busy }
        assertTrue(state.failed)
        assertFalse(state.finished)
        assertEquals(changedElsewhere, projects.readBook().categories.single())
        vm.categoryWrites.reset()
        assertFalse(vm.categoryWrites.state.value.failed)
        val updated = changedElsewhere.copy(monthlyExpenseExpected = true)
        vm.saveCategory(updated, changedElsewhere)
        assertTrue(vm.categoryWrites.state.first { !it.busy }.finished)
        assertEquals(updated, projects.readBook().categories.single())
    }

    @Test fun `reopening allows successive edits archive and restore without leaving settings`() = runTest(dispatcher) {
        seed()
        var previous = category
        val enabled = category.copy(name = "Water bill", monthlyExpenseExpected = true)
        for (next in listOf(enabled, enabled.copy(archived = true), enabled, category)) {
            vm.categoryWrites.reset()
            assertFalse(vm.categoryWrites.state.value.finished)
            vm.saveCategory(next, previous)
            assertTrue(vm.categoryWrites.state.first { !it.busy }.finished)
            assertEquals(next, projects.readBook().categories.single())
            previous = next
        }
    }

    @Test fun `reset cannot interrupt an in-flight save or allow a second write`() = runTest(dispatcher) {
        seed()
        val updated = category.copy(monthlyExpenseExpected = true)
        vm.saveCategory(updated, category)
        vm.categoryWrites.reset()
        assertTrue(vm.categoryWrites.state.value.busy)
        vm.saveCategory(category.copy(name = "Second save"), category)
        assertTrue(vm.categoryWrites.state.first { !it.busy }.finished)
        assertEquals(updated, projects.readBook().categories.single())
    }

    @Test fun `opening credit signals editor completion after successful save`() = runTest(dispatcher) {
        projects.save(Project("p", "Bills"))
        val amount = Money.of("12", Currency.EUR)
        vm.saveOpeningCredit(amount, ProjectFinance("p"))
        vm.creditWrites.state.first { !it.busy }
        assertEquals(amount, projects.readBook().finance("p").openingCredit)
        assertTrue(vm.creditWrites.state.value.finished)
        vm.creditWrites.reset()
        val zero = Money.zero(Currency.EUR)
        vm.saveOpeningCredit(zero, projects.readBook().finance("p"))
        assertTrue(vm.creditWrites.state.first { !it.busy }.finished)
        assertEquals(zero, projects.readBook().finance("p").openingCredit)
    }
}
