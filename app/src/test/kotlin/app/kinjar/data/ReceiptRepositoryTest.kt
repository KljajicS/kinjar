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

    private suspend fun enroll() {
        seed()
        store.activate("user", "family", "dataset", "member", 1, store.captureHistory(), store.currentDigest(), false)
    }

    private fun assertPeerHistory() {
        val peerDb = testDb()
        val peer = EventStore(peerDb, "peer", clock, Projector(peerDb))
        val source = store.captureHistory()
        val copied = peer.writeHistory { emit -> store.historySource(source.id).records().forEach(emit) }
        peer.activate("user", "family", "dataset", "member", 1, copied, peer.currentDigest(), false)
        assertEquals(db.txQueries.all().executeAsList(), peerDb.txQueries.all().executeAsList())
        assertEquals(db.receiptQueries.byId(receipt.id).executeAsOne(), peerDb.receiptQueries.byId(receipt.id).executeAsOne())
        assertTrue(peerDb.syncConflictQueries.all().executeAsList().isEmpty())
        store.discardHistory(source.id)
    }

    @Test fun `synced rescan can be edited and restored on another phone`() = runTest {
        enroll()
        repo.addReceipt(tx(), receipt)
        repo.delete(receipt.transactionId)
        repo.addReceipt(tx().copy(merchant = "Rescanned"), receipt)
        repo.edit(tx().copy(merchant = "Edited rescan", money = Money.of("130", Currency.RSD)))
        store.rebuild()
        assertEquals("Edited rescan", repo.transaction(receipt.transactionId)?.merchant)
        assertEquals(Money.of("130", Currency.RSD), repo.transaction(receipt.transactionId)?.money)
        assertTrue(db.syncConflictQueries.all().executeAsList().isEmpty())
        assertPeerHistory()
    }

    @Test fun `rejected later deletion does not prevent rescan after conflict resolution`() = runTest {
        enroll()
        repo.addReceipt(tx(), receipt)
        repo.delete(receipt.transactionId)
        val deletion = db.syncOperationQueries.all().executeAsList().map { OperationCodec.decode(it.payload) }
            .single { it.events.single().event is TransactionDeleted }
        val competing = SyncOperation(familyId = "family", datasetId = "dataset", authorUid = "other", installId = "other",
            sequence = 1, parents = deletion.parents, resources = deletion.resources,
            events = listOf(OperationEvent("other-delete", "other", 1, clock.now().plusSeconds(60).toEpochMilli(), TransactionDeleted(receipt.transactionId))))
        store.receive(listOf(OperationCodec.encode(competing)), "user", "family", "dataset", 1, 0, competing.id)
        assertEquals(2, db.syncConflictQueries.all().executeAsList().size)
        store.keepConflictingChange(deletion.id)
        assertEquals(deletion.events.single().id, latestReceiptDeletion(db, receipt.transactionId))
        repo.addReceipt(tx().copy(merchant = "Kept rescan"), receipt)
        store.rebuild()
        assertEquals("Kept rescan", repo.transaction(receipt.transactionId)?.merchant)
        assertTrue(db.syncConflictQueries.all().executeAsList().isEmpty())
        assertPeerHistory()
    }

    @Test fun `normal receipt bytes stay compatible and rescan requires version 3`() {
        fun operation(deletionId: String?) = SyncOperation(familyId = "family", datasetId = "dataset", authorUid = "user",
            installId = "one", sequence = 1, parents = emptySet(), resources = mapOf("transaction:${receipt.transactionId}" to ResourceAccess.WRITE),
            events = listOf(OperationEvent("added", "one", 1, 0, TransactionAdded(tx(), receipt, deletionId))))
        val normal = operation(null)
        val legacy = OperationCodec.encode(normal)
        assertEquals(1, normal.version)
        assertFalse(String(legacy).contains("receiptDeletionId"))
        assertArrayEquals(legacy, OperationCodec.encode(OperationCodec.decode(legacy)))
        assertFalse(EventJson.encodeToString(Event.serializer(), TransactionAdded(tx(), receipt)).contains("receiptDeletionId"))
        val rescan = operation("deleted-event")
        val encoded = OperationCodec.encode(rescan)
        assertEquals(3, rescan.version)
        assertEquals(rescan, OperationCodec.decode(encoded))
        assertArrayEquals(encoded, OperationCodec.encode(OperationCodec.decode(encoded)))
        assertThrows(IllegalArgumentException::class.java) {
            OperationCodec.decode(String(encoded).replace("\"version\":3", "\"version\":2").toByteArray())
        }
    }

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
        assertFalse(repo.hasReceipt(receipt.id))
        assertEquals(receipt, repo.receiptForTransaction(receipt.transactionId))
        repo.addReceipt(tx().copy(merchant = "Rescanned"), receipt)
        assertTrue(repo.hasReceipt(receipt.id))
        assertEquals("Rescanned", repo.transactions.first().single().merchant)
        store.rebuild()
        assertEquals("Rescanned", repo.transactions.first().single().merchant)
        assertEquals(receipt, repo.receiptForTransaction(receipt.transactionId))
        assertTrue(runCatching { repo.addReceipt(tx(), receipt) }.exceptionOrNull() is DuplicateReceipt)
    }

    @Test fun `concurrent rescans after deletion restore only one expense`() = runTest {
        seed()
        repo.addReceipt(tx(), receipt)
        repo.delete(receipt.transactionId)
        val results = List(2) { async(Dispatchers.IO) { runCatching { repo.addReceipt(tx(), receipt) } } }.awaitAll()
        assertEquals(1, results.count { it.isSuccess })
        assertTrue(results.single { it.isFailure }.exceptionOrNull() is DuplicateReceipt)
        store.rebuild()
        assertEquals(listOf(tx()), repo.transactions.first())
    }

    @Test fun `stale restoration cannot undo a later deletion in any arrival order`() {
        val records = listOf(
            EventRecord("add", "one", 1, clock.now(), TransactionAdded(tx(), receipt)),
            EventRecord("delete-one", "one", 2, clock.now().plusSeconds(1), TransactionDeleted(receipt.transactionId)),
            EventRecord("restore-one", "one", 3, clock.now().plusSeconds(2), TransactionAdded(tx(), receipt, "delete-one")),
            EventRecord("delete-two", "one", 4, clock.now().plusSeconds(3), TransactionDeleted(receipt.transactionId)),
            EventRecord("stale-restore", "two", 1, clock.now().plusSeconds(4), TransactionAdded(tx(), receipt, "delete-one")),
        )
        listOf(records, records.reversed()).forEach { order ->
            val d = testDb()
            val s = EventStore(d, "local", clock, Projector(d))
            order.forEach { s.applyRemote(listOf(it)) }
            s.rebuild()
            assertTrue(d.txQueries.all().executeAsList().isEmpty())
            val restored = tx().copy(merchant = "Second rescan")
            s.applyRemote(listOf(EventRecord("restore-two", "one", 5, clock.now().plusSeconds(5), TransactionAdded(restored, receipt, "delete-two"))))
            assertEquals("Second rescan", d.txQueries.all().executeAsList().single().merchant)
            assertEquals(receipt.merchant, d.receiptQueries.byId(receipt.id).executeAsOne().merchant)
        }
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
        seedLegacyEvents(old, listOf(TransactionAdded(tx().copy(id = "old", source = TransactionSource.MANUAL))), clock.now())
        KinjarDb.Schema.migrate(driver, 4, 5)
        assertEquals("120.00", old.txQueries.all().executeAsList().single().amount)
        assertEquals(1L, old.eventQueries.count().executeAsOne())
        assertNull(old.receiptQueries.byId(receipt.id).executeAsOneOrNull())
        driver.close()
    }
}
