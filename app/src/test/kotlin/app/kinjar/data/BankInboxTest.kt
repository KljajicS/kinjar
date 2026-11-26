package app.kinjar.data

import app.kinjar.domain.Account
import app.kinjar.domain.AccountCreated
import app.kinjar.domain.BankApp
import app.kinjar.domain.Currency
import app.kinjar.domain.Member
import app.kinjar.domain.MemberCreated
import app.kinjar.domain.Money
import app.kinjar.domain.Transaction
import app.kinjar.domain.TransactionSource
import app.kinjar.domain.TransactionType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class BankInboxTest {
    @Test
    fun `capture is opt in idempotent and cleared across dataset restore`() = runTest {
        val db = testDb()
        val store = EventStore(db, "phone", FixedClock(Instant.EPOCH, ZoneOffset.UTC), Projector(db))
        val inbox = BankInbox(db, store)
        store.append(AccountCreated(Account("account", "Bank", Currency.RSD)))
        fun capture() = inbox.capture(BankApp.RAIFFEISEN, "delivery", 1L, "Bank", "Iznos: 100,00 RSD")
        capture()
        assertEquals(0, db.bankInboxQueries.pending().executeAsList().size)
        db.bankInboxQueries.bindAccount("account", BankApp.RAIFFEISEN.name)
        repeat(2) { capture() }
        assertEquals(1, db.bankInboxQueries.pending().executeAsList().size)
        assertEquals(0, db.txQueries.all().executeAsList().size)
        store.restoreLocal(store.snapshot(), store.currentDigest())
        assertEquals(0, db.bankInboxQueries.pending().executeAsList().size)
        assertEquals(0, db.bankInboxQueries.bindings().executeAsList().size)
    }

    @Test
    fun `either side of a cross currency transfer can save then link the other side`() = runTest {
        for (incomingFirst in listOf(true, false)) {
            val db = testDb()
            val clock = FixedClock(Instant.EPOCH, ZoneOffset.UTC)
            val store = EventStore(db, "phone", clock, Projector(db))
            val inbox = BankInbox(db, store)
            val repo = BudgetRepository(db, store, clock, "phone")
            store.append(MemberCreated(Member("member", "Member", "phone")))
            store.append(AccountCreated(Account("eur", "Yettel", Currency.EUR)))
            store.append(AccountCreated(Account("rsd", "Raiffeisen", Currency.RSD)))
            inbox.bind("eur", BankApp.YETTEL)
            inbox.bind("rsd", BankApp.RAIFFEISEN)
            inbox.capture(BankApp.YETTEL, "first", 1L, "Bank", "Iznos: 100,00 EUR")
            inbox.capture(BankApp.RAIFFEISEN, "second", 2L, "Bank", "Iznos: 11.700,00 RSD")
            val alerts = db.bankInboxQueries.pending().executeAsList().associateBy { it.bank }
            val outgoing = alerts.getValue(BankApp.YETTEL.name).id
            val incoming = alerts.getValue(BankApp.RAIFFEISEN.name).id
            val send = Money.of("100", Currency.EUR)
            val receive = Money.of("11700", Currency.RSD)
            val t = Transaction("transfer", TransactionType.TRANSFER, send, "eur", "rsd", null,
                LocalDate.of(2026, 11, 26), "", "", "member", TransactionSource.MANUAL, receive)
            val first = if (incomingFirst) incoming else outgoing
            repo.saveBankDraft(first, t)
            val retry = runCatching { repo.saveBankDraft(first, t.copy(id = "duplicate")) }
            assertEquals(true, retry.isFailure)
            if (incomingFirst) repo.linkBankDraft(outgoing, t.id, "eur", -send)
            else repo.linkBankDraft(incoming, t.id, "rsd", receive)
            assertEquals(listOf(t.id), db.txQueries.all().executeAsList().map { it.id })
            assertEquals(0, db.bankInboxQueries.pending().executeAsList().size)
        }
    }
}
