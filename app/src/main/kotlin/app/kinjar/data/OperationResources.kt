package app.kinjar.data

import app.kinjar.data.db.KinjarDb
import app.kinjar.domain.*

internal fun operationResources(events: List<Event>, db: KinjarDb): Map<String, ResourceAccess> = buildMap {
    fun read(key: String) { if (key !in this) put(key, ResourceAccess.READ) }
    fun write(key: String) { put(key, ResourceAccess.WRITE) }
    fun transaction(id: String) {
        write("transaction:$id")
        db.txQueries.all().executeAsList().find { it.id == id }?.let {
            read("account:${it.account_id}")
            it.to_account_id?.let { account -> read("account:$account") }
        }
        db.loanPaymentQueries.forTransaction(id).executeAsOneOrNull()?.let { write("loan:${it.loan_id}") }
        db.loanReceiptQueries.forTransaction(id).executeAsOneOrNull()?.let { write("loan:${it.loan_id}") }
    }
    fun transaction(value: Transaction) {
        transaction(value.id)
        read("account:${value.accountId}")
        value.toAccountId?.let { read("account:$it") }
        value.categoryId?.let { read("category:$it") }
    }
    events.forEach { event ->
        when (event) {
            is ProjectSaved -> {
                write("project:${event.project.id}")
                event.project.sourceCategoryIds.forEach { read("category:$it") }
            }
            is ProjectCategorySaved -> {
                write("project:${event.category.projectId}")
                write("project-category:${event.category.id}")
            }
            is ProjectCostSaved -> {
                write("project:${event.cost.projectId}")
                event.cost.categoryId?.let { read("project-category:$it") }
                event.cost.transactionId?.let { transaction(it) }
            }
            is ProjectCostRemoved -> {
                write("project:${event.projectId}")
                event.transactionId?.let { transaction(it) }
            }
            is AccountCreated -> write("account:${event.account.id}")
            is AccountUpdated -> write("account:${event.account.id}")
            is AccountArchived -> write("account:${event.accountId}")
            is AccountsReordered -> write("order:accounts")
            is CategoryCreated -> write("category:${event.category.id}")
            is CategoryUpdated -> write("category:${event.category.id}")
            is CategoryArchived -> write("category:${event.categoryId}")
            is MemberCreated -> write("member:${event.member.id}")
            is MemberRenamed -> write("member:${event.memberId}")
            is TransactionAdded -> {
                transaction(event.transaction)
                event.receipt?.let { write("receipt:${it.id}") }
            }
            is TransactionEdited -> transaction(event.transaction)
            is TransactionDeleted -> transaction(event.transactionId)
            is RecurringRuleSet -> {
                write("rule:${event.rule.id}")
                read("account:${event.rule.accountId}")
            }
            is RecurringRuleArchived -> write("rule:${event.ruleId}")
            is BillSkipped -> {
                read("rule:${event.ruleId}")
                transaction(billTxId(event.ruleId, event.period))
            }
            is GoalSet -> write("goal:${event.goal.id}")
            is GoalArchived -> write("goal:${event.goalId}")
            is GoalsReordered -> write("order:goals")
            is GoalAllocated -> { read("goal:${event.allocation.goalId}"); write("allocation:${event.allocation.id}") }
            is GoalAllocationDeleted -> write("allocation:${event.allocationId}")
            is BudgetSet -> write("budget:${event.budget.categoryId}:${event.budget.month}")
            is BudgetRemoved -> write("budget:${event.categoryId}:${event.month}")
            is ExchangeRateOverrideSet -> write("rate:${event.currency}")
            is LoanCreated -> write("loan:${event.loan.id}")
            is LoanUpdated -> write("loan:${event.loan.id}")
            is LoanArchived -> write("loan:${event.loanId}")
            is LoanPaymentAdded -> { write("loan:${event.payment.loanId}"); transaction(event.payment.transactionId) }
            is LoanPaymentUpdated -> { write("loan:${event.payment.loanId}"); transaction(event.payment.transactionId) }
            is LoanPaymentRemoved -> {
                val payment = db.loanPaymentQueries.byId(event.paymentId).executeAsOneOrNull()
                requireNotNull(payment) { "Unknown loan payment" }
                write("loan:${requireNotNull(payment.loan_id)}")
                transaction(requireNotNull(payment.tx_id))
            }
            is LoanPrincipalAdjusted -> write("loan:${event.adjustment.loanId}")
            is LoanReceiptLinked -> { write("loan:${event.loanId}"); transaction(event.transactionId) }
        }
    }
}
