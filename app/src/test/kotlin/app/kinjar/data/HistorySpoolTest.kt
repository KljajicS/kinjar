package app.kinjar.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kinjar.data.backup.BackupCodec
import app.kinjar.data.db.KinjarDb
import app.kinjar.domain.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId

class HistorySpoolTest {
    private val db = testDb()
    private val clock = FixedClock(Instant.parse("2026-11-07T10:00:00Z"), ZoneId.of("UTC"))
    private val store = EventStore(db, "local", clock, Projector(db))
    private val password = "four long random words".toCharArray()
    private fun account(id: String) = AccountCreated(Account(id, id, Currency.RSD, AccountKind.SPENDING))
    private fun rename(install: String, seq: Long, name: String, resource: String = "account:a") = SyncOperation(familyId = "family",
        datasetId = "dataset", authorUid = install, installId = install, sequence = seq, parents = emptySet(),
        resources = mapOf(resource to ResourceAccess.WRITE), events = listOf(OperationEvent("$install-$seq", install, seq,
            clock.now().toEpochMilli(), AccountUpdated(Account("a", name, Currency.RSD, AccountKind.SPENDING)))))
    private fun records(history: StoredHistory) = store.historySource(history.id).records().toList()
    private fun spools() = db.historySpoolQueries.unreferenced().executeAsList()
    private fun exists(id: String) = db.historySpoolQueries.info(id).executeAsOneOrNull() != null
    private fun v1Digest() = HistoryCodec.digest(HistoryCodec.encode(store.snapshot()))

    @Test fun `history larger than both legacy limits can export restore and survive a recovery restart`() {
        val count = 100_001L
        val name = "Long history ".repeat(55)
        val history = store.writeHistory(sequence {
            yield(HistoryRecord.Baseline(OperationEvent("member", "legacy", 1, 1, MemberCreated(Member("m", "Initial", "legacy")))))
            for (i in 2..count) yield(HistoryRecord.Baseline(OperationEvent("event-$i", "legacy", i, i,
                MemberRenamed("m", name))))
        })
        assertEquals(count, history.baselineCount)
        assertTrue(history.bytes > HistoryCodec.MAX_BYTES)
        val file = File.createTempFile("large-history", ".backup")
        try {
            file.outputStream().buffered().use { BackupCodec.seal(store.historySource(history.id), it, password) }
            val opened = file.inputStream().buffered().use { input ->
                store.writeHistory { sink -> BackupCodec.open(input, password, sink) }
            }
            assertEquals(history.digest, opened.digest)
            store.saveJournal("user", "family", "", "dataset", "token", "REPLACE", "{}".toByteArray(), opened)
            val restarted = EventStore(db, "local", clock, Projector(db))
            val scratch = restarted.beginHistory()
            assertEquals(opened, restarted.history(opened.id))
            restarted.discardHistory(scratch)
            restarted.validateHistory(opened)
            restarted.activate("user", "family", "dataset", "m", 1, opened, restarted.currentDigest(), false)
            assertEquals(name, db.memberQueries.all().executeAsList().single().display_name)
            assertEquals(count, restarted.captureHistory().baselineCount)
        } finally { file.delete() }
    }

    @Test fun `captured history streams the local history and its digest guards confirmation`() = runTest {
        store.append(account("a"))
        val captured = store.captureHistory()
        assertEquals(store.snapshot().source().records().toList(), records(captured))
        assertEquals(store.currentDigest(), captured.digest)
        assertTrue(captured.digest.startsWith(HistoryStream.DIGEST_PREFIX))
        val legacy = v1Digest()
        assertTrue(store.localDigestMatches(captured.digest) && store.localDigestMatches(legacy))
        store.append(account("b"))
        assertFalse(store.localDigestMatches(captured.digest) || store.localDigestMatches(legacy))
        assertThrows(LocalHistoryChanged::class.java) { store.restoreLocal(captured, captured.digest) }
    }

