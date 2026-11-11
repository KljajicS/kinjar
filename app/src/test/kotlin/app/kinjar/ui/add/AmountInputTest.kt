package app.kinjar.ui.add

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class AmountInputTest {
    private fun type(s: String) = s.fold(AmountInput()) { acc, c -> acc.press(c) }

    @Test fun `digits and one decimal point`() = assertEquals(BigDecimal("1240.5"), type("1240.5").value)
    @Test fun `second point ignored`() = assertEquals("1.5", type("1.5.").text)
    @Test fun `max two decimals`() = assertEquals("1.25", type("1.259").text)
    @Test fun `leading zeros collapse`() = assertEquals("7", type("007").text)
    @Test fun `backspace`() = assertEquals("12", type("123<").text)
    @Test fun `empty and zero are null`() { assertNull(type("").value); assertNull(type("0.00").value) }
    @Test fun `length cap at 12 characters`() = assertEquals(12, type("1234567890123456").text.length)

    @Test fun `native edits support replacement insertion and clearing`() {
        val amount = AmountInput("12.50")
        assertEquals("19.50", amount.edit("19.50").text)
        assertEquals("123.50", amount.edit("123.50").text)
        assertEquals("", amount.edit("").text)
        assertNull(amount.edit("").value)
    }

    @Test fun `native input accepts comma and unfinished decimals`() {
        assertEquals(BigDecimal("12.50"), AmountInput().edit("12,50").value)
        assertEquals("12.", AmountInput().edit("12,").text)
        assertEquals(BigDecimal("0.5"), AmountInput().edit(".5").value)
        assertNull(AmountInput().edit(".").value)
        assertNull(AmountInput().edit("0.00").value)
    }

    @Test fun `invalid native edits preserve previous amount without truncation`() {
        val amount = AmountInput("12.50")
        listOf("12.501", "1.234,56", "1,234.56", "-12", "1e3", "12a", "1234567890123").forEach {
            assertEquals(it, amount, amount.edit(it))
        }
        assertEquals("123456789012", amount.edit("123456789012").text)
    }
}
