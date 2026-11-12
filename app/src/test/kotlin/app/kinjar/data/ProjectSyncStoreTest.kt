package app.kinjar.data

import app.kinjar.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ProjectSyncStoreTest {
    private val db = testDb()
    private val clock = FixedClock(Instant.parse("2026-11-10T12:00:00Z"), ZoneId.of("UTC"))
    private val store = EventStore(db, "local", clock, Projector(db))
    private val repo = ProjectRepository(db, store, clock)
    private val project = Project("house", "House", sourceCategoryIds = setOf("household"))
    private val tx = Transaction("expense", TransactionType.EXPENSE, Money.of("100", Currency.EUR), "bank", null, "household", clock.today(), "", "", "member", TransactionSource.MANUAL)
    private val cost = ProjectCost("cost", project.id, null, tx.money, tx.money, tx.date, "", tx.id)

    private suspend fun enrolled() {
        store.appendAll(listOf(AccountCreated(Account("bank", "Bank", Currency.EUR)),
            CategoryCreated(Category("household", "Household", "#123456")), TransactionAdded(tx), ProjectSaved(project)))
        val history = store.snapshot()
        store.activate("user", "family", "dataset", "member", 1, history, HistoryCodec.digest(HistoryCodec.encode(history)), true)
    }

    @Test fun `project link announces dependencies and survives synced replay`() = runTest {
        enrolled()
        repo.record(listOf(cost))
        val operation = OperationCodec.decode(db.syncOperationQueries.all().executeAsList().single().payload)
        assertEquals(5, operation.version)
        assertEquals(ResourceAccess.WRITE, operation.resources["project:house"])
        assertEquals(ResourceAccess.WRITE, operation.resources["transaction:expense"])
        assertEquals(ResourceAccess.READ, operation.resources["account:bank"])
        store.rebuild()
        assertEquals(listOf(cost), repo.readBook().costs)
        assertEquals(listOf(tx), repo.readBook().transactions)
        val before = store.snapshot()
        store.validateHistory(before)
        assertEquals(before, store.snapshot())
    }

    @Test fun `incoming link with hidden expense dependency is quarantined`() = runTest {
        enrolled()
        val operation = SyncOperation("family", "dataset", "peer", "peer", 1, emptySet(),
            mapOf("project:house" to ResourceAccess.WRITE),
            listOf(OperationEvent("peer-cost", "peer", 1, clock.now().toEpochMilli(), ProjectCostSaved(cost))))
        store.receive(listOf(OperationCodec.encode(operation)), "user", "family", "dataset", 100, 0, operation.id)
        assertTrue(repo.readBook().costs.isEmpty())
        assertEquals(listOf(tx), repo.readBook().transactions)
        assertEquals(1, db.syncQuarantineQueries.all().executeAsList().size)
    }
}