    @Test fun `staged operations keep exact duplicates once and quarantine conflicting or invalid bytes`() {
        val id = store.beginHistory()
        val original = HistoryRecord.Operation.of(OperationCodec.encode(rename("peer", 1, "One")))
        store.writeHistoryRecords(id, listOf(original, original, HistoryRecord.Operation.of(OperationCodec.encode(rename("peer", 1, "Two"))),
            HistoryRecord.Operation.of("not an operation".toByteArray())))
        assertThrows(IllegalArgumentException::class.java) { store.history(id) }
        val stored = store.finishHistory(id)
        assertEquals(listOf(0L, 1L, 2L), listOf(stored.baselineCount, stored.operationCount, stored.quarantineCount))
        assertEquals(listOf("IDENTITY_MISMATCH", "INVALID_OPERATION"),
            records(stored).filterIsInstance<HistoryRecord.Quarantine>().map { it.value.reason })
        assertEquals(original, records(stored).first())
        assertThrows(IllegalArgumentException::class.java) { store.writeHistoryRecord(id, original) }
        assertThrows(IllegalArgumentException::class.java) { store.finishHistory(id) }
    }

    @Test fun `failed staging leaves nothing and a restart sweeps spools of interrupted runs`() = runTest {
        store.append(account("a"))
        assertThrows(IllegalStateException::class.java) {
            store.writeHistory { sink -> sink(store.snapshot().source().records().first()); throw IllegalStateException("interrupted") }
        }
        assertTrue(spools().isEmpty())
        val staged = store.beginHistory()
        val finished = store.captureHistory()
        val restarted = EventStore(db, "local", clock, Projector(db))
        val next = restarted.beginHistory()
        assertFalse(exists(staged) || exists(finished.id))
        assertTrue(exists(next))
    }

    @Test fun `damaged backup never becomes a finished spool`() = runTest {
        store.append(account("a"))
        val captured = store.captureHistory()
        val bytes = ByteArrayOutputStream().also { BackupCodec.seal(store.historySource(captured.id), it, password) }.toByteArray()
        val opened = store.writeHistory { sink -> BackupCodec.open(bytes.inputStream(), password, sink) }
        assertEquals(captured.digest, opened.digest)
        val damaged = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertTrue(runCatching { store.writeHistory { sink -> BackupCodec.open(damaged.inputStream(), password, sink) } }.isFailure)
        assertEquals(setOf(captured.id, opened.id), spools().toSet())
    }

    @Test fun `releasing a restore input before capture cannot replace finances with missing history`() = runTest {
        store.append(account("a"))
        val before = store.snapshot()
        val incoming = store.writeHistory(sequenceOf(HistoryRecord.Baseline(
            OperationEvent("import", "peer", 1, 1, account("b")))))
        val digest = store.currentDigest()
        store.releaseHistory(incoming.id)

        assertThrows(IllegalArgumentException::class.java) { store.restoreLocal(incoming, digest) }

        assertEquals(before, store.snapshot())
        assertEquals(digest, store.currentDigest())
        assertTrue(db.syncDraftQueries.draftInfo("local").executeAsList().isEmpty())
        assertTrue(exists(incoming.id))
    }

    @Test fun `activation stores baseline and retained copy as spools and consumes its inputs`() = runTest {
        store.append(account("a"))
        val prior = store.snapshot()
        val captured = store.captureHistory()
        store.saveJournal("user", "family", "", "dataset", "token", "REPLACE", "{}".toByteArray(), captured)
        assertThrows(IllegalArgumentException::class.java) { store.discardHistory(captured.id) }
        store.activate("user", "family", "dataset", "member", 1, captured, store.currentDigest(), preserveDraft = true)
        assertNull(db.restoreJournalQueries.journalInfo().executeAsOneOrNull())
        assertFalse(exists(captured.id))
        val baseline = requireNotNull(db.syncBaselineQueries.baselineHistory().executeAsOne().history_id)
        assertEquals(0, db.syncBaselineQueries.current().executeAsOne().size)
        assertThrows(IllegalArgumentException::class.java) { store.discardHistory(baseline) }
        val draft = db.syncDraftQueries.draftInfo("user").executeAsList().single()
        assertEquals(prior.source().records().toList(), records(requireNotNull(store.draftHistory("user", draft.id))))
        assertEquals(prior, store.snapshot())
        assertTrue(spools().isEmpty())
        store.append(AccountUpdated(Account("a", "Changed", Currency.RSD)))
        store.rebuild()
        assertEquals("Changed", db.accountQueries.active().executeAsList().single().name)
    }

