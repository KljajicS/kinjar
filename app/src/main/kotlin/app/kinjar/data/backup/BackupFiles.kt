package app.kinjar.data.backup

import android.content.Context
import android.net.Uri
import app.kinjar.data.EventStore
import app.kinjar.data.StoredHistory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject

class BackupFiles @Inject constructor(@ApplicationContext private val context: Context, private val store: EventStore) {
    suspend fun write(uri: Uri, history: StoredHistory, password: CharArray) = withContext(Dispatchers.IO) {
        try {
            val written = requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).buffered().use {
                BackupCodec.seal(store.historySource(history.id), it, password)
            }
            check(written.digest == history.digest) { "History changed while writing the backup" }
        } finally { password.fill('\u0000') }
    }

    /** The backup's history as a finished spool; a damaged or partial file leaves no spool behind. */
    suspend fun read(uri: Uri, password: CharArray): OpenStoredBackup = withContext(Dispatchers.IO) {
        try {
            var createdAt: Instant? = null
            val history = requireNotNull(context.contentResolver.openInputStream(uri)).buffered().use { input ->
                store.writeHistory { sink -> createdAt = BackupCodec.open(input, password, sink) }
            }
            OpenStoredBackup(requireNotNull(createdAt), history)
        } finally { password.fill('\u0000') }
    }
}
