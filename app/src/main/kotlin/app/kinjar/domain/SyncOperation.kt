package app.kinjar.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.Instant

@Serializable
data class OperationEvent(
    val id: String,
    val deviceId: String,
    val seq: Long,
    val occurredAt: Long,
    val event: Event,
) {
    fun record() = EventRecord(id, deviceId, seq, Instant.ofEpochMilli(occurredAt), event)

    companion object {
        fun from(record: EventRecord) = OperationEvent(record.id, record.deviceId, record.seq, record.occurredAt.toEpochMilli(), record.event)
    }
}

@Serializable
enum class ResourceAccess { READ, WRITE }

@Serializable
data class SyncOperation(
    val version: Int = 1,
    val familyId: String,
    val datasetId: String,
    val authorUid: String,
    val installId: String,
    val sequence: Long,
    val parents: Set<String>,
    val resources: Map<String, ResourceAccess>,
    val events: List<OperationEvent>,
    val rejected: Set<String> = emptySet(),
) {
    val id: String get() = "$installId:$sequence"

    init {
        require(version == 1 && sequence > 0)
        require(listOf(familyId, datasetId, authorUid, installId).all { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) })
        require(parents.size <= 1_000 && resources.size <= 1_000 && rejected.size <= 1_000)
        require(parents.all(::validOperationId) && rejected.all(::validOperationId))
        require(id !in parents && id !in rejected)
        require(resources.keys.all { it.length in 1..256 })
        require(events.size <= 1_000 && (events.isNotEmpty() || rejected.isNotEmpty()))
        require(events.map { it.id }.distinct().size == events.size)
        require(events.map { it.deviceId to it.seq }.distinct().size == events.size)
        require(events.all { it.id.length in 1..128 && it.deviceId == installId && it.seq > 0 })
        require(events.zipWithNext().all { (a, b) -> b.seq == a.seq + 1 })
    }
}

private fun validOperationId(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_-]{1,128}:[1-9][0-9]{0,18}"))

class UnsupportedSyncVersion : IllegalArgumentException("Unsupported sync version")

object OperationCodec {
    const val MAX_BYTES = 512 * 1024
    private val json = Json { encodeDefaults = true; classDiscriminator = "type" }

    fun encode(operation: SyncOperation): ByteArray = json.encodeToString(operation).toByteArray(Charsets.UTF_8).also {
        require(it.size <= MAX_BYTES) { "Sync operation too large" }
    }

    fun decode(bytes: ByteArray): SyncOperation {
        require(bytes.size in 1..MAX_BYTES)
        val text = bytes.toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "Invalid UTF-8" }
        val root = json.parseToJsonElement(text).jsonObject
        if (root["version"]?.jsonPrimitive?.intOrNull != 1) throw UnsupportedSyncVersion()
        return json.decodeFromString<SyncOperation>(text)
    }
}
