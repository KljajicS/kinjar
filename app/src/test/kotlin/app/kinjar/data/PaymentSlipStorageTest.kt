package app.kinjar.data

import app.kinjar.data.backup.BackupCodec
import app.kinjar.data.receipt.NbsIpsParser
import app.kinjar.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId

class PaymentSlipStorageTest {
    @Test fun `IPS expenses preserve payee note and separate payments through edits sync replay and backup`() = runTest {
        val db = testDb()
        val clock = FixedClock(Instant.parse("2026-11-14T10:00:00Z"), ZoneId.of("Europe/Belgrade"))
        val store = EventStore(db, "one", clock, Projector(db))
        val repo = BudgetRepository(db, store, clock, "one")
        repo.setUp("Family", listOf(Account("cash", "Cash", Currency.RSD, AccountKind.SPENDING)))
        store.activate("user", "family", "dataset", "member", 1, store.captureHistory(), store.currentDigest(), false)
        val slip = NbsIpsParser.parse("K:PR|V:01|C:1|R:200000000000000001|N:ЈП Пример|I:RSD3018,58|SF:221|S:Uplata vode|RO:00000138")
        val first = Transaction("payment-one", TransactionType.EXPENSE, slip.amount, "cash", null, null,
            clock.today(), slip.note, slip.merchant, "member", TransactionSource.MANUAL)
        repo.add(first)
        val second = first.copy(id = "payment-two", date = first.date.plusMonths(1))
        repo.add(second)
        val edited = first.copy(merchant = "Water company")
        repo.edit(edited, expected = first)
        store.rebuild()
        val expected = setOf(edited, second)
        assertEquals(expected, repo.transactions.first().toSet())
        assertNull(repo.receiptForTransaction(first.id))
        assertTrue(db.syncOperationQueries.all().executeAsList().all { OperationCodec.decode(it.payload).version == 1 })

        val history = store.captureHistory()
        val bytes = ByteArrayOutputStream()
        val password = "test-only backup password".toCharArray()
        BackupCodec.seal(store.historySource(history.id), bytes, password, clock.now())
        val peerDb = testDb()
        val peerStore = EventStore(peerDb, "peer", clock, Projector(peerDb))
        val copied = peerStore.writeHistory { emit -> BackupCodec.open(bytes.toByteArray().inputStream(), password, emit) }
        peerStore.activate("user", "family", "dataset", "member", 1, copied, peerStore.currentDigest(), false)
        peerStore.rebuild()
        val peerRepo = BudgetRepository(peerDb, peerStore, clock, "peer")
        assertEquals(expected, peerRepo.transactions.first().toSet())
        assertTrue(peerDb.syncConflictQueries.all().executeAsList().isEmpty())
        assertEquals("Uplata vode\n00 000138", peerRepo.transaction(first.id)?.note)
        store.discardHistory(history.id)
    }
}
