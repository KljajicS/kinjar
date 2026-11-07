package app.kinjar.data

import app.kinjar.data.db.KinjarDb
import kotlinx.serialization.encodeToString
import app.kinjar.domain.EventJson
import app.kinjar.domain.AccountArchived
import app.kinjar.domain.AccountCreated
import app.kinjar.domain.AccountUpdated
import app.kinjar.domain.AccountsReordered
import app.kinjar.domain.BillSkipped
import app.kinjar.domain.GoalsReordered
import app.kinjar.domain.BudgetRemoved
import app.kinjar.domain.BudgetSet
import app.kinjar.domain.CategoryArchived
import app.kinjar.domain.CategoryCreated
import app.kinjar.domain.CategoryUpdated
import app.kinjar.domain.GoalAllocated
import app.kinjar.domain.GoalAllocationDeleted
import app.kinjar.domain.GoalArchived
import app.kinjar.domain.GoalSet
import app.kinjar.domain.MemberRenamed
import app.kinjar.domain.EventRecord
import app.kinjar.domain.ExchangeRateOverrideSet
import app.kinjar.domain.MemberCreated
import app.kinjar.domain.RecurringRuleArchived
import app.kinjar.domain.RecurringRuleSet
import app.kinjar.domain.TransactionAdded
import app.kinjar.domain.TransactionDeleted
import app.kinjar.domain.TransactionEdited
import app.kinjar.domain.LoanCreated
import app.kinjar.domain.LoanUpdated
import app.kinjar.domain.LoanArchived
import app.kinjar.domain.LoanPaymentAdded
import app.kinjar.domain.LoanPaymentUpdated
import app.kinjar.domain.LoanPaymentRemoved
import app.kinjar.domain.LoanPrincipalAdjusted
import app.kinjar.domain.LoanReceiptLinked

// Replay in log order so older edits cannot overwrite newer changes.
class Projector(private val db: KinjarDb) {

