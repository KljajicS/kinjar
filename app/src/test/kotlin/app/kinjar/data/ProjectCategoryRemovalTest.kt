package app.kinjar.data

import app.kinjar.data.backup.BackupCodec
import app.kinjar.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId

class ProjectCategoryRemovalTest {
    private val db = testDb()
    private val clock = FixedClock(Instant.parse("2026-11-17T12:00:00Z"), ZoneId.of("UTC"))
    private val store = EventStore(db, "local", clock, Projector(db))
    private val repo = ProjectRepository(db, store, clock)
    private val project = Project("p", "Bills")
    private val category = ProjectCategory("test", project.id, "Test", "#123456", monthlyExpenseExpected = true)
    private val amount = Money.of("10", Currency.EUR)
    private val cost = ProjectCost("bill", project.id, category.id, amount, amount, clock.today(), "")

    private suspend fun seed(value: ProjectCategory = category) {
        repo.save(project)
        repo.saveCategory(value.copy(archived = false))
        if (value.archived) repo.saveCategory(value, value.copy(archived = false))
    }

    @Test fun `empty active and archived categories disappear and their names can be reused`() = runTest {
        seed()
        repo.removeCategory(category)
        assertTrue(repo.readBook().categories.isEmpty())
        val replacement = category.copy(id = "replacement")
        repo.saveCategory(replacement)
        val archived = replacement.copy(archived = true)
        repo.saveCategory(archived, replacement)
        repo.removeCategory(archived)
        store.rebuild()
        store.validateHistory(store.snapshot())
        assertTrue(repo.readBook().categories.isEmpty())
        assertEquals(2, db.projectCategoryQueries.all().executeAsList().size)
    }

    @Test fun `paid and unpaid costs prevent deletion but allow archiving`() = runTest {
        seed()
        for (unpaid in listOf(false, true)) {
            val bill = cost.copy(id = "bill-$unpaid", unpaid = unpaid)
            repo.record(listOf(bill))
            val count = db.eventQueries.count().executeAsOne()
            assertTrue(runCatching { repo.removeCategory(category) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(count, db.eventQueries.count().executeAsOne())
            val archived = category.copy(archived = true)
            repo.saveCategory(archived, category)
            assertEquals(listOf(bill), repo.readBook().costs)
            assertTrue(runCatching { repo.removeCategory(archived) }.isFailure)
            repo.saveCategory(category, archived)
            repo.removeCost(bill.id, bill)
        }
        repo.removeCategory(category)
        assertTrue(repo.readBook().categories.isEmpty())
    }

    @Test fun `deleted expense still protects its stored project link`() = runTest {
        seed()
        val transaction = Transaction("expense", TransactionType.EXPENSE, amount, "cash", null, null,
            clock.today(), "", "", "member", TransactionSource.MANUAL)
        store.appendAll(listOf(AccountCreated(Account("cash", "Cash", Currency.EUR)), TransactionAdded(transaction)))
        val enabled = project.copy(sourceCategoryIds = setOf("household"))
        store.append(CategoryCreated(Category("household", "Household", "#123456")))
        repo.save(enabled, project)
        val categorized = transaction.copy(categoryId = "household")
        store.append(TransactionEdited(categorized))
        repo.record(listOf(cost.copy(transactionId = transaction.id)))
        store.append(TransactionDeleted(transaction.id))
        assertTrue(repo.readBook().costsFor(project.id).isEmpty())
        assertTrue(runCatching { repo.removeCategory(category) }.isFailure)
        assertEquals(listOf(category), repo.readBook().categories)
    }

    @Test fun `stale deletion archived project and resurrection are rejected`() = runTest {
        seed()
        val renamed = category.copy(name = "Water")
        repo.saveCategory(renamed, category)
        assertTrue(runCatching { repo.removeCategory(category) }.isFailure)
        repo.archive(project.id, true)
        assertTrue(runCatching { repo.removeCategory(renamed) }.isFailure)
        repo.archive(project.id, false)
        repo.removeCategory(renamed)
        assertTrue(runCatching { repo.saveCategory(renamed, renamed) }.isFailure)
        assertTrue(runCatching { repo.saveCategory(renamed) }.isFailure)
        assertTrue(runCatching { repo.record(listOf(cost)) }.isFailure)
        assertTrue(repo.readBook().categories.isEmpty())
    }

    @Test fun `encrypted backup and fresh restore preserve category deletion`() = runTest {
        seed()
        repo.removeCategory(category)
        val history = store.snapshot()
        val output = ByteArrayOutputStream()
        val password = "a backup test passphrase".toCharArray()
        BackupCodec.seal(history.source(), output, password, clock.now())
        val records = mutableListOf<HistoryRecord>()
        BackupCodec.open(output.toByteArray().inputStream(), password, records::add)
        assertEquals(history.source().records().toList(), records)
        val restoredDb = testDb()
        val restored = EventStore(restoredDb, "fresh", clock, Projector(restoredDb))
        val decoded = FamilyHistory(baseline = records.map { (it as HistoryRecord.Baseline).event }, operations = emptyList())
        restored.restoreLocal(decoded, restored.currentDigest())
        assertTrue(ProjectRepository(restoredDb, restored, clock).readBook().categories.isEmpty())
    }
}
