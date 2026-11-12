package app.kinjar.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class OperationCodecTest {
    private fun op(parents: Set<String> = emptySet(), rejected: Set<String> = emptySet()) = SyncOperation(
        familyId = "family", datasetId = "dataset", authorUid = "a", installId = "a", sequence = 1, parents = parents,
        resources = mapOf("account:a" to ResourceAccess.WRITE),
        events = if (rejected.isNotEmpty()) emptyList() else listOf(OperationEvent("a-1", "a", 1, 1, AccountArchived("a"))),
        rejected = rejected,
    )
    private fun ids(count: Int, install: String = "peer") = (1..count).mapTo(mutableSetOf()) { "$install:$it" }
    private fun versionOf(bytes: ByteArray) = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject.getValue("version").jsonPrimitive.int
    private fun ByteArray.withVersion(from: Int, to: Int) = toString(Charsets.UTF_8).replace("\"version\":$from", "\"version\":$to").toByteArray()
    private fun assertRoundTrip(operation: SyncOperation): ByteArray = OperationCodec.encode(operation).also { bytes ->
        assertEquals(operation, OperationCodec.decode(bytes))
        assertArrayEquals(bytes, OperationCodec.encode(OperationCodec.decode(bytes)))
    }

    private val wireParents = """"parents":[{"install":"peer","ranges":[1,1001]}]"""
    private val wire = String(OperationCodec.encode(op(parents = ids(1_001))))
    private fun wire(parents: String = """[{"install":"peer","ranges":[1,1001]}]""", rejected: String = "[]"): ByteArray {
        assertTrue(wire.contains(wireParents) && wire.contains("\"rejected\":[]"))
        return wire.replace(wireParents, "\"parents\":$parents").replace("\"rejected\":[]", "\"rejected\":$rejected").toByteArray()
    }

    @Test fun `version 1 bytes are unchanged`() {
        val v1 = """{"version":1,"familyId":"family","datasetId":"dataset","authorUid":"a","installId":"a","sequence":1,"parents":["peer:1"],""" +
            """"resources":{"account:a":"WRITE"},"events":[{"id":"a-1","deviceId":"a","seq":1,"occurredAt":1,"event":{"type":"AccountArchived","accountId":"a"}}],"rejected":[]}"""
        assertEquals(v1, String(OperationCodec.encode(op(parents = setOf("peer:1")))))
        assertEquals(op(parents = setOf("peer:1")), OperationCodec.decode(v1.toByteArray()))
    }

    @Test fun `bounded references stay version 1 and raw bytes round trip`() {
        val bytes = assertRoundTrip(op(parents = ids(1_000), rejected = ids(1_000, "gone")))
        assertEquals(1, versionOf(bytes))
        assertTrue(String(bytes).contains("\"peer:1000\""))
    }

    @Test fun `more than 1000 parents or rejections announce version 2 and round trip`() {
        listOf(op(parents = ids(1_001)), op(parents = ids(1_001), rejected = ids(1)), op(parents = ids(1), rejected = ids(1_001, "gone")))
            .forEach { operation ->
                assertEquals(2, operation.version)
                assertEquals(2, versionOf(assertRoundTrip(operation)))
            }
        assertTrue(wire.contains(wireParents))
        val mixed = String(OperationCodec.encode(op(parents = ids(1), rejected = ids(1_001, "gone"))))
        assertTrue(mixed.contains(""""parents":[{"install":"peer","ranges":[1,1]}]"""))
        assertTrue(mixed.contains(""""rejected":[{"install":"gone","ranges":[1,1001]}]"""))
    }

    @Test fun `twenty thousand parents from each of two phones fit one account edit`() {
        val phones = listOf("0f8fad5b-d9cb-469f-a165-70867728950e", "7c9e6679-7425-40de-944b-e07fc1f90ae7")
        val parents = phones.flatMapTo(mutableSetOf()) { ids(20_000, it) }
        assertTrue(parents.sumOf { it.length + 3 } > OperationCodec.MAX_BYTES)
        val bytes = assertRoundTrip(op(parents = parents))
        assertTrue(bytes.size < 1_024)
    }

    @Test fun `sparse references stay exact`() {
        val parents = (1..3_001 step 2).mapTo(mutableSetOf()) { "peer:$it" } + setOf("other:7", "other:8", "other:9", "other:40", "edge:${Long.MAX_VALUE}")
        val rejected = (1..2_001 step 3).mapTo(mutableSetOf()) { "gone:$it" } + "gone:2"
        val text = String(assertRoundTrip(op(parents = parents, rejected = rejected)))
        assertTrue(text.contains("""{"install":"edge","ranges":[${Long.MAX_VALUE},${Long.MAX_VALUE}]},{"install":"other","ranges":[7,9,40,40]},{"install":"peer","ranges":[1,1,3,3,"""))
        assertTrue(text.contains(""""rejected":[{"install":"gone","ranges":[1,2,4,4,7,7,"""))
    }

    @Test fun `twenty thousand isolated parents from one phone still fit`() {
        val phone = "0f8fad5b-d9cb-469f-a165-70867728950e"
        val bytes = assertRoundTrip(op(parents = (100_002..140_000 step 2).mapTo(mutableSetOf()) { "$phone:$it" }))
        assertTrue(bytes.size < OperationCodec.MAX_BYTES)
    }

    @Test fun `references are bounded by the family history size`() {
        assertEquals(2, versionOf(assertRoundTrip(op(parents = ids(100_000)))))
        assertThrows(IllegalArgumentException::class.java) { op(parents = ids(100_001)) }
        assertThrows(IllegalArgumentException::class.java) { op(parents = ids(1), rejected = ids(100_001, "gone")) }
        assertEquals(100_000, OperationCodec.decode(wire("""[{"install":"peer","ranges":[1,100000]}]""")).parents.size)
    }

    @Test fun `decoded ranges support exact set lookup without materializing references`() {
        val parents = ids(1_001) + setOf("other:7", "other:9", "edge:${Long.MAX_VALUE}")
        val decoded = OperationCodec.decode(OperationCodec.encode(op(parents = parents))).parents
        assertEquals(parents, decoded)
        assertEquals(parents.size, decoded.size)
        assertEquals(parents, decoded.toList().toSet())
        for (absent in listOf("peer:0", "peer:1002", "peer:01", "peer:+1", "other:8", "missing:1", "", "peer:9999999999999999999")) {
            assertFalse(absent, absent in decoded)
        }
    }

    @Test fun `sequence that cannot be a real operation does not encode as ranges`() {
        assertThrows(IllegalArgumentException::class.java) { OperationCodec.encode(op(parents = ids(1_000) + "peer:9999999999999999999")) }
    }

    @Test fun `reference budget covers parents and rejections together before references are built`() {
        val compact = wire(rejected = """[{"install":"gone","ranges":[1,5]}]""")
        val decoded = OperationCodec.decode(compact, 1_006)
        assertEquals(1_001 to 5, decoded.parents.size to decoded.rejected.size)
        assertArrayEquals(compact, OperationCodec.encode(decoded))
        val explicit = OperationCodec.encode(op(parents = ids(3), rejected = ids(2, "gone")))
        assertEquals(1, versionOf(explicit))
        assertEquals(op(parents = ids(3), rejected = ids(2, "gone")), OperationCodec.decode(explicit, 5))
        val hostile = wire(parents = """[{"install":"peer","ranges":[1,100000]}]""", rejected = """[{"install":"gone","ranges":[1,100000]}]""")
        listOf(compact to 0L, compact to 1_000L, compact to 1_005L, explicit to 4L, hostile to 0L, hostile to 199_999L).forEach { (bytes, budget) ->
            val error = assertThrows("$budget", IllegalArgumentException::class.java) { OperationCodec.decode(bytes, budget) }
            assertFalse(error is UnsupportedSyncVersion)
        }
        assertEquals(200_000, OperationCodec.decode(hostile, 200_000).let { it.parents.size + it.rejected.size })
    }

    @Test(timeout = 10_000) fun `malformed or hostile ranges fail without expanding`() {
        val max = Long.MAX_VALUE
        val parents = listOf(
            """[]""",
            """["peer:1"]""",
            """[{"install":"peer","ranges":[1,500]},{"install":"peer","ranges":[502,1001]}]""",
            """[{"install":"zz","ranges":[1,1001]},{"install":"aa","ranges":[1,5]}]""",
            """[{"install":"peer","ranges":[]}]""",
            """[{"install":"peer","ranges":[1,1001,1003]}]""",
            """[{"install":"peer","ranges":[0,1001]}]""",
            """[{"install":"peer","ranges":[-5,1001]}]""",
            """[{"install":"peer","ranges":[1001,1]}]""",
            """[{"install":"peer","ranges":[1,600,500,1001]}]""",
            """[{"install":"peer","ranges":[1,600,601,1001]}]""",
            """[{"install":"peer","ranges":[700,1001,1,600]}]""",
            """[{"install":"peer","ranges":[1.5,1001]}]""",
            """[{"install":"peer","ranges":[1,$max]}]""",
            """[{"install":"peer","ranges":[1,9223372036854775808]}]""",
            """[{"install":"a","ranges":[1,60000]},{"install":"b","ranges":[1,60000]}]""",
            """[{"install":"peer","ranges":[1,100001]}]""",
            """[{"install":"pe:er","ranges":[1,1001]}]""",
            """[{"install":"","ranges":[1,1001]}]""",
            """[{"install":"${"x".repeat(100_000)}","ranges":[1,100000]}]""",
            """[{"install":"a","ranges":[1,1001]}]""",
            """[{"install":"peer","ranges":[1,1001],"extra":1}]""",
        ).map { wire(parents = it) }
        val rejected = listOf(
            """[{"install":"gone","ranges":[1,$max]}]""",
            """[{"install":"gone","ranges":[$max,$max,1,1]}]""",
        ).map { wire(rejected = it) }
        (parents + rejected + wire.replace(",\"rejected\":[]", "").toByteArray()).forEach { bytes ->
            val error = assertThrows(IllegalArgumentException::class.java) { OperationCodec.decode(bytes) }
            assertFalse(String(bytes), error is UnsupportedSyncVersion)
        }
    }

    @Test fun `declared version must match the references it carries`() {
        assertThrows(IllegalArgumentException::class.java) { op(parents = ids(1_001)).copy(version = 1) }
        assertThrows(IllegalArgumentException::class.java) { op().copy(version = 2) }
        listOf(OperationCodec.encode(op(parents = ids(1_001))).withVersion(2, 1), OperationCodec.encode(op()).withVersion(1, 2)).forEach {
            val error = assertThrows(IllegalArgumentException::class.java) { OperationCodec.decode(it) }
            assertFalse(error is UnsupportedSyncVersion)
        }
    }

    @Test fun `future versions require an update`() {
        assertThrows(UnsupportedSyncVersion::class.java) { OperationCodec.decode(OperationCodec.encode(op()).withVersion(1, 6)) }
        assertThrows(UnsupportedSyncVersion::class.java) { OperationCodec.decode(OperationCodec.encode(op(parents = ids(1_001))).withVersion(2, 6)) }
    }

    @Test fun `version 2 operations still respect the payload limit`() {
        val installs = (1..4_000).map { "x".repeat(124) + it.toString().padStart(4, '0') }
        val huge = op(parents = installs.mapTo(mutableSetOf()) { "$it:1" })
        assertEquals(2, huge.version)
        assertThrows(IllegalArgumentException::class.java) { OperationCodec.encode(huge) }
        val groups = installs.joinToString(",") { """{"install":"$it","ranges":[1,1]}""" }
        val bytes = wire(parents = "[$groups]")
        assertTrue(bytes.size > OperationCodec.MAX_BYTES)
        assertThrows(IllegalArgumentException::class.java) { OperationCodec.decode(bytes) }
    }
}
