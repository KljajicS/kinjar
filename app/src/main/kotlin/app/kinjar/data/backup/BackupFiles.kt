package app.kinjar.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import app.kinjar.data.EventStore
import app.kinjar.data.StoredHistory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject

class BackupFiles internal constructor(
    private val context: Context,
    private val store: EventStore,
    private val deleteDocument: (Uri) -> Unit,
) {
    @Inject constructor(@ApplicationContext context: Context, store: EventStore) :
        this(context, store, { check(DocumentsContract.deleteDocument(context.contentResolver, it)) { "Incomplete backup could not be removed" } })

    // Only an initially empty destination is eligible for failed-export cleanup.
    suspend fun write(uri: Uri, history: StoredHistory, password: CharArray) {
        try {
            withContext(Dispatchers.IO) {
                val created = isEmpty(uri)
                try {
                    val written = requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).buffered().use {
                        BackupCodec.seal(store.historySource(history.id), it, password)
                    }
                    check(written.digest == history.digest) { "History changed while writing the backup" }
                } catch (e: Throwable) {
                    if (created) runCatching { deleteDocument(uri) }.exceptionOrNull()?.let { e.addSuppressed(it) }
                    throw e
                }
            }
        } finally { password.fill('\u0000') }
    }

    private fun isEmpty(uri: Uri): Boolean = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            it.moveToFirst() && !it.isNull(0) && it.getLong(0) == 0L
        }
    }.getOrNull() == true

    /** The backup's history as a finished spool; a damaged or partial file, or cancellation, leaves no spool behind. */
    suspend fun read(uri: Uri, password: CharArray): OpenStoredBackup {
        var staged: StoredHistory? = null
        try {
            return withContext(Dispatchers.IO) {
                var createdAt: Instant? = null
                val history = requireNotNull(context.contentResolver.openInputStream(uri)).buffered().use { input ->
                    store.writeHistory { sink -> createdAt = BackupCodec.open(input, password, sink) }
                }
                staged = history
                OpenStoredBackup(requireNotNull(createdAt), history)
            }
        } catch (e: Throwable) {
            // A cancelled withContext drops a finished result, so the spool is discarded here rather than by the caller.
            staged?.let { withContext(NonCancellable + Dispatchers.IO) { runCatching { store.discardHistory(it.id) } } }
                ?.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        } finally { password.fill('\u0000') }
    }
}
