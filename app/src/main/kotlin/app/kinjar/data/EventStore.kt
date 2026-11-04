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
import java.time.Instant

class EventStore(
    private val db: KinjarDb,
    private val deviceId: String,
    private val clock: Clock,
    private val projector: Projector,
) {
    private val writeLock = Any()

    suspend fun append(event: Event, validate: () -> Unit = {}): EventRecord = appendAll(listOf(event), validate = validate).single()

    suspend fun appendAll(events: List<Event>, skipIf: () -> Boolean = { false }, validate: () -> Unit = {}): List<EventRecord> = withContext(Dispatchers.IO) {
        synchronized(writeLock) {
            db.transactionWithResult {
                if (skipIf()) return@transactionWithResult emptyList()
                validate()
                val session = db.syncSessionQueries.current().executeAsOneOrNull()
                if (session != null) {
                    check(session.install_id == deviceId) { "Installation must recover its session" }
                    check(session.status == "ACTIVE") { "Family data is not writable" }
                }
                val resources = if (session == null) emptyMap() else operationResources(events, db)
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
                if (session != null && records.isNotEmpty()) {
                    val known = db.syncOperationQueries.all().executeAsList().map { OperationCodec.decode(it.payload) }
                    val parentIds = known.flatMapTo(mutableSetOf()) { it.parents }
                    val heads = known.map { it.id }.filterTo(mutableSetOf()) { it !in parentIds }
                    val operation = SyncOperation(
                        familyId = session.family_id, datasetId = session.dataset_id,
                        authorUid = session.uid, installId = deviceId,
                        sequence = Math.addExact(db.syncOperationQueries.lastSequence(deviceId).executeAsOneOrNull()?.MAX ?: 0L, 1L),
                        parents = heads, resources = resources, events = records.map(OperationEvent::from),
                    )
                    db.syncOperationQueries.insert(operation.id, deviceId, operation.sequence, OperationCodec.encode(operation))
                    db.syncOutboxQueries.enqueue(operation.id, session.family_id, session.dataset_id)
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

    private fun rebuildInTransaction() {
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
