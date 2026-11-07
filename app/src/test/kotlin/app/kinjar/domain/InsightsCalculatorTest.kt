package app.kinjar.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

class InsightsCalculatorTest {
    private val today = LocalDate.of(2026, 10, 14)
    private val oct = YearMonth.of(2026, 10)
    private val rates = Rates(mapOf(Currency.RSD to BigDecimal.ONE))

    private fun tx(id: String, amount: String, date: LocalDate, cat: String? = "g", type: TransactionType = TransactionType.EXPENSE, account: String = "cash", to: String? = null, currency: Currency = Currency.RSD) =
        Transaction(id, type, Money.of(amount, currency), account, to, if (type == TransactionType.EXPENSE) cat else null, date, "", "", "m1", TransactionSource.MANUAL)

    private fun rsd(s: String) = Money.of(s, Currency.RSD)

    @Test
    fun `slices are the top four plus other and shares add up to 100`() {
        val txs = listOf("a" to "300", "b" to "250", "c" to "200", "d" to "150", "e" to "50", "f" to "30").mapIndexed { i, (c, amt) -> tx("$i", amt, today, c) } +
            tx("x", "20", today, cat = null)
        val s = InsightsCalculator.summarize(today, oct, txs, rates)
        assertEquals(rsd("1000"), s.spent)
        assertEquals(listOf("a", "b", "c", "d", null), s.slices.map { it.categoryId })
        assertEquals(rsd("100"), s.slices.last().amount)
        assertEquals(100, s.slices.sumOf { it.share })
        assertEquals(listOf(30, 25, 20, 15, 10), s.slices.map { it.share })
        assertEquals(listOf("x", "5", "4"), InsightsCalculator.drilldown(today, s, s.slices.last(), txs).map { it.id }.sorted().reversed())
        assertEquals(listOf("0"), InsightsCalculator.drilldown(today, s, s.slices.first(), txs).map { it.id })
    }

    @Test
    fun `income and transfers are not spending and empty input does not throw`() {
        val txs = listOf(
            tx("1", "1000", today, type = TransactionType.INCOME),
            tx("2", "500", today, type = TransactionType.TRANSFER, to = "sav"),
        )
        val s = InsightsCalculator.summarize(today, oct, txs, rates)
        assertEquals(rsd("0"), s.spent)
        assertEquals(emptyList<Slice>(), s.slices)
        assertEquals(rsd("0"), s.months.last().expenses)
        assertTrue(s.changes.isEmpty())
        val empty = InsightsCalculator.summarize(today, oct, emptyList(), rates)
        assertEquals(6, empty.months.size)
        assertTrue(empty.changes.isEmpty())
        assertEquals(oct..oct, empty.range)
    }

    @Test
    fun `six month window ends at the selected month and keeps empty months`() {
        val txs = listOf(
            tx("1", "100000", LocalDate.of(2026, 5, 1), type = TransactionType.INCOME),
            tx("2", "40000", LocalDate.of(2026, 5, 2)),
            tx("3", "100000", LocalDate.of(2026, 9, 1), type = TransactionType.INCOME),
            tx("4", "70000", LocalDate.of(2026, 9, 2)),
            tx("5", "1000", today),
        )
        val s = InsightsCalculator.summarize(today, oct, txs, rates)
        assertEquals((5..10).map { YearMonth.of(2026, it) }, s.months.map { it.month })
        assertEquals(listOf(false, false, false, false, false, true), s.months.map { it.inProgress })
        assertEquals(rsd("0"), s.months[1].income)
        assertEquals(rsd("60000"), s.months[0].difference)
        assertEquals(rsd("30000"), s.months[4].difference)
        assertEquals(YearMonth.of(2026, 5)..oct, s.range)
        val sep = InsightsCalculator.summarize(today, YearMonth.of(2026, 9), txs, rates)
        assertEquals((4..9).map { YearMonth.of(2026, it) }, sep.months.map { it.month })
        assertTrue(sep.months.none { it.inProgress })
    }

