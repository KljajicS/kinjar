package app.kinjar.ui.projects

import app.kinjar.data.rates.ProjectRateLookup
import app.kinjar.data.rates.ProjectRateQuote
import app.kinjar.domain.Currency
import app.kinjar.domain.Money
import app.kinjar.domain.ProjectCost
import app.kinjar.domain.Rates
import app.kinjar.domain.parseRate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

const val SAME_CURRENCY = "same-currency"
const val MANUAL_RATE = "manual"

/** A manual rate is RSD per one unit of [currency] on exactly [date]; it never stands in for another day. */
data class RateKey(val date: LocalDate, val currency: Currency)

data class Conversion(val converted: Money, val ratesToRsd: Map<Currency, String>, val rateDate: LocalDate, val source: String)

/** Null while the fetch is still running; [quote] is null when it failed. */
data class QuoteResult(val quote: ProjectRateQuote?)

fun neededRates(from: Currency, to: Currency): Set<Currency> = if (from == to) emptySet() else setOf(from, to) - Currency.RSD

fun rateText(rate: BigDecimal): String? =
    rate.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().takeIf { parseRate(it) != null }

fun manualRateText(input: String): String? = parseRate(input.trim().replace(',', '.'))?.let(::rateText)

/**
 * Converts [original] with rates for [date] only: the fetched [quote] first, then manual entries for that same day.
 * Null means a rate is still unknown; there is no fallback to today's rates.
 */
fun convert(original: Money, target: Currency, date: LocalDate, quote: ProjectRateQuote?, manual: Map<RateKey, String>): Conversion? {
    val needed = neededRates(original.currency, target)
    if (needed.isEmpty()) return Conversion(original, emptyMap(), date, SAME_CURRENCY)
    val fetched = needed.mapNotNull { c -> quote?.rates?.get(c)?.let(::rateText)?.let { c to it } }.toMap()
    val typed = (needed - fetched.keys).mapNotNull { c -> manual[RateKey(date, c)]?.let(::manualRateText)?.let { c to it } }.toMap()
    val rates = fetched + typed
    if (rates.keys != needed) return null
    val converted = Rates(rates.mapValues { it.value.toBigDecimal() }).convert(original, target) ?: return null
    val source = when {
        typed.isEmpty() -> requireNotNull(quote).source
        fetched.isEmpty() -> MANUAL_RATE
        else -> "${requireNotNull(quote).source} + $MANUAL_RATE"
    }
    return Conversion(converted, rates, if (fetched.isEmpty()) date else requireNotNull(quote).date, source)
}

/** Manual fields stay visible while typing, even after the first digit forms a valid rate. */
fun missingRates(original: Money, target: Currency, date: LocalDate, quote: ProjectRateQuote?): List<RateKey> =
    neededRates(original.currency, target).filter { c ->
        quote?.rates?.get(c)?.let(::rateText) == null
    }.map { RateKey(date, it) }

/** The cost's own snapshot as a quote, when it holds every rate [target] needs. */
fun savedQuote(cost: ProjectCost, target: Currency): ProjectRateQuote? {
    val needed = neededRates(cost.original.currency, target)
    if (needed.isEmpty() || !cost.ratesToRsd.keys.containsAll(needed)) return null
    return ProjectRateQuote(cost.rateDate, cost.ratesToRsd.mapValues { it.value.toBigDecimal() }, cost.rateSource)
}

fun ProjectCost.withConversion(original: Money, date: LocalDate, conversion: Conversion): ProjectCost {
    val sameExpense = this.date == date && this.original.currency == original.currency
    val keepSnapshot = sameExpense && conversion.ratesToRsd.isEmpty() && ratesToRsd.isNotEmpty()
    val keepOtherRates = sameExpense && rateDate == conversion.rateDate && rateSource == conversion.source
    return copy(
        original = original, date = date, converted = conversion.converted,
        ratesToRsd = when {
            keepSnapshot -> ratesToRsd
            keepOtherRates -> ratesToRsd + conversion.ratesToRsd
            else -> conversion.ratesToRsd
        },
        rateDate = if (keepSnapshot) rateDate else conversion.rateDate,
        rateSource = if (keepSnapshot) rateSource else conversion.source,
    )
}

/** Fetches each historical day once; a failed day stays failed until [retryFailed], so the form asks for a manual rate instead. */
class QuoteCache(private val scope: CoroutineScope, private val lookup: ProjectRateLookup) {
    private val mutable = MutableStateFlow<Map<LocalDate, QuoteResult>>(emptyMap())
    private val running = mutableSetOf<LocalDate>()
    val quotes: StateFlow<Map<LocalDate, QuoteResult>> = mutable

    fun ensure(dates: Collection<LocalDate>) = dates.forEach { date ->
        if (date in mutable.value || !running.add(date)) return@forEach
        scope.launch {
            val result = try {
                QuoteResult(lookup.fetch(date))
            } catch (e: CancellationException) {
                running.remove(date)
                throw e
            } catch (_: Exception) {
                QuoteResult(null)
            }
            running.remove(date)
            mutable.update { it + (date to result) }
        }
    }

    fun retryFailed() {
        val failed = mutable.value.filterValues { it.quote == null }.keys
        mutable.update { it - failed }
        ensure(failed)
    }
}
