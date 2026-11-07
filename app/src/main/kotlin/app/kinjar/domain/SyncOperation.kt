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
    val version: Int,
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
    constructor(
        familyId: String, datasetId: String, authorUid: String, installId: String, sequence: Long,
        parents: Set<String>, resources: Map<String, ResourceAccess>, events: List<OperationEvent>, rejected: Set<String> = emptySet(),
    ) : this(requiredVersion(parents, rejected, events), familyId, datasetId, authorUid, installId, sequence, parents, resources, events, rejected)

    val id: String get() = "$installId:$sequence"

    init {
        require(version == requiredVersion(parents, rejected, events) && sequence > 0)
        require(listOf(familyId, datasetId, authorUid, installId).all { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) })
        require(resources.size <= 1_000 && parents.size <= MAX_REFERENCES && rejected.size <= MAX_REFERENCES)
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

// Older readers must request an update before replaying a receipt restoration they cannot interpret.
private fun requiredVersion(parents: Set<String>, rejected: Set<String>, events: List<OperationEvent>): Int = when {
    events.any { (it.event as? TransactionAdded)?.receiptDeletionId != null } -> 3
    parents.size > 1_000 || rejected.size > 1_000 -> 2
    else -> 1
}

// Per-command validation budget, independent of the number of records in a history stream.
private const val MAX_REFERENCES = 100_000

private val OPERATION_ID = Regex("[A-Za-z0-9_-]{1,128}:[1-9][0-9]{0,18}")

private fun validOperationId(value: String): Boolean = value.matches(OPERATION_ID)

// Version 2 sends references as start,end pairs of maximal contiguous sequence runs, grouped by installation in ascending order.
@Serializable
private data class SyncOperationV2(
    val version: Int,
    val familyId: String,
    val datasetId: String,
    val authorUid: String,
    val installId: String,
    val sequence: Long,
    val parents: List<ReferenceRanges>,
    val resources: Map<String, ResourceAccess>,
    val events: List<OperationEvent>,
    val rejected: List<ReferenceRanges>,
)

@Serializable
private data class ReferenceRanges(val install: String, val ranges: List<Long>)

private fun toRanges(ids: Set<String>): List<ReferenceRanges> = if (ids is ReferenceSet) ids.groups else
    ids.groupBy({ it.substringBeforeLast(':') }) { requireNotNull(it.substringAfterLast(':').toLongOrNull()) { "Sequence out of range" } }
        .toSortedMap().map { (install, sequences) ->
            val ranges = mutableListOf<Long>()
            sequences.sorted().forEach { seq ->
                if (ranges.isNotEmpty() && ranges.last() == seq - 1) ranges[ranges.lastIndex] = seq else { ranges += seq; ranges += seq }
            }
            ReferenceRanges(install, ranges)
        }

private fun fromRanges(groups: List<ReferenceRanges>): Set<String> {
    require(groups.zipWithNext().all { (a, b) -> a.install < b.install }) { "Installations must be unique and ordered" }
    var total = 0L
    groups.forEach { group ->
        require(group.install.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid installation" }
        val ranges = group.ranges
        require(ranges.isNotEmpty() && ranges.size % 2 == 0) { "Invalid reference ranges" }
        for (i in ranges.indices step 2) {
            val start = ranges[i]
            val end = ranges[i + 1]
            require(start in 1..end && (i == 0 || start - 1 > ranges[i - 1])) { "Reference ranges must be ordered, disjoint and not adjacent" }
            require(end - start < MAX_REFERENCES - total) { "Too many references" }
            total += end - start + 1
        }
    }
    return ReferenceSet(groups, total.toInt())
}

// Keep decoded ranges compact; graph traversal can iterate IDs without retaining every expanded string.
private class ReferenceSet(val groups: List<ReferenceRanges>, override val size: Int) : AbstractSet<String>() {
    override fun iterator(): Iterator<String> = sequence {
        groups.forEach { group ->
            for (i in group.ranges.indices step 2) {
                for (seq in group.ranges[i]..group.ranges[i + 1]) yield("${group.install}:$seq")
            }
        }
    }.iterator()

    override fun contains(element: String): Boolean {
        val separator = element.lastIndexOf(':')
        if (separator < 1) return false
        val index = groups.binarySearchBy(element.substring(0, separator)) { it.install }
        if (index < 0) return false
        val suffix = element.substring(separator + 1)
        val seq = suffix.toLongOrNull() ?: return false
        if (seq.toString() != suffix) return false
        val ranges = groups[index].ranges
        var low = 0
        var high = ranges.size / 2 - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            when {
                seq < ranges[middle * 2] -> high = middle - 1
                seq > ranges[middle * 2 + 1] -> low = middle + 1
                else -> return true
            }
        }
        return false
    }
}

class UnsupportedSyncVersion : IllegalArgumentException("Unsupported sync version")

object OperationCodec {
    const val MAX_BYTES = 512 * 1024
    private val json = Json { encodeDefaults = true; classDiscriminator = "type" }

    fun encode(operation: SyncOperation): ByteArray = when (operation.version) {
        1 -> json.encodeToString(operation)
        else -> with(operation) {
            json.encodeToString(SyncOperationV2(version, familyId, datasetId, authorUid, installId, sequence, toRanges(parents), resources, events, toRanges(rejected)))
        }
    }.toByteArray(Charsets.UTF_8).also {
        require(it.size <= MAX_BYTES) { "Sync operation too large" }
    }

    fun decode(bytes: ByteArray): SyncOperation {
        require(bytes.size in 1..MAX_BYTES)
        val text = bytes.toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "Invalid UTF-8" }
        val root = json.parseToJsonElement(text).jsonObject
        val version = root["version"]?.jsonPrimitive?.intOrNull
        require(version != null && version > 0) { "Invalid sync version" }
        if (version > 3) throw UnsupportedSyncVersion()
        if (version == 1) return json.decodeFromString<SyncOperation>(text)
        val wire = json.decodeFromString<SyncOperationV2>(text)
        return SyncOperation(wire.version, wire.familyId, wire.datasetId, wire.authorUid, wire.installId, wire.sequence,
            fromRanges(wire.parents), wire.resources, wire.events, fromRanges(wire.rejected))
    }
}
