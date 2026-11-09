package app.kinjar.ui.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
import app.kinjar.data.ThemeStore
import app.kinjar.data.db.KinjarDb
import app.kinjar.domain.Account
import app.kinjar.domain.Currency
import app.kinjar.domain.Goal
import app.kinjar.domain.Money
import app.kinjar.ui.CategoryPalette
import app.kinjar.ui.goals.EditGoalViewModel
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class RecoverySettingsTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = FixedClock(Instant.parse("2026-10-14T10:00:00Z"), ZoneId.of("Europe/Belgrade"))
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val db = KinjarDb(driver).also { KinjarDb.Schema.create(driver) }
    private val repo = BudgetRepository(db, EventStore(db, "dev-1", clock, Projector(db)), clock, "dev-1")
    private val models = mutableListOf<ViewModel>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() {
        runTest(dispatcher) { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        Dispatchers.resetMain()
    }

    private fun hide(table: String) = driver.execute(null, "ALTER TABLE $table RENAME TO ${table}_hidden", 0)
    private fun restore(table: String) = driver.execute(null, "ALTER TABLE ${table}_hidden RENAME TO $table", 0)
    private fun events() = db.eventQueries.count().executeAsOne()

    @Test
    fun `settings member read failure keeps the typed name and retry enables saving`() = runTest(dispatcher) {
        repo.setUp("Ana", listOf(Account("cash", "Cash", Currency.RSD)))
        val before = events()
        hide("member")
        val vm = SettingsViewModel(repo, ThemeStore({ null }, {})).also { models += it }
        vm.state.first { it.loadFailed }
        vm.setName("Ana M")
        vm.saveName()
        restore("member")
        vm.retryLoad()
        val s = vm.state.first { !it.loadFailed && it.memberId != null }
        assertEquals("Ana M", s.name)
        assertEquals(before, events())
    }

    @Test
    fun `a name typed while an earlier name is saving stays unsaved`() = runTest(dispatcher) {
        repo.setUp("Ana", listOf(Account("cash", "Cash", Currency.RSD)))
        val vm = SettingsViewModel(repo, ThemeStore({ null }, {})).also { models += it }
        vm.state.first { it.memberId != null }
        vm.setName("Ana M")
        vm.saveName()
        vm.setName("Ana Markovic")
        vm.writes.state.first { !it.busy }
        assertFalse(vm.state.value.saved)
        assertEquals("Ana Markovic", vm.state.value.name)
        assertEquals("Ana M", repo.currentMember()?.displayName)

        vm.saveName()
        vm.state.first { it.saved }
        assertEquals("Ana Markovic", repo.currentMember()?.displayName)
    }

    @Test
    fun `category list and editor recover and an unread category cannot be saved or deleted`() = runTest(dispatcher) {
        repo.setUp("S", listOf(Account("cash", "Cash", Currency.RSD)))
        val before = events()
        hide("category")
        val list = CategoriesViewModel(repo).also { models += it }
        val collector = backgroundScope.launch { list.categories.collect {} }
        list.failed.first { it }
        val edit = EditCategoryViewModel(repo, SavedStateHandle(mapOf("id" to "cat-bills"))).also { models += it }
        edit.state.first { it.loadFailed }
        edit.setName("Typed")
        assertFalse(edit.state.value.canSave)
        edit.save()
        edit.delete()
        restore("category")
        list.retry()
        edit.retryLoad()
        assertTrue(list.categories.filterNotNull().first().expense.isNotEmpty())
        val s = edit.state.first { it.loaded }
        assertEquals("Bills", s.name)
        assertFalse(s.loadFailed)
        assertEquals(before, events())
        collector.cancel()
    }

    @Test
    fun `new bill keeps typed draft across a failed list read`() = runTest(dispatcher) {
        repo.setUp("S", listOf(Account("cash", "Cash", Currency.RSD)))
        hide("account")
        val vm = EditRecurringViewModel(repo, clock, SavedStateHandle()).also { models += it }
        vm.state.first { it.loadFailed }
        vm.setName("Rent")
        vm.setAmount("100")
        assertFalse(vm.state.value.canSave)
        restore("account")
        vm.retryLoad()
        val s = vm.state.first { it.loaded }
        assertEquals("Rent", s.name)
        assertEquals("100", s.amountText)
        assertEquals("cash", s.accountId)
        assertTrue(s.canSave)
    }

    @Test
    fun `missing rule and unreadable goal block save and delete`() = runTest(dispatcher) {
        repo.setUp("S", listOf(Account("cash", "Cash", Currency.RSD)))
        val rule = EditRecurringViewModel(repo, clock, SavedStateHandle(mapOf("id" to "gone"))).also { models += it }
        rule.state.first { it.loadFailed }
        rule.delete()

        repo.setGoal(Goal("g", "Trip", Money.of("1000", Currency.EUR), Money.zero(Currency.EUR)))
        val before = events()
        hide("goal")
        val goal = EditGoalViewModel(repo, SavedStateHandle(mapOf("id" to "g"))).also { models += it }
        goal.state.first { it.loadFailed }
        goal.setName("Stale")
        goal.setTarget("5")
        goal.save()
        goal.delete()
        restore("goal")
        goal.retryLoad()
        val s = goal.state.first { it.loaded }
        assertEquals("Trip", s.name)
        assertEquals(Currency.EUR, s.currency)
        assertEquals(before, events())
    }

    @Test
    fun `restarting an editor read cancels the one still running`() = runTest(dispatcher) {
        var started = 0
        var active = 0
        val load = EditorLoad(backgroundScope, {}) {
            started++
            active++
            try { awaitCancellation() } finally { active-- }
        }
        load.start()
        runCurrent()
        load.start()
        load.start()
        runCurrent()
        assertEquals(2, started)
        assertEquals(1, active)
    }

    @Test
    fun `every palette color has a spoken name`() {
        assertEquals(CategoryPalette.size, PaletteNames.size)
        assertEquals(CategoryPalette.size, PaletteNames.toSet().size)
    }
}
