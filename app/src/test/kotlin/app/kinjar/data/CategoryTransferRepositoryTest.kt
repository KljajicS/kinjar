package app.kinjar.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kinjar.data.backup.BackupCodec
import app.kinjar.data.db.KinjarDb
import app.kinjar.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class CategoryTransferRepositoryTest {
    private val db = testDb()
    private val clock = FixedClock(Instant.parse("2026-11-09T10:00:00Z"), ZoneId.of("Europe/Belgrade"))
    private val store = EventStore(db, "one", clock, Projector(db))
    private val repo = BudgetRepository(db, store, clock, "one")
    private val today = LocalDate.of(2026, 11, 9)
    private fun rsd(value: String) = Money.of(value, Currency.RSD)
    private fun eur(value: String) = Money.of(value, Currency.EUR)
    private fun tx(id: String, type: TransactionType, money: Money = rsd("100"), category: String? = null, to: String? = null,
                   received: Money? = null, source: TransactionSource = TransactionSource.MANUAL, account: String = "cash") =
        Transaction(id, type, money, account, to, category, today, "", "", "m", source, received)

    private suspend fun seed() = repo.setUp("Family", listOf(Account("cash", "Cash", Currency.RSD), Account("bank", "Bank", Currency.RSD),
        Account("euro", "Euro", Currency.EUR)))

    private suspend inline fun <reified T : Throwable> fails(crossinline block: suspend () -> Unit) {
        assertTrue(runCatching { block() }.exceptionOrNull() is T)
    }

    @Test fun `fresh setup seeds editable income categories next to the unchanged expense starters`() = runTest {
        seed()
        assertEquals(StarterCategories.all, repo.expenseCategories.first().sortedBy { c -> StarterCategories.all.indexOfFirst { it.id == c.id } })
        assertEquals(StarterCategories.income.map { it.id }.toSet(), repo.incomeCategories.first().map { it.id }.toSet())
        assertEquals(15, repo.categories.first().size)
        val salary = repo.incomeCategories.first().first { it.id == "cat-income-salary" }
        repo.updateCategory(salary.copy(name = "Pay", icon = "laptop"))
        assertEquals("Pay" to "laptop", repo.incomeCategories.first().first { it.id == salary.id }.let { it.name to it.icon })
    }

    @Test fun `setup income defaults are configurable`() = runTest {
        repo.setUp("Family", listOf(Account("cash", "Cash", Currency.RSD)), incomeCategories = emptyList())
        assertTrue(repo.incomeCategories.first().isEmpty())
    }

    @Test fun `names are unique per kind among active categories ignoring case and spaces`() = runTest {
        seed()
        fails<DuplicateCategoryName> { repo.addCategory("  groceries ", "#000000") }
        repo.addCategory("Groceries", "#000000", "cart", CategoryKind.INCOME)
        fails<DuplicateCategoryName> { repo.addCategory("GROCERIES", "#000000", kind = CategoryKind.INCOME) }
        repo.archiveCategory("cat-fun")
        repo.addCategory("fun", "#000000")
        val bills = repo.expenseCategories.first().first { it.id == "cat-bills" }
        fails<DuplicateCategoryName> { repo.updateCategory(bills.copy(name = "Health")) }
        repo.updateCategory(bills.copy(name = " bills ", color = "#111111"))
        assertEquals("bills", repo.expenseCategories.first().first { it.id == "cat-bills" }.name)
    }

    @Test fun `kind never changes and an update without an icon keeps the stored one`() = runTest {
        seed()
        repo.addCategory("Side job", "#000000", "work", CategoryKind.INCOME)
        val side = repo.incomeCategories.first().first { it.name == "Side job" }
        fails<IllegalArgumentException> { repo.updateCategory(side.copy(kind = CategoryKind.EXPENSE)) }
        store.append(CategoryUpdated(Category(side.id, "Renamed", "#222222")))
        val updated = repo.incomeCategories.first().first { it.id == side.id }
        assertEquals(Category(side.id, "Renamed", "#222222", kind = CategoryKind.INCOME, icon = "work"), updated)
        store.rebuild()
        assertEquals(updated, repo.incomeCategories.first().first { it.id == side.id })
    }

    @Test fun `new writes need a category of the matching kind on every path`() = runTest {
        seed()
        repo.add(tx("salary", TransactionType.INCOME, category = "cat-income-salary"))
        repo.add(tx("food", TransactionType.EXPENSE, category = "cat-groceries"))
        fails<IllegalArgumentException> { repo.add(tx("a", TransactionType.INCOME, category = "cat-groceries")) }
        fails<IllegalArgumentException> { repo.add(tx("b", TransactionType.EXPENSE, category = "cat-income-salary")) }
        fails<IllegalArgumentException> { repo.add(tx("c", TransactionType.TRANSFER, category = "cat-groceries", to = "bank")) }
        fails<IllegalArgumentException> { repo.edit(tx("food", TransactionType.EXPENSE, category = "cat-income-bonus")) }
        val receipt = Receipt("https://suf.purs.gov.rs/v/?vl=kind", "123456789", "ABCDEFGH-IJKLMNOP-2", "Shop", today, rsd("120"), emptyList())
        fails<InvalidReceiptCategory> {
            repo.addReceipt(tx(receipt.transactionId, TransactionType.EXPENSE, rsd("120"), "cat-income-other", source = TransactionSource.RECEIPT), receipt)
        }
        fails<IllegalArgumentException> { repo.setRule(RecurringRule("r", "Rent", rsd("10"), "cash", "cat-income-salary", Cadence.MONTHLY, today)) }
        repo.setRule(RecurringRule("r", "Rent", rsd("10"), "cash", "cat-bills", Cadence.MONTHLY, today))
        val loan = Loan("l", "Car", rsd("1000"), today)
        fails<IllegalArgumentException> {
            repo.loans.create(loan, tx("deposit", TransactionType.LOAN_RECEIPT, rsd("1000"), "cat-income-other"))
        }
        assertEquals(setOf("salary", "food"), repo.transactions.first().map { it.id }.toSet())
    }

    @Test fun `cross currency transfers store both amounts and replay to the same balances`() = runTest {
        seed()
        repo.add(tx("in", TransactionType.INCOME, rsd("5000"), "cat-income-salary"))
        repo.add(tx("fx", TransactionType.TRANSFER, rsd("1170"), to = "euro", received = eur("10")))
        repo.add(tx("same", TransactionType.TRANSFER, rsd("100"), to = "bank"))
        fails<IllegalArgumentException> { repo.add(tx("x", TransactionType.TRANSFER, rsd("1"), to = "euro")) }
        fails<IllegalArgumentException> { repo.add(tx("y", TransactionType.TRANSFER, rsd("1"), to = "bank", received = eur("1"))) }
        val home = repo.home.first()
        assertEquals(mapOf("cash" to rsd("3730"), "bank" to rsd("100"), "euro" to eur("10")), home.balances)
        assertEquals(rsd("5000"), home.income)
        assertTrue(home.spent.isZero)
        assertEquals(eur("10"), repo.recent(10).first().first { it.transaction.id == "fx" }.transaction.received)
        assertEquals("work", repo.recent(10).first().first { it.transaction.id == "in" }.categoryIcon)
        repo.edit(tx("fx", TransactionType.TRANSFER, rsd("1200"), to = "euro", received = eur("10.25")))
        val before = repo.financial.first()
        store.rebuild()
        assertEquals(before.transactions.toSet(), repo.financial.first().transactions.toSet())
        assertEquals(eur("10.25"), repo.home.first().balances["euro"])
    }

    @Test fun `synced writes of new data use operation version 4 and replay from a version 3 backup`() = runTest {
        seed()
        store.activate("user", "family", "dataset", "member", 1, store.captureHistory(), store.currentDigest(), false)
        repo.addCategory("Rent", "#000000", "home", CategoryKind.INCOME)
        repo.add(tx("fx", TransactionType.TRANSFER, rsd("1170"), to = "euro", received = eur("10")))
        repo.addCategory("Pets", "#000000")
        val versions = db.syncOperationQueries.all().executeAsList().map { OperationCodec.decode(it.payload).version }
        assertEquals(listOf(4, 4, 1), versions)

        val captured = store.captureHistory()
        val sealed = ByteArrayOutputStream().also { BackupCodec.seal(store.historySource(captured.id), it, "four long random words".toCharArray()) }
        val opened = store.writeHistory { sink -> BackupCodec.open(sealed.toByteArray().inputStream(), "four long random words".toCharArray(), sink) }
        assertEquals(captured.digest, opened.digest)
        assertEquals(store.currentDigest(), opened.digest)
        val before = repo.financial.first()
        store.validateHistory(opened)
        store.rebuild()
        assertEquals(before.home, repo.financial.first().home)
        assertEquals(CategoryKind.INCOME, repo.categories.first().first { it.name == "Rent" }.kind)
    }

    @Test fun `migration thirteen keeps existing categories and transactions as expense and single currency`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        javaClass.getResource("/schema-v5.sql")!!.readText().split(';').filter { it.isNotBlank() }.forEach { driver.execute(null, it, 0) }
        KinjarDb.Schema.migrate(driver, 5, 13)
        listOf(
            "INSERT INTO account(id, name, currency, kind, archived) VALUES ('a', 'Cash', 'RSD', 'SPENDING', 0)",
            "INSERT INTO category(id, name, color, archived) VALUES ('c', 'Food', '#3D4BD6', 0)",
            "INSERT INTO tx(id, type, amount, currency, account_id, to_account_id, category_id, date, note, merchant, paid_by, source, edited_at) " +
                "VALUES ('t', 'EXPENSE', '10.00', 'RSD', 'a', NULL, 'c', '2026-11-09', '', '', 'm', 'MANUAL', 1)",
        ).forEach { driver.execute(null, it, 0) }
        KinjarDb.Schema.migrate(driver, 13, KinjarDb.Schema.version)
        val migrated = KinjarDb(driver)
        val repository = BudgetRepository(migrated, EventStore(migrated, "one", clock, Projector(migrated)), clock)
        assertEquals(listOf(Category("c", "Food", "#3D4BD6")), repository.categories.first())
        assertEquals(false, repository.accounts.first().single().excludedFromGoals)
        assertEquals(Transaction("t", TransactionType.EXPENSE, rsd("10"), "a", null, "c", today, "", "", "m", TransactionSource.MANUAL),
            repository.transactions.first().single())
        assertNull(repository.recent(1).first().single().categoryIcon)
    }
}
