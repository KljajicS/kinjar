package app.kinjar.data.sync

import app.kinjar.domain.HistoryCodec
import app.kinjar.domain.FamilyHistory
import app.kinjar.domain.HistoryStream
import app.kinjar.domain.source
import app.kinjar.domain.OperationCodec
import app.kinjar.domain.SyncOperation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

class FirestoreTransportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val chunk = FirestoreTransport.CHUNK_BYTES

    private fun rejecting(count: Int) = SyncOperation(familyId = "family", datasetId = "dataset", authorUid = "user", installId = "device",
        sequence = 5_000, parents = emptySet(), resources = emptyMap(), events = emptyList(), rejected = (1..count).map { "peer:$it" }.toSet())

    private fun spool(bytes: ByteArray, chunks: Long = (bytes.size + chunk - 1L) / chunk, digest: String = HistoryCodec.digest(bytes)) =
        ChunkSpool(File(temporary.root, "baseline-${Random.nextLong()}"), chunks, bytes.size.toLong(), digest)

    private fun pieces(bytes: ByteArray) = (bytes.indices step chunk).map { bytes.copyOfRange(it, minOf(it + chunk, bytes.size)) }

    @Test fun `stream pieces regroup into full chunks with only a short tail`() {
        val pieces = listOf(ByteArray(10) { 1 }, ByteArray(0), ByteArray(25) { 2 }, ByteArray(3) { 3 })
        val chunks = chunked(pieces.asSequence(), 16).toList()
        assertEquals(listOf(16, 16, 6), chunks.map { it.size })
        assertArrayEquals(pieces.reduce(ByteArray::plus), chunks.reduce(ByteArray::plus))
    }

    @Test fun `manifest covers the whole stream without holding it`() {
        val bytes = Random(1).nextBytes(chunk * 2 + 7)
        val manifest = StreamManifest.of(sequenceOf(bytes.copyOfRange(0, 100), bytes.copyOfRange(100, bytes.size)))
        assertEquals(StreamManifest(HistoryCodec.digest(bytes), bytes.size.toLong(), 3), manifest)
        assertTrue(runCatching { StreamManifest.of(emptySequence()) }.isFailure)
    }

    @Test fun `cloud stream and staged history use the same bytes and legacy files remain exact`() {
        val history = FamilyHistory(baseline = emptyList(), operations = emptyList())
        val source = history.source()
        val summary = HistoryStream.summarize(source.records())
        val manifest = StreamManifest.of(historyPieces(source))
        assertEquals(summary.digest.removePrefix("2:"), manifest.digest)
        assertEquals(summary.bytes, manifest.bytes)
        val legacy = temporary.newFile()
        legacy.outputStream().use { HistoryCodec.write(source, it) }
        assertArrayEquals(HistoryCodec.encode(history), filePieces(legacy).reduce(ByteArray::plus))
    }

    @Test fun `complete baseline spool keeps the exact downloaded bytes`() {
        val bytes = Random(2).nextBytes(chunk * 2 + 9)
        val spool = spool(bytes)
        pieces(bytes).forEach(spool::write)
        spool.finish()
        assertArrayEquals(bytes, spool.file.readBytes())
    }

    @Test fun `truncated reordered or tampered baselines are rejected before parsing`() {
        val bytes = Random(3).nextBytes(chunk * 2 + 9)
        val chunks = pieces(bytes)
        val truncated = spool(bytes)
        chunks.dropLast(1).forEach(truncated::write)
        assertTrue(runCatching { truncated.finish() }.isFailure)

        val reordered = spool(bytes)
        listOf(chunks[1], chunks[0], chunks[2]).forEach(reordered::write)
        assertTrue(runCatching { reordered.finish() }.isFailure)

        val tampered = spool(bytes)
        chunks.forEachIndexed { index, piece -> tampered.write(if (index == 1) piece.copyOf().also { it[7] = (it[7] + 1).toByte() } else piece) }
        assertTrue(runCatching { tampered.finish() }.isFailure)

        val extra = spool(bytes)
        chunks.forEach(extra::write)
        assertTrue(runCatching { extra.write(byteArrayOf(1)) }.isFailure)
    }

    @Test fun `baseline manifests and chunks stay within technical bounds`() {
        assertTrue(runCatching { spool(ByteArray(chunk + 1), chunks = 1) }.isFailure)
        assertTrue(runCatching { spool(ByteArray(3), chunks = 4) }.isFailure)
        val oversized = spool(ByteArray(chunk + 1), chunks = 2)
        assertTrue(runCatching { oversized.write(ByteArray(chunk + 1)) }.isFailure)
        val empty = spool(ByteArray(2), chunks = 1)
        assertTrue(runCatching { empty.write(ByteArray(0)) }.isFailure)
    }

    @Test fun `operation cost charges compact references at their expanded size`() {
        val operation = rejecting(1_001)
        val payload = OperationCodec.encode(operation)
        assertEquals(2, operation.version)
        assertEquals(payload.size + 32L * 1_001, FirestoreTransport.operationCost(operation, payload.size))
        assertTrue(payload.size < 32 * 1_001)
    }

    @Test fun `downloaded operations must carry their exact cost unless they predate costs`() {
        val compact = OperationCodec.encode(rejecting(1_001))
        val cost = FirestoreTransport.operationCost(OperationCodec.decode(compact), compact.size)
        assertEquals(rejecting(1_001), FirestoreTransport.checkedOperation(compact, cost))
        for (claimed in listOf(null, cost - 1, cost + 1, compact.size.toLong() - 1, cost.toInt(), FirestoreTransport.MAX_DAILY_BYTES + 1, "$cost")) {
            assertTrue("$claimed", runCatching { FirestoreTransport.checkedOperation(compact, claimed) }.isFailure)
        }
        val legacy = OperationCodec.encode(rejecting(1))
        assertEquals(1, FirestoreTransport.checkedOperation(legacy, null).version)
        assertEquals(1, FirestoreTransport.checkedOperation(legacy, legacy.size + 32L).version)
    }
}
