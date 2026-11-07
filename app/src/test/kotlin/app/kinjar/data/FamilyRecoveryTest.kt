package app.kinjar.data

import app.cash.sqldelight.Query
import app.kinjar.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate

class FamilyRecoveryTest {
    private val db = testDb()
    private val clock = FixedClock(Instant.parse("2026-11-04T10:00:00Z"), ZoneId.of("UTC"))
    private val store = EventStore(db, "local", clock, Projector(db))
    private fun account(id: String, currency: Currency = Currency.RSD) = AccountCreated(Account(id, id, currency, AccountKind.SPENDING))
    private fun digest() = HistoryCodec.digest(HistoryCodec.encode(store.snapshot()))
    private fun enroll(history: FamilyHistory = store.snapshot(), expected: String = digest()) =
        store.activate("user", "family", "dataset", "member", 1, history, expected, true)
    private fun operation(device: String, name: String) = SyncOperation(familyId = "family", datasetId = "dataset",
        authorUid = device, installId = device, sequence = 1, parents = emptySet(), resources = mapOf("account:a" to ResourceAccess.WRITE),
        events = listOf(OperationEvent("$device-event", device, 1, clock.now().toEpochMilli(),
            AccountUpdated(Account("a", name, Currency.RSD, AccountKind.SPENDING)))))
    private fun receive(vararg ops: SyncOperation) = store.receive(ops.map(OperationCodec::encode), "user", "family", "dataset", 100, 0, ops.last().id)

    @Test fun `stale overwrite confirmation preserves new local edits`() = runTest {
        store.append(account("a"))
        val confirmed = digest()
        store.append(account("b"))
        assertThrows(IllegalStateException::class.java) { enroll(FamilyHistory(baseline = emptyList(), operations = emptyList()), confirmed) }
        assertEquals(2, db.accountQueries.active().executeAsList().size)
        assertNull(db.syncSessionQueries.current().executeAsOneOrNull())
    }

    @Test fun `validation replay never commits temporary replacement`() = runTest {
        store.append(account("a"))
        val prior = store.snapshot()
        store.validateHistory(FamilyHistory(baseline = emptyList(), operations = emptyList()))
        assertEquals(prior, store.snapshot())
        assertEquals("a", db.accountQueries.active().executeAsList().single().id)
        assertNull(db.syncBaselineQueries.current().executeAsOneOrNull())
    }

    @Test fun `local restore retains later offline edits in subsequent backups and rebuilds`() = runTest {
        store.append(account("a"))
        val history = store.snapshot()
        store.restoreLocal(history, digest())
        store.append(AccountUpdated(Account("a", "Changed", Currency.RSD)))
        val updated = store.snapshot()
        assertEquals(1, updated.operations.size)
        assertTrue(db.syncOutboxQueries.pending("local", "local").executeAsList().isEmpty())
        store.rebuild()
        assertEquals("Changed", db.accountQueries.active().executeAsList().single().name)
        store.restoreLocal(updated, digest())
        assertEquals("Changed", db.accountQueries.active().executeAsList().single().name)
    }

