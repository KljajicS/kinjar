package app.kinjar.data

import app.kinjar.domain.Account
import app.kinjar.domain.AccountCreated
import app.kinjar.domain.BankApp
import app.kinjar.domain.Currency
import java.time.Instant
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
}
