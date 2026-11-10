package app.kinjar.data.update

import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class UpdateManifest(
    val schemaVersion: Int,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val apkUrl: String,
    val sizeBytes: Long,
    val sha256: String,
)

interface UpdateSource {
    suspend fun latest(): UpdateManifest
    suspend fun download(release: UpdateManifest, onProgress: (Long) -> Unit): File
}
