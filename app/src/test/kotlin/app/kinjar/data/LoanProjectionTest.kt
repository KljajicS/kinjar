package app.kinjar.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import app.kinjar.data.db.KinjarDb
import app.kinjar.domain.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class LoanProjectionTest {
    private val clock = FixedClock(Instant.parse("2026-10-17T10:00:00Z"), ZoneId.of("Europe/Belgrade"))
    private fun eur(value: String) = Money.of(value, Currency.EUR)

    @Test fun `removed payment cannot resurrect and a new link can reuse transaction`() = runTest {
        val db = testDb()
        val store = EventStore(db, "d", clock, Projector(db))
        val loan = Loan("l", "Car", eur("6800"), LocalDate.of(2026, 10, 17))
        val p = LoanPayment("p", "l", "t", eur("250"), eur("200"))
        store.appendAll(listOf(LoanCreated(loan), LoanPaymentAdded(p), LoanPaymentRemoved(p.id), LoanPaymentUpdated(p.copy(principal = eur("100"))), LoanPaymentAdded(p)))
        assertTrue(db.loanPaymentQueries.all().executeAsList().isEmpty())
        store.append(LoanPaymentAdded(p.copy(id = "p2")))
        store.append(LoanArchived(loan.id))
        store.append(LoanUpdated(loan.copy(name = "Updated")))
        val before = db.loanPaymentQueries.all().executeAsList()
        store.rebuild()
        assertEquals(before, db.loanPaymentQueries.all().executeAsList())
        assertEquals(1L, db.loanQueries.byId(loan.id).executeAsOne().archived)
    }

    @Test fun `schema seven migration preserves financial history through replay`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        javaClass.getResource("/schema-v5.sql")!!.readText().split(';').filter { it.isNotBlank() }.forEach { driver.execute(null, it, 0) }
        KinjarDb.Schema.migrate(driver, 5, 7)
        val oldDb = KinjarDb(driver)
        fun rsd(value: String) = Money.of(value, Currency.RSD)
        val income = Transaction("income", TransactionType.INCOME, rsd("1000"), "a", null, null, clock.today(), "", "", "m", TransactionSource.MANUAL)
        val events = listOf(AccountCreated(Account("a", "Cash", Currency.RSD)), TransactionAdded(income),
            TransactionAdded(income.copy(id = "expense", type = TransactionType.EXPENSE, money = rsd("250"))),
            GoalSet(Goal("g", "Reserve", rsd("1000"))),
            RecurringRuleSet(RecurringRule("r", "Bill", rsd("100"), "a", "c", Cadence.MONTHLY, clock.today())))
        // Rows as the schema 7 projector wrote them; the current projector targets the current schema.
        seedLegacyEventRows(oldDb, events, clock.now())
        val today = clock.today()
        listOf(
            "INSERT INTO account(id, name, currency, kind, archived) VALUES ('a', 'Cash', 'RSD', 'SPENDING', 0)",
            "INSERT INTO tx(id, type, amount, currency, account_id, to_account_id, category_id, date, note, merchant, paid_by, source, edited_at) " +
                "VALUES ('income', 'INCOME', '1000.00', 'RSD', 'a', NULL, NULL, '$today', '', '', 'm', 'MANUAL', 1)",
            "INSERT INTO tx(id, type, amount, currency, account_id, to_account_id, category_id, date, note, merchant, paid_by, source, edited_at) " +
                "VALUES ('expense', 'EXPENSE', '250.00', 'RSD', 'a', NULL, NULL, '$today', '', '', 'm', 'MANUAL', 2)",
            "INSERT INTO goal(id, name, target, monthly, currency) VALUES ('g', 'Reserve', '1000.00', '0.00', 'RSD')",
            "INSERT INTO recurring_rule(id, name, amount, currency, account_id, category_id, cadence, first_due) " +
                "VALUES ('r', 'Bill', '100.00', 'RSD', 'a', 'c', 'MONTHLY', '$today')",
        ).forEach { driver.execute(null, it, 0) }
        KinjarDb.Schema.migrate(driver, 7, KinjarDb.Schema.version)
        val db = KinjarDb(driver)
        assertEquals("Cash", db.accountQueries.byId("a").executeAsOne().name)
        assertEquals(events.size.toLong(), db.eventQueries.count().executeAsOne())
        assertTrue(db.loanQueries.all().executeAsList().isEmpty())
        val store = EventStore(db, "new", clock, Projector(db))
        val repo = BudgetRepository(db, store, clock)
        val before = repo.financial.first()
        store.rebuild()
        val after = repo.financial.first()
        assertEquals(before.home, after.home)
        assertEquals(before.transactions, after.transactions)
        assertEquals(before.goals, after.goals)
        assertEquals(before.bills, after.bills)
        assertEquals(rsd("750"), after.home.savingsRsd)
        assertEquals(rsd("250"), after.home.spent)
        assertEquals(rsd("750"), after.goals.single().funded)
        assertEquals("r", after.bills.single().rule.id)
    }
}
