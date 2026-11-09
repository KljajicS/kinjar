package app.kinjar.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import app.kinjar.domain.Account
import app.kinjar.domain.AccountKind
import app.kinjar.domain.Bill
import app.kinjar.domain.Cadence
import app.kinjar.domain.Currency
import app.kinjar.domain.Money
import app.kinjar.domain.RecurringRule
import app.kinjar.domain.Transaction
import app.kinjar.domain.TransactionSource
import app.kinjar.domain.TransactionType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class RecurringRepositoryTest {
    private val db = testDb()
    private val clock = FixedClock(Instant.parse("2026-10-14T10:00:00Z"), ZoneId.of("Europe/Belgrade"))
    private val store = EventStore(db, "dev-1", clock, Projector(db))
    private val repo = BudgetRepository(db, store, clock, "dev-1")
    private val rent = RecurringRule("rent", "Rent", Money.of("40000", Currency.RSD), "cash", "cat-bills", Cadence.MONTHLY, LocalDate.of(2026, 10, 20))

    private suspend fun seed(): String {
        repo.setUp("S", listOf(Account("cash", "Cash", Currency.RSD, AccountKind.SPENDING)))
        val m = repo.currentMember()!!.id
        repo.add(Transaction("t1", TransactionType.INCOME, Money.of("100000", Currency.RSD), "cash", null, null, LocalDate.of(2026, 10, 1), "", "", m, TransactionSource.MANUAL))
        return m
    }

    private fun pay(bill: Bill, member: String) = Transaction(
        bill.txId, TransactionType.EXPENSE, bill.rule.money, bill.rule.accountId, null, bill.rule.categoryId, clock.today(), "", bill.rule.name, member, TransactionSource.RECURRING,
    )

    @Test
    fun `rules are set, edited, archived and skipped through events`() = runTest {
        seed()
        repo.setRule(rent)
        repo.setRule(rent.copy(name = "Kirija"))
        assertEquals("Kirija", repo.rule("rent")!!.name)
        assertEquals(listOf("2026-10"), repo.upcomingBills.first().map { it.period })
        repo.skipBill("rent", "2026-10")
        assertEquals(emptyList<Bill>(), repo.upcomingBills.first())
        repo.archiveRule("rent")
        assertNull(repo.rules.first().firstOrNull { it.id == "rent" })
    }

    @Test
    fun `an unpaid bill holds nothing back, paying it moves the balance once`() = runTest {
        val m = seed()
        repo.setRule(rent)
        assertEquals(Money.of("100000", Currency.RSD), repo.home.first().savingsRsd)

        val bill = repo.upcomingBills.first().single()
        repo.add(pay(bill, m))
        repo.add(pay(bill, m))
        assertEquals(emptyList<Bill>(), repo.upcomingBills.first())
        assertEquals(Money.of("60000", Currency.RSD), repo.home.first().savingsRsd)
        assertEquals(Money.of("40000", Currency.RSD), repo.home.first().spent)
    }

    @Test
    fun `stale bill payment rejects changed contents without changing history or balance`() = runTest {
        val member = seed()
        repo.setRule(rent)
        val payment = pay(repo.upcomingBills.first().single(), member)
        repo.add(payment)
        val history = store.snapshot()

        val failure = runCatching { repo.add(payment.copy(money = Money.of("45000", Currency.RSD))) }.exceptionOrNull()

        assertTrue(failure is StaleTransaction)
        assertEquals(payment, repo.transaction(payment.id))
        assertEquals(history, store.snapshot())
        assertEquals(Money.of("60000", Currency.RSD), repo.home.first().savingsRsd)
        assertEquals(Money.of("40000", Currency.RSD), repo.home.first().spent)
    }

    @Test
    fun `archiving the account hides its bills`() = runTest {
        seed()
        repo.setRule(rent)
        repo.archiveAccount("cash")
        assertEquals(emptyList<Bill>(), repo.upcomingBills.first())
    }
}
