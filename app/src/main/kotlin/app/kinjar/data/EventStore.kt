package app.kinjar.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import java.time.Instant

class EventStore(
    private val db: KinjarDb,
    private val deviceId: String,
    private val clock: Clock,
    private val projector: Projector,
) {
    private val writeLock = Any()
    private val synced = SyncedHistory(db, projector)

    suspend fun append(event: Event, validate: () -> Unit = {}): EventRecord = appendAll(listOf(event), validate = validate).single()

    suspend fun appendAll(events: List<Event>, skipIf: () -> Boolean = { false }, validate: () -> Unit = {}): List<EventRecord> = withContext(Dispatchers.IO) {
        synchronized(writeLock) {
            db.transactionWithResult {
                if (skipIf()) return@transactionWithResult emptyList()
                validate()
                check(db.restoreJournalQueries.current().executeAsOneOrNull()?.phase !in setOf("CREATE", "RESTORE", "SWITCH")) { "Finish family setup or restore before editing finances" }
                val session = db.syncSessionQueries.current().executeAsOneOrNull()
                if (session != null) {
                    check(session.install_id == deviceId) { "Installation must recover its session" }
                    check(session.status == "ACTIVE") { "Family data is not writable" }
                }
                val tracked = session != null || db.syncBaselineQueries.current().executeAsOneOrNull() != null
                val resources = if (!tracked) emptyMap() else operationResources(events, db)
                val known = if (!tracked) emptyList() else db.syncOperationQueries.all().executeAsList().map { OperationCodec.decode(it.payload) }
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
        val journal = db.restoreJournalQueries.current().executeAsOneOrNull() ?: return@read
        require(journal.uid == uid)
        db.restoreJournalQueries.deleteAll()
    }

    fun restoreLocal(history: FamilyHistory, expectedLocalDigest: String) = read {
        require(db.restoreJournalQueries.current().executeAsOneOrNull() == null) { "Cancel pending recovery before restoring a file" }
        require(db.syncSessionQueries.current().executeAsOneOrNull() == null) { "Restore shared family data while signed in" }
        val before = HistoryCodec.encode(synced.snapshot())
        if (HistoryCodec.digest(before) != expectedLocalDigest) throw app.kinjar.domain.LocalHistoryChanged()
        db.syncDraftQueries.save(newId(), "local", "", clock.now().toEpochMilli(), before)
        synced.replace(history)
    }

    fun activate(uid: String, familyId: String, datasetId: String, memberId: String, generation: Long,
                 history: FamilyHistory, expectedLocalDigest: String, preserveDraft: Boolean) = read {
        val prior = HistoryCodec.encode(synced.snapshot())
        if (HistoryCodec.digest(prior) != expectedLocalDigest) throw app.kinjar.domain.LocalHistoryChanged()
        if (preserveDraft && db.eventQueries.count().executeAsOne() > 0) {
            val priorSession = db.syncSessionQueries.current().executeAsOneOrNull()
            db.syncDraftQueries.save(newId(), priorSession?.uid ?: uid, priorSession?.family_id ?: "", clock.now().toEpochMilli(), prior)
        }
        synced.replace(history)
        db.syncSessionQueries.save(uid, familyId, datasetId, memberId, deviceId, generation, "ACTIVE")
        db.restoreJournalQueries.deleteAll()
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
        val held = HeldChanges(known, db.syncConflictQueries.all().executeAsList().associate { it.operation_id to it.reason })
        require(held.keepable(id)) { "Change cannot be kept" }
        reject(held.competitors(id), known)
    }

    private fun knownOperations() = db.syncOperationQueries.all().executeAsList().map { OperationCodec.decode(it.payload) }

    private fun reject(ids: Set<String>, known: List<SyncOperation>) {
        val session = requireNotNull(db.syncSessionQueries.current().executeAsOneOrNull())
        check(session.status == "ACTIVE" && session.install_id == deviceId)
        check(db.restoreJournalQueries.current().executeAsOneOrNull()?.phase !in setOf("CREATE", "RESTORE", "SWITCH")) { "Finish family setup or restore before editing finances" }
        require(ids.isNotEmpty() && ids.all { id -> known.any { it.id == id } })
        val held = db.syncConflictQueries.all().executeAsList().map { it.operation_id }.toSet()
        check(ids.all(held::contains))
        val rejected = ids.toMutableSet()
        do { val before = rejected.size; known.filter { it.parents.any(rejected::contains) }.forEach { rejected += it.id } } while (before != rejected.size)
        val resources = known.filter { it.id in rejected }.flatMap { it.resources.keys }.associateWith { app.kinjar.domain.ResourceAccess.WRITE }
        val parents = ConflictResolver.parentsFor(known, resources) + rejected
        // Replay lets a resolution supersede earlier ones on the same records, so their rejections are restated.
        val ancestors = ancestors(parents, known)
        val effective = ConflictResolver.replay(known.map { it.copy(familyId = "history", datasetId = "history") }).rejected
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
    }

    private fun rebuildInTransaction() {
        if (db.syncBaselineQueries.current().executeAsOneOrNull() != null) {
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
