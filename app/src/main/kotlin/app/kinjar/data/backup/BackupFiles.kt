package app.kinjar.data.backup

import android.content.Context
import android.net.Uri
import app.kinjar.domain.FamilyHistory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject

class BackupFiles @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun write(uri: Uri, history: FamilyHistory, password: CharArray) = withContext(Dispatchers.IO) {
        try {
            val bytes = BackupCodec.seal(history, password)
            requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes); it.flush() }
        } finally { password.fill('\u0000') }
    }

    suspend fun read(uri: Uri, password: CharArray): OpenBackup = withContext(Dispatchers.IO) {
        try {
            val output = ByteArrayOutputStream()
            requireNotNull(context.contentResolver.openInputStream(uri)).use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= BackupCodec.MAX_BYTES) { "Backup exceeds size limit" }
                    output.write(buffer, 0, count)
                }
            }
            BackupCodec.open(output.toByteArray(), password)
        } finally { password.fill('\u0000') }
    }
}