    @Test
    fun `range clamps a future dated first transaction to the current month`() {
        val s = InsightsCalculator.summarize(today, oct, listOf(tx("1", "10", LocalDate.of(2026, 12, 1))), rates)
        assertEquals(oct..oct, s.range)
    }

    @Test
    fun `current month compares the same elapsed days, top three by absolute change, new spending has no percent`() {
        val txs = listOf(
            tx("1", "10000", LocalDate.of(2026, 9, 5), "groceries"),
            tx("2", "50000", LocalDate.of(2026, 9, 20), "groceries"),
            tx("3", "18000", today, "groceries"),
            tx("4", "6000", LocalDate.of(2026, 9, 14), "transport"),
            tx("5", "1000", today, "transport"),
            tx("6", "3000", today, "new"),
            tx("7", "100", LocalDate.of(2026, 9, 1), "small"),
            tx("8", "200", today, "small"),
            tx("9", "400", LocalDate.of(2026, 9, 3), "same"),
            tx("10", "400", today, "same"),
        )
        val s = InsightsCalculator.summarize(today, oct, txs, rates)
        assertEquals(14, s.comparedDays)
        assertEquals(listOf("groceries", "transport", "new"), s.changes.map { it.categoryId })
        assertEquals(rsd("8000"), s.changes[0].delta)
        assertEquals("80".toBigDecimal(), s.changes[0].percent)
        assertEquals(rsd("-5000"), s.changes[1].delta)
        assertEquals("-83".toBigDecimal(), s.changes[1].percent)
        assertNull(s.changes[2].percent)
        assertFalse(s.changesPartial)
    }

    @Test
    fun `extreme percentages keep their sign and magnitude past the Int range`() {
        assertEquals("2147483700".toBigDecimal(), CategoryChange("g", rsd("214748.38"), rsd("0.01")).percent)
        assertEquals("-100".toBigDecimal(), CategoryChange("g", rsd("0"), rsd("214748.38")).percent)
        assertEquals("9999999999999999999999800".toBigDecimal(), CategoryChange("g", rsd("999999999999999999999.99"), rsd("0.01")).percent)
    }

    @Test
    fun `completed months compare whole months and partial comparison clamps to a shorter month`() {
        val txs = listOf(
            tx("1", "100", LocalDate.of(2026, 8, 31), "a"),
            tx("2", "300", LocalDate.of(2026, 9, 30), "a"),
        )
        val sep = InsightsCalculator.summarize(today, YearMonth.of(2026, 9), txs, rates)
        assertNull(sep.comparedDays)
        assertEquals(rsd("200"), sep.changes.single().delta)

        val march31 = LocalDate.of(2027, 3, 31)
        val leap = listOf(tx("3", "500", LocalDate.of(2027, 2, 28), "a"), tx("4", "100", march31, "a"))
        val march = InsightsCalculator.summarize(march31, YearMonth.of(2027, 3), leap, rates)
        assertEquals(rsd("-400"), march.changes.single().delta)
        val jan = InsightsCalculator.summarize(LocalDate.of(2027, 1, 2), YearMonth.of(2027, 1), listOf(tx("5", "50", LocalDate.of(2026, 12, 2), "a"), tx("6", "60", LocalDate.of(2026, 12, 3), "a")), rates)
        assertEquals(rsd("-50"), jan.changes.single().delta)
    }

    @Test
    fun `amounts without a rate are skipped and flagged per section`() {
        val s = InsightsCalculator.summarize(today, oct, listOf(tx("1", "10", today, currency = Currency.EUR), tx("2", "10", LocalDate.of(2026, 5, 1), currency = Currency.EUR)), rates)
        assertTrue(s.spendingPartial)
        assertTrue(s.monthsPartial)
        assertTrue(s.changesPartial)
        assertEquals(rsd("0"), s.spent)
        val may = InsightsCalculator.summarize(today, YearMonth.of(2026, 3), listOf(tx("2", "10", LocalDate.of(2026, 5, 1), currency = Currency.EUR)), rates)
        assertFalse(may.spendingPartial)
    }
}
