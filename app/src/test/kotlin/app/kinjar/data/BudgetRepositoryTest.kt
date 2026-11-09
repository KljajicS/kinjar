package app.kinjar.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import app.kinjar.domain.Account
import app.kinjar.domain.Currency
import app.kinjar.domain.Money
import app.kinjar.domain.Transaction
import app.kinjar.domain.TransactionSource
import app.kinjar.domain.TransactionType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class BudgetRepositoryTest {
    private val db = testDb()
    private val clock = FixedClock(Instant.parse("2026-10-14T10:00:00Z"), ZoneId.of("Europe/Belgrade"))
    private val store = EventStore(db, "dev-1", clock, Projector(db))
    private val repo = BudgetRepository(db, store, clock)

    private fun tx(id: String, type: TransactionType, amount: String, account: String, currency: Currency = Currency.RSD, to: String? = null, member: String = "m") =
        Transaction(id, type, Money.of(amount, currency), account, to, null, LocalDate.of(2026, 10, 14), "", "", member, TransactionSource.MANUAL)

    @Test
    fun `setUp creates member, starter categories and accounts`() = runTest {
        repo.setUp("Siniša", listOf(Account("cash", "Cash", Currency.RSD)))
        assertNotNull(repo.currentMember())
        assertEquals(StarterCategories.all.size + StarterCategories.income.size, repo.categories.first().size)
        assertEquals("Cash", repo.accounts.first().single().name)
    }

    @Test
    fun `add shows up in recent and home`() = runTest {
        repo.setUp("S", listOf(Account("cash", "Cash", Currency.RSD)))
        val member = repo.currentMember()!!
        repo.add(tx("t1", TransactionType.INCOME, "1000", "cash", member = member.id))
        repo.add(tx("t2", TransactionType.EXPENSE, "300", "cash", member = member.id).copy(categoryId = "cat-groceries", merchant = "Maxi"))
        val recent = repo.recent(10).first()
        assertEquals("Maxi", recent.first().transaction.merchant)
        assertEquals("Groceries", recent.first().categoryName)
        assertEquals("S", recent.first().paidByName)
        val home = repo.home.first()
        assertEquals(Money.of("700", Currency.RSD), home.savingsRsd)
        assertEquals(Money.of("700", Currency.RSD), home.difference)
    }

    @Test
    fun `archiving an account with money drops it from savings and goals and keeps its history`() = runTest {
        repo.setUp("S", listOf(Account("cash", "Cash", Currency.RSD), Account("eur", "Euro", Currency.EUR)))
        repo.exchangeRates.cache(mapOf(Currency.EUR to BigDecimal("117"), Currency.USD to BigDecimal("105")), clock.today())
        repo.add(tx("t1", TransactionType.INCOME, "1000", "cash"))
        repo.add(tx("t2", TransactionType.INCOME, "10", "eur", Currency.EUR))
        repo.setGoal(app.kinjar.domain.Goal("g", "Trip", Money.of("2000", Currency.RSD)))
        assertEquals(Money.of("2170", Currency.RSD), repo.home.first().savingsRsd)
        assertEquals(Money.of("2000", Currency.RSD), repo.financial.first().goals.single().funded)

        repo.archiveAccount("eur")
        val after = repo.financial.first()
        assertEquals(Money.of("1000", Currency.RSD), after.home.savingsRsd)
        assertNull(after.home.balances["eur"])
        assertEquals(Money.of("1000", Currency.RSD), after.goals.single().funded)
        assertEquals(Money.of("2170", Currency.RSD), after.home.income)
        assertEquals(2, after.transactions.size)
        assertEquals(listOf("cash"), repo.accounts.first().map { it.id })
        assertEquals(listOf("cash", "eur"), repo.allAccounts.first().map { it.id })
    }

    @Test
    fun `missing rate leaves savings unavailable and lists the currency`() = runTest {
        repo.setUp("S", listOf(Account("cash", "Cash", Currency.RSD), Account("usd", "Dollars", Currency.USD)))
        repo.add(tx("t1", TransactionType.INCOME, "10", "usd", Currency.USD))
        val f = repo.financial.first()
        assertNull(f.home.savingsRsd)
        assertEquals(setOf(Currency.USD), f.missingCurrencies)
    }
}
