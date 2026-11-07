package app.kinjar.data

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class DiagnosticOperation { UI_WRITE, FAMILY_READ, FAMILY_WRITE, TRANSACTION_READ, TRANSACTION_WRITE, CONFLICT_READ, SYNC, SYNC_CLEANUP }

object Diagnostics {
    private val reporter = DiagnosticReporter(
        clock = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
        sink = { Log.w("Kinjar", it) },
    )

    fun report(operation: DiagnosticOperation, error: Throwable) = reporter.report(operation, error)
}

internal class DiagnosticReporter(private val clock: () -> Long, private val sink: (String) -> Unit) {
    private val last = mutableMapOf<Pair<DiagnosticOperation, String>, Long>()

    @Synchronized
    fun report(operation: DiagnosticOperation, error: Throwable) {
        if (error is CancellationException) return
        // Only fixed categories reach logs, never messages, causes, stack traces, or user identifiers.
        val category = when (error) {
            is FirebaseFirestoreException -> "FIRESTORE_${error.code.name}"
            is IOException -> "IO"
            is IllegalArgumentException -> "VALIDATION"
            is IllegalStateException -> "STATE"
            else -> "UNEXPECTED"
        }
        val key = operation to category
        val now = clock()
        if (last[key]?.let { now - it < 60_000 } == true) return
        last[key] = now
        runCatching { sink("operation=${operation.name} error=$category") }
    }
}
