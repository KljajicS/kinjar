package app.kinjar.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.serialization.Serializable

private val bankZone = ZoneId.of("Europe/Belgrade")

fun LocalDateTime.bankTimestamp(): Long? = bankZone.rules.getValidOffsets(this).singleOrNull()?.let {
    toInstant(it).toEpochMilli()
}

fun bankDate(timestamp: Long): LocalDate = Instant.ofEpochMilli(timestamp).atZone(bankZone).toLocalDate()

enum class BankBalanceAction { VERIFY, ALREADY_INCLUDED, COUNT_SEPARATELY }

@Serializable
data class CoveredBankMovement(
    val transactionId: String,
    @Serializable(with = LocalDateSerializer::class) val date: LocalDate,
    val movement: Money,
) {
    init { require(transactionId.length in 1..128 && !movement.isZero) }

    fun matches(accountId: String, t: Transaction?): Boolean =
        t?.id == transactionId && t.date == date && t.bankMovement(accountId) == movement
}

fun Transaction.coveredBankMovement(accountId: String): CoveredBankMovement? =
    bankMovement(accountId)?.let { CoveredBankMovement(id, date, it) }

@Serializable
data class BankBalanceCheckpoint(
    val accountId: String,
    val asOf: Long,
    val balance: Money,
    val covered: List<CoveredBankMovement>,
) {
    init {
        require(accountId.length in 1..128 && asOf >= 0L)
        require(covered.size <= 2_000 && covered.map { it.transactionId }.toSet().size == covered.size)
        require(covered.all { it.movement.currency == balance.currency })
    }

    fun balanceOffset(): Money = balance - covered.fold(Money.zero(balance.currency)) { total, row -> total + row.movement }

    fun canReplace(previous: BankBalanceCheckpoint?): Boolean = previous == null ||
        asOf > previous.asOf || asOf == previous.asOf && balance == previous.balance && covered.containsAll(previous.covered)
}

@Serializable
data class BankMovementEvidence(val accountId: String, val asOf: Long, val covered: CoveredBankMovement) {
    init { require(accountId.length in 1..128 && asOf >= 0L) }
}
