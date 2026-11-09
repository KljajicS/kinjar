package app.kinjar.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kinjar.data.db.KinjarDb
import app.kinjar.domain.Event
import app.kinjar.domain.EventJson
import app.kinjar.domain.EventRecord
import java.time.Instant
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun testDb(): KinjarDb {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    KinjarDb.Schema.create(driver)
    return KinjarDb(driver)
}

fun seedLegacyEvents(db: KinjarDb, events: List<Event>, at: Instant) {
    seedLegacyEventRows(db, events, at).forEach(Projector(db)::apply)
}

/** Event rows only, for schemas older than the current projector writes. */
fun seedLegacyEventRows(db: KinjarDb, events: List<Event>, at: Instant): List<EventRecord> = events.mapIndexed { index, event ->
    val payload = EventJson.encodeToString(Event.serializer(), event)
    val record = EventRecord("legacy-$index", "legacy", index.toLong() + 1, at.plusMillis(index.toLong()), event)
    db.eventQueries.insert(record.id, record.deviceId, record.seq, record.occurredAt.toEpochMilli(),
        EventJson.parseToJsonElement(payload).jsonObject.getValue("type").jsonPrimitive.content, payload)
    record
}
