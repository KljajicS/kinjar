package app.kinjar.ui.home

import app.kinjar.domain.Currency
import app.kinjar.domain.Money
import app.kinjar.domain.Transaction
import app.kinjar.domain.TransactionSource
import app.kinjar.domain.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class RecentAmountsTest {
    private fun tx(type: TransactionType, money: Money, received: Money? = null) = Transaction(
        id = "t1", type = type, money = money, accountId = "a1",
        toAccountId = if (type == TransactionType.TRANSFER) "a2" else null,
        categoryId = null, date = LocalDate.of(2026, 11, 9), note = "", merchant = "", paidBy = "m1",
        source = TransactionSource.MANUAL, received = received,
    )

    @Test
    fun `expense is negative, income and loan receipt positive, transfer unsigned`() {
        val m = Money.of("1234567.89", Currency.RSD)
        assertEquals("-1,234,567.89 RSD" to null, recentAmounts(tx(TransactionType.EXPENSE, m), Locale.US))
        assertEquals("+1,234,567.89 RSD" to null, recentAmounts(tx(TransactionType.INCOME, m), Locale.US))
        assertEquals("+1,234,567.89 RSD" to null, recentAmounts(tx(TransactionType.LOAN_RECEIPT, m), Locale.US))
        assertEquals("1,234,567.89 RSD" to null, recentAmounts(tx(TransactionType.TRANSFER, m), Locale.US))
    }

    @Test
    fun `cross currency transfer shows sent and received amounts`() {
        val t = tx(TransactionType.TRANSFER, Money.of("100", Currency.EUR), received = Money.of("11700", Currency.RSD))
        assertEquals("100 EUR" to "→ 11,700 RSD", recentAmounts(t, Locale.US))
    }

    @Test
    fun `same currency transfer has no received line`() {
        assertEquals("50 EUR" to null, recentAmounts(tx(TransactionType.TRANSFER, Money.of("50", Currency.EUR)), Locale.US))
    }
}