    @Test fun `local restore from a spool retains the replaced history and validation never commits`() = runTest {
        store.append(account("a"))
        val backup = store.captureHistory()
        store.append(account("b"))
        val before = store.snapshot()
        store.validateHistory(backup)
        assertEquals(before, store.snapshot())
        assertNull(db.syncBaselineQueries.current().executeAsOneOrNull())
        assertThrows(IllegalStateException::class.java) { store.validateHistory(backup.copy(digest = "2:changed")) }
        store.restoreLocal(backup, store.currentDigest())
        assertEquals(listOf("a"), db.accountQueries.active().executeAsList().map { it.id })
        assertFalse(exists(backup.id))
        val retained = db.syncDraftQueries.draftInfo("local").executeAsList().single()
        assertEquals(before.source().records().toList(), records(requireNotNull(store.draftHistory("local", retained.id))))
    }

    @Test fun `cloud preparation quarantines invalid operations without touching local data`() = runTest {
        store.append(account("a"))
        store.activate("user", "family", "dataset", "member", 1, store.captureHistory(), store.currentDigest(), false)
        val local = store.snapshot()
        val valid = OperationCodec.encode(rename("peer", 1, "Renamed"))
        val invalid = OperationCodec.encode(rename("other", 1, "Broken", resource = "account:b"))
        val remote = store.writeHistory(local.source().records() +
            sequenceOf(HistoryRecord.Operation.of(valid), HistoryRecord.Operation.of(invalid)))
        assertThrows(IllegalArgumentException::class.java) { store.validateHistory(remote) }
        val prepared = store.prepareCloudHistory(remote)
        assertEquals(listOf(1L, 1L), listOf(prepared.operationCount, prepared.quarantineCount))
        assertEquals(QuarantinedOperation.from(invalid), (records(prepared).last() as HistoryRecord.Quarantine).value)
        store.validateHistory(prepared)
        assertEquals(local, store.snapshot())
        assertSame(prepared, store.prepareCloudHistory(prepared))
    }

    @Test fun `legacy writer reproduces version 1 bytes from a spool`() = runTest {
        store.append(account("a"))
        store.activate("user", "family", "dataset", "member", 1, store.captureHistory(), store.currentDigest(), false)
        store.receive(listOf(OperationCodec.encode(rename("peer", 1, "Renamed")),"junk".toByteArray()),
            "user", "family", "dataset", 1, 0, "peer:1")
        val history = store.snapshot()
        assertEquals(1, history.operations.size)
        assertEquals(1, history.quarantine.size)
        val out = ByteArrayOutputStream()
        val encoded = HistoryCodec.write(store.historySource(store.captureHistory().id), out)
        assertArrayEquals(HistoryCodec.encode(history), out.toByteArray())
        assertEquals(HistoryCodec.digest(out.toByteArray()), encoded.digest)
    }

