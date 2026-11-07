package app.kinjar.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

class FinancialIntegrityTest {
    @Test fun `in place validation preserves copy based decisions through failed mixed commands`() {
        var reference = FinancialIntegrity()
        val actual = FinancialIntegrity()
        val random = Random(7)
        val account = Account("cash", "Cash", Currency.RSD)
        fun apply(events: List<Event>) {
            val expected = runCatching { reference.applying(events, strict = true) }
            val result = runCatching { actual.applyAtomically(events, strict = true) }
            assertEquals(events.toString(), expected.isSuccess, result.isSuccess)
            expected.getOrNull()?.let { reference = it }
            actual.validate()
        }
        apply(listOf(AccountCreated(account)))
        repeat(300) { index ->
            val id = "tx-${random.nextInt(20)}"
            val currency = if (random.nextInt(5) == 0) Currency.EUR else Currency.RSD
            val transaction = Transaction(id, TransactionType.EXPENSE, Money.of("1", currency), "cash", null, null,
                LocalDate.of(2026, 11, 7), "", "", "member", TransactionSource.MANUAL)
            val change: Event = when (random.nextInt(3)) {
                0 -> TransactionAdded(transaction)
                1 -> TransactionEdited(transaction)
                else -> TransactionDeleted(id)
            }
            apply(if (index % 7 == 0) listOf(change, AccountUpdated(account.copy(currency = Currency.EUR))) else listOf(change))
            apply(listOf(AccountUpdated(account)))
        }
    }
}
