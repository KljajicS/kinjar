package app.kinjar.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kinjar.data.db.KinjarDb
import app.kinjar.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class WeeklyBudgetRepositoryTest {
    private val db = testDb()
    private val clock = FixedClock(Instant.parse("2026-11-22T12:00:00Z"), ZoneId.of("Europe/Belgrade"))
    private val store = EventStore(db, "local", clock, Projector(db))
    private val repo = BudgetRepository(db, store, clock)
    private val monday = weekStart(clock.today())
    private val budget = WeeklyBudget("cat-groceries", monday, Money.of("5000", Currency.RSD))

    @Test fun `save future override disable and replay retain history and reject stale writes`() = runTest {
        repo.setUp("Test", listOf(Account("cash", "Cash", Currency.RSD)))
        repo.setWeeklyBudget(budget, emptyList())
        val original = repo.weeklyBudgets.first()
        val future = budget.copy(effectiveFrom = monday.plusWeeks(1), limit = Money.of("6000", Currency.RSD))
        repo.setWeeklyBudget(future, original)
        assertTrue(runCatching { repo.setWeeklyBudget(budget.copy(limit = null), original) }.isFailure)
        val before = repo.weeklyBudgets.first()
        repo.setWeeklyBudget(future.copy(limit = null), before)
        store.rebuild()
        assertEquals(budget, WeeklyBudgetCalculator.active(repo.weeklyBudgets.first(), monday).single())
        assertTrue(WeeklyBudgetCalculator.active(repo.weeklyBudgets.first(), monday.plusWeeks(1)).isEmpty())
        repo.setWeeklyBudget(budget, repo.weeklyBudgets.first())
        assertEquals(listOf(budget), repo.weeklyBudgets.first())
        store.validateHistory(store.snapshot())
        val restoredDb = testDb()
        val restored = EventStore(restoredDb, "fresh", clock, Projector(restoredDb))
        restored.restoreLocal(store.snapshot(), restored.currentDigest())
        assertEquals(listOf(budget), BudgetRepository(restoredDb, restored, clock).weeklyBudgets.first())
    }

    @Test fun `only active expense categories and current or next week can be written`() = runTest {
        repo.setUp("Test", listOf(Account("cash", "Cash", Currency.RSD)))
        for (week in listOf(monday.minusWeeks(1), monday.plusWeeks(2))) {
            assertTrue(runCatching { repo.setWeeklyBudget(budget.copy(effectiveFrom = week), emptyList()) }.isFailure)
        }
        val income = repo.incomeCategories.first().first()
        assertTrue(runCatching { repo.setWeeklyBudget(budget.copy(categoryId = income.id), emptyList()) }.isFailure)
        repo.archiveCategory(budget.categoryId)
        assertTrue(runCatching { repo.setWeeklyBudget(budget, emptyList()) }.isFailure)
        assertTrue(repo.weeklyBudgets.first().isEmpty())
    }

    @Test fun `weekly schedule has a category dependency and conflicts across effective weeks`() {
        val event = WeeklyBudgetSet(budget)
        val resources = operationResources(listOf(event), db)
        assertEquals(ResourceAccess.READ, resources["category:${budget.categoryId}"])
        assertEquals(ResourceAccess.WRITE, resources["weekly-budget:${budget.categoryId}"])
        assertEquals(resources, operationResources(listOf(WeeklyBudgetSet(budget.copy(effectiveFrom = monday.plusWeeks(1)))), db))
        val operation = SyncOperation("family", "dataset", "user", "local", 1, emptySet(), resources,
            listOf(OperationEvent("event", "local", 1, clock.now().toEpochMilli(), event)))
        assertEquals(12, operation.version)
        assertEquals(operation, OperationCodec.decode(OperationCodec.encode(operation)))
    }

    @Test fun `shared weekly budget operations pass history validation and rebuild`() = runTest {
        repo.setUp("Test", listOf(Account("cash", "Cash", Currency.RSD)))
        val history = store.snapshot()
        store.activate("user", "family", "dataset", repo.currentMember()!!.id, 1, history, HistoryCodec.digest(HistoryCodec.encode(history)), true)
        repo.setWeeklyBudget(budget, emptyList())
        store.validateHistory(store.snapshot())
        store.rebuild()
        assertEquals(listOf(budget), repo.weeklyBudgets.first())
        assertEquals(12, store.snapshot().decodedOperations().last().version)
    }

    @Test fun `schema seventeen upgrade preserves legacy budgets and creates empty weekly schedule`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "CREATE TABLE budget(category_id TEXT NOT NULL, month TEXT NOT NULL, amount TEXT NOT NULL, currency TEXT NOT NULL, PRIMARY KEY(category_id, month))", 0)
        driver.execute(null, "INSERT INTO budget VALUES ('food', '2026-11', '5000.00', 'RSD')", 0)
        KinjarDb.Schema.migrate(driver, 17, 18)
        val upgraded = KinjarDb(driver)
        assertEquals("5000.00", upgraded.budgetQueries.all().executeAsOne().amount)
        assertTrue(upgraded.weeklyBudgetQueries.all().executeAsList().isEmpty())
        driver.close()
    }
}
