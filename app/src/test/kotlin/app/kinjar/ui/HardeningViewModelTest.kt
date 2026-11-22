package app.kinjar.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import app.kinjar.RootViewModel
import app.kinjar.data.*
import app.kinjar.data.db.KinjarDb
import app.kinjar.domain.*
import app.kinjar.ui.add.AddViewModel
import app.kinjar.ui.goals.EditGoalState
import app.kinjar.ui.goals.EditGoalViewModel
import app.kinjar.ui.settings.EditAccountViewModel
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class HardeningViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val db = KinjarDb(driver).also { KinjarDb.Schema.create(driver) }
    private val clock = FixedClock(Instant.parse("2026-10-14T10:00:00Z"), ZoneId.of("Europe/Belgrade"))
    private val repo = BudgetRepository(db, EventStore(db, "device", clock, Projector(db)), clock)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    private val models = mutableListOf<ViewModel>()
    @After fun tearDown() {
        runTest(dispatcher) { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        Dispatchers.resetMain()
    }

    @Test fun `money inputs reject exponents excess precision and unbounded values`() {
        for (text in listOf("1e2147483647", "1e-2147483647", "0.001", "12.345", "9".repeat(1000), "-5", "NaN")) {
            assertNull(parseMoneyInput(text, Currency.RSD))
            assertFalse(EditGoalState(name = "Trip", targetText = text).canSave)
        }
        assertFalse(EditGoalState(name = "Trip", targetText = "0").canSave)
        assertEquals(Money.of("12.34", Currency.RSD), parseMoneyInput("12.34", Currency.RSD))
        assertTrue(EditGoalState(name = "Trip", targetText = "100").canSave)
    }

    @Test fun `double tap creates one goal and completed form cannot resubmit`() = runTest(dispatcher) {
        val vm = EditGoalViewModel(repo, SavedStateHandle()).also { models += it }
        vm.setName("Trip")
        vm.setTarget("1000")
        vm.save()
        vm.save()
        assertTrue(vm.writes.state.value.busy)
        vm.writes.state.first { it.finished }
        vm.save()
        assertEquals(1, db.goalQueries.active().executeAsList().size)
        assertEquals(1L, db.eventQueries.count().executeAsOne())
    }

    @Test fun `failed account archive keeps the account and retry archives it once`() = runTest(dispatcher) {
        repo.setUp("S", listOf(Account("cash", "Cash", Currency.RSD), Account("card", "Card", Currency.RSD)))
        repo.add(Transaction("t", TransactionType.INCOME, Money.of("500", Currency.RSD), "card", null, null, clock.today(), "", "", "m", TransactionSource.MANUAL))
        val vm = EditAccountViewModel(repo, SavedStateHandle(mapOf("id" to "card"))).also { models += it }
        vm.state.first { it.name == "Card" && it.balance != null }
        assertEquals(Money.of("500", Currency.RSD), vm.state.value.balanceRsd)
        driver.execute(null, "CREATE TRIGGER fail_archive BEFORE UPDATE ON account BEGIN SELECT RAISE(ABORT, 'disk full'); END", 0)
        vm.delete()
        vm.delete()
        vm.writes.state.first { it.failed }
        assertEquals(2, db.accountQueries.active().executeAsList().size)
        driver.execute(null, "DROP TRIGGER fail_archive", 0)
        val before = db.eventQueries.count().executeAsOne()
        vm.delete()
        vm.state.first { it.done }
        assertEquals(before + 1, db.eventQueries.count().executeAsOne())
        assertEquals(listOf("cash"), db.accountQueries.active().executeAsList().map { it.id })
        assertEquals(1, db.txQueries.all().executeAsList().size)
    }

    @Test fun `startup initializes off caller thread and supports retry`() = runTest(dispatcher) {
        val caller = Thread.currentThread()
        val worker = AtomicReference<Thread>()
        var fail = true
        val vm = RootViewModel(dagger.Lazy {
            worker.set(Thread.currentThread())
            if (fail) error("Cannot open database")
            repo
        }, ThemeStore({ null }, {}), dagger.Lazy { error("Refresh must not block startup") }).also { models += it }
        assertNull(vm.hasMember.value)
        vm.failed.first { it }
        assertNotEquals(caller, worker.get())
        fail = false
        vm.load()
        assertEquals(false, vm.hasMember.first { it != null })
        assertFalse(vm.failed.value)
    }

    @Test fun `failed bill skip retains sheet and retries without duplicate events`() = runTest(dispatcher) {
        repo.setUp("S", listOf(Account("cash", "Cash", Currency.RSD, AccountKind.SPENDING)))
        repo.setRule(RecurringRule("rent", "Rent", Money.of("100", Currency.RSD), "cash", "cat-bills", Cadence.MONTHLY, clock.today()))
        val vm = AddViewModel(repo, clock, SavedStateHandle(mapOf("rule" to "rent", "due" to clock.today().toString()))).also { models += it }
        vm.state.first { it.bill != null && it.accounts.isNotEmpty() }
        driver.execute(null, "CREATE TRIGGER fail_skip BEFORE INSERT ON bill_skip BEGIN SELECT RAISE(ABORT, 'disk full'); END", 0)
        vm.skip()
        vm.skip()
        vm.state.first { it.saveError != null }
        assertFalse(vm.state.value.saved)
        assertFalse(vm.state.value.saving)
        assertTrue(db.billSkipQueries.all().executeAsList().isEmpty())
        driver.execute(null, "DROP TRIGGER fail_skip", 0)
        val before = db.eventQueries.count().executeAsOne()
        vm.skip()
        vm.state.first { it.saved }
        assertEquals(before + 1, db.eventQueries.count().executeAsOne())
        assertEquals(1, db.billSkipQueries.all().executeAsList().size)
    }
}
