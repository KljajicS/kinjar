package app.kinjar.data

import app.kinjar.data.db.KinjarDb
import app.kinjar.domain.FamilyHistory
import app.kinjar.domain.HistoryCodec
import app.kinjar.domain.HistoryRecord
import app.kinjar.domain.HistoryStream
import app.kinjar.domain.OperationCodec
import app.kinjar.domain.QuarantinedOperation
import app.kinjar.domain.UnsupportedSyncVersion
import app.kinjar.domain.newId
import app.kinjar.domain.source
import kotlinx.serialization.Serializable

/** A complete history kept in the local spool; [digest] and [bytes] describe its version 2 stream. */
@Serializable
data class StoredHistory(
    val id: String,
    val digest: String,
    val bytes: Long,
    val baselineCount: Long,
    val operationCount: Long,
    val quarantineCount: Long,
)

/**
 * One row per history record, so no history is ever one blob or one ByteArray. A spool is staged record by record
 * and becomes readable only once finished; finished spools never change.
 */
internal class HistorySpool(private val db: KinjarDb) {
    private val queries get() = db.historySpoolQueries

    fun begin(): String = newId().also(queries::begin)

    fun write(id: String, record: HistoryRecord) {
        val info = requireNotNull(queries.info(id).executeAsOneOrNull()) { "Unknown history" }
        require(info.complete == 0L) { "History already complete" }
        when (record) {
            is HistoryRecord.Baseline -> insert(id, BASELINE, null, null, HistoryStream.encodeEvent(record.event))
            is HistoryRecord.Quarantine -> quarantine(id, record.value)
            is HistoryRecord.Operation -> {
                val bytes = record.bytes()
                val operationId = try { OperationCodec.decode(bytes).id } catch (e: UnsupportedSyncVersion) { throw e }
                    catch (_: IllegalArgumentException) { null }
                val prior = operationId?.let { queries.recordByKey(id, OPERATION, it).executeAsOneOrNull() }
                when {
                    operationId == null -> quarantine(id, QuarantinedOperation.from(bytes))
                    prior == null -> insert(id, OPERATION, operationId, null, bytes)
                    !prior.contentEquals(bytes) -> quarantine(id, QuarantinedOperation.from(bytes, "IDENTITY_MISMATCH"))
                }
            }
        }
    }

    fun finish(id: String): StoredHistory {
        val info = requireNotNull(queries.info(id).executeAsOneOrNull()) { "Unknown history" }
        require(info.complete == 0L) { "History already complete" }
        val expected = listOf(info.baseline_count, info.operation_count, info.quarantine_count)
        check(listOf(BASELINE, OPERATION, QUARANTINE).map { queries.countKind(id, it).executeAsOne() } == expected) { "History staging lost records" }
        val summary = HistoryStream.summarize(records(id, requireComplete = false))
        check(listOf(summary.baselineCount, summary.operationCount, summary.quarantineCount) == expected) { "History staging lost records" }
        queries.complete(summary.digest, summary.bytes, id)
        return stored(id)
    }

    fun stored(id: String): StoredHistory {
        val info = requireNotNull(queries.info(id).executeAsOneOrNull()) { "Unknown history" }
        require(info.complete == 1L) { "History is incomplete" }
        return StoredHistory(info.id, info.digest, info.bytes, info.baseline_count, info.operation_count, info.quarantine_count)
    }

    fun exists(id: String): Boolean = queries.info(id).executeAsOneOrNull() != null

    /**
     * Pages through the spool; [guard] wraps every database access, so callers can read outside one long transaction.
     * Throws when the spool changed or lost records while being read.
     */
    fun records(id: String, guard: (() -> Unit) -> Unit = { it() }, requireComplete: Boolean = true): Sequence<HistoryRecord> = sequence {
        var before: StoredHistory? = null
        guard { before = if (requireComplete) stored(id) else null }
        val counts = LongArray(3)
        for (kind in BASELINE..QUARANTINE) {
            var position = -1L
            while (true) {
                var page = emptyList<app.kinjar.data.db.SpoolPage>()
                guard { page = queries.spoolPage(id, kind, position, PAGE).executeAsList() }
                if (page.isEmpty()) break
                for (row in page) {
                    position = row.position
                    counts[kind.toInt()]++
                    yield(when (kind) {
                        BASELINE -> HistoryRecord.Baseline(HistoryStream.decodeEvent(row.payload))
                        OPERATION -> HistoryRecord.Operation.of(row.payload)
                        else -> HistoryRecord.Quarantine(QuarantinedOperation.from(row.payload, requireNotNull(row.reason)))
                    })
                }
            }
        }
        before?.let { expected ->
            var after: StoredHistory? = null
            guard { after = if (exists(id)) stored(id) else null }
            check(after == expected && counts.toList() == listOf(expected.baselineCount, expected.operationCount, expected.quarantineCount)) {
                "History changed while reading"
            }
        }
    }

    fun delete(id: String) {
        queries.deleteRecords(id)
        queries.delete(id)
    }

    fun unreferenced(): List<String> = queries.unreferenced().executeAsList()

    /** Converts a version 1 history, which is bounded by its own 64 MiB format, into a finished spool. */
    fun import(history: FamilyHistory): StoredHistory {
        val id = begin()
        history.source().records().forEach { write(id, it) }
        return finish(id)
    }

    private fun quarantine(id: String, value: QuarantinedOperation) {
        val bytes = value.bytes()
        val key = HistoryCodec.digest(bytes)
        if (queries.recordByKey(id, QUARANTINE, key).executeAsOneOrNull() == null) insert(id, QUARANTINE, key, value.reason, bytes)
    }

    private fun insert(id: String, kind: Long, key: String?, reason: String?, payload: ByteArray) {
        val info = queries.info(id).executeAsOne()
        queries.insertRecord(id, kind, info.next_position, key, reason, payload)
        queries.advance(if (kind == BASELINE) 1 else 0, if (kind == OPERATION) 1 else 0, if (kind == QUARANTINE) 1 else 0, id)
    }

    private companion object {
        const val BASELINE = 0L
        const val OPERATION = 1L
        const val QUARANTINE = 2L
        // Records are bounded near 1 MiB, so a page stays far below memory and cursor window limits.
        const val PAGE = 16L
    }
}
