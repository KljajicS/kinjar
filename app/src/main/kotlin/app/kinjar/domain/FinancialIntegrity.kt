package app.kinjar.domain

import java.time.LocalDate

class FinancialIntegrity private constructor(
    private val accounts: MutableMap<String, Account>,
    private val transactions: MutableMap<String, Transaction>,
    private val loans: MutableMap<String, Loan>,
    private val payments: MutableMap<String, LoanPayment>,
    private val adjustments: MutableMap<String, LoanAdjustment>,
    private val receipts: MutableMap<String, Receipt>,
    private val removedPayments: MutableSet<String>,
) {
    constructor() : this(mutableMapOf(), mutableMapOf(), mutableMapOf(), mutableMapOf(), mutableMapOf(), mutableMapOf(), mutableSetOf())

    fun applying(events: List<Event>, strict: Boolean): FinancialIntegrity {
        val next = FinancialIntegrity(accounts.toMutableMap(), transactions.toMutableMap(), loans.toMutableMap(), payments.toMutableMap(),
            adjustments.toMutableMap(), receipts.toMutableMap(), removedPayments.toMutableSet())
        events.forEach { next.apply(it, strict) }
        if (strict) next.validate()
        return next
    }

    fun validate() {
        transactions.values.forEach { tx ->
            require(accounts[tx.accountId]?.currency == tx.money.currency) { "Transaction account currency mismatch" }
            tx.toAccountId?.let { require(accounts[it]?.currency == tx.money.currency) { "Transfer account currency mismatch" } }
        }
        payments.values.forEach { require(it.loanId in loans) { "Unknown loan" } }
        adjustments.values.forEach { require(it.loanId in loans) { "Unknown loan adjustment" } }
        loans.values.forEach { loan ->
            require(!LoanCalculator.balance(loan, payments.values.toList(), adjustments.values.toList(), transactions, LocalDate.MAX).invalidHistory) { "Invalid loan history" }
        }
    }

    private fun apply(event: Event, strict: Boolean) {
        when (event) {
            is AccountCreated -> accounts.putIfAbsent(event.account.id, event.account)
            is AccountUpdated -> accounts[event.account.id]?.let { accounts[it.id] = event.account.copy(archived = it.archived) }
            is AccountArchived -> accounts[event.accountId]?.let { accounts[it.id] = it.copy(archived = true) }
            is TransactionAdded -> {
                if (strict) require(transactions[event.transaction.id]?.let { it == event.transaction } != false) { "Conflicting duplicate transaction" }
                val prior = event.receipt?.let { receipts[it.id] }
                if (strict && prior != null) require(prior == event.receipt) { "Conflicting duplicate receipt" }
                if (prior == null) {
                    if (strict) require(accounts[event.transaction.accountId]?.archived == false) { "Archived transaction account" }
                    event.receipt?.let { receipts[it.id] = it }
                    transactions.putIfAbsent(event.transaction.id, event.transaction)
                }
            }
            is TransactionEdited -> {
                if (strict) require(event.transaction.id in transactions) { "Transaction was deleted" }
                if (event.transaction.id in transactions) transactions[event.transaction.id] = event.transaction
            }
            is TransactionDeleted -> transactions.remove(event.transactionId)
            is LoanCreated -> loans.putIfAbsent(event.loan.id, event.loan)
            is LoanUpdated -> loans[event.loan.id]?.let {
                if (strict) require(event.loan.opening == it.opening && event.loan.start == it.start)
                loans[it.id] = event.loan.copy(archived = it.archived)
            }
            is LoanArchived -> loans[event.loanId]?.let { loans[it.id] = it.copy(archived = true) }
            is LoanPaymentAdded -> if (event.payment.id !in removedPayments) {
                if (strict) require(payments.values.none { it.transactionId == event.payment.transactionId && it.id != event.payment.id })
                payments.putIfAbsent(event.payment.id, event.payment)
            }
            is LoanPaymentUpdated -> payments[event.payment.id]?.let {
                if (strict) require(it.loanId == event.payment.loanId && it.transactionId == event.payment.transactionId && it.total == event.payment.total)
                payments[event.payment.id] = event.payment
            }
            is LoanPaymentRemoved -> { payments.remove(event.paymentId); removedPayments += event.paymentId }
            is LoanPrincipalAdjusted -> adjustments.putIfAbsent(event.adjustment.id, event.adjustment)
            else -> Unit
        }
    }
}
