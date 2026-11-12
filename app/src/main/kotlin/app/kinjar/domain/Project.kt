package app.kinjar.domain

import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable
data class Project(
    val id: String,
    val name: String,
    val currency: Currency = Currency.EUR,
    val budget: Money? = null,
    val sourceCategoryIds: Set<String> = emptySet(),
    val archived: Boolean = false,
) {
    init {
        require(id.isNotBlank() && name.isNotBlank() && name.length <= 200)
        require(currency in setOf(Currency.EUR, Currency.RSD))
        require(budget == null || (budget.currency == currency && !budget.isNegative && !budget.isZero))
        require(sourceCategoryIds.size <= 1_000 && sourceCategoryIds.none { it.isBlank() })
    }
}

@Serializable
data class ProjectCategory(
    val id: String,
    val projectId: String,
    val name: String,
    val color: String,
    val archived: Boolean = false,
) {
    init {
        require(id.isNotBlank() && projectId.isNotBlank() && name.isNotBlank() && name.length <= 100)
        require(color.matches(Regex("#[0-9a-fA-F]{6}")))
    }
}

@Serializable
data class ProjectCost(
    val id: String,
    val projectId: String,
    val categoryId: String?,
    val original: Money,
    val converted: Money,
    @Serializable(with = LocalDateSerializer::class) val date: LocalDate,
    val note: String,
    val transactionId: String? = null,
    val ratesToRsd: Map<Currency, String> = emptyMap(),
    @Serializable(with = LocalDateSerializer::class) val rateDate: LocalDate = date,
    val rateSource: String = "same-currency",
    val unpaid: Boolean = false,
) {
    init {
        require(id.isNotBlank() && projectId.isNotBlank())
        require(transactionId == null || transactionId.isNotBlank())
        require(!unpaid || transactionId == null) { "An unpaid cost cannot have a payment expense" }
        require(categoryId == null || categoryId.isNotBlank())
        require(!original.isNegative && !original.isZero) { "Project cost must be positive" }
        require(date.year in 1900..2100 && rateDate <= date && rateDate.year >= 1900)
        require(rateSource.isNotBlank() && rateSource.length <= 200 && note.length <= 10_000)
        require(ratesToRsd.values.all { parseRate(it) != null })
        // The converted amount may round to zero (0.01 RSD in EUR); refusing it would block linking or converting such costs.
        require(Rates(ratesToRsd.mapValues { it.value.toBigDecimal() }).convert(original, converted.currency) == converted) {
            "Project amount must match its saved conversion"
        }
    }
}
