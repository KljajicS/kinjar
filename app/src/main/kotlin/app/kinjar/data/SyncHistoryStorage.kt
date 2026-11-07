package app.kinjar.data

import app.kinjar.data.db.KinjarDb

// Keep each history slice below Android's CursorWindow row limit, including on API 26.
internal const val HISTORY_CHUNK_BYTES = 256 * 1024

internal fun KinjarDb.hasSyncBaseline(): Boolean = syncBaselineQueries.present().executeAsOne() > 0

internal fun KinjarDb.syncBaselinePayload(): ByteArray? = readBlob(
    size = { syncBaselineQueries.baselineSize().executeAsOneOrNull() },
    chunk = { start, span -> syncBaselineQueries.baselineChunk(start.toString(), span.toString()).executeAsOneOrNull() },
)

internal fun KinjarDb.syncDraftPayload(uid: String, id: String): ByteArray? = readBlob(
    size = { syncDraftQueries.draftSize(uid, id).executeAsOneOrNull() },
    chunk = { start, span -> syncDraftQueries.draftChunk(start.toString(), span.toString(), uid, id).executeAsOneOrNull() },
)

internal fun KinjarDb.restoreManifest(): ByteArray? = readBlob(
    size = { restoreJournalQueries.manifestSize().executeAsOneOrNull() },
    chunk = { start, span -> restoreJournalQueries.manifestChunk(start.toString(), span.toString()).executeAsOneOrNull() },
)

private fun KinjarDb.readBlob(size: () -> Long?, chunk: (Long, Long) -> ByteArray?): ByteArray? = transactionWithResult {
    val length = size() ?: return@transactionWithResult null
    val out = ByteArray(Math.toIntExact(length))
    var at = 0
    while (at < out.size) {
        val bytes = checkNotNull(chunk(at + 1L, HISTORY_CHUNK_BYTES.toLong())) { "History changed while reading" }
        check(bytes.size == minOf(HISTORY_CHUNK_BYTES, out.size - at)) { "History changed while reading" }
        bytes.copyInto(out, at)
        at += bytes.size
    }
    out
}