    @Test fun `interrupted creation freezes initial source until resumed`() = runTest {
        store.append(account("a"))
        db.restoreJournalQueries.save("user", "family", "", "dataset", "token", "CREATE", byteArrayOf(1))
        val reopened = EventStore(db, "local", clock, Projector(db))
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { reopened.append(account("b")) } }
        assertEquals(1L, db.eventQueries.count().executeAsOne())
        assertThrows(IllegalArgumentException::class.java) { reopened.restoreLocal(reopened.snapshot(), digest()) }
        reopened.abandonRecovery("user")
        reopened.append(account("b"))
        assertEquals(2L, db.eventQueries.count().executeAsOne())
    }

    @Test fun `invalid restored history rolls back financial data session and retained draft`() = runTest {
        store.append(account("a"))
        val bad = store.snapshot().copy(baseline = store.snapshot().baseline + store.snapshot().baseline.first().copy(id = "collision"))
        assertThrows(IllegalArgumentException::class.java) { enroll(bad) }
        assertEquals(1L, db.eventQueries.count().executeAsOne())
        assertTrue(db.syncDraftQueries.all("user").executeAsList().isEmpty())
        assertNull(db.syncSessionQueries.current().executeAsOneOrNull())
    }

    @Test fun `activation preserves previous history under original account and survives reopening`() = runTest {
        store.append(account("a"))
        val prior = store.snapshot()
        enroll(FamilyHistory(baseline = emptyList(), operations = emptyList()))
        assertTrue(db.accountQueries.active().executeAsList().isEmpty())
        assertEquals(prior, HistoryCodec.decode(db.syncDraftQueries.all("user").executeAsList().single().payload))
        assertTrue(db.syncDraftQueries.all("different-user").executeAsList().isEmpty())
        val reopened = EventStore(db, "local", clock, Projector(db))
        reopened.append(account("new"))
        assertEquals(1, db.syncOutboxQueries.pending("family", "dataset").executeAsList().size)
    }

    @Test fun `concurrent edits restore prior value until one complete change is discarded`() = runTest {
        store.append(account("a"))
        enroll()
        val a = operation("one", "One")
        val b = operation("two", "Two")
        receive(a)
        assertEquals("One", db.accountQueries.active().executeAsList().single().name)
        receive(b)
        assertEquals("a", db.accountQueries.active().executeAsList().single().name)
        assertEquals(2, db.syncConflictQueries.all().executeAsList().size)
        store.append(account("independent"))
        store.discardConflictingChange(b.id)
        assertEquals("One", db.accountQueries.active().executeAsList().single { it.id == "a" }.name)
        assertTrue(db.syncConflictQueries.all().executeAsList().isEmpty())
    }

    @Test fun `duplicate import is idempotent and changed bytes are quarantined`() = runTest {
        store.append(account("a")); enroll()
        val a = operation("one", "One")
        receive(a); receive(a)
        assertEquals(1, db.syncOperationQueries.all().executeAsList().size)
        receive(operation("one", "Forged"))
        assertEquals(1, db.syncQuarantineQueries.all().executeAsList().size)
        assertEquals("One", db.accountQueries.active().executeAsList().single().name)
    }

    @Test fun `duplicate-only page advances the checkpoint without rewriting projections or quarantine`() = runTest {
        store.append(account("a")); enroll()
        val one = OperationCodec.encode(operation("one", "One"))
        val forged = OperationCodec.encode(operation("one", "Forged"))
        store.receive(listOf(one, forged), "user", "family", "dataset", 100, 0, "one:1")
        val quarantined = db.syncQuarantineQueries.all().executeAsList().map { it.digest to it.reason }
        assertEquals(1, quarantined.size)
        val rewrites = mutableListOf<String>()
        listOf("account" to db.accountQueries.active(), "conflict" to db.syncConflictQueries.all(), "operation" to db.syncOperationQueries.all())
            .forEach { (table, query) -> query.addListener(object : Query.Listener { override fun queryResultsChanged() { rewrites += table } }) }
        store.receive(listOf(one, forged), "user", "family", "dataset", 160, 0, "one:1",
            listOf(QuarantinedOperation.from(forged)))
        assertEquals(emptyList<String>(), rewrites)
        assertEquals(160L, db.syncCheckpointQueries.byDataset("dataset").executeAsOne().seconds)
        assertEquals(quarantined, db.syncQuarantineQueries.all().executeAsList().map { it.digest to it.reason })
        assertEquals("One", db.accountQueries.active().executeAsList().single().name)
        store.receive(listOf(one, OperationCodec.encode(operation("two", "Two"))), "user", "family", "dataset", 170, 0, "two:1")
        assertTrue("a new operation still replays", "account" in rewrites && "conflict" in rewrites)
    }

    @Test fun `malformed versions and invalid resolutions preserve progress and cloud recovery`() = runTest {
        store.append(account("a")); enroll()
        val valid = operation("one", "One")
        val invalid = operation("two", "Ignored").copy(events = emptyList(), resources = emptyMap(), rejected = setOf("unseen:1"))
        store.receive(listOf("{}".toByteArray(), OperationCodec.encode(invalid), OperationCodec.encode(valid)),
            "user", "family", "dataset", 100, 0, valid.id)
        assertEquals("One", db.accountQueries.active().executeAsList().single().name)
        assertEquals(1, db.syncQuarantineQueries.all().executeAsList().size)
        assertEquals(valid.id, db.syncCheckpointQueries.byDataset("dataset").executeAsOne().document_id)
        assertEquals("INVALID_FINANCIAL_CHANGE", db.syncConflictQueries.all().executeAsList().single().reason)
        val prepared = store.prepareCloudHistory(store.snapshot())
        store.validateHistory(prepared)
        assertEquals(2, prepared.operations.size)
        store.discardConflictingChange(invalid.id)
        assertTrue(db.syncConflictQueries.all().executeAsList().isEmpty())
    }

    @Test fun `wrong account and dataset imports preserve local state`() = runTest {
        store.append(account("a")); enroll()
        val bytes = listOf(OperationCodec.encode(operation("one", "One")))
        assertThrows(IllegalStateException::class.java) { store.receive(bytes, "other", "family", "dataset", 1, 0, "one:1") }
        assertThrows(IllegalStateException::class.java) { store.receive(bytes, "user", "family", "retired", 1, 0, "one:1") }
        assertEquals("a", db.accountQueries.active().executeAsList().single().name)
    }

    @Test fun `concurrent loan repayments hold linked expenses atomically`() = runTest {
        val date = LocalDate.of(2026, 11, 4)
        store.appendAll(listOf(account("a"), LoanCreated(Loan("loan", "Loan", Money.of("100", Currency.RSD), date))))
        enroll()
        fun payment(device: String): SyncOperation {
            val tx = Transaction(device, TransactionType.EXPENSE, Money.of("60", Currency.RSD), "a", null, null,
                date, "", "", "member", TransactionSource.MANUAL)
            val events = listOf(TransactionAdded(tx), LoanPaymentAdded(LoanPayment(device, "loan", tx.id, tx.money, tx.money)))
            return SyncOperation(familyId = "family", datasetId = "dataset", authorUid = device, installId = device,
                sequence = 1, parents = emptySet(), resources = mapOf("loan:loan" to ResourceAccess.WRITE,
                    "transaction:$device" to ResourceAccess.WRITE, "account:a" to ResourceAccess.READ),
                events = events.mapIndexed { index, e -> OperationEvent("$device-$index", device, index + 1L, 1L, e) })
        }
        val first = payment("first")
        val second = payment("second")
        receive(first)
        assertEquals(1, db.txQueries.all().executeAsList().size)
        receive(second)
        assertTrue(db.txQueries.all().executeAsList().isEmpty())
        assertTrue(db.loanPaymentQueries.all().executeAsList().isEmpty())
        store.discardConflictingChange(second.id)
        assertEquals(listOf("first"), db.txQueries.all().executeAsList().map { it.id })
        assertEquals(1, db.loanPaymentQueries.all().executeAsList().size)
    }

    @Test fun `forged resource claims cannot hide conflicting financial changes`() = runTest {
        store.append(account("a")); enroll()
        val bad = operation("one", "Forged").copy(resources = emptyMap())
        receive(bad)
        assertEquals("a", db.accountQueries.active().executeAsList().single().name)
        assertNotNull(db.syncCheckpointQueries.byDataset("dataset").executeAsOneOrNull())
        assertEquals(1, db.syncQuarantineQueries.all().executeAsList().size)
    }

    @Test fun `malformed and colliding events do not block independent imports or backup recovery`() = runTest {
        store.append(account("a")); enroll()
        val valid = operation("valid", "Valid")
        val collision = operation("collision", "Collision").let { it.copy(events = listOf(it.events.single().copy(id = valid.events.single().id))) }
        store.receive(listOf("{\"version\":1}".toByteArray(), OperationCodec.encode(valid), OperationCodec.encode(collision)),
            "user", "family", "dataset", 200, 0, collision.id)
        assertEquals("Valid", db.accountQueries.active().executeAsList().single().name)
        assertEquals(2, db.syncQuarantineQueries.all().executeAsList().size)
        val backup = store.snapshot()
        assertEquals(2, backup.quarantine.size)
        store.validateHistory(HistoryCodec.decode(HistoryCodec.encode(backup)))
        val reopened = EventStore(db, "local", clock, Projector(db))
        reopened.append(account("independent"))
        assertEquals(1, db.syncOutboxQueries.pending("family", "dataset").executeAsList().size)
        assertEquals(200L, db.syncCheckpointQueries.byDataset("dataset").executeAsOne().seconds)
    }

    @Test fun `cloud recovery quarantines resource forgery without touching current local data`() = runTest {
        store.append(account("a"))
        val before = store.snapshot()
        val bad = operation("bad", "Bad").copy(resources = emptyMap())
        val good = operation("good", "Good")
        val candidate = before.copy(operations = listOf(bad, good).map { java.util.Base64.getEncoder().encodeToString(OperationCodec.encode(it)) })
        val prepared = store.prepareCloudHistory(candidate)
        assertEquals(before, store.snapshot())
        assertEquals(1, prepared.operations.size)
        assertEquals(1, prepared.quarantine.size)
        enroll(prepared)
        assertEquals("Good", db.accountQueries.active().executeAsList().single().name)
    }

    @Test fun `removed family clears a pending restore and only its retained copies`() = runTest {
        store.append(account("a")); enroll()
        db.syncDraftQueries.save("other", "different-user", "different-family", 1, HistoryCodec.encode(store.snapshot()))
        db.restoreJournalQueries.save("user", "family", "dataset", "replacement", "token", "RESTORE", byteArrayOf(1))
        store.clearRemovedFamily("user", "family")
        assertNull(db.restoreJournalQueries.current().executeAsOneOrNull())
        assertEquals(1, db.syncDraftQueries.all("different-user").executeAsList().size)
        store.append(account("new"))
        assertEquals(1, db.accountQueries.active().executeAsList().size)
    }

    @Test fun `unsupported operation leaves entire import and checkpoint unchanged`() = runTest {
        store.append(account("a")); enroll()
        val future = OperationCodec.encode(operation("one", "Newer")).toString(Charsets.UTF_8)
            .replace("\"version\":1", "\"version\":4").toByteArray()
        assertThrows(UnsupportedSyncVersion::class.java) {
            store.receive(listOf(OperationCodec.encode(operation("two", "Valid")), future), "user", "family", "dataset", 100, 0, "one:1")
        }
        assertTrue(db.syncOperationQueries.all().executeAsList().isEmpty())
        assertEquals("a", db.accountQueries.active().executeAsList().single().name)
        assertNull(db.syncCheckpointQueries.byDataset("dataset").executeAsOneOrNull())
    }
}
