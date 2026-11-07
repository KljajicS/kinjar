package app.kinjar.ui

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLocaleTest {
    private fun tags(vararg tags: String) = serbianLatin(tags.map(Locale::forLanguageTag)).map(Locale::toLanguageTag)

    @Test fun `every Serbian variant resolves to Latin and keeps its region`() {
        assertEquals(listOf("sr-Latn"), tags("sr"))
        assertEquals(listOf("sr-Latn-RS"), tags("sr-RS"))
        assertEquals(listOf("sr-Latn-RS"), tags("sr-Cyrl-RS"))
        assertEquals(listOf("sr-Latn-ME"), tags("sr-Latn-ME"))
        assertEquals(listOf("sr-Latn-RS-u-nu-latn"), tags("sr-RS-u-nu-latn"))
    }

    @Test fun `other languages and their order are untouched`() {
        assertEquals(listOf("en-US", "sr-Latn-RS", "de-DE"), tags("en-US", "sr-RS", "de-DE"))
        assertEquals(listOf("hr-HR", "bs-Cyrl-BA"), tags("hr-HR", "bs-Cyrl-BA"))
    }

    @Test fun `both Serbian scripts in the system list collapse to one entry`() {
        assertEquals(listOf("sr-Latn-RS", "en-US"), tags("sr-Cyrl-RS", "sr-Latn-RS", "en-US"))
    }
}
