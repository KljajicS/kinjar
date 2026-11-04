package app.kinjar.data.backup

import app.kinjar.domain.FamilyHistory
import app.kinjar.domain.HistoryCodec
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class OpenBackup(val createdAt: Instant, val history: FamilyHistory)

object BackupCodec {
    private val magic = byteArrayOf(75, 73, 78, 74, 65, 82, 66, 1)
    private const val HEADER_SIZE = 52
    private const val ITERATIONS = 600_000
    const val MAX_BYTES = HistoryCodec.MAX_BYTES + HEADER_SIZE + 16

    fun seal(history: FamilyHistory, password: CharArray, createdAt: Instant = Instant.now()): ByteArray {
        require(password.size in 12..1024) { "Use a passphrase of at least 12 characters" }
        val plaintext = HistoryCodec.encode(history)
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_SIZE).put(magic).putLong(createdAt.toEpochMilli()).putInt(ITERATIONS)
            .put(salt).put(nonce).putInt(plaintext.size + 16).array()
        val key = derive(password, salt)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            header + cipher.doFinal(plaintext)
        } finally { key.fill(0); plaintext.fill(0) }
    }

    fun open(bytes: ByteArray, password: CharArray): OpenBackup {
        require(bytes.size in HEADER_SIZE + 17..MAX_BYTES && password.size in 12..1024) { "Invalid backup or password" }
        val input = ByteBuffer.wrap(bytes)
        require(ByteArray(8).also(input::get).contentEquals(magic)) { "Unsupported backup format" }
        val createdAt = Instant.ofEpochMilli(input.long)
        require(input.int == ITERATIONS) { "Unsupported backup parameters" }
        val salt = ByteArray(16).also(input::get)
        val nonce = ByteArray(12).also(input::get)
        require(input.int == bytes.size - HEADER_SIZE) { "Incomplete backup" }
        val key = derive(password, salt)
        val plaintext = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(bytes, 0, HEADER_SIZE)
            cipher.doFinal(bytes, HEADER_SIZE, bytes.size - HEADER_SIZE)
        } finally { key.fill(0) }
        return try { OpenBackup(createdAt, HistoryCodec.decode(plaintext)) } finally { plaintext.fill(0) }
    }

    private fun derive(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
}
