package app.kinjar.domain

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class WeeklyBudgetTest {
    private val monday = LocalDate.of(2026, 12, 28)
    private fun rsd(amount: String) = Money.of(amount, Currency.RSD)
    private fun tx(id: String, date: LocalDate, type: TransactionType = TransactionType.EXPENSE, currency: Currency = Currency.RSD) =
        Transaction(id, type, Money.of("100", currency), "cash", if (type == TransactionType.TRANSFER) "other" else null,
            if (type == TransactionType.EXPENSE) "food" else null, date, "", "", "member", TransactionSource.MANUAL)

    @Test fun `Monday to Sunday includes year boundary and excludes future expenses income and transfers`() {
        val today = monday.plusDays(6)
        val txs = listOf(tx("before", monday.minusDays(1)), tx("mon", monday), tx("sun", today),
            tx("future", today.plusDays(1)), tx("income", monday, TransactionType.INCOME), tx("transfer", monday, TransactionType.TRANSFER))
        val schedule = listOf(WeeklyBudget("food", monday, rsd("150")))
        val p = WeeklyBudgetCalculator.progress(today, today, schedule, txs, Rates.EMPTY).single()
        assertEquals(rsd("200"), p.spent)
        assertEquals(rsd("-50"), p.remaining)
        assertTrue(p.ratio!! > BigDecimal.ONE)
        assertEquals(rsd("100"), WeeklyBudgetCalculator.progress(monday, monday, schedule, txs, Rates.EMPTY).single().spent)
        assertEquals(monday, weekStart(today))
        assertEquals(monday.plusWeeks(1), weekStart(today.plusDays(1)))
    }

    @Test fun `recurring amount changes and removal preserve previous weeks without rollover`() {
        val schedule = listOf(WeeklyBudget("food", monday, rsd("500")), WeeklyBudget("food", monday.plusWeeks(2), rsd("700")),
            WeeklyBudget("food", monday.plusWeeks(3), null))
        assertTrue(WeeklyBudgetCalculator.active(schedule, monday.minusWeeks(1)).isEmpty())
        assertEquals(rsd("500"), WeeklyBudgetCalculator.active(schedule, monday.plusWeeks(1)).single().limit)
        assertEquals(rsd("700"), WeeklyBudgetCalculator.active(schedule, monday.plusWeeks(2)).single().limit)
        assertTrue(WeeklyBudgetCalculator.active(schedule, monday.plusWeeks(4)).isEmpty())
        val p = WeeklyBudgetCalculator.progress(monday.plusWeeks(1), monday.plusWeeks(1), schedule, listOf(tx("old", monday)), Rates.EMPTY).single()
        assertEquals(rsd("500"), p.remaining)
    }

    @Test fun `missing conversion leaves progress unknown and available rates convert expenses`() {
        val schedule = listOf(WeeklyBudget("food", monday, rsd("20000")))
        val txs = listOf(tx("eur", monday, currency = Currency.EUR))
        val missing = WeeklyBudgetCalculator.progress(monday, monday, schedule, txs, Rates.EMPTY).single()
        assertNull(missing.spent)
        assertNull(missing.remaining)
        assertNull(missing.ratio)
        assertEquals(rsd("11700"), WeeklyBudgetCalculator.progress(monday, monday, schedule, txs, Rates(mapOf(Currency.EUR to BigDecimal("117")))).single().spent)
    }

    @Test fun `invalid limits and non Monday dates are rejected`() {
        assertTrue(runCatching { WeeklyBudget("food", monday.plusDays(1), rsd("1")) }.isFailure)
        listOf("0", "-1").forEach { assertTrue(runCatching { WeeklyBudget("food", monday, rsd(it)) }.isFailure) }
        assertTrue(runCatching { WeeklyBudget("food", monday, Money.of("1", Currency.EUR)) }.isFailure)
        val event: Event = WeeklyBudgetSet(WeeklyBudget("food", monday, rsd("10")))
        assertEquals(event, EventJson.decodeFromString<Event>(EventJson.encodeToString(Event.serializer(), event)))
    }
}
