package app.kinjar.domain

import org.junit.Assert.*
import org.junit.Test

class ConflictResolverTest {
    private fun op(device: String, seq: Long = 1, parents: Set<String> = emptySet(),
                   resources: Map<String, ResourceAccess> = mapOf("transaction:t" to ResourceAccess.WRITE),
                   rejected: Set<String> = emptySet()): SyncOperation = SyncOperation(
        familyId = "family", datasetId = "dataset", authorUid = device, installId = device,
        sequence = seq, parents = parents, resources = resources,
        events = if (rejected.isNotEmpty()) emptyList() else listOf(OperationEvent("$device-$seq", device, seq, seq, TransactionDeleted("t"))),
        rejected = rejected,
    )

    @Test fun `concurrent financial edits hold both commands in every delivery order`() {
        val a = op("a")
        val b = op("b")
        val independent = op("c", resources = mapOf("transaction:other" to ResourceAccess.WRITE))
        val dependent = op("a", 2, setOf(a.id))
        val expected = ConflictResolver.replay(listOf(a, b, independent, dependent))
        assertEquals(setOf(a.id, b.id), expected.conflicts)
        assertEquals(setOf(dependent.id), expected.waiting)
        assertEquals(listOf(independent), expected.applicable)
        repeat(20) { assertEquals(expected, ConflictResolver.replay(listOf(a, b, independent, dependent).shuffled(java.util.Random(it.toLong())))) }
    }

    @Test fun `independent transactions may read the same account`() {
        val a = op("a", resources = mapOf("transaction:a" to ResourceAccess.WRITE, "account:cash" to ResourceAccess.READ))
        val b = op("b", resources = mapOf("transaction:b" to ResourceAccess.WRITE, "account:cash" to ResourceAccess.READ))
        assertEquals(2, ConflictResolver.replay(listOf(a, b)).applicable.size)
        val changedCurrency = op("c", resources = mapOf("account:cash" to ResourceAccess.WRITE))
        assertEquals(setOf(a.id, b.id, changedCurrency.id), ConflictResolver.replay(listOf(a, b, changedCurrency)).conflicts)
    }

    @Test fun `resolution selects one whole operation without allowing stale descendants`() {
        val a = op("a")
        val b = op("b")
        val resolve = op("a", 2, setOf(a.id, b.id), rejected = setOf(b.id))
        val result = ConflictResolver.replay(listOf(a, b, resolve))
        assertTrue(result.conflicts.isEmpty())
        assertEquals(listOf(a, resolve), result.applicable)
        assertEquals(setOf(b.id), result.rejected)
    }

    @Test fun `missing parent waits and sequential edit does not conflict`() {
        val a = op("a")
        val next = op("a", 2, setOf(a.id))
        assertEquals(setOf(next.id), ConflictResolver.replay(listOf(next)).waiting)
        assertEquals(listOf(a, next), ConflictResolver.replay(listOf(next, a)).applicable)
    }

    @Test(timeout = 15_000) fun `compact missing parents stay waiting without building a missing node graph`() {
        val parents = (1..100_000).mapTo(mutableSetOf()) { "missing:$it" }
        val compact = OperationCodec.decode(OperationCodec.encode(op("offline", parents = parents)))
        val operations = (1L..20L).map { compact.copy(sequence = it) }
        val replay = ConflictResolver.replay(operations)
        assertEquals(operations.map { it.id }.toSet(), replay.waiting)
        assertTrue(replay.applicable.isEmpty())
        assertEquals(operations.map { it.id }.toSet(), ConflictResolver.parentsFor(operations, mapOf("transaction:t" to ResourceAccess.WRITE)))
    }

    @Test fun `concurrent choices must be resolved again`() {
        val a = op("a")
        val b = op("b")
        val r1 = op("a", 2, setOf(a.id, b.id), rejected = setOf(b.id))
        val r2 = op("b", 2, setOf(a.id, b.id), rejected = setOf(a.id))
        val result = ConflictResolver.replay(listOf(a, b, r1, r2))
        assertTrue(result.conflicts.containsAll(listOf(a.id, b.id, r1.id, r2.id)))
        assertTrue(result.applicable.isEmpty())
    }

    @Test fun `unknown version is upgrade required and raw operation round trips`() {
        val original = op("a")
        val bytes = OperationCodec.encode(original)
        assertEquals(original, OperationCodec.decode(bytes))
        assertThrows(UnsupportedSyncVersion::class.java) { OperationCodec.decode(bytes.toString(Charsets.UTF_8).replace("\"version\":1", "\"version\":6").toByteArray()) }
    }

    @Test fun `invalid resolution cannot reject unobserved commands or block independent work`() {
        val valid = op("a")
        val invalid = op("b", rejected = setOf(valid.id))
        val child = op("b", 2, parents = setOf(invalid.id), resources = mapOf("transaction:other" to ResourceAccess.WRITE))
        val result = ConflictResolver.replay(listOf(valid, invalid, child))
        assertEquals(listOf(valid), result.applicable)
        assertEquals(setOf(invalid.id), result.invalid)
        assertEquals(setOf(invalid.id), result.conflicts)
        assertEquals(setOf(child.id), result.waiting)
        assertTrue(result.rejected.isEmpty())
        val discard = op("c", parents = setOf(invalid.id, child.id), rejected = setOf(invalid.id, child.id))
        assertTrue(ConflictResolver.replay(listOf(valid, invalid, child, discard)).conflicts.isEmpty())
    }

    @Test fun `missing zero and negative versions are invalid rather than upgrade required`() {
        listOf("{}", "{\"version\":0}", "{\"version\":-1}").forEach { text ->
            val error = assertThrows(IllegalArgumentException::class.java) { OperationCodec.decode(text.toByteArray()) }
            assertFalse(error is UnsupportedSyncVersion)
        }
    }

    @Test fun `twenty thousand transactions sharing an account replay with account edits and late offline conflicts`() {
        val transactions = (1L..20_000L).map { seq -> op("phone", seq,
            resources = mapOf("transaction:$seq" to ResourceAccess.WRITE, "account:cash" to ResourceAccess.READ)) }
        val accountEdit = op("other", parents = transactions.mapTo(mutableSetOf()) { it.id },
            resources = mapOf("account:cash" to ResourceAccess.WRITE))
        val start = System.nanoTime()
        val result = ConflictResolver.replay(transactions + accountEdit)
        println("20,000 transactions and account edit replay: ${(System.nanoTime() - start) / 1_000_000} ms")
        assertEquals(20_001, result.applicable.size)
        assertTrue(result.conflicts.isEmpty())
        assertTrue(result.waiting.isEmpty())
        val offline = op("offline", resources = mapOf("transaction:offline" to ResourceAccess.WRITE, "account:cash" to ResourceAccess.READ))
        val withOffline = ConflictResolver.replay(transactions + accountEdit + offline)
        assertEquals(setOf(accountEdit.id, offline.id), withOffline.conflicts)
        assertEquals(20_000, withOffline.applicable.size)
    }
}
