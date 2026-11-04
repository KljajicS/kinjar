package app.kinjar.data

import app.kinjar.data.backup.BackupCodec
import app.kinjar.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import javax.crypto.AEADBadTagException

class BackupCodecTest {
    private val password = "four long random words".toCharArray()
    private val history = FamilyHistory(baseline = listOf(OperationEvent("event", "old-install", 1, 0,
        AccountCreated(Account("account", "Cash", Currency.RSD)))), operations = emptyList())

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
