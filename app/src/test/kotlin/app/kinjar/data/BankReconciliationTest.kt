package app.kinjar.data

import app.kinjar.domain.Account
import app.kinjar.domain.BankApp
import app.kinjar.domain.BankBalanceAction
import app.kinjar.domain.CategoryKind
import app.kinjar.domain.Currency
import app.kinjar.domain.Money
import app.kinjar.domain.Transaction
import app.kinjar.domain.TransactionSource
import app.kinjar.domain.TransactionType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BankReconciliationTest {
    private class Ledger {
        val day: LocalDate = LocalDate.of(2026, 11, 26)
        val db = testDb()
        val clock = FixedClock(Instant.parse("2026-11-27T08:00:00Z"), ZoneOffset.UTC)
        val store = EventStore(db, "phone", clock, Projector(db))
        val repo = BudgetRepository(db, store, clock, "phone")
        val inbox = BankInbox(db, store)
        lateinit var member: String

        suspend fun start() {
            repo.setUp("Member", listOf(Account("card", "Raiffeisen", Currency.RSD), Account("cash", "Cash", Currency.RSD)))
            member = requireNotNull(repo.currentMember()).id
            repo.addCategory("Food", "#888888")
            inbox.bind("card", BankApp.RAIFFEISEN)
            repo.add(Transaction("opening", TransactionType.INCOME, Money.of("1500", Currency.RSD), "card", null,
                null, day.minusDays(1), "", "", member, TransactionSource.MANUAL))
        }

        fun capture(key: String, time: String, amount: String, balance: String): String {
            inbox.capture(BankApp.RAIFFEISEN, key, 1, "Bank", """
                Koriscenje kartice 0000**0000
                Datum: 26.11.2026 $time
                Iznos: $amount RSD
                Mesto: SHOP
                Stanje: $balance
            """.trimIndent())
            return db.bankInboxQueries.pending().executeAsList().single { time in it.body }.id
        }

        suspend fun expense(id: String, amount: String): Transaction =
            Transaction(id, TransactionType.EXPENSE, Money.of(amount, Currency.RSD), "card", null,
                repo.categories.first().first { it.kind == CategoryKind.EXPENSE }.id, day, "", "", member, TransactionSource.MANUAL)

        suspend fun balance(id: String = "card"): Money = repo.financial.first().home.balances.getValue(id)
    }

    @Test
    fun `confirmed checkpoints produce the same balance in either review order and survive restore`() = runTest {
        for (newerFirst in listOf(true, false)) {
            val l = Ledger().apply { start() }
            val older = l.capture("older", "12:00:00", "100,00", "900,00")
            val newer = l.capture("newer", "13:00:00", "200,00", "700,00")
            if (newerFirst) {
                l.repo.saveBankDraft(newer, l.expense("newer", "200"), "card", BankBalanceAction.VERIFY)
                assertTrue(runCatching {
                    l.repo.saveBankDraft(older, l.expense("older", "100"), "card", BankBalanceAction.VERIFY)
                }.isFailure)
                l.repo.saveBankDraft(older, l.expense("older", "100"), "card", BankBalanceAction.ALREADY_INCLUDED)
            } else {
                l.repo.saveBankDraft(older, l.expense("older", "100"), "card", BankBalanceAction.VERIFY)
                l.repo.saveBankDraft(newer, l.expense("newer", "200"), "card", BankBalanceAction.VERIFY)
            }
            assertEquals(Money.of("700", Currency.RSD), l.balance())
            assertEquals(setOf("opening", "older", "newer"), l.repo.transactions.first().map { it.id }.toSet())
            l.store.restoreLocal(l.store.snapshot(), l.store.currentDigest())
            assertEquals(Money.of("700", Currency.RSD), l.balance())
            l.repo.add(l.expense("later", "50").copy(date = l.day.plusDays(1)))
            assertEquals(Money.of("650", Currency.RSD), l.balance())
            val olderTx = requireNotNull(l.repo.transaction("older"))
            l.repo.edit(olderTx.copy(money = Money.of("110", Currency.RSD)), olderTx)
            assertEquals(setOf("card"), l.repo.financial.first().home.bankReconciliationNeeded)
            assertEquals(Money.of("1140", Currency.RSD), l.balance())
        }
    }

    @Test
    fun `offline expense after the verified day stays writable while same-day activity needs review`() = runTest {
        for (daysAfter in listOf(0L, 1L)) checkConcurrentVerification(daysAfter)
    }

    private suspend fun checkConcurrentVerification(daysAfter: Long) {
        val l = Ledger().apply { start() }
        val peerDb = testDb()
        val peer = EventStore(peerDb, "peer", l.clock, Projector(peerDb))
        val peerRepo = BudgetRepository(peerDb, peer, l.clock, "peer")
        val baseline = l.store.snapshot()
        peer.activate("user", "family", "dataset", l.member, 1, baseline, peer.currentDigest(), false)
        l.store.activate("user", "family", "dataset", l.member, 1, baseline, l.store.currentDigest(), false)
        l.inbox.bind("card", BankApp.RAIFFEISEN)
        val alert = l.capture("newer", "13:00:00", "200,00", "700,00")
        assertEquals(Money.of("1500", Currency.RSD), l.balance())
        l.repo.saveBankDraft(alert, l.expense("bank", "200"), "card", BankBalanceAction.VERIFY)
        assertEquals(Money.of("700", Currency.RSD), l.balance())
        peerRepo.add(l.expense("offline", "50").copy(date = l.day.plusDays(daysAfter)))
        l.store.receive(peerDb.syncOperationQueries.all().executeAsList().map { it.payload }, "user", "family", "dataset", 1, 0, "")
        l.repo.add(l.expense("control", "1").copy(accountId = "cash"))
        if (daysAfter == 0L) {
            assertTrue(runCatching { l.repo.add(l.expense("later", "10")) }.exceptionOrNull() is RelatedSyncConflict)
            return
        }
        l.repo.add(l.expense("later", "10").copy(date = l.day.plusDays(1)))
        assertEquals(Money.of("640", Currency.RSD), l.balance())
    }

    @Test
    fun `old withdrawal included by the bank still credits the cash account`() = runTest {
        val l = Ledger().apply { start() }
        val withdrawal = l.capture("withdrawal", "12:00:00", "100,00", "900,00")
        val newer = l.capture("newer", "13:00:00", "200,00", "700,00")
        l.repo.saveBankDraft(newer, l.expense("newer", "200"), "card", BankBalanceAction.VERIFY)
        val transfer = Transaction("withdrawal", TransactionType.TRANSFER, Money.of("100", Currency.RSD),
            "card", "cash", null, l.day, "", "", l.member, TransactionSource.MANUAL)
        l.repo.saveBankDraft(withdrawal, transfer, "card", BankBalanceAction.ALREADY_INCLUDED)
        assertEquals(Money.of("700", Currency.RSD), l.balance())
        assertEquals(Money.of("100", Currency.RSD), l.balance("cash"))
    }
}
