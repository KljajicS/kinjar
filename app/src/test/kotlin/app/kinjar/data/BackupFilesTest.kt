package app.kinjar.data

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import app.kinjar.data.backup.BackupCodec
import app.kinjar.data.backup.BackupFiles
import app.kinjar.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId

class BackupFilesTest {
    private val db = testDb()
    private val store = EventStore(db, "local", FixedClock(Instant.parse("2026-11-07T10:00:00Z"), ZoneId.of("UTC")), Projector(db))
    private val uri = mock(Uri::class.java)
    private val resolver = mock(ContentResolver::class.java)
    private val context = mock(Context::class.java).also { `when`(it.contentResolver).thenReturn(resolver) }
    private val deleted = mutableListOf<Uri>()
    private var deleteFailure: (() -> Exception)? = null
    private val files = BackupFiles(context, store) { deleted += it; deleteFailure?.let { failure -> throw failure() } }

    private class FailingOutput(private val failure: () -> Throwable) : OutputStream() {
        override fun write(b: Int) = throw failure()
    }

    private fun documentSize(size: Long) {
        val cursor = mock(Cursor::class.java)
        `when`(cursor.moveToFirst()).thenReturn(true)
        `when`(cursor.getLong(0)).thenReturn(size)
        `when`(resolver.query(uri, arrayOf("_size"), null, null, null)).thenReturn(cursor)
    }

    private fun output(stream: OutputStream) = `when`(resolver.openOutputStream(uri, "wt")).thenReturn(stream)

    private suspend fun history(): StoredHistory {
        store.append(AccountCreated(Account("a", "a", Currency.RSD, AccountKind.SPENDING)))
        return store.captureHistory()
    }

    private suspend fun failure(history: StoredHistory, password: CharArray) =
        runCatching { files.write(uri, history, password) }.exceptionOrNull()

    @Test fun `successful export writes an openable backup and keeps the document`() = runTest {
        documentSize(0)
        val bytes = ByteArrayOutputStream().also(::output)
        val history = history()
        val password = "four long random words".toCharArray()
        files.write(uri, history, password)
        val records = mutableListOf<HistoryRecord>()
        BackupCodec.open(ByteArrayInputStream(bytes.toByteArray()), "four long random words".toCharArray(), records::add)
        assertEquals(store.historySource(history.id).records().toList(), records)
        assertTrue(deleted.isEmpty())
        assertTrue(password.all { it == '\u0000' })
    }

    @Test fun `failed write deletes the new document and keeps the original error`() = runTest {
        documentSize(0)
        output(FailingOutput { IOException("disk full") })
        val password = "four long random words".toCharArray()
        val error = failure(history(), password)
        assertTrue(error is IOException && error.message == "disk full")
        assertEquals(listOf(uri), deleted)
        assertTrue(password.all { it == '\u0000' })
    }

    @Test fun `digest mismatch deletes the new document`() = runTest {
        documentSize(0)
        output(ByteArrayOutputStream())
        val error = failure(history().copy(digest = "changed"), "four long random words".toCharArray())
        assertTrue(error is IllegalStateException && error.message == "History changed while writing the backup")
        assertEquals(listOf(uri), deleted)
    }

    @Test fun `cancellation during the write deletes the new document and stays a cancellation`() = runTest {
        documentSize(0)
        output(FailingOutput { CancellationException("cancelled") })
        val password = "four long random words".toCharArray()
        assertTrue(failure(history(), password) is CancellationException)
        assertEquals(listOf(uri), deleted)
        assertTrue(password.all { it == '\u0000' })
    }

    @Test fun `missing output stream deletes the new document`() = runTest {
        documentSize(0)
        assertTrue(failure(history(), "four long random words".toCharArray()) is IllegalArgumentException)
        assertEquals(listOf(uri), deleted)
    }

    @Test fun `cancellation before dispatch wipes the password without touching the document`() = runTest {
        val history = history()
        val password = "four long random words".toCharArray()
        var error: Throwable? = null
        launch {
            cancel()
            error = failure(history, password)
        }.join()
        assertTrue(error is CancellationException)
        assertTrue(password.all { it == '\u0000' })
        verifyNoInteractions(resolver)
        assertTrue(deleted.isEmpty())
    }

    @Test fun `failure on a document that already had content or an unknown size never deletes it`() = runTest {
        output(FailingOutput { IOException("disk full") })
        val history = history()
        assertTrue(failure(history, "four long random words".toCharArray()) is IOException)
        documentSize(4096)
        assertTrue(failure(history, "four long random words".toCharArray()) is IOException)
        assertTrue(deleted.isEmpty())
    }

    @Test fun `cancellation after a backup was read discards its history and wipes the password`() = runTest {
        documentSize(0)
        val bytes = ByteArrayOutputStream().also(::output)
        val history = history()
        files.write(uri, history, "four long random words".toCharArray())
        store.discardHistory(history.id)
        val password = "four long random words".toCharArray()
        lateinit var job: Job
        `when`(resolver.openInputStream(uri)).thenAnswer { job.cancel(); ByteArrayInputStream(bytes.toByteArray()) }
        job = launch { files.read(uri, password) }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(db.historySpoolQueries.unreferenced().executeAsList().isEmpty())
        assertTrue(password.all { it == '\u0000' })
    }

    @Test fun `unsupported deletion keeps the original error with the cleanup failure suppressed`() = runTest {
        documentSize(0)
        output(FailingOutput { IOException("disk full") })
        deleteFailure = { UnsupportedOperationException("Delete not supported") }
        val error = failure(history(), "four long random words".toCharArray())
        assertTrue(error is IOException && error.message == "disk full")
        assertEquals(listOf(uri), deleted)
        assertTrue(generateSequence(error) { it.cause }.any { cause ->
            cause.suppressed.any { it is UnsupportedOperationException }
        })
    }
}
