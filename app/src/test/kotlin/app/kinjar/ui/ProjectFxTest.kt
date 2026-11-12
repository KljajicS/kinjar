package app.kinjar.ui

import app.kinjar.data.rates.ProjectRateQuote
import app.kinjar.domain.Currency
import app.kinjar.domain.Money
import app.kinjar.domain.ProjectCost
import app.kinjar.ui.projects.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class ProjectFxTest {
    private val day = LocalDate.of(2026, 11, 8)
    private val original = Money.of("11750", Currency.RSD)
    private val quote = ProjectRateQuote(day.minusDays(2), mapOf(Currency.EUR to BigDecimal("117.5")), "NBS middle rate")

    @Test fun `expense date rate produces frozen project amount and actual publication date`() {
        val result = requireNotNull(convert(original, Currency.EUR, day, quote, emptyMap()))
        assertEquals(Money.of("100", Currency.EUR), result.converted)
        assertEquals(day.minusDays(2), result.rateDate)
        assertEquals("117.5", result.ratesToRsd[Currency.EUR])
    }

    @Test fun `manual rate applies only to its expense date`() {
        val manual = mapOf(RateKey(day, Currency.EUR) to "117,5")
        assertEquals(Money.of("100", Currency.EUR), convert(original, Currency.EUR, day, null, manual)?.converted)
        assertNull(convert(original, Currency.EUR, day.plusDays(1), null, manual))
        assertNull(convert(original, Currency.EUR, day, null, emptyMap()))
    }

    @Test fun `manual rate fields stay available after entering a valid digit`() {
        val tx = app.kinjar.domain.Transaction("t", app.kinjar.domain.TransactionType.EXPENSE, original, "a", null, null, day, "", "", "m", app.kinjar.domain.TransactionSource.MANUAL)
        val plan = linkPlan(listOf(tx), app.kinjar.domain.Project("p", "House"), mapOf(day to QuoteResult(null)), mapOf(RateKey(day, Currency.EUR) to "1"))
        assertTrue(plan.ready)
        assertEquals(listOf(RateKey(day, Currency.EUR)), plan.missing)
    }

    @Test fun `saved quote wins independently of later market rates`() {
        val cost = ProjectCost("cost", "house", null, original, Money.of("100", Currency.EUR), day, "",
            ratesToRsd = mapOf(Currency.EUR to "117.5"), rateDate = day.minusDays(2), rateSource = "manual")
        val saved = savedQuote(cost, Currency.EUR)
        assertEquals(Money.of("100", Currency.EUR), convert(original, Currency.EUR, day, saved, emptyMap())?.converted)
        assertEquals("manual", saved?.source)
    }

    @Test fun `same currency never needs a network quote`() {
        assertEquals(original, convert(original, Currency.RSD, day, null, emptyMap())?.converted)
        assertTrue(neededRates(Currency.RSD, Currency.RSD).isEmpty())
    }

    @Test fun `tiny costs may round to zero in project currency`() {
        assertEquals(Money.zero(Currency.EUR), convert(Money.of("0.01", Currency.RSD), Currency.EUR, day, quote, emptyMap())?.converted)
    }

    @Test fun `returning through original currency retains manual snapshot`() {
        val cost = ProjectCost("cost", "house", null, original, Money.of("100", Currency.EUR), day, "",
            ratesToRsd = mapOf(Currency.EUR to "117.5"), rateDate = day, rateSource = "manual")
        val identity = requireNotNull(convert(original, Currency.RSD, day, null, emptyMap()))
        val inRsd = cost.withConversion(original, day, identity)
        assertEquals(cost.ratesToRsd, inRsd.ratesToRsd)
        assertEquals("manual", inRsd.rateSource)
        val back = requireNotNull(convert(original, Currency.EUR, day, savedQuote(inRsd, Currency.EUR), emptyMap()))
        assertEquals(cost, inRsd.withConversion(original, day, back))
    }

    @Test fun `cross rates require both foreign currencies`() {
        val usd = Money.of("100", Currency.USD)
        assertNull(convert(usd, Currency.EUR, day, quote, emptyMap()))
        val manual = mapOf(RateKey(day, Currency.USD) to "100")
        assertEquals(Money.of("85.11", Currency.EUR), convert(usd, Currency.EUR, day, quote, manual)?.converted)
    }
}
