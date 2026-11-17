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

    @Test fun `category deletion syncs to another phone and survives replay`() = runTest {
        enrolled()
        val category = ProjectCategory("test", project.id, "Test", "#123456")
        repo.saveCategory(category)
        val before = store.snapshot()
        val peerDb = testDb()
        val peer = EventStore(peerDb, "peer", clock, Projector(peerDb))
        peer.activate("user", "family", "dataset", "member", 1, before, peer.currentDigest(), true)
        repo.removeCategory(category)
        val operation = db.syncOperationQueries.all().executeAsList().map { OperationCodec.decode(it.payload) }.single { it.version == 11 }
        assertEquals(ResourceAccess.WRITE, operation.resources["project:house"])
        assertEquals(ResourceAccess.WRITE, operation.resources["project-category:test"])
        peer.receive(listOf(OperationCodec.encode(operation)), "user", "family", "dataset", 100, 0, operation.id)
        peer.rebuild()
        peer.validateHistory(peer.snapshot())
        assertTrue(ProjectRepository(peerDb, peer, clock).readBook().categories.isEmpty())
        assertTrue(peerDb.syncQuarantineQueries.all().executeAsList().isEmpty())
    }

    @Test fun `concurrent expense and category deletion conflict without losing category`() = runTest {
        enrolled()
        val category = ProjectCategory("test", project.id, "Test", "#123456")
        repo.saveCategory(category)
        val parent = db.syncOperationQueries.all().executeAsList().single().id
        repo.removeCategory(category)
        val bill = cost.copy(categoryId = category.id, transactionId = null)
        val operation = SyncOperation("family", "dataset", "peer", "peer", 1, setOf(parent),
            mapOf("project:house" to ResourceAccess.WRITE, "project-category:test" to ResourceAccess.READ),
            listOf(OperationEvent("peer-cost", "peer", 1, clock.now().toEpochMilli(), ProjectCostSaved(bill))))
        store.receive(listOf(OperationCodec.encode(operation)), "user", "family", "dataset", 100, 0, operation.id)
        assertEquals(listOf(category), repo.readBook().categories)
        assertTrue(repo.readBook().costs.isEmpty())
        assertEquals(2, db.syncConflictQueries.all().executeAsList().size)
        assertTrue(db.syncQuarantineQueries.all().executeAsList().isEmpty())
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

    @Test fun `unpaid settlement syncs as one operation and replays without duplicate cost`() = runTest {
        enrolled()
        val unpaid = cost.copy(id = "pending", transactionId = null, unpaid = true)
        repo.record(listOf(unpaid))
        repo.settle(unpaid, cost)
        val operations = db.syncOperationQueries.all().executeAsList().map { OperationCodec.decode(it.payload) }
        assertEquals(2, operations.size)
        assertTrue(operations.any { operation ->
            operation.events.map { it.event } == listOf(ProjectCostRemoved(unpaid.id, project.id), ProjectCostSaved(cost))
        })
        store.rebuild()
        store.validateHistory(store.snapshot())
        assertEquals(listOf(cost), repo.readBook().costs)
        assertEquals(listOf(tx), repo.readBook().transactions)
    }

    @Test fun `monthly expectation survives replay rename archive restore and disabling`() = runTest {
        enrolled()
        val category = ProjectCategory("bills", project.id, "Bills", "#123456")
        repo.saveCategory(category)
        val enabled = category.copy(monthlyExpenseExpected = true)
        repo.saveCategory(enabled, category)
        assertEquals(listOf(5, 10), db.syncOperationQueries.all().executeAsList().map { OperationCodec.decode(it.payload).version }.sorted())
        store.rebuild()
        assertEquals(enabled, repo.readBook().categories.single())
        assertTrue(runCatching { repo.saveCategory(category.copy(name = "Stale"), category) }.exceptionOrNull() is IllegalArgumentException)
        val archived = enabled.copy(name = "Utilities", archived = true)
        repo.saveCategory(archived, enabled)
        store.rebuild()
        assertEquals(archived, repo.readBook().categories.single())
        val restored = archived.copy(archived = false)
        repo.saveCategory(restored, archived)
        store.rebuild()
        assertEquals(restored, repo.readBook().categories.single())
        val disabled = restored.copy(monthlyExpenseExpected = false)
        repo.saveCategory(disabled, restored)
        store.rebuild()
        store.validateHistory(store.snapshot())
        assertEquals(disabled, repo.readBook().categories.single())
    }

    @Test fun `category report preference survives sync replay rename archive and restore`() = runTest {
        enrolled()
        val category = ProjectCategory("bills", project.id, "Bills", "#123456", excludedFromReport = true)
        repo.saveCategory(category)
        val operation = OperationCodec.decode(db.syncOperationQueries.all().executeAsList().single().payload)
        assertEquals(9, operation.version)
        store.rebuild()
        assertEquals(category, repo.readBook().categories.single())
        val renamed = category.copy(name = "Utilities", archived = true)
        repo.saveCategory(renamed, category)
        store.rebuild()
        assertEquals(renamed, repo.readBook().categories.single())
        val restored = renamed.copy(archived = false, excludedFromReport = false)
        repo.saveCategory(restored, renamed)
        store.rebuild()
        store.validateHistory(store.snapshot())
        assertEquals(restored, repo.readBook().categories.single())
    }
}
