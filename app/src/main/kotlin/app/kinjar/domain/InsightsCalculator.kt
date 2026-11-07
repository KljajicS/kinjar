package app.kinjar.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

data class Slice(val categoryId: String?, val amount: Money, val share: Int)

data class MonthTotals(val month: YearMonth, val income: Money, val expenses: Money, val inProgress: Boolean) {
    val difference: Money get() = income - expenses
}

data class CategoryChange(val categoryId: String?, val current: Money, val previous: Money) {
    val delta: Money get() = current - previous

    /** Null when nothing was spent before: shown as new spending rather than an infinite percentage. */
    val percent: BigDecimal? get() = if (previous.isZero) null else
        delta.amount.multiply(BigDecimal(100)).divide(previous.amount, 0, RoundingMode.HALF_UP)
}

data class InsightsSummary(
    val month: YearMonth,
    val range: ClosedRange<YearMonth>,
    val spent: Money,
    val slices: List<Slice>,
    val spendingPartial: Boolean,
    val months: List<MonthTotals>,
    val monthsPartial: Boolean,
    val changes: List<CategoryChange>,
    /** Days compared from the start of each month while [month] is in progress; null when whole months are compared. */
    val comparedDays: Int?,
    val changesPartial: Boolean,
)

object InsightsCalculator {
    private const val WINDOW = 6
    private const val TOP = 4
    private const val CHANGES = 3

    private class Converter(private val rates: Rates) {
        var partial = false
        fun sum(list: List<Transaction>): Money =
            list.fold(Money.zero(Currency.RSD)) { acc, t -> acc + (rates.convert(t.money) ?: Money.zero(Currency.RSD).also { partial = true }) }
    }

    fun summarize(today: LocalDate, month: YearMonth, transactions: List<Transaction>, rates: Rates): InsightsSummary {
        val posted = transactions.filter { it.date <= today }
        val current = YearMonth.from(today)
        val first = posted.minOfOrNull { YearMonth.from(it.date) }?.coerceAtMost(current) ?: current
        val expenses = posted.filter { it.type == TransactionType.EXPENSE }

        val spending = Converter(rates)
        val thisMonth = expenses.filter { YearMonth.from(it.date) == month }
        val spent = spending.sum(thisMonth)
        val slices = slices(thisMonth.groupBy { it.categoryId }.mapValues { (_, list) -> spending.sum(list) }, spent)

        val totals = Converter(rates)
        val months = (WINDOW - 1 downTo 0).map { month.minusMonths(it.toLong()) }.map { m ->
            val inMonth = posted.filter { YearMonth.from(it.date) == m }
            MonthTotals(m, totals.sum(inMonth.filter { it.type == TransactionType.INCOME }), totals.sum(inMonth.filter { it.type == TransactionType.EXPENSE }), m == current)
        }

        // A month in progress is compared with the same number of days of the month before, clamped to its length.
        val comparedDays = if (month == current) today.dayOfMonth else null
        val previous = month.minusMonths(1)
        val previousEnd = comparedDays?.let { previous.atDay(minOf(it, previous.lengthOfMonth())) } ?: previous.atEndOfMonth()
        val changing = Converter(rates)
        val now = thisMonth.groupBy { it.categoryId }.mapValues { (_, list) -> changing.sum(list) }
        val before = expenses.filter { YearMonth.from(it.date) == previous && it.date <= previousEnd }
            .groupBy { it.categoryId }.mapValues { (_, list) -> changing.sum(list) }
        val zero = Money.zero(Currency.RSD)
        val changes = (now.keys + before.keys)
            .map { CategoryChange(it, now[it] ?: zero, before[it] ?: zero) }
            .filterNot { it.delta.isZero }
            .sortedWith(compareByDescending<CategoryChange> { it.delta.amount.abs() }.thenBy { it.categoryId ?: "" })
            .take(CHANGES)

        return InsightsSummary(month, first..current, spent, slices, spending.partial, months, totals.partial, changes, comparedDays, changing.partial)
    }

    /** Posted expenses behind [slice]; the unnamed slice holds everything outside the named ones. */
    fun drilldown(today: LocalDate, summary: InsightsSummary, slice: Slice, transactions: List<Transaction>): List<Transaction> {
        val named = summary.slices.mapNotNull { it.categoryId }.toSet()
        return transactions
            .filter { it.date <= today && it.type == TransactionType.EXPENSE && YearMonth.from(it.date) == summary.month }
            .filter { if (slice.categoryId != null) it.categoryId == slice.categoryId else it.categoryId !in named }
            .sortedWith(compareByDescending<Transaction> { it.date }.thenByDescending { it.id })
    }

    private fun slices(byCategory: Map<String?, Money>, total: Money): List<Slice> {
        if (total.isZero) return emptyList()
        val named = byCategory.filterKeys { it != null }.entries.sortedByDescending { it.value.amount }
        val top = named.take(TOP)
        val rest = named.drop(TOP).fold(byCategory[null] ?: Money.zero(Currency.RSD)) { acc, e -> acc + e.value }
        fun share(m: Money) = m.amount.multiply(BigDecimal(100)).divide(total.amount, 0, RoundingMode.HALF_UP).toInt()
        val slices = top.map { Slice(it.key, it.value, share(it.value)) } + (if (rest.isZero) emptyList() else listOf(Slice(null, rest, share(rest))))
        // Rounding can leave the shares at 99 or 101; the largest slice absorbs the difference so the legend adds up.
        val drift = 100 - slices.sumOf { it.share }
        return slices.mapIndexed { i, s -> if (i == 0) s.copy(share = s.share + drift) else s }
    }
}
