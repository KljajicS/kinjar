package app.kinjar.ui.conflict

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kinjar.data.BudgetRepository
import app.kinjar.data.EventStore
import app.kinjar.data.FixedClock
import app.kinjar.data.Projector
import app.kinjar.data.TransactionConflicts
import app.kinjar.data.db.KinjarDb
import app.kinjar.domain.Account
import app.kinjar.domain.AccountCreated
import app.kinjar.domain.Currency
import app.kinjar.domain.HistoryCodec
import app.kinjar.domain.Member
import app.kinjar.domain.MemberCreated
import app.kinjar.domain.Money
import app.kinjar.domain.OperationCodec
import app.kinjar.domain.OperationEvent
import app.kinjar.domain.ResourceAccess
import app.kinjar.domain.SyncOperation
import app.kinjar.domain.Transaction
import app.kinjar.domain.TransactionAdded
import app.kinjar.domain.TransactionEdited
import app.kinjar.domain.TransactionSource
import app.kinjar.domain.TransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionConflictViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = FixedClock(Instant.parse("2026-11-04T10:00:00Z"), ZoneId.of("UTC"))
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val db = KinjarDb(driver).also { KinjarDb.Schema.create(driver) }
    private val store = EventStore(db, "local", clock, Projector(db))
    private val repo = BudgetRepository(db, store, clock, "local")
    private val conflicts = TransactionConflicts(db, store)
    private val models = mutableListOf<ViewModel>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() {
        runTest(dispatcher) { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        Dispatchers.resetMain()
    }

    private fun hide(table: String) = driver.execute(null, "ALTER TABLE $table RENAME TO ${table}_hidden", 0)
    private fun restore(table: String) = driver.execute(null, "ALTER TABLE ${table}_hidden RENAME TO $table", 0)

    private fun tx(amount: String) = Transaction("t", TransactionType.EXPENSE, Money.of(amount, Currency.RSD), "a", null, null,
        LocalDate.of(2026, 11, 4), "", "Groceries", "payer", TransactionSource.MANUAL)

    private fun edit(device: String, amount: String) = SyncOperation(
        familyId = "family", datasetId = "dataset", authorUid = "uid-$device", installId = device, sequence = 1, parents = emptySet(),
        resources = mapOf("transaction:t" to ResourceAccess.WRITE, "account:a" to ResourceAccess.READ),
        events = listOf(OperationEvent("$device-1", device, 1, clock.now().toEpochMilli() + 1, TransactionEdited(tx(amount)))))

    private suspend fun heldConflict() {
        store.appendAll(listOf(MemberCreated(Member("payer", "Ana", "uid-one")), AccountCreated(Account("a", "Cash", Currency.RSD)), TransactionAdded(tx("1200"))))
        store.activate("user", "family", "dataset", "member", 1, store.snapshot(), HistoryCodec.digest(HistoryCodec.encode(store.snapshot())), true)
        val ops = listOf(edit("one", "1400"), edit("two", "1300"))
        store.receive(ops.map(OperationCodec::encode), "user", "family", "dataset", 100, 0, ops.last().id)
    }

    private fun model() = TransactionConflictViewModel(conflicts, repo, SavedStateHandle(mapOf("id" to "t"))).also { models += it }

    @Test fun `an initial lookup failure stays unloaded until a retry succeeds`() = runTest(dispatcher) {
        heldConflict()
        hide("sync_conflict")
        val vm = model()
        backgroundScope.launch { vm.review.collect {} }
        val failed = vm.review.first { it.failed }
        assertFalse(failed.loaded)
        assertNull(failed.review)

        restore("sync_conflict")
        vm.retry()
        val loaded = vm.review.first { it.loaded && !it.failed }
        assertEquals(2, requireNotNull(loaded.review).versions.size)
    }

    @Test fun `a later lookup failure keeps the shown review and badges until a retry succeeds`() = runTest(dispatcher) {
        heldConflict()
        val vm = model()
        backgroundScope.launch { vm.review.collect {} }
        backgroundScope.launch { vm.held.collect {} }
        assertNotNull(vm.review.first { it.loaded }.review)
        assertEquals(setOf("t"), vm.held.first { it.ids.isNotEmpty() }.ids)

        hide("sync_operation")
        driver.notifyListeners("sync_conflict")
        val failed = vm.review.first { it.failed }
        assertEquals(2, requireNotNull(failed.review).versions.size)
        assertEquals(setOf("t"), vm.held.first { it.failed }.ids)

        restore("sync_operation")
        val before = store.currentDigest()
        vm.keep(requireNotNull(failed.review).versions.first().operationId)
        vm.keepCurrent()
        assertFalse(vm.writes.state.value.busy)
        assertEquals(before, store.currentDigest())
        vm.retry()
        assertEquals(2, requireNotNull(vm.review.first { !it.failed }.review).versions.size)
        assertEquals(setOf("t"), vm.held.first { !it.failed }.ids)
    }
}
