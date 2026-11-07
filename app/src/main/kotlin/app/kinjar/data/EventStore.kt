package app.kinjar.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.kinjar.data.db.KinjarDb
import app.kinjar.data.db.Event as EventRow
import app.kinjar.domain.Event
import app.kinjar.domain.EventJson
import app.kinjar.domain.EventRecord
import app.kinjar.domain.newId
import app.kinjar.domain.OperationCodec
import app.kinjar.domain.OperationEvent
import app.kinjar.domain.SyncOperation
import app.kinjar.domain.ConflictResolver
import app.kinjar.domain.FamilyHistory
import app.kinjar.domain.HeldChanges
import app.kinjar.domain.HistoryCodec
import app.kinjar.domain.HistoryRecord
import app.kinjar.domain.HistorySource
import app.kinjar.domain.HistoryStream
import app.kinjar.domain.LocalHistoryChanged
import app.kinjar.domain.QuarantinedOperation
import app.kinjar.domain.source
import java.time.Instant

class EventStore(
    private val db: KinjarDb,
    private val deviceId: String,
    private val clock: Clock,
    private val projector: Projector,
) {
    private val writeLock = Any()
    private val spool = HistorySpool(db)
    private val synced = SyncedHistory(db, projector, spool)
    // Spools handed out by this process; any other unreferenced spool was left behind by an interrupted run.
    private val live = mutableSetOf<String>()

    suspend fun append(event: Event, validate: () -> Unit = {}): EventRecord = appendAll(listOf(event), validate = validate).single()

    suspend fun appendAll(events: List<Event>, skipIf: () -> Boolean = { false }, validate: () -> Unit = {}): List<EventRecord> = withContext(Dispatchers.IO) {
        synchronized(writeLock) {
            db.transactionWithResult {
                if (skipIf()) return@transactionWithResult emptyList()
                validate()
                check(db.restoreJournalQueries.journalInfo().executeAsOneOrNull()?.phase !in setOf("CREATE", "RESTORE", "SWITCH")) { "Finish family setup or restore before editing finances" }
                val session = db.syncSessionQueries.current().executeAsOneOrNull()
                if (session != null) {
                    check(session.install_id == deviceId) { "Installation must recover its session" }
                    check(session.status == "ACTIVE") { "Family data is not writable" }
                }
                val tracked = session != null || db.hasSyncBaseline()
                val resources = if (!tracked) emptyMap() else operationResources(events, db)
                val known = if (!tracked) emptyList() else synced.operationViews()
                val parents = ConflictResolver.parentsFor(known, resources)
                val blocked = db.syncConflictQueries.all().executeAsList().map { it.operation_id }.toSet()
                check(parents.none { it in blocked }) { "Resolve related changes first" }
                val records = events.map { event ->
                    val seq = (db.eventQueries.lastSeq(deviceId).executeAsOne().max_seq ?: 0L) + 1
                    // Logical clock preserves causality when a device clock moves backwards.
                    val last = db.eventQueries.lastOccurredAt().executeAsOne().max_at ?: 0L
                    val at = Instant.ofEpochMilli(maxOf(clock.now().toEpochMilli(), last + 1))
                    val record = EventRecord(newId(), deviceId, seq, at, event)
                    insert(record)
                    projector.apply(record)
                    record
                }
                if (tracked && records.isNotEmpty()) {
                    val operation = SyncOperation(
                        familyId = session?.family_id ?: "local", datasetId = session?.dataset_id ?: "local",
                        authorUid = session?.uid ?: "local", installId = deviceId,
                        sequence = Math.addExact(db.syncOperationQueries.lastSequence(deviceId).executeAsOneOrNull()?.MAX ?: 0L, 1L),
                        parents = parents, resources = resources, events = records.map(OperationEvent::from),
                    )
                    db.syncOperationQueries.insert(operation.id, deviceId, operation.sequence, OperationCodec.encode(operation))
                    if (session != null) db.syncOutboxQueries.enqueue(operation.id, session.family_id, session.dataset_id)
                }
                records
            }
        }
    }

    fun applyRemote(records: List<EventRecord>) = synchronized(writeLock) { db.transaction {
        val unique = records.groupBy { it.deviceId to it.seq }.values.map { duplicates ->
            require(duplicates.all { it == duplicates.first() }) { "Conflicting event sequence" }
            duplicates.first()
        }
        val fresh = unique.filter { record ->
            val existing = db.eventQueries.bySequence(record.deviceId, record.seq).executeAsOneOrNull()
            if (existing != null) require(toRecord(existing) == record) { "Conflicting stored event sequence" }
            existing == null
        }
        if (fresh.isEmpty()) return@transaction
        fresh.forEach(::insert)
        rebuildInTransaction()
    } }

    fun rebuild() = synchronized(writeLock) { db.transaction { rebuildInTransaction() } }

    fun <T> read(block: () -> T): T = synchronized(writeLock) { db.transactionWithResult { block() } }

    fun snapshot(): FamilyHistory = read { synced.snapshot() }

    /** Digest of the local history as a version 2 stream, prefixed with [HistoryStream.DIGEST_PREFIX]. */
    fun currentDigest(): String = read { synced.summary().digest }

    /** Checks a confirmation digest; unprefixed digests come from version 1 journals and are checked the version 1 way. */
    fun localDigestMatches(expected: String): Boolean = read { matches(expected) }

    private fun matches(expected: String): Boolean =
        if (expected.startsWith(HistoryStream.DIGEST_PREFIX)) synced.summary().digest == expected
        else runCatching { HistoryCodec.digest(HistoryCodec.encode(synced.snapshot())) }.getOrNull() == expected

    /** Copies the local history into a new finished spool, consistently with concurrent edits. */
    fun captureHistory(): StoredHistory = read { capture() }

    private fun capture(): StoredHistory {
        sweep()
        val id = spool.begin()
        live += id
        synced.forEachRecord { spool.write(id, it) }
        return spool.finish(id)
    }

    fun history(id: String): StoredHistory = read { spool.stored(id).also { live += id } }

    /** Reads [id] page by page, each page under the store lock, so long reads never block edits. */
    fun historySource(id: String): HistorySource {
        history(id)
        return HistorySource { spool.records(id, guard = { block -> read(block) }) }
    }

    fun beginHistory(): String = read { sweep(); spool.begin().also(live::add) }

    /** Operations are deduplicated by ID: identical bytes are kept once, different bytes are quarantined. */
    fun writeHistoryRecord(id: String, record: HistoryRecord) = writeHistoryRecords(id, listOf(record))

    fun writeHistoryRecords(id: String, records: List<HistoryRecord>) = read { records.forEach { spool.write(id, it) } }

    fun finishHistory(id: String): StoredHistory = read { spool.finish(id) }

    fun releaseHistory(id: String) = synchronized(writeLock) { live -= id }

    /** Deletes a staged or finished spool; spools still used by the baseline, a retained copy or recovery are kept. */
    fun discardHistory(id: String) = read {
        if (!spool.exists(id)) return@read
        require(id in spool.unreferenced()) { "History is still in use" }
        spool.delete(id)
        live -= id
    }

    /** Stages everything [produce] emits in batched transactions; the spool is discarded unless it finishes. */
    fun writeHistory(produce: ((HistoryRecord) -> Unit) -> Unit): StoredHistory {
        val id = beginHistory()
        try {
            val batch = mutableListOf<HistoryRecord>()
            var size = 0L
            produce { record ->
                batch += record
                size += when (record) {
                    is HistoryRecord.Operation -> record.payload.length.toLong()
                    is HistoryRecord.Quarantine -> record.value.payload.length.toLong()
                    is HistoryRecord.Baseline -> 2_048L
                }
                if (batch.size >= 512 || size >= 4L * 1024 * 1024) { writeHistoryRecords(id, batch.toList()); batch.clear(); size = 0 }
            }
            writeHistoryRecords(id, batch)
            return finishHistory(id)
        } catch (e: Throwable) {
            runCatching { discardHistory(id) }
            throw e
        }
    }

    fun writeHistory(records: Sequence<HistoryRecord>): StoredHistory = writeHistory { sink -> records.forEach(sink) }

    private fun stored(history: StoredHistory): HistorySource {
        check(spool.stored(history.id) == history) { "History changed" }
        return HistorySource { spool.records(history.id) }
    }

    private fun sweep() {
        spool.unreferenced().filter { it !in live }.forEach(spool::delete)
    }

    private fun dropIfUnused(id: String?) {
        if (id != null && id in spool.unreferenced()) { spool.delete(id); live -= id }
    }

    private fun clearJournal() {
        val history = db.restoreJournalQueries.journalHistory().executeAsOneOrNull()?.history_id
        db.restoreJournalQueries.deleteAll()
        dropIfUnused(history)
    }

    fun validateHistory(history: StoredHistory) = synchronized(writeLock) {
        db.transaction {
            synced.replace(stored(history))
            rollback()
        }
    }

    /** [history] with operations that fail local validation moved to quarantine; [history] itself when none do. */
    fun prepareCloudHistory(history: StoredHistory): StoredHistory = synchronized(writeLock) {
        val moved = mutableSetOf<String>()
        db.transaction { synced.replace(stored(history), quarantineInvalid = true) { moved += HistoryCodec.digest(it) }; rollback() }
        if (moved.isEmpty()) return@synchronized history
        writeHistory { sink ->
            stored(history).records().forEach { record ->
                val bytes = (record as? HistoryRecord.Operation)?.bytes()
                sink(if (bytes != null && HistoryCodec.digest(bytes) in moved) HistoryRecord.Quarantine(QuarantinedOperation.from(bytes)) else record)
            }
        }
    }

    fun validateHistory(history: FamilyHistory) = synchronized(writeLock) {
        db.transaction {
            synced.replace(history)
            rollback()
        }
    }

    fun prepareCloudHistory(history: FamilyHistory): FamilyHistory = synchronized(writeLock) {
        var prepared: FamilyHistory? = null
        db.transaction { synced.replace(history, quarantineInvalid = true); prepared = synced.snapshot(); rollback() }
        requireNotNull(prepared)
    }

    fun abandonRecovery(uid: String) = read {
        val journal = db.restoreJournalQueries.journalInfo().executeAsOneOrNull() ?: return@read
        require(journal.uid == uid)
        clearJournal()
    }

    /** Saves a recovery journal whose history lives in [history]'s spool instead of the manifest. */
    fun saveJournal(uid: String, familyId: String, sourceDataset: String, targetDataset: String, token: String, phase: String,
                    manifest: ByteArray, history: StoredHistory?) = read {
        history?.let(::stored)
        val previous = db.restoreJournalQueries.journalHistory().executeAsOneOrNull()?.history_id
        db.restoreJournalQueries.saveStored(uid, familyId, sourceDataset, targetDataset, token, phase, manifest, history?.id)
        if (previous != history?.id) dropIfUnused(previous)
    }

    /**
     * History of the pending recovery journal. A version 1 manifest embeds it as `history` or `enrollment.history`;
     * that history moves to a spool and leaves the manifest in the same transaction, so the journal is never half converted.
     */
    fun journalHistory(): StoredHistory? = read {
        val row = db.restoreJournalQueries.journalHistory().executeAsOneOrNull() ?: return@read null
        row.history_id?.let { return@read spool.stored(it).also { live += it.id } }
        val manifest = runCatching { Json.parseToJsonElement(requireNotNull(db.restoreManifest()).toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
            ?: return@read null
        val enrollment = manifest["enrollment"] as? JsonObject
        val element = manifest["history"] ?: enrollment?.get("history") ?: return@read null
        val stored = spool.import(Json.decodeFromJsonElement(FamilyHistory.serializer(), element))
        live += stored.id
        val stripped = if ("history" in manifest) JsonObject(manifest - "history")
            else JsonObject(manifest + ("enrollment" to JsonObject(requireNotNull(enrollment) - "history")))
        db.restoreJournalQueries.attachHistory(stripped.toString().toByteArray(Charsets.UTF_8), stored.id)
        stored
    }

    /** A retained copy as a spool; a version 1 copy is converted on first access. */
    fun draftHistory(uid: String, id: String): StoredHistory? = read {
        val row = db.syncDraftQueries.draftHistory(uid, id).executeAsOneOrNull() ?: return@read null
        val stored = row.history_id?.let(spool::stored) ?: spool.import(HistoryCodec.decode(requireNotNull(db.syncDraftPayload(uid, id))))
            .also { db.syncDraftQueries.attachHistory(it.id, uid, id) }
        stored.also { live += it.id }
    }

    /** On success [history] is consumed: its spool is deleted unless something else still references it. */
    fun restoreLocal(history: StoredHistory, expectedLocalDigest: String) = read {
        require(db.restoreJournalQueries.journalInfo().executeAsOneOrNull() == null) { "Cancel pending recovery before restoring a file" }
        require(db.syncSessionQueries.current().executeAsOneOrNull() == null) { "Restore shared family data while signed in" }
        if (!matches(expectedLocalDigest)) throw LocalHistoryChanged()
        val source = stored(history)
        db.syncDraftQueries.saveStored(newId(), "local", "", clock.now().toEpochMilli(), capture().id)
        synced.replace(source)
        dropIfUnused(history.id)
    }

    /** On success [history] and the journal spool are consumed, as in [restoreLocal]. */
    fun activate(uid: String, familyId: String, datasetId: String, memberId: String, generation: Long,
                 history: StoredHistory, expectedLocalDigest: String, preserveDraft: Boolean) = read {
        if (!matches(expectedLocalDigest)) throw LocalHistoryChanged()
        val source = stored(history)
        if (preserveDraft && db.eventQueries.count().executeAsOne() > 0) {
            val priorSession = db.syncSessionQueries.current().executeAsOneOrNull()
            db.syncDraftQueries.saveStored(newId(), priorSession?.uid ?: uid, priorSession?.family_id ?: "", clock.now().toEpochMilli(), capture().id)
        }
        synced.replace(source)
        db.syncSessionQueries.save(uid, familyId, datasetId, memberId, deviceId, generation, "ACTIVE")
        clearJournal()
        dropIfUnused(history.id)
    }

    fun restoreLocal(history: FamilyHistory, expectedLocalDigest: String) = read {
        require(db.restoreJournalQueries.journalInfo().executeAsOneOrNull() == null) { "Cancel pending recovery before restoring a file" }
        require(db.syncSessionQueries.current().executeAsOneOrNull() == null) { "Restore shared family data while signed in" }
        if (!matches(expectedLocalDigest)) throw LocalHistoryChanged()
        val before = HistoryCodec.encode(synced.snapshot())
        db.syncDraftQueries.save(newId(), "local", "", clock.now().toEpochMilli(), before)
        synced.replace(history)
    }

    fun activate(uid: String, familyId: String, datasetId: String, memberId: String, generation: Long,
                 history: FamilyHistory, expectedLocalDigest: String, preserveDraft: Boolean) = read {
        if (!matches(expectedLocalDigest)) throw LocalHistoryChanged()
        if (preserveDraft && db.eventQueries.count().executeAsOne() > 0) {
            val priorSession = db.syncSessionQueries.current().executeAsOneOrNull()
            db.syncDraftQueries.save(newId(), priorSession?.uid ?: uid, priorSession?.family_id ?: "", clock.now().toEpochMilli(),
                HistoryCodec.encode(synced.snapshot()))
        }
        synced.replace(history.source())
        db.syncSessionQueries.save(uid, familyId, datasetId, memberId, deviceId, generation, "ACTIVE")
        clearJournal()
    }

    fun receive(operations: List<ByteArray>, uid: String, familyId: String, datasetId: String,
                checkpointSeconds: Long, checkpointNanos: Long, checkpointId: String,
                quarantine: List<app.kinjar.domain.QuarantinedOperation> = emptyList()) = read {
        val session = requireNotNull(db.syncSessionQueries.current().executeAsOneOrNull())
        check(session.uid == uid && session.family_id == familyId && session.dataset_id == datasetId && session.status == "ACTIVE")
        synced.receive(operations, familyId, datasetId)
        quarantine.forEach(synced::quarantine)
        synced.rebuild()
        db.syncCheckpointQueries.save(datasetId, checkpointSeconds, checkpointNanos, checkpointId)
    }

    fun discardConflictingChange(id: String) = discardConflictingChanges(setOf(id))

    /** Rejects every listed held change, and what depends on them, in one resolution. */
    fun discardConflictingChanges(ids: Set<String>) = read { reject(ids, knownOperations()) }

    /** Keeps [id] by rejecting, in one resolution, the held changes that compete with it directly. */
    fun keepConflictingChange(id: String) = read {
        val known = knownOperations()
        val reasons = db.syncConflictQueries.all().executeAsList().associate { it.operation_id to it.reason }
        val held = HeldChanges(known.filter { it.id in reasons }.map { synced.operation(it.id) }, reasons)
        require(held.keepable(id)) { "Change cannot be kept" }
        reject(held.competitors(id), known)
    }

    private fun knownOperations() = synced.operationViews()

    private fun reject(ids: Set<String>, known: List<SyncOperation>) {
        val session = requireNotNull(db.syncSessionQueries.current().executeAsOneOrNull())
        check(session.status == "ACTIVE" && session.install_id == deviceId)
        check(db.restoreJournalQueries.journalInfo().executeAsOneOrNull()?.phase !in setOf("CREATE", "RESTORE", "SWITCH")) { "Finish family setup or restore before editing finances" }
        require(ids.isNotEmpty() && ids.all { id -> known.any { it.id == id } })
        val held = db.syncConflictQueries.all().executeAsList().map { it.operation_id }.toSet()
        check(ids.all(held::contains))
        val rejected = ids.toMutableSet()
        do { val before = rejected.size; known.filter { it.parents.any(rejected::contains) }.forEach { rejected += it.id } } while (before != rejected.size)
        val resources = known.filter { it.id in rejected }.flatMap { it.resources.keys }.associateWith { app.kinjar.domain.ResourceAccess.WRITE }
        val parents = ConflictResolver.parentsFor(known, resources) + rejected
        // Replay lets a resolution supersede earlier ones on the same records, so their rejections are restated.
        val ancestors = ancestors(parents, known)
        val effective = ConflictResolver.replay(known).rejected
        val restated = known.filter { it.id in ancestors && it.rejected.isNotEmpty() && it.resources.keys.any(resources::containsKey) }
            .flatMapTo(mutableSetOf()) { it.rejected }.intersect(effective)
        val operation = SyncOperation(familyId = session.family_id, datasetId = session.dataset_id, authorUid = session.uid,
            installId = deviceId, sequence = Math.addExact(db.syncOperationQueries.lastSequence(deviceId).executeAsOne().MAX ?: 0L, 1L),
            parents = parents, resources = resources, events = emptyList(), rejected = rejected + restated)
        db.syncOperationQueries.insert(operation.id, deviceId, operation.sequence, OperationCodec.encode(operation))
        db.syncOutboxQueries.enqueue(operation.id, session.family_id, session.dataset_id)
        synced.rebuild()
    }

    private fun ancestors(ids: Set<String>, known: List<SyncOperation>): Set<String> {
        val nodes = known.associateBy { it.id }
        val found = mutableSetOf<String>()
        val pending = ArrayDeque(ids)
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (found.add(id)) nodes[id]?.parents?.let(pending::addAll)
        }
        return found
    }

    fun clearRemovedFamily(uid: String, familyId: String) = read {
        val session = db.syncSessionQueries.current().executeAsOneOrNull() ?: return@read
        if (session.uid != uid || session.family_id != familyId) return@read
        val histories = listOf(db.syncBaselineQueries.baselineHistory().executeAsOneOrNull()?.history_id,
            db.restoreJournalQueries.journalHistory().executeAsOneOrNull()?.history_id) +
            db.syncDraftQueries.familyHistories(uid, familyId).executeAsList().map { it.history_id }
        projector.clear()
        db.syncOutboxQueries.deleteAll()
        db.syncOperationQueries.deleteAll()
        db.syncConflictQueries.deleteAll()
        db.syncCheckpointQueries.deleteAll()
        db.syncBaselineQueries.deleteAll()
        db.syncQuarantineQueries.deleteAll()
        db.syncDraftQueries.deleteFamily(uid, familyId)
        db.restoreJournalQueries.deleteAll()
        db.eventQueries.deleteAll()
        db.syncSessionQueries.deleteAll()
        histories.forEach(::dropIfUnused)
    }

    private fun rebuildInTransaction() {
        if (db.hasSyncBaseline()) {
            synced.rebuild()
            return
        }
        projector.clear()
        db.eventQueries.allInOrder().executeAsList().forEach { row -> toRecord(row)?.let(projector::apply) }
    }

    private fun insert(r: EventRecord) {
        val payload = EventJson.encodeToString(Event.serializer(), r.event)
        db.eventQueries.insert(
            id = r.id,
            device_id = r.deviceId,
            seq = r.seq,
            occurred_at = r.occurredAt.toEpochMilli(),
            type = EventJson.parseToJsonElement(payload).jsonObject.getValue("type").jsonPrimitive.content,
            payload = payload,
        )
    }

    /** Null for events this app version cannot read (newer type or shape); they stay in the log and are skipped. */
    private fun toRecord(row: EventRow): EventRecord? {
        val event = runCatching { EventJson.decodeFromString(Event.serializer(), row.payload) }.getOrNull() ?: return null
        return EventRecord(row.id, row.device_id, row.seq, Instant.ofEpochMilli(row.occurred_at), event)
    }
}
