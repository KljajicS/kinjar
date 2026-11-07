package app.kinjar.data

import app.kinjar.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class TransactionConflictTest {
    private val db = testDb()
    private val clock = FixedClock(Instant.parse("2026-11-04T10:00:00Z"), ZoneId.of("UTC"))
    private val store = EventStore(db, "local", clock, Projector(db))
    private val conflicts = TransactionConflicts(db, store)
    private val repo = BudgetRepository(db, store, clock, "local")
    private val date = LocalDate.of(2026, 11, 4)

    private fun tx(amount: String, id: String = "t") = Transaction(id, TransactionType.EXPENSE, Money.of(amount, Currency.RSD), "a", null, null,
        date, "", "Groceries", "payer", TransactionSource.MANUAL)

    private fun edit(device: String, amount: String, seq: Long = 1, parents: Set<String> = emptySet()) = SyncOperation(
        familyId = "family", datasetId = "dataset", authorUid = "uid-$device", installId = device, sequence = seq, parents = parents,
        resources = mapOf("transaction:t" to ResourceAccess.WRITE, "account:a" to ResourceAccess.READ),
        events = listOf(OperationEvent("$device-$seq", device, seq, clock.now().toEpochMilli() + seq, TransactionEdited(tx(amount)))))

    private fun receive(vararg ops: SyncOperation) =
        store.receive(ops.map(OperationCodec::encode), "user", "family", "dataset", 100, 0, ops.last().id)

    private suspend fun enrolled(vararg extra: Event) {
        store.appendAll(listOf(MemberCreated(Member("payer", "Ana", "uid-one")), MemberCreated(Member("m2", "Marko", "uid-two")),
            AccountCreated(Account("a", "Cash", Currency.RSD)), TransactionAdded(tx("1200"))) + extra)
        store.activate("user", "family", "dataset", "member", 1, store.snapshot(), HistoryCodec.digest(HistoryCodec.encode(store.snapshot())), true)
    }

    private fun amount() = db.txQueries.all().executeAsList().single { it.id == "t" }.amount

    @Test fun `keeping one version applies it whole, keeps the payer and blocks edits until then`() = runTest {
        enrolled()
        val a = edit("one", "1400")
        val b = edit("two", "1300")
        receive(a, b)
        val review = requireNotNull(conflicts.review("t").first())
        assertEquals("1200.00", review.current?.money?.amount?.toPlainString())
        assertEquals(listOf("Ana" to "1400.00", "Marko" to "1300.00"), review.versions.map { it.author to it.transaction?.money?.amount?.toPlainString() })
        assertTrue(review.versions.all { it.keepable && !it.thisPhone })
        assertEquals(setOf("t"), conflicts.held.first().ids)
        assertTrue(runCatching { repo.edit(tx("1500")) }.exceptionOrNull() is IllegalStateException)

        conflicts.keep(b.id)
        assertEquals("1300.00", amount())
        assertEquals("payer", db.txQueries.all().executeAsList().single().paid_by)
        assertTrue(db.syncConflictQueries.all().executeAsList().isEmpty())
        assertEquals(1, db.syncOutboxQueries.pending("family", "dataset").executeAsList().size)
        assertNull(conflicts.review("t").first())
    }

    @Test fun `a later choice on the same transaction does not revive an earlier rejected version`() = runTest {
        enrolled()
        val a = edit("one", "1400")
        val b = edit("two", "1300")
        receive(a, b)
        conflicts.keep(b.id)
        val first = db.syncOperationQueries.all().executeAsList().single { it.install_id == "local" }.id
        val c = edit("one", "1500", 2, setOf(first))
        val d = edit("two", "1600", 2, setOf(first))
        receive(c, d)
        assertEquals(listOf(c.id, d.id), conflicts.review("t").first()?.versions?.map { it.operationId })
        conflicts.keep(c.id)
        assertEquals("1500.00", amount())
        assertTrue(db.syncConflictQueries.all().executeAsList().isEmpty())
    }

    @Test fun `a version includes later changes that wait on it and the earlier value can be kept`() = runTest {
        enrolled()
        val a1 = edit("one", "1400")
        val a2 = edit("one", "1450", 2, setOf(a1.id))
        val b = edit("two", "1300")
        receive(a1, a2, b)
        assertEquals(listOf("1450.00", "1300.00"), conflicts.review("t").first()?.versions?.map { it.transaction?.money?.amount?.toPlainString() })
        conflicts.keepCurrent("t")
        assertEquals("1200.00", amount())
        assertTrue(db.syncConflictQueries.all().executeAsList().isEmpty())
    }

    @Test fun `linked loan payment and expense are kept or rejected together`() = runTest {
        enrolled(LoanCreated(Loan("loan", "Loan", Money.of("100", Currency.RSD), date)))
        fun payment(device: String): SyncOperation {
            val t = tx("60", device).copy(merchant = "")
            val events = listOf(TransactionAdded(t), LoanPaymentAdded(LoanPayment(device, "loan", t.id, t.money, t.money)))
            return SyncOperation(familyId = "family", datasetId = "dataset", authorUid = "uid-$device", installId = device,
                sequence = 1, parents = emptySet(), resources = mapOf("loan:loan" to ResourceAccess.WRITE,
                    "transaction:$device" to ResourceAccess.WRITE, "account:a" to ResourceAccess.READ),
                events = events.mapIndexed { index, e -> OperationEvent("$device-$index", device, index + 1L, 1L, e) })
        }
        val first = payment("one")
        val second = payment("two")
        receive(first, second)
        assertEquals(listOf("one", "two"), conflicts.held.first().unsaved.map { it.id })
        assertEquals(listOf(true, false), conflicts.review("one").first()?.versions?.map { it.transaction != null })

        conflicts.keep(first.id)
        assertEquals(setOf("t", "one"), db.txQueries.all().executeAsList().map { it.id }.toSet())
        assertEquals(listOf("one"), db.loanPaymentQueries.all().executeAsList().map { it.id })
        assertTrue(db.syncConflictQueries.all().executeAsList().isEmpty())
    }

    @Test fun `pending setup or restore freezes conflict decisions`() = runTest {
        enrolled()
        val a = edit("one", "1400")
        val b = edit("two", "1300")
        receive(a, b)
        db.restoreJournalQueries.save("user", "family", "dataset", "next", "token", "RESTORE", byteArrayOf(1))
        val before = store.snapshot()
        assertThrows(IllegalStateException::class.java) { store.keepConflictingChange(b.id) }
        assertThrows(IllegalStateException::class.java) { store.discardConflictingChange(a.id) }
        assertEquals(before, store.snapshot())
        assertTrue(db.syncOutboxQueries.pending("family", "dataset").executeAsList().isEmpty())
    }
}
