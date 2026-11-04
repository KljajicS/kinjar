package app.kinjar.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import app.kinjar.data.db.KinjarDb
import app.kinjar.domain.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ReceiptRepositoryTest {
    private val db = testDb()
    private val clock = FixedClock(Instant.parse("2026-10-15T10:00:00Z"), ZoneId.of("Europe/Belgrade"))
    private val store = EventStore(db, "one", clock, Projector(db))
    private val repo = BudgetRepository(db, store, clock, "one")
    private val receipt = Receipt("https://suf.purs.gov.rs/v/?vl=test", "123456789", "ABCDEFGH-IJKLMNOP-1", "Shop",
        LocalDate.of(2026, 10, 15), Money.of("120", Currency.RSD), listOf(ReceiptItem("Food", BigDecimal.ONE, BigDecimal("120"), BigDecimal("120"))))
    private fun tx(r: Receipt = receipt) = Transaction(r.transactionId, TransactionType.EXPENSE, r.total, "cash", null,
        "cat-groceries", r.date, "", r.merchant, "member", TransactionSource.RECEIPT)
    private suspend fun seed() = repo.setUp("Family", listOf(Account("cash", "Cash", Currency.RSD, AccountKind.SPENDING)))

    @Test fun `receipt lookup preserves original metadata and fractional items through replay`() = runTest {
        seed()
        assertNull(repo.receiptForTransaction("missing"))
        val original = receipt.copy(items = listOf(ReceiptItem("Weighted item", BigDecimal("0.345"),
            BigDecimal("100.00"), BigDecimal("34.50"))), total = Money.of("34.50", Currency.RSD))
        repo.addReceipt(tx(original), original)
        assertEquals(original, repo.receiptForTransaction(original.transactionId))
        repo.edit(tx(original).copy(merchant = "Corrected", money = Money.of("40", Currency.RSD)))
        store.rebuild()
        assertEquals(original, repo.receiptForTransaction(original.transactionId))
        assertEquals("Corrected", repo.transaction(original.transactionId)?.merchant)
    }

    @Test fun `concurrent confirmations store one expense and one receipt event`() = runTest {
        seed()
        val before = db.eventQueries.count().executeAsOne()
        val results = List(2) { async(Dispatchers.IO) { runCatching { repo.addReceipt(tx(), receipt) } } }.awaitAll()
        assertEquals(1, results.count { it.isSuccess })
        val failure = results.single { it.isFailure }.exceptionOrNull()
        assertTrue(failure.toString(), failure is DuplicateReceipt)
        assertEquals(before + 1, db.eventQueries.count().executeAsOne())
        assertEquals(1, repo.transactions.first().size)
        assertEquals("cat-groceries", repo.receiptCategory(receipt.issuer))
        repo.archiveCategory("cat-groceries")
        assertNull(repo.receiptCategory(receipt.issuer))
    }

    @Test fun `delete and late duplicate never resurrect receipt expense on rebuild`() = runTest {
        seed()
        repo.addReceipt(tx(), receipt)
        assertEquals(receipt, repo.receiptForTransaction(receipt.transactionId))
        repo.delete(receipt.transactionId)
        store.applyRemote(listOf(EventRecord("remote", "two", 1, clock.now().plusSeconds(60), TransactionAdded(tx(), receipt))))
        store.rebuild()
        assertTrue(repo.transactions.first().isEmpty())
        assertTrue(repo.hasReceipt(receipt.id))
        assertEquals(receipt, repo.receiptForTransaction(receipt.transactionId))
        assertTrue(runCatching { repo.addReceipt(tx(), receipt) }.exceptionOrNull() is DuplicateReceipt)
    }

    @Test fun `failed validation writes neither event nor receipt`() = runTest {
        seed()
        repo.updateAccount(Account("cash", "Foreign", Currency.EUR, AccountKind.SPENDING))
        val before = db.eventQueries.count().executeAsOne()
        assertTrue(runCatching { repo.addReceipt(tx(), receipt) }.exceptionOrNull() is InvalidReceiptAccount)
        assertEquals(before, db.eventQueries.count().executeAsOne())
        assertFalse(repo.hasReceipt(receipt.id))
        assertTrue(repo.transactions.first().isEmpty())
    }

    @Test fun `projection failure rolls back event and receipt together`() = runTest {
        seed()
        val before = db.eventQueries.count().executeAsOne()
        assertTrue(runCatching { store.append(TransactionAdded(tx().copy(id = "wrong"), receipt)) }.isFailure)
        assertEquals(before, db.eventQueries.count().executeAsOne())
        assertFalse(repo.hasReceipt(receipt.id))
    }

    @Test fun `duplicate replay order converges to matching original receipt and expense`() {
        val other = receipt.copy(merchant = "Other")
        val records = listOf(
            EventRecord("a", "one", 1, clock.now(), TransactionAdded(tx(), receipt)),
            EventRecord("b", "two", 1, clock.now().plusSeconds(1), TransactionAdded(tx(other), other)),
        )
        fun replay(order: List<EventRecord>): Pair<String, String> {
            val d = testDb()
            val s = EventStore(d, "local", clock, Projector(d))
            order.forEach { s.applyRemote(listOf(it)) }
            return d.txQueries.all().executeAsList().single().merchant to d.receiptQueries.byId(receipt.id).executeAsOne().merchant
        }
        assertEquals("Shop" to "Shop", replay(records))
        assertEquals(replay(records), replay(records.reversed()))
    }

    @Test fun `old transaction JSON decodes without receipt`() {
        val old = EventJson.encodeToString(Event.serializer(), TransactionAdded(tx())).replace(",\"receipt\":null", "")
        assertNull((EventJson.decodeFromString(Event.serializer(), old) as TransactionAdded).receipt)
    }

    @Test fun `schema upgrade retains existing transaction and event data`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        javaClass.getResource("/schema-v4.sql")!!.readText().split(';').filter { it.isNotBlank() }
            .forEach { driver.execute(null, it, 0) }
        val old = KinjarDb(driver)
        val oldStore = EventStore(old, "old", clock, Projector(old))
        oldStore.append(TransactionAdded(tx().copy(id = "old", source = TransactionSource.MANUAL)))
        KinjarDb.Schema.migrate(driver, 4, 5)
        assertEquals("120.00", old.txQueries.all().executeAsList().single().amount)
        assertEquals(1L, old.eventQueries.count().executeAsOne())
        assertNull(old.receiptQueries.byId(receipt.id).executeAsOneOrNull())
        driver.close()
    }
}
