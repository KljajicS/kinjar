package app.kinjar.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

class HomeSummaryTest {
    private val today = LocalDate.of(2026, 10, 14)
    private val cash = Account("cash", "Cash", Currency.RSD)
    private val card = Account("card", "Card", Currency.RSD)
    private val eurSavings = Account("eur", "EUR savings", Currency.EUR, AccountKind.SAVINGS)
    private val rates = Rates(mapOf(Currency.RSD to BigDecimal.ONE, Currency.EUR to BigDecimal("117.20")))

    private fun tx(id: String, type: TransactionType, amount: String, account: Account, date: LocalDate = today, to: Account? = null, currency: Currency = Currency.RSD) =
        Transaction(id, type, Money.of(amount, currency), account.id, to?.id, null, date, "", "", "m1", TransactionSource.MANUAL)

    private fun rsd(s: String) = Money.of(s, Currency.RSD)

    @Test
    fun `excluded foreign balance without a rate does not block goals`() {
        val excluded = eurSavings.copy(excludedFromGoals = true)
        val home = HomeCalculator.summarize(today, listOf(cash, excluded), listOf(
            tx("cash", TransactionType.INCOME, "1000", cash),
            tx("eur", TransactionType.INCOME, "100", excluded, currency = Currency.EUR),
        ), Rates(emptyMap()))
        assertNull(home.savingsRsd)
        assertEquals(rsd("1000"), GoalCalculator.progress(listOf(Goal("g", "Goal", rsd("2000"))), home, Rates(emptyMap())).single().funded)
    }

    @Test
    fun `excluded negative balances do not reduce funding and transfers change eligibility`() {
        val excluded = card.copy(excludedFromGoals = true)
        val txs = listOf(tx("in", TransactionType.INCOME, "1000", cash), tx("out", TransactionType.EXPENSE, "100", excluded))
        val before = HomeCalculator.summarize(today, listOf(cash, excluded), txs, rates)
        val after = HomeCalculator.summarize(today, listOf(cash, excluded), txs + tx("move", TransactionType.TRANSFER, "300", cash, to = excluded), rates)
        assertEquals(rsd("900"), before.savingsRsd)
        assertEquals(before.savingsRsd, after.savingsRsd)
        assertEquals(0, before.goalPoolRsd!!.compareTo(BigDecimal("1000")))
        assertEquals(0, after.goalPoolRsd!!.compareTo(BigDecimal("700")))
    }

    @Test
    fun `same currency transfers cannot change converted savings`() {
        val a = Account("a", "A", Currency.USD)
        val b = Account("b", "B", Currency.USD)
        val fx = Rates(mapOf(Currency.USD to BigDecimal("105.333"), Currency.EUR to BigDecimal("117.2")))
        val initial = listOf(
            tx("a", TransactionType.INCOME, "0.01", a, currency = Currency.USD),
            tx("b", TransactionType.INCOME, "0.01", b, currency = Currency.USD),
        )
        val before = HomeCalculator.summarize(today, listOf(a, b), initial, fx)
        val after = HomeCalculator.summarize(today, listOf(a, b), initial + tx("move", TransactionType.TRANSFER, "0.01", a, to = b, currency = Currency.USD), fx)
        assertEquals(rsd("2.11"), before.savingsRsd)
        assertEquals(before.savingsRsd, after.savingsRsd)
        assertEquals(before.savingsEur, after.savingsEur)
    }

    @Test
    fun `future transactions do not affect current balances or insights`() {
        val savings = Account("savings", "Savings", Currency.RSD)
        val txs = listOf(
            tx("posted", TransactionType.INCOME, "1000", card),
            tx("future-income", TransactionType.INCOME, "100000", card, today.plusDays(1)),
            tx("future-spend", TransactionType.EXPENSE, "500", card, today.plusDays(1)).copy(categoryId = "food"),
            tx("future-transfer", TransactionType.TRANSFER, "200", card, today.plusDays(1), to = savings),
            tx("next-month", TransactionType.INCOME, "200000", card, today.plusMonths(1)),
        )
        val home = HomeCalculator.summarize(today, listOf(card, savings), txs, rates)
        val insights = InsightsCalculator.summarize(today, YearMonth.from(today), txs, rates)
        assertEquals(rsd("1000"), home.income)
        assertEquals(rsd("1000"), home.savingsRsd)
        assertEquals(rsd("0"), home.balances["savings"])
        assertEquals(rsd("0"), insights.spent)
        assertEquals(home.income, insights.months.last().income)
        val tomorrow = HomeCalculator.summarize(today.plusDays(1), listOf(card, savings), txs, rates)
        assertEquals(rsd("100500"), tomorrow.savingsRsd)
        assertEquals(rsd("200"), tomorrow.balances["savings"])
    }

