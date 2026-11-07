package app.kinjar.domain

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class HistoryStreamTest {
    private fun operation(seq: Long) = SyncOperation(familyId = "family", datasetId = "dataset", authorUid = "a", installId = "a",
        sequence = seq, parents = emptySet(), resources = mapOf("account:a" to ResourceAccess.WRITE),
        events = listOf(OperationEvent("a-$seq", "a", seq, seq, AccountArchived("a"))))
    private val records = listOf(
        HistoryRecord.Baseline(OperationEvent("event", "old", 1, 0, AccountCreated(Account("a", "Cash", Currency.RSD)))),
        HistoryRecord.Operation.of(OperationCodec.encode(operation(1))),
        HistoryRecord.Quarantine(QuarantinedOperation.from(byteArrayOf(1, 2), "INVALID_OPERATION")),
    )

    private fun encode(list: List<HistoryRecord> = records): Pair<ByteArray, HistorySummary> {
        val out = ByteArrayOutputStream()
        val summary = HistoryStream.Writer(out).run { list.forEach(::write); finish() }
        return out.toByteArray() to summary
    }

    private fun read(bytes: ByteArray) = HistoryStream.read(bytes.inputStream()).toList()

    @Test fun `round trip keeps records and digests the exact stream bytes`() {
        val (bytes, summary) = encode()
        assertEquals(records, read(bytes))
        assertEquals(bytes.size.toLong(), summary.bytes)
        assertEquals(listOf(1L, 1L, 1L), listOf(summary.baselineCount, summary.operationCount, summary.quarantineCount))
        assertEquals(HistoryStream.DIGEST_PREFIX + HistoryCodec.digest(bytes), summary.digest)
        assertEquals(summary, HistoryStream.summarize(records.asSequence()))
    }

    @Test fun `version 1 history converts to the same records in canonical order`() {
        val legacy = FamilyHistory(baseline = listOf((records[0] as HistoryRecord.Baseline).event),
            operations = listOf((records[1] as HistoryRecord.Operation).payload), quarantine = listOf((records[2] as HistoryRecord.Quarantine).value))
        assertEquals(records, legacy.source().records().toList())
    }

    @Test fun `missing end wrong counts trailing data and truncation never complete`() {
        val (bytes, _) = encode()
        val text = bytes.toString(Charsets.UTF_8)
        val withoutEnd = text.lines().filter { it.isNotEmpty() }.dropLast(1).joinToString("\n", postfix = "\n")
        val wrongCount = text.replace("\"baseline\":1,", "\"baseline\":2,")
        assertNotEquals(text, wrongCount)
        listOf(withoutEnd.toByteArray(), wrongCount.toByteArray(), bytes + "\n".toByteArray(), bytes + bytes,
            bytes.copyOf(bytes.size - 1), bytes.copyOf(bytes.size - 20), ByteArray(0)).forEach {
            assertThrows(IllegalArgumentException::class.java) { read(it) }
        }
    }

    @Test fun `out of order records non canonical lines and newer versions are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { encode(records.reversed()) }
        val lines = encode().first.toString(Charsets.UTF_8).lines().filter { it.isNotEmpty() }
        val swapped = listOf(lines[0], lines[2], lines[1]) + lines.drop(3)
        assertThrows(IllegalArgumentException::class.java) { read(swapped.joinToString("\n", postfix = "\n").toByteArray()) }
        val spaced = lines.joinToString("\n", postfix = "\n").replace("{\"type\":\"operation\",", "{\"type\": \"operation\",")
        assertThrows(IllegalArgumentException::class.java) { read(spaced.toByteArray()) }
        val newer = lines.joinToString("\n", postfix = "\n").replaceFirst("{\"version\":2}", "{\"version\":3}")
        assertThrows(UnsupportedSyncVersion::class.java) { read(newer.toByteArray()) }
    }

    @Test fun `each line is bounded and payloads must be canonical`() {
        val huge = "{\"version\":2}\n".toByteArray() + ByteArray(HistoryStream.MAX_LINE_BYTES + 10) { 'a'.code.toByte() }
        assertThrows(IllegalArgumentException::class.java) { read(huge) }
        assertThrows(IllegalArgumentException::class.java) { HistoryRecord.Operation("AQ") }
        assertThrows(IllegalArgumentException::class.java) { HistoryRecord.Operation("") }
        assertThrows(IllegalArgumentException::class.java) { HistoryRecord.Quarantine(QuarantinedOperation("AQ", "INVALID_OPERATION")) }
    }

    @Test fun `record count is not capped at the version 1 limit`() {
        val count = 150_001
        val record = HistoryRecord.Quarantine(QuarantinedOperation.from(byteArrayOf(1), "INVALID_OPERATION"))
        val out = ByteArrayOutputStream()
        val summary = HistoryStream.Writer(out).run { repeat(count) { write(record) }; finish() }
        assertEquals(count.toLong(), summary.quarantineCount)
        assertEquals(count, HistoryStream.read(out.toByteArray().inputStream()).count())
    }
}
