package app.kinjar.data

import app.kinjar.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64

class LargeHistoryReplayTest {
    @Test fun `account rename between ten thousand transactions on each side rebuilds without ancestor expansion`() {
        val db = testDb()
        val store = EventStore(db, "local", FixedClock(Instant.EPOCH, ZoneId.of("UTC")), Projector(db))
        val account = Account("cash", "Cash", Currency.RSD)
        val baseline = listOf(
            OperationEvent("member", "setup", 1, 1, MemberCreated(Member("member", "Test", "user"))),
            OperationEvent("account", "setup", 2, 2, AccountCreated(account)),
        )
        fun transaction(seq: Long, parents: Set<String>): SyncOperation {
            val tx = Transaction("tx-$seq", TransactionType.EXPENSE, Money.of("1", Currency.RSD), "cash", null, null,
                LocalDate.of(2026, 11, 7), "", "", "member", TransactionSource.MANUAL)
            return SyncOperation(familyId = "family", datasetId = "dataset", authorUid = "user", installId = "peer", sequence = seq,
                parents = parents, resources = mapOf("transaction:${tx.id}" to ResourceAccess.WRITE, "account:cash" to ResourceAccess.READ),
                events = listOf(OperationEvent("event-$seq", "peer", seq, seq + 2, TransactionAdded(tx))))
        }
        val before = (1L..10_000L).map { transaction(it, emptySet()) }
        val rename = SyncOperation(familyId = "family", datasetId = "dataset", authorUid = "user", installId = "peer", sequence = 10_001,
            parents = before.mapTo(mutableSetOf()) { it.id }, resources = mapOf("account:cash" to ResourceAccess.WRITE),
            events = listOf(OperationEvent("rename", "peer", 10_001, 10_003, AccountUpdated(account.copy(name = "Renamed")))))
        val after = (10_002L..20_001L).map { transaction(it, setOf(rename.id)) }
        val history = FamilyHistory(baseline = baseline,
            operations = (before + rename + after).map { Base64.getEncoder().encodeToString(OperationCodec.encode(it)) })

        val start = System.nanoTime()
        store.activate("user", "family", "dataset", "member", 1, history, HistoryCodec.digest(HistoryCodec.encode(store.snapshot())), false)
        println("20,000 transactions with intermediate account rename, complete database rebuild: ${(System.nanoTime() - start) / 1_000_000} ms")
        assertEquals(20_000, db.txQueries.all().executeAsList().size)
        assertEquals("Renamed", db.accountQueries.active().executeAsList().single().name)
        assertTrue(db.syncConflictQueries.all().executeAsList().isEmpty())
        assertEquals(history, store.snapshot())
    }
}
