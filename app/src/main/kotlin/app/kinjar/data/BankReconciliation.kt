package app.kinjar.data

import app.kinjar.data.db.Bank_alert
import app.kinjar.data.db.KinjarDb
import app.kinjar.domain.BankApp
import app.kinjar.domain.BankBalanceAction
import app.kinjar.domain.BankBalanceCheckpoint
import app.kinjar.domain.BankBalanceCheckpointSet
import app.kinjar.domain.BankMovementEvidence
import app.kinjar.domain.BankMovementVerified
import app.kinjar.domain.BankNotificationParser
import app.kinjar.domain.Event
import app.kinjar.domain.EventJson
import app.kinjar.domain.Money
import app.kinjar.domain.Transaction
import app.kinjar.domain.TransactionType
import app.kinjar.domain.bankDate
import app.kinjar.domain.bankTimestamp
import app.kinjar.domain.coveredBankMovement

internal fun KinjarDb.bankCheckpoint(accountId: String): BankBalanceCheckpoint? =
    bankReconciliationQueries.checkpoint(accountId).executeAsOneOrNull()?.let { EventJson.decodeFromString(it) }

internal fun KinjarDb.bankReviewEvents(
    alert: Bank_alert, t: Transaction, accountId: String?, action: BankBalanceAction?,
): List<Event> {
    val evidence = accountId?.let { bankEvidence(alert, t, it) }
    if (evidence == null) {
        require(action == null) { "Bank details no longer match the reviewed transaction" }
        return emptyList()
    }
    val events = mutableListOf<Event>(BankMovementVerified(evidence))
    val checkpoint = bankCheckpoint(evidence.accountId)
    val earlier = checkpoint != null && evidence.asOf < checkpoint.asOf
    if (action == BankBalanceAction.COUNT_SEPARATELY) {
        require(earlier) { "Verified bank balance changed; review again" }
        return events
    }
    if (action == null) {
        require(!earlier || checkpoint?.covered?.contains(evidence.covered) == true) { "Confirm whether the older bank entry was included" }
        return events
    }
    val transactions = txQueries.all().executeAsList().map { it.toDomain() }.associateBy { it.id } + (t.id to t)
    val next = when (action) {
        BankBalanceAction.ALREADY_INCLUDED -> {
            requireNotNull(checkpoint)
            require(evidence.asOf < checkpoint.asOf || checkpoint.covered.contains(evidence.covered)) { "Bank entry is not earlier than the saved balance" }
            require(checkpoint.covered.all { it.matches(evidence.accountId, transactions[it.transactionId]) }) { "Review the changed bank balance first" }
            checkpoint.copy(covered = (checkpoint.covered + evidence.covered).distinctBy { it.transactionId }.sortedBy { it.transactionId })
        }
        BankBalanceAction.VERIFY -> {
            require(checkpoint == null || evidence.asOf > checkpoint.asOf) { "A newer bank balance is already saved" }
            val parsed = BankNotificationParser.parse(BankApp.valueOf(alert.bank), alert.title, alert.body)
            val reported = requireNotNull(parsed.balances.singleOrNull { !it.available && it.currency == evidence.covered.movement.currency })
            val known = bankReconciliationQueries.evidenceForAccount(evidence.accountId).executeAsList()
                .map { EventJson.decodeFromString<BankMovementEvidence>(it) }.associateBy { it.covered.transactionId } +
                (t.id to evidence)
            val priorCoverage = checkpoint?.covered.orEmpty().associateBy { it.transactionId }
            val day = bankDate(evidence.asOf)
            val covered = transactions.values.mapNotNull { transaction ->
                val movement = transaction.coveredBankMovement(evidence.accountId) ?: return@mapNotNull null
                val verified = known[transaction.id]?.takeIf { it.covered == movement }
                when {
                    transaction.id == t.id -> movement
                    verified != null -> {
                        require(verified.asOf != evidence.asOf) { "Bank movements have the same timestamp" }
                        movement.takeIf { verified.asOf < evidence.asOf }
                    }
                    priorCoverage[transaction.id] == movement -> movement
                    movement.date < day -> movement
                    movement.date > day -> null
                    else -> error("Review undated bank activity before verifying this balance")
                }
            }.sortedBy { it.transactionId }
            BankBalanceCheckpoint(evidence.accountId, evidence.asOf, Money.of(reported.amount, reported.currency), covered)
        }
        BankBalanceAction.COUNT_SEPARATELY -> error("Handled before reconciliation")
    }
    if (next != checkpoint) events += BankBalanceCheckpointSet(evidence.accountId, next)
    return events
}

private fun KinjarDb.bankEvidence(alert: Bank_alert, t: Transaction, accountId: String): BankMovementEvidence? {
    if (bankInboxQueries.bindings().executeAsList().none { it.account_id == accountId && it.bank == alert.bank }) return null
    val parsed = BankNotificationParser.parse(BankApp.valueOf(alert.bank), alert.title, alert.body)
    val asOf = parsed.transactionTime?.bankTimestamp() ?: return null
    val covered = t.coveredBankMovement(accountId) ?: return null
    val observed = parsed.money ?: return null
    val expected = when (parsed.direction) {
        TransactionType.EXPENSE -> -observed
        TransactionType.INCOME -> observed
        TransactionType.TRANSFER -> if (parsed.cashWithdrawal) -observed else return null
        else -> return null
    }
    return if (covered.movement == expected) BankMovementEvidence(accountId, asOf, covered) else null
}
