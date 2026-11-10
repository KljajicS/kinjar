package app.kinjar.ui.update

import app.kinjar.data.update.UpdateManifest

internal data class UpdateApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int,
    val debuggable: Boolean,
    val signers: Set<String>,
)

internal fun verifyUpdateIdentity(
    apk: UpdateApkIdentity,
    release: UpdateManifest,
    installedPackage: String,
    installedCode: Long,
    installedSigners: Set<String>,
    deviceSdk: Int,
) {
    require(apk.packageName == installedPackage && apk.versionName == release.versionName)
    require(apk.versionCode == release.versionCode && apk.versionCode > installedCode)
    require(apk.minSdk == release.minSdk && apk.minSdk <= deviceSdk)
    require(!apk.debuggable)
    require(installedSigners.isNotEmpty() && apk.signers == installedSigners)
}
