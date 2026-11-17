package app.kinjar.ui.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectReportImageTest {
    @Test fun `monthly status stays plain while amounts and adjustments retain emphasis`() {
        listOf("Monthly expenses", "Mesečni troškovi").forEach { label ->
            assertFalse(reportLineEmphasized("$label · November 2026: Missing: Water", label))
            assertFalse(reportLineEmphasized("• Water: 100 RSD", label))
            assertFalse(reportLineEmphasized("", label))
            assertTrue(reportLineEmphasized("Total to pay: 100 RSD", label))
            assertTrue(reportLineEmphasized("Adjustment: -100 RSD", label))
        }
    }

    @Test fun `short report fits one image`() {
        assertEquals(listOf(0..2), reportPageLines(listOf(40, 80, 120), 120))
    }

    @Test fun `long report splits at line boundaries without dropping lines`() {
        val pages = reportPageLines(listOf(40, 80, 120, 160, 200), 100)
        assertEquals(listOf(0..1, 2..3, 4..4), pages)
        assertEquals((0..4).toList(), pages.flatMap { it.toList() })
    }
}
