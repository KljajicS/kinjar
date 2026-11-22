package app.kinjar.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

@Serializable
data class WeeklyBudget(
    val categoryId: String,
    @Serializable(with = LocalDateSerializer::class) val effectiveFrom: LocalDate,
    val limit: Money?,
) {
    init {
        require(categoryId.isNotBlank())
        require(effectiveFrom.dayOfWeek == DayOfWeek.MONDAY)
        require(limit == null || (limit.currency == Currency.RSD && !limit.isNegative && !limit.isZero))
    }
}

@Serializable @SerialName("WeeklyBudgetSet")
data class WeeklyBudgetSet(val budget: WeeklyBudget) : Event

data class WeeklyBudgetProgress(val budget: WeeklyBudget, val spent: Money?) {
    val remaining: Money? get() = spent?.let { requireNotNull(budget.limit) - it }
    val ratio: BigDecimal? get() = spent?.amount?.divide(requireNotNull(budget.limit).amount, 4, RoundingMode.HALF_UP)
}

object WeeklyBudgetCalculator {
    fun active(schedule: List<WeeklyBudget>, week: LocalDate): List<WeeklyBudget> = schedule
        .filter { it.effectiveFrom <= weekStart(week) }
        .groupBy { it.categoryId }.values.map { rows -> rows.maxBy { it.effectiveFrom } }
        .filter { it.limit != null }.sortedBy { it.categoryId }

    fun progress(today: LocalDate, week: LocalDate, schedule: List<WeeklyBudget>, transactions: List<Transaction>, rates: Rates): List<WeeklyBudgetProgress> {
        val start = weekStart(week)
        val expenses = transactions.filter { it.type == TransactionType.EXPENSE && it.date in start..start.plusDays(6) && it.date <= today }
            .groupBy { it.categoryId }
        return active(schedule, start).map { budget ->
            val amounts = expenses[budget.categoryId].orEmpty().map { rates.convert(it.money) }
            val spent = if (amounts.any { it == null }) null else amounts.filterNotNull().fold(Money.zero(Currency.RSD), Money::plus)
            WeeklyBudgetProgress(budget, spent)
        }
    }
}