    fun apply(record: EventRecord) {
        val at = record.occurredAt.toEpochMilli()
        when (val e = record.event) {
            is LoanCreated -> db.loanQueries.insertIgnore(e.loan.id, EventJson.encodeToString(e.loan))
            is LoanUpdated -> db.loanQueries.update(EventJson.encodeToString(e.loan), e.loan.id)
            is LoanArchived -> db.loanQueries.archive(e.loanId)
            is LoanPaymentAdded -> with(e.payment) {
                db.loanPaymentQueries.insertIgnore(id, loanId, transactionId, EventJson.encodeToString(this))
            }
            is LoanPaymentUpdated -> db.loanPaymentQueries.update(EventJson.encodeToString(e.payment), e.payment.id)
            is LoanPaymentRemoved -> {
                db.loanPaymentQueries.insertTombstone(e.paymentId)
                db.loanPaymentQueries.remove(e.paymentId)
            }
            is LoanPrincipalAdjusted -> with(e.adjustment) {
                db.loanAdjustmentQueries.insertIgnore(id, loanId, EventJson.encodeToString(this))
            }
            is LoanReceiptLinked -> db.loanReceiptQueries.insertIgnore(e.id, e.loanId, e.transactionId)
            is ExchangeRateOverrideSet -> db.exchangeRateOverrideQueries.upsert(e.currency.name, e.rate, at)
            is MemberCreated -> db.memberQueries.insertIgnore(e.member.id, e.member.displayName, e.member.deviceId)
            is MemberRenamed -> db.memberQueries.rename(e.displayName, e.memberId)
            is AccountCreated -> db.accountQueries.insertIgnore(
                e.account.id, e.account.name, e.account.currency.name, e.account.kind.name, if (e.account.archived) 1 else 0,
            )
            // Edits never change archived: a stale edit from the other phone must not bring a deleted row back (ADR 8).
            is AccountUpdated -> db.accountQueries.update(e.account.name, e.account.currency.name, e.account.kind.name, e.account.id)
            is AccountArchived -> db.accountQueries.archive(e.accountId)
            is AccountsReordered -> {
                val existing = db.accountQueries.all().executeAsList().map { it.id }
                (e.accountIds.filter { it in existing } + existing).distinct()
                    .forEachIndexed { i, id -> db.accountQueries.setPosition(i.toLong(), id) }
            }
            is CategoryCreated -> db.categoryQueries.insertIgnore(
                e.category.id, e.category.name, e.category.color, if (e.category.archived) 1 else 0,
            )
            is CategoryUpdated -> db.categoryQueries.update(e.category.name, e.category.color, e.category.id)
            is CategoryArchived -> db.categoryQueries.archive(e.categoryId)
            is TransactionAdded -> with(e.transaction) {
                e.receipt?.let { receipt ->
                    require(id == receipt.transactionId && source == app.kinjar.domain.TransactionSource.RECEIPT)
                    if (db.receiptQueries.byId(receipt.id).executeAsOneOrNull() != null) {
                        if (db.receiptQueries.activeById(receipt.id).executeAsOneOrNull() != null ||
                            e.receiptDeletionId == null || e.receiptDeletionId != latestReceiptDeletion(db, id)) return
                    } else {
                        db.receiptQueries.insert(receipt.id, id, receipt.issuer, receipt.invoiceNumber,
                            receipt.fiscalUrl, receipt.merchant, receipt.date.toString(),
                            receipt.total.amount.toPlainString(), EventJson.encodeToString(receipt.items))
                    }
                }
                db.txQueries.insertIgnore(
                    id, type.name, money.amount.toPlainString(), money.currency.name, accountId, toAccountId, categoryId,
                    date.toString(), note, merchant, paidBy, source.name, at,
                )
            }
            is TransactionEdited -> with(e.transaction) {
                db.txQueries.update(
                    type.name, money.amount.toPlainString(), money.currency.name, accountId, toAccountId, categoryId,
                    date.toString(), note, merchant, paidBy, source.name, at, id,
                )
            }
            is TransactionDeleted -> {
                db.receiptDeletionQueries.save(e.transactionId, record.id)
                db.txQueries.delete(e.transactionId)
            }
            is BudgetSet -> db.budgetQueries.upsert(
                e.budget.categoryId, e.budget.month.toString(), e.budget.money.amount.toPlainString(), e.budget.money.currency.name,
            )
            // Legacy budget events still replay so old logs rebuild unchanged; a zero row was the removal tombstone.
            is BudgetRemoved -> db.budgetQueries.upsert(e.categoryId, e.month.toString(), "0.00", "RSD")
            is RecurringRuleSet -> with(e.rule) {
                db.recurringRuleQueries.insertIgnore(id, name, money.amount.toPlainString(), money.currency.name, accountId, categoryId, cadence.name, firstDue.toString())
                db.recurringRuleQueries.update(name, money.amount.toPlainString(), money.currency.name, accountId, categoryId, cadence.name, firstDue.toString(), id)
            }
            is RecurringRuleArchived -> db.recurringRuleQueries.archive(e.ruleId)
            is BillSkipped -> db.billSkipQueries.insertIgnore(e.ruleId, e.period)
            is GoalSet -> with(e.goal) {
                db.goalQueries.insertIgnore(id, name, target.amount.toPlainString(), monthly.amount.toPlainString(), target.currency.name)
                db.goalQueries.update(name, target.amount.toPlainString(), monthly.amount.toPlainString(), target.currency.name, id)
            }
            is GoalArchived -> db.goalQueries.archive(e.goalId)
            is GoalsReordered -> {
                val existing = db.goalQueries.all().executeAsList().map { it.id }
                (e.goalIds.filter { it in existing } + existing).distinct()
                    .forEachIndexed { i, id -> db.goalQueries.setPosition(i.toLong(), id) }
            }
            is GoalAllocated -> with(e.allocation) {
                db.goalAllocationQueries.insertIgnore(id, goalId, money.amount.toPlainString(), money.currency.name, date.toString())
            }
            is GoalAllocationDeleted -> db.goalAllocationQueries.delete(e.allocationId)
        }
    }

    fun clear() {
        db.loanPaymentQueries.deleteAll()
        db.loanReceiptQueries.deleteAll()
        db.loanAdjustmentQueries.deleteAll()
        db.loanQueries.deleteAll()
        db.exchangeRateOverrideQueries.deleteAll()
        db.receiptQueries.deleteAll()
        db.receiptDeletionQueries.deleteAll()
        db.goalAllocationQueries.deleteAll()
        db.goalQueries.deleteAll()
        db.billSkipQueries.deleteAll()
        db.recurringRuleQueries.deleteAll()
        db.budgetQueries.deleteAll()
        db.txQueries.deleteAll()
        db.accountQueries.deleteAll()
        db.categoryQueries.deleteAll()
        db.memberQueries.deleteAll()
    }

    companion object {
        /** Bump when a projector change must reach existing installs; the app rebuilds once on mismatch. */
        const val VERSION = 7
    }
}

internal fun latestReceiptDeletion(db: KinjarDb, transactionId: String): String? =
    db.receiptDeletionQueries.byTransaction(transactionId).executeAsOneOrNull()
