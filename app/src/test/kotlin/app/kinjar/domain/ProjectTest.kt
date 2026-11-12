package app.kinjar.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

class ProjectTest {
    private val day = LocalDate.of(2026, 10, 10)
    private val nbs = mapOf(Currency.EUR to "117.17")
    private fun rsd(value: String) = Money.of(value, Currency.RSD)
    private fun eur(value: String) = Money.of(value, Currency.EUR)
    private fun cost(original: Money, converted: Money, rates: Map<Currency, String> = nbs) =
        ProjectCost("c", "p", null, original, converted, day, "", ratesToRsd = rates, rateSource = "NBS middle rate")

    @Test fun `same currency costs need no saved rates`() {
        val value = ProjectCost("c", "p", null, eur("12.34"), eur("12.34"), day, "")
        assertEquals(emptyMap<Currency, String>(), value.ratesToRsd)
        assertThrows(IllegalArgumentException::class.java) { ProjectCost("c", "p", null, eur("12.34"), eur("12.35"), day, "") }
    }

    @Test fun `converted amount must be recomputable from the saved rates`() {
        assertEquals(eur("8.53"), cost(rsd("1000"), eur("8.53")).converted)
        assertThrows(IllegalArgumentException::class.java) { cost(rsd("1000"), eur("8.54")) }
        assertThrows(IllegalArgumentException::class.java) { cost(rsd("1000"), eur("8.53"), emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { cost(rsd("1000"), eur("8.53"), mapOf(Currency.EUR to "117,17")) }
        assertThrows(IllegalArgumentException::class.java) { cost(rsd("1000"), eur("8.53"), nbs + (Currency.RSD to "2")) }
        assertEquals(rsd("1445.88"), cost(eur("12.34"), rsd("1445.88")).converted)
    }

    @Test fun `a positive cent may round to zero but a cost is never zero or negative`() {
        assertEquals(eur("0.00"), cost(rsd("0.50"), eur("0.00")).converted)
        assertThrows(IllegalArgumentException::class.java) { cost(rsd("0"), eur("0")) }
        assertThrows(IllegalArgumentException::class.java) { cost(rsd("-5"), eur("-0.04")) }
    }

    @Test fun `converting from the original snapshot round trips exactly`() {
        val rates = Rates(nbs.mapValues { it.value.toBigDecimal() })
        val original = rsd("0.50")
        val inEur = rates.convert(original, Currency.EUR)!!
        assertEquals(eur("0.00"), inEur)
        assertEquals(rsd("0.00"), rates.convert(inEur, Currency.RSD))
        assertEquals(original, cost(original, inEur).copy(converted = original, ratesToRsd = emptyMap()).converted)
    }

    @Test fun `rate date cannot follow the expense date`() {
        assertThrows(IllegalArgumentException::class.java) { cost(rsd("1000"), eur("8.53")).copy(rateDate = day.plusDays(1)) }
        assertEquals(day.minusDays(3), cost(rsd("1000"), eur("8.53")).copy(rateDate = day.minusDays(3)).rateDate)
    }

    @Test fun `projects use euro or dinar and a budget in their own currency`() {
        assertThrows(IllegalArgumentException::class.java) { Project("p", "Trip", Currency.USD) }
        assertThrows(IllegalArgumentException::class.java) { Project("p", "Trip", Currency.EUR, rsd("100")) }
        assertThrows(IllegalArgumentException::class.java) { Project("p", "Trip", Currency.EUR, eur("0")) }
        assertThrows(IllegalArgumentException::class.java) { Project("p", " ", Currency.EUR) }
        assertThrows(IllegalArgumentException::class.java) { ProjectCategory("k", "p", "Tiles", "red") }
    }

    @Test fun `project events round trip through the event codec`() {
        val events: List<Event> = listOf(
            ProjectSaved(Project("p", "Kitchen", Currency.RSD, rsd("500000"), setOf("cat-household"), archived = true)),
            ProjectCategorySaved(ProjectCategory("k", "p", "Tiles", "#112233", archived = true)),
            ProjectCostSaved(cost(eur("12.34"), rsd("1445.88")).copy(projectId = "p", categoryId = "k", transactionId = "t", note = "Grout")),
            ProjectCostRemoved("c", "p", "t"),
        )
        events.forEach { assertEquals(it, EventJson.decodeFromString(Event.serializer(), EventJson.encodeToString(Event.serializer(), it))) }
    }
}