    @Test fun `version 1 baseline retained copy and journal convert to spools atomically`() = runTest {
        store.append(account("a"))
        val legacy = store.snapshot()
        val digest = v1Digest()
        db.syncBaselineQueries.save(HistoryCodec.encode(legacy))
        store.rebuild()
        assertNotNull(db.syncBaselineQueries.baselineHistory().executeAsOne().history_id)
        assertEquals(listOf("a"), db.accountQueries.active().executeAsList().map { it.id })
        assertEquals(legacy, store.snapshot())

        db.syncDraftQueries.save("copy", "user", "family", 1, HistoryCodec.encode(legacy))
        val draft = requireNotNull(store.draftHistory("user", "copy"))
        assertEquals(legacy.source().records().toList(), records(draft))
        assertEquals(0, db.syncDraftQueries.all("user").executeAsList().single().payload.size)
        assertEquals(draft, store.draftHistory("user", "copy"))
        assertNull(store.draftHistory("other", "copy"))

        val history = Json.encodeToJsonElement(FamilyHistory.serializer(), legacy)
        val manifest = JsonObject(mapOf("enrollment" to JsonObject(mapOf("memberId" to JsonPrimitive("m"),
            "localDigest" to JsonPrimitive(digest), "history" to history)), "sourceGeneration" to JsonPrimitive(3)))
        db.restoreJournalQueries.save("user", "family", "source", "target", "token", "RESTORE", manifest.toString().toByteArray())
        val info = db.restoreJournalQueries.journalInfo().executeAsOne()
        val journal = requireNotNull(store.journalHistory())
        assertEquals(legacy.source().records().toList(), records(journal))
        val stripped = Json.parseToJsonElement(requireNotNull(db.restoreManifest()).toString(Charsets.UTF_8)).jsonObject
        assertEquals(JsonObject(mapOf("enrollment" to JsonObject(mapOf("memberId" to JsonPrimitive("m"), "localDigest" to JsonPrimitive(digest))),
            "sourceGeneration" to JsonPrimitive(3))), stripped)
        assertEquals(info, db.restoreJournalQueries.journalInfo().executeAsOne())
        assertEquals(journal, store.journalHistory())
        assertTrue(store.localDigestMatches(digest))

        db.restoreJournalQueries.save("user", "family", "", "target", "token", "REPLACE",
            JsonObject(mapOf("memberId" to JsonPrimitive("m"), "history" to history)).toString().toByteArray())
        assertEquals(legacy.source().records().toList(), records(requireNotNull(store.journalHistory())))
        assertEquals(JsonObject(mapOf("memberId" to JsonPrimitive("m"))),
            Json.parseToJsonElement(requireNotNull(db.restoreManifest()).toString(Charsets.UTF_8)))
        db.restoreJournalQueries.save("user", "family", "", "", "", "SWITCH", "{\"slot\":\"s\"}".toByteArray())
        assertNull(store.journalHistory())
    }

    @Test fun `version 1 rows written before migration eleven survive it`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        javaClass.getResource("/schema-v5.sql")!!.readText().split(';').filter { it.isNotBlank() }.forEach { driver.execute(null, it, 0) }
        KinjarDb.Schema.migrate(driver, 5, 11)
        val old = KinjarDb(driver)
        seedLegacyEvents(old, listOf(account("a")), Instant.EPOCH)
        val legacy = FamilyHistory(baseline = listOf(OperationEvent("legacy-0", "legacy", 1, 0, account("a"))), operations = emptyList())
        old.syncBaselineQueries.save(HistoryCodec.encode(legacy))
        old.syncDraftQueries.save("copy", "user", "family", 1, HistoryCodec.encode(legacy))
        old.restoreJournalQueries.save("user", "family", "", "target", "token", "REPLACE",
            JsonObject(mapOf("history" to Json.encodeToJsonElement(FamilyHistory.serializer(), legacy))).toString().toByteArray())
        KinjarDb.Schema.migrate(driver, 11, KinjarDb.Schema.version)
        val db = KinjarDb(driver)
        val migrated = EventStore(db, "local", clock, Projector(db))
        migrated.rebuild()
        assertEquals(listOf("a"), db.accountQueries.active().executeAsList().map { it.id })
        assertEquals(legacy.source().records().toList(), migrated.historySource(requireNotNull(migrated.draftHistory("user", "copy")).id).records().toList())
        assertEquals(legacy.source().records().toList(), migrated.historySource(requireNotNull(migrated.journalHistory()).id).records().toList())
        assertEquals(legacy, migrated.snapshot())
    }
}
