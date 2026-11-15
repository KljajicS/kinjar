package app.kinjar.ui.projects

import app.kinjar.R
import app.kinjar.data.ProjectBook
import app.kinjar.domain.Money
import app.kinjar.domain.Project
import app.kinjar.domain.TransactionType
import app.kinjar.domain.reimbursementOutstanding
import java.time.LocalDate

fun projectReport(project: Project, book: ProjectBook, today: LocalDate, label: (Int) -> String): String = buildString {
    val categories = book.categories.filter { it.projectId == project.id }.associateBy { it.id }
    val costs = includedCosts(book.costsFor(project.id), categories.values.toList())
    val finance = book.finance(project.id)
    val config = finance.reimbursement
    val pending = if (config == null) costs.filter { it.unpaid }.map { it.copy(converted = book.unpaidAmount(it), original = book.unpaidAmount(it), ratesToRsd = emptyMap(), rateSource = "same-currency", rateDate = it.date) }
        else costs.filter { it.date >= config.start }
    val correction = if (config == null) -book.incomeBalance(project.id) - reimbursementOutstanding(finance, book.costs, book.transactions.associateBy { it.id }, project.currency) else {
        val receipts = book.activeRepayments(project.id).filter { it.payment.date >= config.start }
            .fold(Money.zero(project.currency)) { total, entry -> total + entry.payment.converted }
        val income = finance.settlements.filter { it.type == TransactionType.INCOME && it.payment.date >= config.start &&
            book.transactions.any { tx -> tx.id == it.payment.transactionId && tx.type == TransactionType.INCOME } }
            .fold(Money.zero(project.currency)) { total, entry -> total + entry.payment.converted }
        config.opening - receipts - income - (finance.openingCredit ?: Money.zero(project.currency))
    }
    fun amount(value: Money) = "${value.amount.stripTrailingZeros().toPlainString()} ${value.currency}"
    appendLine(label(R.string.project_report_title))
    appendLine(today)
    appendLine()
    if (categories.values.any { it.excludedFromReport }) {
        appendLine(label(R.string.project_report_selected_categories))
        appendLine()
    }
    pending.groupBy { it.categoryId?.let(categories::get) }.entries.sortedBy { it.key?.name.orEmpty() }.forEach { (category, entries) ->
        appendLine("• ${category?.name ?: label(R.string.project_uncategorized)}: ${amount(sum(entries, project.currency))}")
    }
    if (!correction.isZero) {
        appendLine()
        val reason = label(if (correction.isNegative) R.string.project_report_prepaid else R.string.project_report_previous_debt)
        appendLine("${label(R.string.project_report_adjustment)}: ${if (correction.isNegative) "" else "+"}${amount(correction)} ($reason)")
    }
    val due = sum(pending, project.currency) + correction
    appendLine()
    appendLine("${label(R.string.project_report_to_send)}: ${amount(if (due.isNegative) Money.zero(project.currency) else due)}")
    if (due.isNegative) appendLine("${label(R.string.project_report_credit_remaining)}: ${amount(-due)}")
    if (book.costs.any { it.projectId == project.id && book.needsReview(it) } ||
        finance.repayments.any { book.paymentNeedsReview(it.payment, TransactionType.INCOME) }) {
        appendLine()
        appendLine(label(R.string.project_report_review))
    }
}
