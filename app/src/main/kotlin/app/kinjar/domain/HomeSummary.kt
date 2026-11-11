package app.kinjar.domain

import java.time.LocalDate
import java.time.YearMonth
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Savings totals are null when a nonzero active balance has no exchange rate: a partial sum would read as the real figure.
 * Month figures skip unconvertible amounts and set [monthPartial] instead.
 * [goalPoolRsd] excludes opted-out accounts and stays unrounded so foreign balances can fully fund matching goals.
 */
data class HomeSummary(
    val savingsRsd: Money?,
    val savingsEur: Money?,
    val income: Money,
    val spent: Money,
    val monthPartial: Boolean,
    val balances: Map<String, Money>,
    val savingsPoolRsd: BigDecimal? = savingsRsd?.amount,
    val goalPoolRsd: BigDecimal? = savingsPoolRsd,
) {
    val difference: Money get() = income - spent
}

object HomeCalculator {

    /** Archived accounts drop out of savings but their transactions still count in the month they happened. */
    fun summarize(today: LocalDate, accounts: List<Account>, transactions: List<Transaction>, rates: Rates): HomeSummary {
        val posted = transactions.filter { it.date <= today }
        val balances = balances(accounts.filterNot { it.archived }, posted)

        fun total(currency: Currency, amounts: Collection<Money> = balances.values): BigDecimal? {
            val grouped = amounts.groupBy { it.currency }
                .mapValues { (_, amounts) -> amounts.fold(BigDecimal.ZERO) { sum, m -> sum + m.amount } }
                .filterValues { it.signum() != 0 }
            var native = BigDecimal.ZERO
            var foreignRsd = BigDecimal.ZERO
            for ((source, amount) in grouped) {
                if (source == currency) native += amount
                else foreignRsd += amount.multiply(rates.rate(source) ?: return null)
            }
            if (grouped.keys.any { it != currency }) {
                native += if (currency == Currency.RSD) foreignRsd
                else foreignRsd.divide(rates.rate(currency) ?: return null, Money.SCALE, RoundingMode.HALF_UP)
            }
            return native
        }

        var partial = false
        val month = YearMonth.from(today)
        val thisMonth = posted.filter { YearMonth.from(it.date) == month }
        fun sum(type: TransactionType) = thisMonth.filter { it.type == type }.fold(Money.zero(Currency.RSD)) { acc, t ->
            acc + (rates.convert(t.money) ?: Money.zero(Currency.RSD).also { partial = true })
        }
        val income = sum(TransactionType.INCOME)
        val spent = sum(TransactionType.EXPENSE)
        val poolRsd = total(Currency.RSD)
        val goalBalances = accounts.filterNot { it.archived || it.excludedFromGoals }.mapNotNull { balances[it.id] }
        return HomeSummary(
            poolRsd?.let { Money.of(it, Currency.RSD) }, total(Currency.EUR)?.let { Money.of(it, Currency.EUR) },
            income, spent, partial, balances, poolRsd, total(Currency.RSD, goalBalances),
        )
    }

    fun balances(accounts: List<Account>, transactions: List<Transaction>): Map<String, Money> {
        val result = accounts.associate { it.id to Money.zero(it.currency) }.toMutableMap()
        fun add(accountId: String, delta: Money) {
            val current = result[accountId] ?: return
            // Old events may carry another currency; the account's currency is the truth, so those are skipped, not thrown.
            if (delta.currency != current.currency) return
            result[accountId] = current + delta
        }
        for (t in transactions) {
            when (t.type) {
                TransactionType.INCOME, TransactionType.LOAN_RECEIPT -> add(t.accountId, t.money)
                TransactionType.EXPENSE -> add(t.accountId, -t.money)
                TransactionType.TRANSFER -> {
                    add(t.accountId, -t.money)
                    add(t.toAccountId!!, t.received ?: t.money)
                }
            }
        }
        return result
    }
}
