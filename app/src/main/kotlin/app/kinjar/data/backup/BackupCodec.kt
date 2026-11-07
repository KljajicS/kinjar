package app.kinjar.data.backup

import app.kinjar.data.StoredHistory
import app.kinjar.domain.FamilyHistory
import app.kinjar.domain.HistoryCodec
import app.kinjar.domain.HistoryRecord
import app.kinjar.domain.HistorySource
import app.kinjar.domain.HistoryStream
import app.kinjar.domain.HistorySummary
import app.kinjar.domain.source
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class OpenBackup(val createdAt: Instant, val history: FamilyHistory)

data class OpenStoredBackup(val createdAt: Instant, val history: StoredHistory)

object BackupCodec {
    private val magic = byteArrayOf(75, 73, 78, 74, 65, 82, 66, 1)
    private const val HEADER_SIZE = 52
    private const val ITERATIONS = 600_000
    const val MAX_BYTES = HistoryCodec.MAX_BYTES + HEADER_SIZE + 16

    private val streamMagic = byteArrayOf(75, 73, 78, 74, 65, 82, 66, 2)
    private const val STREAM_HEADER_SIZE = 8 + 8 + 4 + 16 + 7 + 4
    private const val TAG_BYTES = 16
    const val SEGMENT_BYTES = 64 * 1024

    /**
     * Writes a version 2 backup: the history stream in AES-GCM segments. Every segment authenticates the header and
     * carries its index and a final flag in the nonce, so truncation, reordering and tampering fail to open.
     */
    fun seal(source: HistorySource, output: OutputStream, password: CharArray, createdAt: Instant = Instant.now()): HistorySummary {
        require(password.size in 12..1024) { "Use a passphrase of at least 12 characters" }
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val prefix = ByteArray(7).also(random::nextBytes)
        val header = ByteBuffer.allocate(STREAM_HEADER_SIZE).put(streamMagic).putLong(createdAt.toEpochMilli()).putInt(ITERATIONS)
            .put(salt).put(prefix).putInt(SEGMENT_BYTES).array()
        val key = derive(password, salt)
        return try {
            output.write(header)
            val sealed = SegmentOutput(output, SecretKeySpec(key, "AES"), header, prefix)
            val writer = HistoryStream.Writer(sealed)
            source.records().forEach(writer::write)
            writer.finish().also { sealed.finish(); output.flush() }
        } finally { key.fill(0) }
    }

    /**
     * Opens a version 1 or 2 backup, emitting records to [sink] while it decrypts. Returns only after the end line,
     * the record counts, the final segment and the end of input all checked out; until then nothing is complete.
     */
    fun open(input: InputStream, password: CharArray, sink: (HistoryRecord) -> Unit): Instant {
        require(password.size in 12..1024) { "Invalid backup or password" }
        val version = readFully(input, 8)
        if (version.contentEquals(magic)) {
            val legacy = ByteArrayOutputStream().apply { write(version) }
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(legacy.size() + count <= MAX_BYTES) { "Backup exceeds size limit" }
                legacy.write(buffer, 0, count)
            }
            val opened = open(legacy.toByteArray(), password)
            opened.history.source().records().forEach(sink)
            return opened.createdAt
        }
        require(version.contentEquals(streamMagic)) { "Unsupported backup format" }
        val header = version + readFully(input, STREAM_HEADER_SIZE - 8)
        require(header.size == STREAM_HEADER_SIZE) { "Incomplete backup" }
        val fields = ByteBuffer.wrap(header, 8, STREAM_HEADER_SIZE - 8)
        val createdAt = Instant.ofEpochMilli(fields.long)
        require(fields.int == ITERATIONS) { "Unsupported backup parameters" }
        val salt = ByteArray(16).also(fields::get)
        val prefix = ByteArray(7).also(fields::get)
        require(fields.int == SEGMENT_BYTES) { "Unsupported backup parameters" }
        val key = derive(password, salt)
        try {
            HistoryStream.read(SegmentInput(input, SecretKeySpec(key, "AES"), header, prefix)).forEach(sink)
        } finally { key.fill(0) }
        return createdAt
    }

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

    private fun readFully(input: InputStream, size: Int): ByteArray {
        val out = ByteArray(size)
        var at = 0
        while (at < size) {
            val count = input.read(out, at, size - at)
            if (count < 0) break
            at += count
        }
        return if (at == size) out else out.copyOf(at)
    }

    private fun nonce(prefix: ByteArray, index: Int, last: Boolean): GCMParameterSpec =
        GCMParameterSpec(128, ByteBuffer.allocate(12).put(prefix).putInt(index).put((if (last) 1 else 0).toByte()).array())

    private class SegmentOutput(private val output: OutputStream, private val key: SecretKeySpec, private val header: ByteArray,
                                private val prefix: ByteArray) : OutputStream() {
        private val buffer = ByteArray(SEGMENT_BYTES)
        private var size = 0
        private var index = 0

        override fun write(b: Int) {
            if (size == SEGMENT_BYTES) seal(false)
            buffer[size++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var at = off
            val end = off + len
            while (at < end) {
                if (size == SEGMENT_BYTES) seal(false)
                val count = minOf(SEGMENT_BYTES - size, end - at)
                b.copyInto(buffer, size, at, at + count)
                size += count
                at += count
            }
        }

        fun finish() = seal(true)

        private fun seal(last: Boolean) {
            check(index != Int.MAX_VALUE) { "Backup too large" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, nonce(prefix, index++, last))
            cipher.updateAAD(header)
            output.write(cipher.doFinal(buffer, 0, size))
            buffer.fill(0, 0, size)
            size = 0
        }
    }

    /** Decrypts segment by segment; a segment counts as final only when the input ends right after it. */
    private class SegmentInput(private val input: InputStream, private val key: SecretKeySpec, private val header: ByteArray,
                               private val prefix: ByteArray) : InputStream() {
        private var ahead = readFully(input, SEGMENT_BYTES + TAG_BYTES)
        private var plain = ByteArray(0)
        private var at = 0
        private var index = 0
        private var done = false

        init { require(ahead.size >= TAG_BYTES) { "Incomplete backup" } }

        override fun read(): Int {
            while (at == plain.size) if (!advance()) return -1
            return plain[at++].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (at == plain.size) if (!advance()) return -1
            val count = minOf(len, plain.size - at)
            plain.copyInto(b, off, at, at + count)
            at += count
            return count
        }

        private fun advance(): Boolean {
            if (done) return false
            val current = ahead
            val next = if (current.size == SEGMENT_BYTES + TAG_BYTES) readFully(input, SEGMENT_BYTES + TAG_BYTES) else ByteArray(0)
            val last = next.isEmpty()
            require(current.size >= TAG_BYTES) { "Incomplete backup" }
            check(index != Int.MAX_VALUE) { "Backup too large" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, nonce(prefix, index++, last))
            cipher.updateAAD(header)
            plain.fill(0)
            plain = cipher.doFinal(current)
            at = 0
            if (last) done = true else ahead = next
            return true
        }
    }
}
