package app.kinjar.ui.add

import androidx.compose.ui.text.AnnotatedString
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class AmountVisualTransformationTest {
    @Test fun `groups digits and pads decimal places without changing typed digits in both locales`() {
        val english = AmountVisualTransformation(Locale.US)
        val serbian = AmountVisualTransformation(Locale.forLanguageTag("sr-Latn-RS"))
        assertEquals("12,450.50", english.filter(AnnotatedString("12450.50")).text.text)
        assertEquals("12.450,50", serbian.filter(AnnotatedString("12450.50")).text.text)
        assertEquals("1,000.00", english.filter(AnnotatedString("1000.")).text.text)
        assertEquals("2,000.00", english.filter(AnnotatedString("2000")).text.text)
        assertEquals("2,000.50", english.filter(AnnotatedString("2000.5")).text.text)
        assertEquals("2.000,00", serbian.filter(AnnotatedString("2000")).text.text)
        assertEquals("0.00", english.filter(AnnotatedString(".")).text.text)
        assertEquals("0.50", english.filter(AnnotatedString(".5")).text.text)
        assertEquals("0,50", serbian.filter(AnnotatedString("0.50")).text.text)
        assertEquals("", english.filter(AnnotatedString("")).text.text)
    }

    @Test fun `cursor mappings round trip every raw boundary and stay monotonic around groups`() {
        listOf(Locale.US, Locale.forLanguageTag("sr-Latn-RS")).forEach { locale ->
            listOf("", ".", "0.00", "1234", "123456789.50", "00012.3").forEach { raw ->
                val transformed = AmountVisualTransformation(locale).filter(AnnotatedString(raw))
                val map = transformed.offsetMapping
                (0..raw.length).forEach { offset -> assertEquals(offset, map.transformedToOriginal(map.originalToTransformed(offset))) }
                val positions = (0..transformed.text.length).map(map::transformedToOriginal)
                assertEquals(positions.sorted(), positions)
                assertTrue(positions.all { it in 0..raw.length })
                assertEquals(raw.length, map.transformedToOriginal(transformed.text.length))
            }
        }
    }

    @Test fun `account search matches trimmed currency and names without archived accounts`() {
        val accounts = listOf(app.kinjar.domain.Account("a", "Travel card", app.kinjar.domain.Currency.EUR),
            app.kinjar.domain.Account("b", "Cash", app.kinjar.domain.Currency.RSD),
            app.kinjar.domain.Account("c", "Retired card", app.kinjar.domain.Currency.EUR, archived = true))
        assertEquals(listOf("a"), matchingAccounts(accounts, " EUR ").map { it.id })
        assertEquals(listOf("a"), matchingAccounts(accounts, "CARD").map { it.id })
        assertEquals(2, matchingAccounts(accounts, "").size)
    }
}
