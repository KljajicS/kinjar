package app.kinjar.data

import app.kinjar.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SyncOperationStoreTest {
    private val db = testDb()
    private val clock = FixedClock(Instant.parse("2026-11-04T10:00:00Z"), ZoneId.of("UTC"))
    private val store = EventStore(db, "device", clock, Projector(db))
    private fun activate(install: String = "device") {
        db.syncSessionQueries.save("user", "family", "dataset", "member", install, 1, "ACTIVE")
    }
    private fun account(id: String) = AccountCreated(Account(id, id, Currency.RSD, AccountKind.SPENDING))

    @Test fun `whole command produces one durable operation and one outbox entry`() = runTest {
        activate()
        val records = store.appendAll(listOf(account("a"), account("b")))
        val saved = db.syncOperationQueries.all().executeAsList().single()
        val operation = OperationCodec.decode(saved.payload)
        assertEquals(records, operation.events.map { it.record() })
        assertEquals("device:1", operation.id)
        val queued = db.syncOutboxQueries.pending("family", "dataset").executeAsList().single()
        assertEquals(saved.id, queued.id)
        assertArrayEquals(saved.payload, queued.payload)
        val reopened = EventStore(db, "device", clock, Projector(db))
        reopened.append(AccountUpdated(Account("a", "Renamed", Currency.RSD, AccountKind.SPENDING)))
        val next = OperationCodec.decode(db.syncOperationQueries.all().executeAsList().last().payload)
        assertEquals(2L, next.sequence)
        assertEquals(setOf("device:1"), next.parents)
    }

    @Test fun `account and category edits still sync after twenty thousand independent transactions from two phones`() = runTest {
        activate()
        store.appendAll(listOf(account("a"), CategoryCreated(Category("c", "Food", "#000000"))))
        val date = java.time.LocalDate.of(2026, 11, 4)
        val phones = listOf("0f8fad5b-d9cb-469f-a165-70867728950e", "7c9e6679-7425-40de-944b-e07fc1f90ae7")
        val history = phones.flatMap { phone ->
            (1..10_500L).map { seq ->
                val tx = Transaction("$phone-t$seq", TransactionType.EXPENSE, Money.of("1", Currency.RSD), "a", null, "c", date, "", "", "member", TransactionSource.MANUAL)
                SyncOperation(familyId = "family", datasetId = "dataset", authorUid = "peer", installId = phone, sequence = seq, parents = setOf("device:1"),
                    resources = mapOf("transaction:$phone-t$seq" to ResourceAccess.WRITE, "account:a" to ResourceAccess.READ, "category:c" to ResourceAccess.READ),
                    events = listOf(OperationEvent("$phone-$seq", phone, seq, seq, TransactionAdded(tx))))
            }
        }
        val parents = history.mapTo(mutableSetOf()) { it.id }
        assertTrue(parents.sumOf { it.length + 3 } > OperationCodec.MAX_BYTES)
        db.transaction { history.forEach { db.syncOperationQueries.insert(it.id, it.installId, it.sequence, OperationCodec.encode(it)) } }

        store.append(AccountUpdated(Account("a", "Renamed", Currency.RSD, AccountKind.SPENDING)))
        store.append(CategoryUpdated(Category("c", "Groceries", "#000000")))

        val saved = db.syncOperationQueries.all().executeAsList().filter { it.install_id == "device" }
        assertEquals(listOf(1, 2, 2), saved.map { OperationCodec.decode(it.payload).version })
        saved.drop(1).forEach { row ->
            val operation = OperationCodec.decode(row.payload)
            assertEquals(parents, operation.parents)
            assertTrue(row.payload.size < 2_048)
            assertArrayEquals(row.payload, OperationCodec.encode(operation))
        }
        assertEquals(saved.map { it.id }, db.syncOutboxQueries.pending("family", "dataset").executeAsList().map { it.id })
        assertEquals("Renamed", db.accountQueries.active().executeAsList().single().name)
        assertEquals("Groceries", db.categoryQueries.active().executeAsList().single().name)
    }

    @Test fun `failed command leaves projections history and outbox untouched`() = runTest {
        activate()
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { store.append(account("a")) { require(false) } }
        }
        assertTrue(db.syncOperationQueries.all().executeAsList().isEmpty())
        assertTrue(db.syncOutboxQueries.pending("family", "dataset").executeAsList().isEmpty())
        assertEquals(0L, db.eventQueries.count().executeAsOne())
        assertTrue(db.accountQueries.active().executeAsList().isEmpty())
    }

    @Test fun `copied session cannot append with new installation identity`() = runTest {
        activate("old-device")
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { store.append(account("a")) }
        }
        assertEquals(0L, db.eventQueries.count().executeAsOne())
    }

    @Test fun `stored event identity cannot silently accept different content`() = runTest {
        val first = store.append(account("a"))
        assertThrows(IllegalArgumentException::class.java) { store.applyRemote(listOf(first.copy(event = account("b")))) }
        assertEquals(listOf("a"), db.accountQueries.active().executeAsList().map { it.id })
    }

    @Test fun `standalone writes remain local until explicitly enrolled`() = runTest {
        store.append(account("a"))
        assertEquals(1L, db.eventQueries.count().executeAsOne())
        assertTrue(db.syncOperationQueries.all().executeAsList().isEmpty())
    }
}
