package app.kinjar.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import java.time.LocalDate
import java.time.YearMonth

@Serializable
data class ProjectReimbursement(
    val person: String,
    val opening: Money,
    @Serializable(with = LocalDateSerializer::class) val start: LocalDate,
    val enabled: Boolean = true,
) {
    init {
        require(person.isNotBlank() && person.length <= 100)
        require(start.year in 1900..2100 && start.dayOfMonth == 1)
    }
}

@Serializable
data class ProjectPayment(
    val original: Money,
    val converted: Money,
    @Serializable(with = LocalDateSerializer::class) val date: LocalDate,
    val transactionId: String? = null,
    val ratesToRsd: Map<Currency, String> = emptyMap(),
    @Serializable(with = LocalDateSerializer::class) val rateDate: LocalDate = date,
    val rateSource: String = "same-currency",
) {
    init {
        require(!original.isNegative && !original.isZero && !converted.isNegative)
        require(transactionId == null || transactionId.isNotBlank())
        require(date.year in 1900..2100 && rateDate.year >= 1900 && rateDate <= date)
        require(rateSource.isNotBlank() && rateSource.length <= 200 && ratesToRsd.values.all { parseRate(it) != null })
        require(Rates(ratesToRsd.mapValues { it.value.toBigDecimal() }).convert(original, converted.currency) == converted)
    }

    fun matches(transaction: Transaction?, type: TransactionType): Boolean = transaction != null &&
        transaction.type == type && transaction.money == original && transaction.date == date
}

@Serializable
data class ProjectSettlement(val id: String, val payment: ProjectPayment, val allocations: Map<String, Money>, val type: TransactionType = TransactionType.EXPENSE) {
    init {
        require(id.isNotBlank() && payment.transactionId != null)
        require(type == TransactionType.EXPENSE || type == TransactionType.INCOME)
        require(allocations.size in 1..200 && allocations.keys.none { it.isBlank() })
        require(allocations.values.all { it.currency == payment.converted.currency && !it.isNegative && !it.isZero })
        require(type == TransactionType.INCOME || allocated == payment.converted)
    }

    val allocated: Money get() = allocations.values.fold(Money.zero(payment.converted.currency), Money::plus)
}

@Serializable
data class ProjectRepayment(val id: String, val payment: ProjectPayment, val note: String = "") {
    init { require(id.isNotBlank() && note.length <= 1_000) }
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class ProjectFinance(
    val projectId: String,
    val reimbursement: ProjectReimbursement? = null,
    val settlements: List<ProjectSettlement> = emptyList(),
    val repayments: List<ProjectRepayment> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val openingCredit: Money? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val creditCosts: Map<String, Money> = emptyMap(),
) {
    init {
        require(projectId.isNotBlank() && settlements.size + repayments.size + creditCosts.size <= 1_000)
        require(openingCredit?.isNegative != true)
        require(creditCosts.keys.none { it.isBlank() })
        require(creditCosts.values.all { !it.isNegative && !it.isZero })
        require(settlements.map { it.id }.distinct().size == settlements.size)
        require(repayments.map { it.id }.distinct().size == repayments.size)
        val costIds = settlements.flatMap { it.allocations.keys }
        require(costIds.distinct().size == costIds.size) { "Cost already settled" }
        val txIds = payments().mapNotNull { it.transactionId }
        require(txIds.size <= 400) { "Project linked payment limit reached" }
        require(txIds.distinct().size == txIds.size) { "Payment already linked" }
        require(repayments.isEmpty() || reimbursement != null)
    }

    fun payments(): List<ProjectPayment> = settlements.map { it.payment } + repayments.map { it.payment }

    val hasCredit: Boolean get() = openingCredit != null || creditCosts.isNotEmpty()
}

fun projectCreditBalance(finance: ProjectFinance, costs: List<ProjectCost>, transactions: Map<String, Transaction>, currency: Currency): Money {
    return projectUnallocatedBalance(finance, costs, transactions, currency) - reimbursementOutstanding(finance, costs, transactions, currency)
}

fun reimbursementOutstanding(finance: ProjectFinance, costs: List<ProjectCost>, transactions: Map<String, Transaction>, currency: Currency): Money =
    costs.filter { cost -> cost.projectId == finance.projectId && cost.reimbursable && cost.unpaid &&
        transactions[cost.transactionId]?.type == TransactionType.EXPENSE && finance.settlements.none {
            cost.id in it.allocations && transactions[it.payment.transactionId]?.type == it.type
        }
    }.fold(Money.zero(currency)) { total, cost -> total + cost.converted - (finance.creditCosts[cost.id] ?: Money.zero(currency)) }

fun projectUnallocatedBalance(finance: ProjectFinance, costs: List<ProjectCost>, transactions: Map<String, Transaction>, currency: Currency): Money {
    val originals = costs.associateBy { it.id }
    val spent = finance.creditCosts.values.fold(Money.zero(currency), Money::plus)
    return finance.settlements.filter {
        it.type == TransactionType.INCOME && transactions[it.payment.transactionId]?.type == TransactionType.INCOME
    }.fold((finance.openingCredit ?: Money.zero(currency)) - spent) { balance, settlement ->
        val covered = settlement.allocations.keys.fold(Money.zero(currency)) { total, id ->
            total + requireNotNull(originals[id]).converted - (finance.creditCosts[id] ?: Money.zero(currency))
        }
        balance + settlement.payment.converted - covered
    }
}

@Serializable @SerialName("ProjectFinanceSaved")
data class ProjectFinanceSaved(val finance: ProjectFinance) : Event

data class ProjectMonthBalance(val opening: Money, val bills: Money, val received: Money, val closing: Money, val hasBills: Boolean) {
    fun laterApplied(later: Money): Money {
        require(later.currency == closing.currency && !later.isNegative)
        return Money.of(closing.amount.max(java.math.BigDecimal.ZERO).min(later.amount), closing.currency)
    }
}

fun projectMonthBalance(config: ProjectReimbursement, costs: List<ProjectCost>, repayments: List<ProjectRepayment>, month: YearMonth): ProjectMonthBalance {
    val currency = config.opening.currency
    fun billsUntil(end: LocalDate) = costs.filter { it.date >= config.start && it.date <= end }
        .fold(Money.zero(currency)) { total, cost -> total + cost.converted }
    fun receivedUntil(end: LocalDate) = repayments.filter { it.payment.date >= config.start && it.payment.date <= end }
        .fold(Money.zero(currency)) { total, entry -> total + entry.payment.converted }
    val before = month.atDay(1).minusDays(1)
    val end = month.atEndOfMonth()
    val opening = config.opening + billsUntil(before) - receivedUntil(before)
    val bills = billsUntil(end) - billsUntil(before)
    val received = receivedUntil(end) - receivedUntil(before)
    return ProjectMonthBalance(opening, bills, received, opening + bills - received, costs.any { YearMonth.from(it.date) == month && it.date >= config.start })
}
