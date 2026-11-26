package app.kinjar.data

import app.kinjar.domain.*
import java.time.LocalDate

/** One coherent read for every money screen. [accounts] is every account in saved order, archived ones included. */
data class FinancialSnapshot(
    val day: LocalDate,
    val accounts: List<Account>,
    val transactions: List<Transaction>,
    val bills: List<Bill>,
    val goals: List<GoalProgress>,
    val home: HomeSummary,
    val exchange: ExchangeRateSnapshot,
    val missingCurrencies: Set<Currency>,
    val bankCheckpoints: Map<String, BankBalanceCheckpoint> = emptyMap(),
) {
    val activeAccounts: List<Account> get() = accounts.filterNot { it.archived }
}
