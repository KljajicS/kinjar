package app.kinjar.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import app.kinjar.domain.Account
import app.kinjar.domain.Currency
import app.kinjar.domain.Goal
import app.kinjar.domain.Money
import app.kinjar.domain.Transaction
import app.kinjar.domain.TransactionSource
import app.kinjar.domain.TransactionType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class GoalRepositoryTest {
    private val db = testDb()
    private val clock = FixedClock(Instant.parse("2026-10-14T10:00:00Z"), ZoneId.of("Europe/Belgrade"))
    private val store = EventStore(db, "dev-1", clock, Projector(db))
    private val repo = BudgetRepository(db, store, clock, "dev-1")
    private fun rsd(s: String) = Money.of(s, Currency.RSD)

    private suspend fun seed(amount: String = "150000") {
        repo.setUp("S", listOf(Account("cash", "Cash", Currency.RSD)))
        val m = repo.currentMember()!!.id
        repo.add(Transaction("t1", TransactionType.INCOME, rsd(amount), "cash", null, null, LocalDate.of(2026, 10, 1), "", "", m, TransactionSource.MANUAL))
    }

    @Test
    fun `excluding an account changes goals only and survives replay`() = runTest {
        seed("1000")
        repo.setGoal(Goal("goal", "Goal", rsd("2000")))
        val account = repo.accounts.first().single()
        repo.updateAccount(account.copy(excludedFromGoals = true))
        store.rebuild()
        assertTrue(repo.accounts.first().single().excludedFromGoals)
        val excluded = repo.financial.first()
        assertEquals(rsd("1000"), excluded.home.savingsRsd)
        assertEquals(rsd("1000"), excluded.home.income)
        assertEquals(rsd("1000"), excluded.home.balances[account.id])
        assertEquals(rsd("0"), excluded.goals.single().funded)
        repo.updateAccount(account)
        assertEquals(rsd("1000"), repo.financial.first().goals.single().funded)
    }

    @Test
    fun `goals are set, edited and archived through events`() = runTest {
        seed()
        val fund = Goal("fund", "Emergency fund", rsd("100000"))
        repo.setGoal(fund)
        repo.setGoal(fund.copy(name = "Rezerva"))
        assertEquals("Rezerva", repo.goal("fund")!!.name)
        repo.archiveGoal("fund")
        assertEquals(0, repo.goals.first().size)
        repo.setGoal(fund.copy(name = "Back"))
        assertNull(repo.goals.first().firstOrNull { it.id == "fund" })
    }

    @Test
    fun `new goals append, priority order drives funding and survives a rebuild`() = runTest {
        seed()
        repo.setGoal(Goal("z", "Zoo", rsd("100000")))
        repo.setGoal(Goal("a", "Apartment", rsd("100000")))
        assertEquals(listOf("z", "a"), repo.goals.first().map { it.id })
        assertEquals(listOf(rsd("100000"), rsd("50000")), repo.financial.first().goals.map { it.funded })

        repo.reorderGoals(listOf("a", "z"))
        assertEquals(listOf(rsd("100000"), rsd("50000")), repo.financial.first().goals.map { it.funded })
        assertEquals(listOf("a", "z"), repo.financial.first().goals.map { it.goal.id })

        repo.setGoal(Goal("b", "Bike", rsd("1000")))
        assertEquals(listOf("a", "z", "b"), repo.goals.first().map { it.id })
        assertTrue(repo.financial.first().goals.last().waiting)

        repo.archiveGoal("a")
        assertEquals(listOf(rsd("100000"), rsd("1000")), repo.financial.first().goals.map { it.funded })

        store.rebuild()
        assertEquals(listOf("z", "b"), repo.goals.first().map { it.id })
    }

    @Test
    fun `spending savings lowers coverage from the bottom`() = runTest {
        seed("100000")
        repo.setGoal(Goal("a", "A", rsd("60000")))
        repo.setGoal(Goal("b", "B", rsd("60000")))
        repo.add(Transaction("t2", TransactionType.EXPENSE, rsd("50000"), "cash", null, null, LocalDate.of(2026, 10, 2), "", "", "m", TransactionSource.MANUAL))
        assertEquals(listOf(rsd("50000"), rsd("0")), repo.financial.first().goals.map { it.funded })
    }

    @Test
    fun `a goal without a rate makes progress unknown and lists the currency`() = runTest {
        seed()
        repo.setGoal(Goal("trip", "Trip", Money.of("3000", Currency.EUR)))
        val f = repo.financial.first()
        assertNull(f.goals.single().funded)
        assertEquals(setOf(Currency.EUR), f.missingCurrencies)
    }
}
