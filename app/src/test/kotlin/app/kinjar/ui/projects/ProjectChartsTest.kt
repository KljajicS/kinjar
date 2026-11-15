package app.kinjar.ui.projects

import app.kinjar.data.ProjectBook
import app.kinjar.domain.Currency
import app.kinjar.domain.Money
import app.kinjar.domain.Project
import app.kinjar.domain.ProjectCategory
import app.kinjar.domain.ProjectCost
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class ProjectChartsTest {
    private val project = Project("p", "House")
    private val day = LocalDate.of(2026, 11, 15)
    private fun eur(amount: String) = Money.of(amount, Currency.EUR)
    private val categories = listOf(
        ProjectCategory("shown", "p", "Utilities", "#123456"),
        ProjectCategory("hidden", "p", "Furniture", "#654321", archived = true, excludedFromReport = true),
    )
    private val costs = listOf(
        ProjectCost("a", "p", "shown", eur("100"), eur("100"), day, "", unpaid = true),
        ProjectCost("b", "p", "hidden", eur("300"), eur("300"), day, "", unpaid = true),
        ProjectCost("c", "p", null, eur("20"), eur("20"), day.minusMonths(1), ""),
    )

    @Test fun `category and monthly charts exclude the same costs and retain uncategorized costs`() {
        val included = includedCosts(costs, categories)
        val totals = categoryTotals(included, categories, Currency.EUR)
        assertEquals(listOf("shown", null), totals.map { it.category?.id })
        assertEquals(listOf(eur("100"), eur("20")), totals.map { it.total })
        assertEquals(eur("120"), sum(included, Currency.EUR))
        val months = monthTotals(included, Currency.EUR, YearMonth.from(day)).toMap()
        assertEquals(eur("100"), months[YearMonth.from(day)])
        assertEquals(eur("20"), months[YearMonth.from(day.minusMonths(1))])
    }

    @Test fun `project card chart excludes categories while spending and payment totals remain complete`() {
        val summary = ProjectBook(listOf(project), categories, costs, emptyList()).summary(project)
        assertEquals(eur("420"), summary.total)
        assertEquals(eur("400"), summary.unpaid)
        assertEquals(listOf("shown", null), summary.byCategory.map { it.category?.id })
        assertEquals(eur("120"), summary.byCategory.fold(Money.zero(Currency.EUR)) { total, item -> total + item.total })
    }

    @Test fun `excluding every category leaves empty chart data and reenabling restores costs`() {
        val categorized = costs.filter { it.categoryId != null }
        val excluded = categories.map { it.copy(excludedFromReport = true) }
        val included = includedCosts(categorized, excluded)
        assertTrue(included.isEmpty())
        assertTrue(categoryTotals(included, excluded, Currency.EUR).isEmpty())
        assertTrue(monthTotals(included, Currency.EUR, YearMonth.from(day)).all { it.second.isZero })
        assertEquals(categorized, includedCosts(categorized, excluded.map { it.copy(excludedFromReport = false) }))
    }
}