    @Test
    fun `income spent and difference cover this calendar month only, transfers excluded`() {
        val txs = listOf(
            tx("1", TransactionType.INCOME, "245000", card),
            tx("2", TransactionType.EXPENSE, "98400", card),
            tx("3", TransactionType.EXPENSE, "5000", card, date = LocalDate.of(2026, 11, 1)),
            tx("4", TransactionType.EXPENSE, "7000", card, date = LocalDate.of(2026, 9, 30)),
            tx("5", TransactionType.TRANSFER, "1000", card, to = cash),
        )
        val s = HomeCalculator.summarize(today, listOf(cash, card), txs, rates)
        assertEquals(rsd("245000"), s.income)
        assertEquals(rsd("98400"), s.spent)
        assertEquals(rsd("146600"), s.difference)
        assertFalse(s.monthPartial)
    }

    @Test
    fun `every active balance counts as savings in both currencies and may be negative`() {
        val txs = listOf(
            tx("1", TransactionType.EXPENSE, "1172", card),
            tx("2", TransactionType.INCOME, "10", eurSavings, currency = Currency.EUR),
            tx("3", TransactionType.INCOME, "1172", cash),
        )
        val s = HomeCalculator.summarize(today, listOf(card, eurSavings, cash), txs, rates)
        assertEquals(rsd("1172"), s.savingsRsd)
        assertEquals(Money.of("10", Currency.EUR), s.savingsEur)
        val negative = HomeCalculator.summarize(today, listOf(card), txs, rates)
        assertEquals(rsd("-1172"), negative.savingsRsd)
        assertEquals(Money.of("-10", Currency.EUR), negative.savingsEur)
    }

    @Test
    fun `archived accounts leave savings but their transactions stay in the month`() {
        val old = card.copy(archived = true)
        val txs = listOf(tx("1", TransactionType.INCOME, "5000", old), tx("2", TransactionType.INCOME, "100", cash))
        val s = HomeCalculator.summarize(today, listOf(old, cash), txs, rates)
        assertEquals(rsd("100"), s.savingsRsd)
        assertNull(s.balances["card"])
        assertEquals(rsd("5100"), s.income)
    }

    @Test
    fun `transaction in another currency than its account is skipped, not thrown`() {
        val txs = listOf(
            tx("1", TransactionType.INCOME, "1000", card),
            tx("2", TransactionType.EXPENSE, "10", card, currency = Currency.EUR),
        )
        val s = HomeCalculator.summarize(today, listOf(card), txs, rates)
        assertEquals(rsd("1000"), s.balances["card"])
    }

    @Test
    fun `balances include all history and transfers move money`() {
        val txs = listOf(
            tx("1", TransactionType.INCOME, "1000", card, date = LocalDate.of(2025, 1, 1)),
            tx("2", TransactionType.TRANSFER, "400", card, to = cash),
            tx("3", TransactionType.EXPENSE, "100", cash),
        )
        val s = HomeCalculator.summarize(today, listOf(cash, card), txs, rates)
        assertEquals(rsd("600"), s.balances["card"])
        assertEquals(rsd("300"), s.balances["cash"])
        assertEquals(rsd("900"), s.savingsRsd)
    }

    @Test
    fun `missing rate makes totals unavailable instead of a partial sum`() {
        val txs = listOf(
            tx("1", TransactionType.INCOME, "1000", card),
            tx("2", TransactionType.INCOME, "10", eurSavings, currency = Currency.EUR),
        )
        val s = HomeCalculator.summarize(today, listOf(card, eurSavings), txs, Rates.EMPTY)
        assertNull(s.savingsRsd)
        assertNull(s.savingsEur)
        assertTrue(s.monthPartial)
        assertEquals(rsd("1000"), s.income)
        assertEquals(Money.of("10", Currency.EUR), s.balances["eur"])
        val rsdOnly = HomeCalculator.summarize(today, listOf(card), txs, Rates.EMPTY)
        assertEquals(rsd("1000"), rsdOnly.savingsRsd)
        assertNull(rsdOnly.savingsEur)
    }
}
