package app.kinjar.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

@Serializable
data class QuarantinedOperation(val payload: String, val reason: String) {
    init { require(reason in setOf("INVALID_OPERATION", "IDENTITY_MISMATCH")) }
    fun bytes(): ByteArray = Base64.getDecoder().decode(payload).also { require(it.size in 1..OperationCodec.MAX_BYTES) }
    companion object {
        fun from(bytes: ByteArray, reason: String = "INVALID_OPERATION") = QuarantinedOperation(Base64.getEncoder().encodeToString(bytes), reason)
    }
}

@Serializable
data class FamilyHistory(val version: Int = 1, val baseline: List<OperationEvent>, val operations: List<String>, val quarantine: List<QuarantinedOperation> = emptyList()) {
    init { require(version == 1 && baseline.size <= 100_000 && operations.size <= 100_000 && quarantine.size <= 100_000) }
    fun decodedOperations(): List<SyncOperation> = operations.map { OperationCodec.decode(Base64.getDecoder().decode(it)) }
}

object HistoryCodec {
    const val MAX_BYTES = 64 * 1024 * 1024
    private val json = Json { encodeDefaults = true; classDiscriminator = "type" }
    fun encode(history: FamilyHistory): ByteArray = json.encodeToString(history).toByteArray(Charsets.UTF_8).also {
        require(it.size <= MAX_BYTES) { "Family history capacity reached" }
    }
    fun decode(bytes: ByteArray): FamilyHistory {
        require(bytes.size in 1..MAX_BYTES)
        val text = bytes.toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes))
        if (json.parseToJsonElement(text).jsonObject["version"]?.jsonPrimitive?.intOrNull != 1) throw UnsupportedSyncVersion()
        return json.decodeFromString<FamilyHistory>(text)
    }
    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    data class Encoded(val digest: String, val bytes: Long)

    /**
     * Writes exactly the bytes of [encode] for the same history without holding it, so an interrupted version 1
     * upload can resume from a spool. [source] must be in canonical order; the version 1 caps still apply.
     */
    fun write(source: HistorySource, output: OutputStream): Encoded {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        fun emit(text: String) {
            val data = text.toByteArray(Charsets.UTF_8)
            bytes += data.size
            require(bytes <= MAX_BYTES) { "Family history capacity reached" }
            digest.update(data)
            output.write(data)
        }
        val sections = listOf("baseline", "operations", "quarantine")
        val counts = IntArray(3)
        var kind = 0
        emit("{\"version\":1,\"baseline\":[")
        source.records().forEach { record ->
            require(record.kind >= kind) { "History records out of order" }
            while (kind < record.kind) emit("],\"${sections[++kind]}\":[")
            if (counts[kind]++ > 0) emit(",")
            require(counts[kind] <= 100_000) { "Family history capacity reached" }
            emit(when (record) {
                is HistoryRecord.Baseline -> json.encodeToString(OperationEvent.serializer(), record.event)
                is HistoryRecord.Operation -> json.encodeToString(String.serializer(), record.payload)
                is HistoryRecord.Quarantine -> json.encodeToString(QuarantinedOperation.serializer(), record.value)
            })
        }
        while (kind < 2) emit("],\"${sections[++kind]}\":[")
        emit("]}")
        return Encoded(digest.digest().joinToString("") { "%02x".format(it) }, bytes)
    }
}

fun FamilyHistory.source(): HistorySource = HistorySource {
    baseline.asSequence().map<OperationEvent, HistoryRecord> { HistoryRecord.Baseline(it) } +
        operations.asSequence().map { HistoryRecord.Operation.of(Base64.getDecoder().decode(it)) } +
        quarantine.asSequence().map { HistoryRecord.Quarantine(it) }
}

/** One entry of a family history; [kind] fixes the canonical order: baseline, then operations, then quarantine. */
sealed interface HistoryRecord {
    val kind: Int

    data class Baseline(val event: OperationEvent) : HistoryRecord {
        override val kind get() = 0
    }

    /** Operation bytes exactly as authored, so digests and identity checks never depend on re-encoding. */
    data class Operation(val payload: String) : HistoryRecord {
        init { require(Base64.getEncoder().encodeToString(bytes()) == payload) { "Operation payload is not canonical" } }
        override val kind get() = 1
        fun bytes(): ByteArray = Base64.getDecoder().decode(payload).also { require(it.size in 1..OperationCodec.MAX_BYTES) { "Invalid operation size" } }
        companion object {
            fun of(bytes: ByteArray) = Operation(Base64.getEncoder().encodeToString(bytes))
        }
    }

    data class Quarantine(val value: QuarantinedOperation) : HistoryRecord {
        init { require(Base64.getEncoder().encodeToString(value.bytes()) == value.payload) { "Quarantine payload is not canonical" } }
        override val kind get() = 2
    }
}

/** A history that can be read more than once, one record at a time. */
fun interface HistorySource {
    fun records(): Sequence<HistoryRecord>
}

