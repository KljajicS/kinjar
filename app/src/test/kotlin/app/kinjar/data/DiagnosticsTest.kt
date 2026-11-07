package app.kinjar.data

import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class DiagnosticsTest {
    @Test fun `diagnostics exclude private messages causes and cancellation`() {
        val messages = mutableListOf<String>()
        val reporter = DiagnosticReporter({ 0 }, messages::add)
        reporter.report(DiagnosticOperation.UI_WRITE, IOException("secret receipt URL and amount", RuntimeException("token")))
        reporter.report(DiagnosticOperation.SYNC, FirebaseFirestoreException("private email", FirebaseFirestoreException.Code.PERMISSION_DENIED))
        reporter.report(DiagnosticOperation.UI_WRITE, CancellationException("private details"))
        assertEquals(listOf("operation=UI_WRITE error=IO", "operation=SYNC error=FIRESTORE_PERMISSION_DENIED"), messages)
    }

    @Test fun `repeated failures are bounded without hiding different categories`() {
        var now = 0L
        val messages = mutableListOf<String>()
        val reporter = DiagnosticReporter({ now }, messages::add)
        repeat(100) { reporter.report(DiagnosticOperation.SYNC, IOException("different data $it")) }
        reporter.report(DiagnosticOperation.SYNC, IllegalStateException("state"))
        now = 59_999
        reporter.report(DiagnosticOperation.SYNC, IOException())
        assertEquals(2, messages.size)
        now = 60_000
        reporter.report(DiagnosticOperation.SYNC, IOException())
        assertEquals(3, messages.size)
    }

    @Test fun `diagnostic sink failure does not replace application failure`() {
        DiagnosticReporter({ 0 }, { error("logger unavailable") }).report(DiagnosticOperation.UI_WRITE, IOException())
    }
}
