package app.kinjar.ui.projects

import app.kinjar.R
import app.kinjar.data.ProjectBook
import app.kinjar.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class ProjectReportTest {
    private val day = LocalDate.of(2026, 11, 15)
    private val project = Project("p", "Računi", Currency.RSD)
    private fun rsd(value: String) = Money.of(value, Currency.RSD)
    private val old = ProjectCost("old", "p", "electric", rsd("5000"), rsd("5000"), day.minusMonths(1), "Old bill", unpaid = true)
    private val electricity = old.copy(id = "electricity", date = day, note = "Do not include item details")
    private val water = electricity.copy(id = "water", categoryId = "water", original = rsd("3000"), converted = rsd("3000"))
    private fun book(received: String = "7000"): ProjectBook {
        val tx = Transaction("income", TransactionType.INCOME, rsd(received), "bank", null, null, old.date, "", "", "member", TransactionSource.MANUAL)
        val settlement = ProjectSettlement("s", ProjectPayment(tx.money, tx.money, tx.date, tx.id), mapOf(old.id to old.converted), TransactionType.INCOME)
        return ProjectBook(listOf(project), listOf(ProjectCategory("electric", "p", "Struja", "#123456"), ProjectCategory("water", "p", "Voda", "#123456")),
            listOf(old, electricity, water), listOf(tx), listOf(ProjectFinance("p", settlements = listOf(settlement))))
    }
    private fun report(book: ProjectBook) = projectReport(project, book, day, Locale.ENGLISH) {
        when (it) {
            R.string.project_report_title -> "Izveštaj"
            R.string.project_report_adjustment -> "Korekcija"
            R.string.project_report_prepaid -> "plaćeno ranije"
            R.string.project_report_previous_debt -> "dug od ranije"
            R.string.project_report_to_send -> "Ukupno za plaćanje"
            R.string.project_monthly_expenses -> "Monthly expenses"
            R.string.project_monthly_complete -> "All monthly expenses recorded"
            R.string.project_monthly_missing -> "Missing: %1\$s"
            else -> it.toString()
        }
    }

    @Test fun `checklist uses all recorded costs without changing payable amounts`() {
        val original = book().copy(costs = listOf(old, water.copy(unpaid = false)))
        val enabled = original.copy(categories = original.categories.map { it.copy(monthlyExpenseExpected = true) })
        assertEquals(report(original) + "\nMonthly expenses · November 2026: Missing: Struja\n", report(enabled))
        val complete = enabled.copy(costs = enabled.costs + electricity)
        assertTrue(report(complete).endsWith("Monthly expenses · November 2026: All monthly expenses recorded\n"))
    }

    @Test fun `report checklist excludes hidden and archived categories`() {
        val original = book().copy(costs = emptyList(), finances = emptyList())
        val enabled = original.copy(categories = original.categories.map { it.copy(monthlyExpenseExpected = true, excludedFromReport = it.id == "water") })
        val text = report(enabled)
        assertTrue(text.endsWith("Monthly expenses · November 2026: Missing: Struja\n"))
        assertFalse(text.contains("Voda"))
        assertFalse(report(enabled.copy(categories = enabled.categories.map { it.copy(archived = true) })).contains("Monthly expenses"))
        assertFalse(report(enabled.copy(categories = enabled.categories.map { it.copy(excludedFromReport = true) })).contains("Monthly expenses"))
        val archived = projectReport(project.copy(archived = true), enabled, day, Locale.ENGLISH) { "label-$it" }
        assertFalse(archived.contains("label-${R.string.project_monthly_expenses}"))
    }

    @Test fun `category report deducts previous overpayment exactly once`() {
        assertEquals("Izveštaj\n2026-11-15\n\n• Struja: 5000 RSD\n• Voda: 3000 RSD\n\nKorekcija: -2000 RSD (plaćeno ranije)\n\nUkupno za plaćanje: 6000 RSD\n", report(book()))
    }

    @Test fun `previous shortfall adds to amount due`() {
        val report = report(book("4000"))
        assertTrue(report.contains("Korekcija: +1000 RSD (dug od ranije)"))
        assertTrue(report.contains("Ukupno za plaćanje: 9000 RSD"))
    }

    @Test fun `credit exceeding bills leaves zero due and remaining credit`() {
        val report = report(book("15000"))
        assertTrue(report.contains("Ukupno za plaćanje: 0 RSD"))
        assertTrue(report.contains("${R.string.project_report_credit_remaining}: 2000 RSD"))
    }

    @Test fun `deleted income restores bill and warns about review`() {
        val report = report(book().copy(transactions = emptyList()))
        assertTrue(report.contains("Struja: 10000 RSD"))
        assertTrue(report.contains("Ukupno za plaćanje: 13000 RSD"))
        assertFalse(report.contains("Korekcija"))
        assertTrue(report.contains(R.string.project_report_review.toString()))
    }

    @Test fun `no adjustment omits correction and unrelated projects`() {
        val book = book("5000")
        val report = report(book.copy(categories = emptyList(), costs = book.costs + electricity.copy(id = "other", projectId = "other")))
        assertTrue(report.contains("${R.string.project_uncategorized}: 8000 RSD"))
        assertTrue(report.contains("Ukupno za plaćanje: 8000 RSD"))
        assertFalse(report.contains("Korekcija"))
    }

    @Test fun `reimbursement includes vendor paid bills and subtracts receipts once`() {
        val config = ProjectReimbursement("Dad", rsd("1000"), day.withDayOfMonth(1))
        val repayment = ProjectRepayment("receipt", ProjectPayment(rsd("3000"), rsd("3000"), day))
        val book = book().copy(costs = listOf(electricity.copy(unpaid = false), water), transactions = emptyList(),
            finances = listOf(ProjectFinance("p", reimbursement = config, repayments = listOf(repayment))))
        assertTrue(report(book).contains("Korekcija: -2000 RSD (plaćeno ranije)"))
        assertTrue(report(book).contains("Ukupno za plaćanje: 6000 RSD"))
    }

    @Test fun `partial credit reports only remainder while project totals keep whole bill`() {
        val cost = electricity.copy(original = rsd("9000"), converted = rsd("9000"))
        val finance = ProjectFinance("p", openingCredit = rsd("7000"), creditCosts = mapOf(cost.id to rsd("7000")))
        val book = book().copy(costs = listOf(cost), transactions = emptyList(), finances = listOf(finance))
        assertTrue(report(book).contains("Struja: 2000 RSD"))
        assertTrue(report(book).contains("Ukupno za plaćanje: 2000 RSD"))
        assertFalse(report(book).contains("Korekcija"))
        assertEquals(rsd("9000"), book.summary(project).total)
        assertEquals(rsd("2000"), book.summary(project).unpaid)
    }

    @Test fun `tracked reimbursement debt is not added to unpaid amount twice`() {
        val tx = Transaction("bill", TransactionType.EXPENSE, rsd("9000"), "bank", null, null, day, "", "", "member", TransactionSource.RECEIPT)
        val cost = electricity.copy(original = tx.money, converted = tx.money, transactionId = tx.id, reimbursable = true)
        val base = book().copy(costs = listOf(cost), transactions = listOf(tx), finances = emptyList())
        assertEquals(rsd("-9000"), base.incomeBalance(project.id))
        assertTrue(report(base).contains("Ukupno za plaćanje: 9000 RSD"))
        assertFalse(report(base).contains("Korekcija"))
        val partial = base.copy(finances = listOf(ProjectFinance(project.id, openingCredit = rsd("7000"), creditCosts = mapOf(cost.id to rsd("7000")))))
        assertEquals(rsd("-2000"), partial.incomeBalance(project.id))
        assertTrue(report(partial).contains("Ukupno za plaćanje: 2000 RSD"))
        assertFalse(report(partial).contains("Korekcija"))
    }

    @Test fun `excluded category removes its bills and total but preserves project credit and spending`() {
        val original = book()
        val filtered = original.copy(categories = original.categories.map { it.copy(excludedFromReport = it.id == "water") })
        val text = report(filtered)
        assertFalse(text.contains("Voda"))
        assertTrue(text.contains("Struja: 5000 RSD"))
        assertTrue(text.contains("Korekcija: -2000 RSD"))
        assertTrue(text.contains("Ukupno za plaćanje: 3000 RSD"))
        assertEquals(original.summary(project).total, filtered.summary(project).total)
        assertEquals(original.summary(project).unpaid, filtered.summary(project).unpaid)
        assertEquals(listOf("electric"), filtered.summary(project).byCategory.map { it.category?.id })
        assertEquals(original.incomeBalance(project.id), filtered.incomeBalance(project.id))
        assertEquals(report(original), report(filtered.copy(categories = original.categories)))
    }

    @Test fun `archived exclusions stay excluded while uncategorized costs stay included`() {
        val original = book("5000")
        val filtered = original.copy(
            categories = original.categories.map { it.copy(archived = true, excludedFromReport = true) },
            costs = original.costs + water.copy(id = "uncategorized", categoryId = null),
        )
        val text = report(filtered)
        assertFalse(text.contains("Struja"))
        assertFalse(text.contains("Voda"))
        assertTrue(text.contains("${R.string.project_uncategorized}: 3000 RSD"))
        assertTrue(text.contains("Ukupno za plaćanje: 3000 RSD"))
        val empty = report(filtered.copy(costs = original.costs))
        assertFalse(empty.contains("• "))
        assertTrue(empty.contains("Ukupno za plaćanje: 0 RSD"))
    }

    @Test fun `excluded reimbursement bills preserve project wide receipts and opening debt`() {
        val original = book().copy(costs = listOf(electricity.copy(unpaid = false), water), transactions = emptyList(),
            finances = listOf(ProjectFinance("p", reimbursement = ProjectReimbursement("Dad", rsd("1000"), day.withDayOfMonth(1)),
                repayments = listOf(ProjectRepayment("receipt", ProjectPayment(rsd("3000"), rsd("3000"), day))))))
        val text = report(original.copy(categories = original.categories.map { it.copy(excludedFromReport = it.id == "water") }))
        assertFalse(text.contains("Voda"))
        assertTrue(text.contains("Struja: 5000 RSD"))
        assertTrue(text.contains("Korekcija: -2000 RSD"))
        assertTrue(text.contains("Ukupno za plaćanje: 3000 RSD"))
    }

    @Test fun `excluded partially credited reimbursement does not leak back through adjustment`() {
        val tx = Transaction("bill", TransactionType.EXPENSE, rsd("9000"), "bank", null, null, day, "", "", "member", TransactionSource.RECEIPT)
        val cost = electricity.copy(original = tx.money, converted = tx.money, transactionId = tx.id, reimbursable = true)
        val original = book().copy(costs = listOf(cost, water), transactions = listOf(tx),
            finances = listOf(ProjectFinance("p", openingCredit = rsd("7000"), creditCosts = mapOf(cost.id to rsd("7000")))))
        val text = report(original.copy(categories = original.categories.map { it.copy(excludedFromReport = it.id == "electric") }))
        assertFalse(text.contains("Struja"))
        assertFalse(text.contains("Korekcija"))
        assertTrue(text.contains("Ukupno za plaćanje: 3000 RSD"))
    }
}
