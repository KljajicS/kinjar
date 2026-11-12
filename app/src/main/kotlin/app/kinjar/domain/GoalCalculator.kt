package app.kinjar.domain

import java.math.RoundingMode
import java.math.BigDecimal

/** Null funding means a missing rate prevents a reliable progress calculation. */
data class GoalProgress(
    val goal: Goal,
    val funded: Money?,
    val reached: Boolean,
    val waiting: Boolean,
) {
    val ratio: Double? get() = funded?.amount?.divide(goal.target.amount, 4, RoundingMode.HALF_UP)?.toDouble()
    val left: Money? get() = funded?.let { goal.target - it }
}

object GoalCalculator {

    fun progress(goals: List<Goal>, home: HomeSummary, rates: Rates): List<GoalProgress> = fromPool(goals, home.goalPoolRsd, rates)

    fun progress(goals: List<Goal>, savingsRsd: Money?, rates: Rates): List<GoalProgress> = fromPool(goals, savingsRsd?.amount, rates)

    private fun fromPool(goals: List<Goal>, savingsRsd: BigDecimal?, rates: Rates): List<GoalProgress> {
        var pool: BigDecimal? = savingsRsd?.max(BigDecimal.ZERO)
        var earlierOpen = false
        val result = mutableListOf<GoalProgress>()
        for (goal in goals.filterNot { it.archived }) {
            val available = pool
            val rate = rates.rate(goal.target.currency)
            if (available == null || rate == null) {
                pool = null
                result += GoalProgress(goal, null, reached = false, waiting = false)
                continue
            }
            // Rounding a target cost before depletion could fund small targets for free.
            val target = goal.target.amount.multiply(rate)
            val take = available.min(target)
            pool = available - take
            val reached = take.compareTo(target) == 0
            val funded = if (reached) goal.target else Money.of(take.divide(rate, Money.SCALE, RoundingMode.DOWN), goal.target.currency)
            result += GoalProgress(goal, funded, reached, waiting = take.signum() == 0 && earlierOpen)
            earlierOpen = earlierOpen || !reached
        }
        return result
    }
}
