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
        reopened.append(account("c"))
        val next = OperationCodec.decode(db.syncOperationQueries.all().executeAsList().last().payload)
        assertEquals(2L, next.sequence)
        assertEquals(setOf("device:1"), next.parents)
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