data class HistorySummary(val digest: String, val bytes: Long, val baselineCount: Long, val operationCount: Long, val quarantineCount: Long)

/**
 * Version 2 history: one JSON object per line, a version header, records in canonical kind order and an end line with
 * the record counts. Lines are bounded individually; the history as a whole is not.
 */
object HistoryStream {
    const val VERSION = 2
    const val DIGEST_PREFIX = "2:"
    const val MAX_LINE_BYTES = 1024 * 1024
    private val json = Json { encodeDefaults = true; classDiscriminator = "type" }

    @Serializable private data class Header(val version: Int)
    @Serializable private sealed interface Line
    @Serializable @SerialName("baseline") private data class BaselineLine(val event: OperationEvent) : Line
    @Serializable @SerialName("operation") private data class OperationLine(val payload: String) : Line
    @Serializable @SerialName("quarantine") private data class QuarantineLine(val payload: String, val reason: String) : Line
    @Serializable @SerialName("end") private data class EndLine(val baseline: Long, val operations: Long, val quarantine: Long) : Line

    fun encodeEvent(event: OperationEvent): ByteArray = json.encodeToString(OperationEvent.serializer(), event).toByteArray(Charsets.UTF_8)
        .also { require(it.size < MAX_LINE_BYTES) { "History record too large" } }

    fun decodeEvent(bytes: ByteArray): OperationEvent = json.decodeFromString(OperationEvent.serializer(), utf8(bytes))

    fun summarize(records: Sequence<HistoryRecord>): HistorySummary = Writer(Discard).run { records.forEach(::write); finish() }

    class Writer(private val output: OutputStream) {
        private val digest = MessageDigest.getInstance("SHA-256")
        private val counts = LongArray(3)
        private var bytes = 0L
        private var kind = 0
        private var finished = false

        init { emit(json.encodeToString(Header.serializer(), Header(VERSION))) }

        fun write(record: HistoryRecord) {
            check(!finished)
            require(record.kind >= kind) { "History records out of order" }
            kind = record.kind
            emit(json.encodeToString<Line>(when (record) {
                is HistoryRecord.Baseline -> BaselineLine(record.event)
                is HistoryRecord.Operation -> OperationLine(record.payload)
                is HistoryRecord.Quarantine -> QuarantineLine(record.value.payload, record.value.reason)
            }))
            counts[kind]++
        }

        fun finish(): HistorySummary {
            check(!finished)
            emit(json.encodeToString<Line>(EndLine(counts[0], counts[1], counts[2])))
            finished = true
            return HistorySummary(DIGEST_PREFIX + digest.digest().joinToString("") { "%02x".format(it) }, bytes, counts[0], counts[1], counts[2])
        }

        private fun emit(text: String) {
            val line = (text + "\n").toByteArray(Charsets.UTF_8)
            require(line.size <= MAX_LINE_BYTES) { "History record too large" }
            digest.update(line)
            output.write(line)
            bytes += line.size
        }
    }

    /**
     * Yields records while reading; the final element is only produced after the end line matched the counts and the
     * input ended there, so a consumer must treat the history as complete only once iteration finished without error.
     */
    fun read(input: InputStream): Sequence<HistoryRecord> = sequence {
        val stream = input as? BufferedInputStream ?: BufferedInputStream(input)
        val header = readLine(stream) ?: throw IllegalArgumentException("Empty history")
        val version = json.parseToJsonElement(header).jsonObject["version"]?.jsonPrimitive?.intOrNull
        if (version != null && version > VERSION) throw UnsupportedSyncVersion()
        require(version == VERSION && json.encodeToString(Header.serializer(), Header(VERSION)) == header) { "Unsupported history" }
        val counts = LongArray(3)
        var kind = 0
        while (true) {
            val text = readLine(stream) ?: throw IllegalArgumentException("Incomplete history")
            val line = json.decodeFromString<Line>(text)
            require(json.encodeToString<Line>(line) == text) { "History is not canonical" }
            val record = when (line) {
                is EndLine -> {
                    require(line.baseline == counts[0] && line.operations == counts[1] && line.quarantine == counts[2]) { "History count mismatch" }
                    require(stream.read() == -1) { "Unexpected data after history" }
                    return@sequence
                }
                is BaselineLine -> HistoryRecord.Baseline(line.event)
                is OperationLine -> HistoryRecord.Operation(line.payload)
                is QuarantineLine -> HistoryRecord.Quarantine(QuarantinedOperation(line.payload, line.reason))
            }
            require(record.kind >= kind) { "History records out of order" }
            kind = record.kind
            counts[kind]++
            yield(record)
        }
    }

    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next == -1) {
                require(line.size() == 0) { "Incomplete history" }
                return null
            }
            if (next == '\n'.code) return utf8(line.toByteArray())
            require(line.size() < MAX_LINE_BYTES - 1) { "History record too large" }
            line.write(next)
        }
    }

    private fun utf8(bytes: ByteArray): String = bytes.toString(Charsets.UTF_8).also {
        require(it.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "Invalid UTF-8" }
    }

    private object Discard : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }
}
