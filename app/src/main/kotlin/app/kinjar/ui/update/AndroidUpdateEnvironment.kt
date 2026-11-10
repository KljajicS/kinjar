package app.kinjar.ui.update

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import app.kinjar.BuildConfig
import app.kinjar.data.update.UpdateManifest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class AndroidUpdateEnvironment @Inject constructor(@ApplicationContext private val context: Context) : UpdateEnvironment {
    override val enabled: Boolean get() = !BuildConfig.DEBUG
    override val versionCode: Long get() = BuildConfig.VERSION_CODE.toLong()
    override val versionName: String get() = BuildConfig.VERSION_NAME
    override val sdk: Int get() = Build.VERSION.SDK_INT

    @Suppress("DEPRECATION")
    override suspend fun verify(file: File, release: UpdateManifest) = withContext(Dispatchers.IO) {
        require(enabled)
        require(file.canonicalFile.parentFile == File(context.cacheDir, "updates").canonicalFile && file.isFile)
        require(file.length() == release.sizeBytes)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        require(digest.digest().joinToString("") { "%02x".format(it) }.equals(release.sha256, ignoreCase = true))
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = requireNotNull(context.packageManager.getPackageArchiveInfo(file.path, flags))
        val installed = context.packageManager.getPackageInfo(BuildConfig.APPLICATION_ID, flags)
        val app = requireNotNull(archive.applicationInfo)
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        verifyUpdateIdentity(
            UpdateApkIdentity(archive.packageName, code, archive.versionName, app.minSdkVersion,
                app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0, signers(archive)),
            release, BuildConfig.APPLICATION_ID, versionCode, signers(installed), sdk,
        )
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            ?.map { it.toCharsString() }?.toSet().orEmpty()
}
