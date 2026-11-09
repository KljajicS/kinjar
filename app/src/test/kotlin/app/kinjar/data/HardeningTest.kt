package app.kinjar.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import app.kinjar.data.db.KinjarDb
import app.kinjar.domain.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class HardeningTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val db = KinjarDb(driver).also { KinjarDb.Schema.create(driver) }
    private val clock = FixedClock(Instant.parse("2026-10-14T10:00:00Z"), ZoneId.of("Europe/Belgrade"))
    private val store = EventStore(db, "device", clock, Projector(db))
    private val repo = BudgetRepository(db, store, clock)
    private val account = Account("cash", "Cash", Currency.RSD, AccountKind.SPENDING)

    @Test fun `failed setup rolls back member categories accounts and log then retries`() = runTest {
        driver.execute(null, "CREATE TRIGGER fail_category BEFORE INSERT ON category BEGIN SELECT RAISE(ABORT, 'disk full'); END", 0)
        assertTrue(runCatching { repo.setUp("S", listOf(account)) }.isFailure)
        assertTrue(db.memberQueries.all().executeAsList().isEmpty())
        assertTrue(db.categoryQueries.all().executeAsList().isEmpty())
        assertTrue(db.accountQueries.all().executeAsList().isEmpty())
        assertEquals(0L, db.eventQueries.count().executeAsOne())
        driver.execute(null, "DROP TRIGGER fail_category", 0)
        repo.setUp("S", listOf(account))
        assertEquals(1, db.memberQueries.all().executeAsList().size)
        assertEquals(StarterCategories.all.size + StarterCategories.income.size, db.categoryQueries.active().executeAsList().size)
        assertEquals(1, db.accountQueries.active().executeAsList().size)
        assertTrue(runCatching { repo.setUp("Again", listOf(account)) }.isFailure)
        assertEquals(1, db.memberQueries.all().executeAsList().size)
    }

    @Test fun `duplicate remote batch is idempotent and conflicting batch rolls back`() {
        val record = EventRecord("event", "remote", 1, clock.now(), AccountCreated(account))
        store.applyRemote(listOf(record, record))
        store.applyRemote(listOf(record))
        assertEquals(1L, db.eventQueries.count().executeAsOne())
        val next = record.copy(id = "next", seq = 2, event = AccountCreated(account.copy(id = "card")))
        assertTrue(runCatching { store.applyRemote(listOf(next, next.copy(event = AccountArchived("cash")))) }.isFailure)
        assertEquals(1L, db.eventQueries.count().executeAsOne())
    }

    @Test fun `schedule edits preserve paid periods and reject cadence or start changes`() = runTest {
        val rule = RecurringRule("rent", "Rent", Money.of("100", Currency.RSD), account.id, "cat-bills", Cadence.MONTHLY, clock.today())
        repo.setRule(rule)
        repo.setRule(rule.copy(name = "New name"))
        assertTrue(runCatching { repo.setRule(rule.copy(cadence = Cadence.YEARLY)) }.isFailure)
        assertTrue(runCatching { repo.setRule(rule.copy(firstDue = clock.today().minusMonths(1))) }.isFailure)
        assertEquals(rule.copy(name = "New name"), repo.rule(rule.id))
    }

    @Test fun `manual write revalidates archived category and account inside transaction`() = runTest {
        repo.setUp("S", listOf(account))
        val tx = Transaction("t", TransactionType.EXPENSE, Money.of("100", Currency.RSD), account.id, null, "cat-groceries",
            LocalDate.of(2026, 10, 14), "", "", repo.currentMember()!!.id, TransactionSource.MANUAL)
        repo.archiveCategory("cat-groceries")
        assertTrue(runCatching { repo.add(tx) }.isFailure)
        repo.archiveAccount(account.id)
        assertTrue(runCatching { repo.add(tx.copy(categoryId = null)) }.isFailure)
        assertTrue(db.txQueries.all().executeAsList().isEmpty())
    }

    @Test fun `future bill payment keeps the bill upcoming until its date`() = runTest {
        repo.setUp("S", listOf(account))
        val amount = Money.of("100", Currency.RSD)
        val rule = RecurringRule("rent", "Rent", amount, account.id, "cat-bills", Cadence.MONTHLY, clock.today().plusDays(1))
        repo.setRule(rule)
        val period = BillCalculator.period(rule.cadence, rule.firstDue)
        val tx = Transaction(billTxId(rule.id, period), TransactionType.EXPENSE, amount, account.id, null, rule.categoryId,
            rule.firstDue, "", "", repo.currentMember()!!.id, TransactionSource.RECURRING)
        assertEquals(1, BillCalculator.upcoming(clock.today(), listOf(rule), setOf(account.id), listOf(tx), emptySet()).size)
        assertTrue(BillCalculator.upcoming(rule.firstDue, listOf(rule), setOf(account.id), listOf(tx), emptySet()).isEmpty())
    }
}
