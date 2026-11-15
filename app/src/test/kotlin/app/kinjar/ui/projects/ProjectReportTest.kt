package app.kinjar.ui.projects

import app.kinjar.R
import app.kinjar.data.ProjectBook
import app.kinjar.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

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
    private fun report(book: ProjectBook) = projectReport(project, book, day) {
        when (it) {
            R.string.project_report_title -> "Izveštaj"
            R.string.project_report_adjustment -> "Korekcija"
            R.string.project_report_prepaid -> "plaćeno ranije"
            R.string.project_report_previous_debt -> "dug od ranije"
            R.string.project_report_to_send -> "Ukupno za plaćanje"
            else -> it.toString()
        }
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
}
