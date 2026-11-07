package app.kinjar.data

import app.kinjar.data.backup.BackupCodec
import app.kinjar.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.time.Instant
import javax.crypto.AEADBadTagException

class BackupCodecTest {
    private val password = "four long random words".toCharArray()
    private val history = FamilyHistory(baseline = listOf(OperationEvent("event", "old-install", 1, 0,
        AccountCreated(Account("account", "Cash", Currency.RSD)))), operations = emptyList())
    private val large = FamilyHistory(baseline = (1..2_000).map { OperationEvent("event-$it", "old-install", it.toLong(), it.toLong(),
        AccountCreated(Account("account-$it", "Cash $it", Currency.RSD))) }, operations = emptyList())
    private val headerBytes = 47
    private val segmentBytes = BackupCodec.SEGMENT_BYTES + 16

    private fun sealStream(value: FamilyHistory = large, at: Instant = Instant.EPOCH) =
        ByteArrayOutputStream().also { BackupCodec.seal(value.source(), it, password, at) }.toByteArray()

    private fun openStream(bytes: ByteArray, secret: CharArray = password): Pair<Instant, List<HistoryRecord>> {
        val records = mutableListOf<HistoryRecord>()
        return BackupCodec.open(bytes.inputStream(), secret, records::add) to records
    }

    private fun segment(bytes: ByteArray, index: Int) = bytes.copyOfRange(headerBytes + index * segmentBytes, headerBytes + (index + 1) * segmentBytes)

    @Test fun `segmented backup reproduces the history stream across many segments`() {
        val at = Instant.parse("2026-11-07T10:00:00Z")
        val bytes = sealStream(at = at)
        assertTrue(bytes.size > headerBytes + 3 * segmentBytes)
        assertFalse(bytes.contentEquals(sealStream(at = at)))
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("Cash"))
        val (createdAt, records) = openStream(bytes)
        assertEquals(at, createdAt)
        assertEquals(large.source().records().toList(), records)
        assertEquals(HistoryStream.summarize(large.source().records()).digest, HistoryStream.summarize(records.asSequence()).digest)
    }

    @Test fun `version 1 backups open through the streaming reader`() {
        val at = Instant.parse("2026-11-04T10:00:00Z")
        val (createdAt, records) = openStream(BackupCodec.seal(history, password, at))
        assertEquals(at, createdAt)
        assertEquals(history.source().records().toList(), records)
    }

    @Test fun `truncated reordered tampered or extended segmented backups never complete`() {
        val bytes = sealStream()
        val count = (bytes.size - headerBytes + segmentBytes - 1) / segmentBytes
        val header = bytes.copyOf(headerBytes)
        val swapped = header + segment(bytes, 1) + segment(bytes, 0) + bytes.copyOfRange(headerBytes + 2 * segmentBytes, bytes.size)
        val variants = listOf(
            bytes.copyOf(headerBytes + 2 * segmentBytes),
            bytes.copyOf(headerBytes + (count - 1) * segmentBytes),
            bytes.copyOf(bytes.size - 1),
            swapped,
            bytes + byteArrayOf(0),
            bytes + segment(bytes, 0),
            bytes.copyOf().also { it[headerBytes + segmentBytes + 7] = (it[headerBytes + segmentBytes + 7].toInt() xor 1).toByte() },
            bytes.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() },
        )
        variants.forEach { assertThrows(GeneralSecurityException::class.java) { openStream(it) } }
        assertThrows(GeneralSecurityException::class.java) { openStream(bytes, "a different password".toCharArray()) }
    }

    @Test fun `unknown parameters and missing segments fail before decrypting`() {
        val bytes = sealStream(history)
        val iterations = bytes.copyOf().also { it[16] = 127 }
        val segmentSize = bytes.copyOf().also { it[headerBytes - 1] = 1 }
        listOf(bytes.copyOf(headerBytes), bytes.copyOf(headerBytes + 15), bytes.copyOf(20), iterations, segmentSize, ByteArray(0)).forEach {
            assertThrows(IllegalArgumentException::class.java) { openStream(it) }
        }
    }

    @Test fun `authenticated backup preserves history without reusing salt or nonce`() {
        val at = Instant.parse("2026-11-04T10:00:00Z")
        val first = BackupCodec.seal(history, password, at)
        val second = BackupCodec.seal(history, password, at)
        assertFalse(first.contentEquals(second))
        val opened = BackupCodec.open(first, password)
        assertEquals(at, opened.createdAt)
        assertEquals(history, opened.history)
        assertFalse(first.toString(Charsets.UTF_8).contains("Cash"))
    }

    @Test fun `wrong password changed header and changed ciphertext fail authentication`() {
        val bytes = BackupCodec.seal(history, password)
        assertThrows(AEADBadTagException::class.java) { BackupCodec.open(bytes, "a different password".toCharArray()) }
        listOf(10, bytes.lastIndex).forEach { index ->
            val corrupted = bytes.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertThrows(AEADBadTagException::class.java) { BackupCodec.open(corrupted, password) }
        }
    }

    @Test fun `truncation trailing data unsupported version and hostile KDF fail before deriving key`() {
        val bytes = BackupCodec.seal(history, password)
        val version = bytes.copyOf().also { it[7] = 2 }
        val iterations = bytes.copyOf().also { it[16] = 127 }
        listOf(bytes.copyOf(20), bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(0), version, iterations).forEach {
            assertThrows(IllegalArgumentException::class.java) { BackupCodec.open(it, password) }
        }
    }
}
