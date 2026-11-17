package app.kinjar.ui.projects

import app.kinjar.R
import app.kinjar.data.ProjectBook
import app.kinjar.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

class ProjectMonthlyChecklistTest {
    private val project = Project("p", "Bills")
    private val day = LocalDate.of(2026, 11, 15)
    private val month = YearMonth.from(day)
    private val water = ProjectCategory("water", "p", "Water", "#123456", monthlyExpenseExpected = true)
    private val internet = water.copy(id = "internet", name = "Internet")
    private val loans = water.copy(id = "loans", name = "Loans", monthlyExpenseExpected = false)
    private val categories = listOf(water, loans, internet)
    private val amount = Money.of("100", Currency.EUR)
    private val cost = ProjectCost("cost", "p", water.id, amount, amount, day, "", unpaid = true)
    private val tx = Transaction("tx", TransactionType.EXPENSE, amount, "bank", null, null, day, "", "", "member", TransactionSource.MANUAL)

    @Test fun `only expected categories without costs in this project and month are missing`() {
        val unrelated = listOf(cost.copy(id = "old", date = day.minusMonths(1), rateDate = day.minusMonths(1)),
            cost.copy(id = "other", projectId = "other"), cost.copy(id = "uncategorized", categoryId = null))
        assertEquals(listOf(internet, water), missingMonthlyCategories(project, categories, unrelated, month))
        assertEquals(listOf(internet), missingMonthlyCategories(project, categories, unrelated + cost + cost.copy(id = "duplicate"), month))
        assertEquals(emptyList<ProjectCategory>(), missingMonthlyCategories(project, categories, listOf(cost, cost.copy(id = "internet", categoryId = internet.id)), month))
    }

    @Test fun `disabled empty and archived checklists are hidden`() {
        assertNull(missingMonthlyCategories(project, emptyList(), emptyList(), month))
        assertNull(missingMonthlyCategories(project, listOf(loans), emptyList(), month))
        assertNull(missingMonthlyCategories(project.copy(archived = true), categories, emptyList(), month))
        assertNull(missingMonthlyCategories(project, listOf(water.copy(archived = true), water.copy(projectId = "other")), emptyList(), month))
        assertEquals(listOf(water), missingMonthlyCategories(project, listOf(water), emptyList(), month))
    }

    @Test fun `renames retain presence while removing or recategorizing a cost restores missing status`() {
        val renamed = water.copy(name = "Water and sewage")
        assertEquals(emptyList<ProjectCategory>(), missingMonthlyCategories(project, listOf(renamed), listOf(cost), month))
        assertEquals(listOf(renamed), missingMonthlyCategories(project, listOf(renamed), emptyList(), month))
        assertEquals(listOf(renamed), missingMonthlyCategories(project, listOf(renamed), listOf(cost.copy(categoryId = loans.id)), month))
    }

    @Test fun `december costs do not satisfy january`() {
        val december = cost.copy(date = LocalDate.of(2026, 12, 31))
        assertEquals(emptyList<ProjectCategory>(), missingMonthlyCategories(project, listOf(water), listOf(december), YearMonth.of(2026, 12)))
        assertEquals(listOf(water), missingMonthlyCategories(project, listOf(water), listOf(december), YearMonth.of(2027, 1)))
    }

    @Test fun `unpaid and reimbursable expenses count without checking reimbursement`() {
        val reimbursable = cost.copy(id = "reimbursable", categoryId = internet.id, transactionId = tx.id, reimbursable = true)
        val book = ProjectBook(listOf(project), categories, listOf(cost, reimbursable), listOf(tx))
        assertEquals(emptyList<ProjectCategory>(), missingMonthlyCategories(project, categories, book.costsFor(project.id), month))
    }

    @Test fun `deleted income and transfer links do not satisfy category`() {
        val linked = cost.copy(transactionId = tx.id, unpaid = false)
        val book = ProjectBook(listOf(project), listOf(water), listOf(linked), listOf(tx))
        assertEquals(emptyList<ProjectCategory>(), missingMonthlyCategories(project, book.categories, book.costsFor(project.id), month))
        listOf(emptyList(), listOf(tx.copy(type = TransactionType.INCOME)), listOf(tx.copy(type = TransactionType.TRANSFER, toAccountId = "other"))).forEach { transactions ->
            assertEquals(listOf(water), missingMonthlyCategories(project, book.categories, book.copy(transactions = transactions).costsFor(project.id), month))
        }
    }

    @Test fun `linked expense edits keep saved project month until reviewed`() {
        val linked = cost.copy(transactionId = tx.id, unpaid = false)
        val book = ProjectBook(listOf(project), listOf(water), listOf(linked), listOf(tx.copy(date = day.plusMonths(1))))
        assertTrue(book.needsReview(linked))
        assertEquals(emptyList<ProjectCategory>(), missingMonthlyCategories(project, book.categories, book.costsFor(project.id), month))
        assertEquals(listOf(water), missingMonthlyCategories(project, book.categories, book.costsFor(project.id), month.plusMonths(1)))
    }

    @Test fun `status uses selected locale and category names`() {
        val labels = mapOf(R.string.project_monthly_expenses to "Monthly expenses", R.string.project_monthly_missing to "Missing: %1\$s", R.string.project_monthly_complete to "All monthly expenses recorded")
        assertEquals("Monthly expenses · November 2026: Missing: Internet, Water", monthlyExpenseStatus(month, listOf(internet, water), Locale.ENGLISH, labels::getValue))
        val serbian = mapOf(R.string.project_monthly_expenses to "Mesečni troškovi", R.string.project_monthly_missing to "Nedostaje: %1\$s", R.string.project_monthly_complete to "Svi mesečni troškovi su evidentirani")
        assertEquals("Mesečni troškovi · Novembar 2026: Svi mesečni troškovi su evidentirani", monthlyExpenseStatus(month, emptyList(), Locale.forLanguageTag("sr-Latn"), serbian::getValue))
    }
}
