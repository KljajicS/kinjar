package app.kinjar.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinjar.data.update.UpdateManifest
import app.kinjar.data.update.UpdateSource
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

interface UpdateEnvironment {
    val enabled: Boolean
    val versionCode: Long
    val versionName: String
    val sdk: Int
    suspend fun verify(file: File, release: UpdateManifest)
}

enum class UpdateNotice { CURRENT, CHECK_FAILED, INCOMPATIBLE, DOWNLOAD_FAILED, VERIFY_FAILED, PERMISSION_REQUIRED, INSTALL_FAILED }

data class UpdateState(
    val visible: Boolean = false,
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val verifying: Boolean = false,
    val downloadedBytes: Long = 0,
    val release: UpdateManifest? = null,
    val ready: Boolean = false,
    val notice: UpdateNotice? = null,
    val handoff: File? = null,
) {
    val busy: Boolean get() = checking || downloading || verifying
}

@HiltViewModel
class UpdateViewModel internal constructor(
    private val source: UpdateSource,
    private val environment: UpdateEnvironment,
) : ViewModel() {
    @Inject constructor(source: UpdateSource, environment: AndroidUpdateEnvironment) :
        this(source, environment as UpdateEnvironment)

    private val mutableState = MutableStateFlow(UpdateState())
    val state = mutableState.asStateFlow()
    val enabled: Boolean get() = environment.enabled
    val currentVersion: String get() = environment.versionName
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var downloaded: File? = null

    fun check() {
        if (!enabled || state.value.downloading || state.value.verifying || state.value.handoff != null) return
        if (state.value.checking) {
            mutableState.update { it.copy(visible = true) }
            return
        }
        mutableState.update { it.copy(checking = true, notice = null, visible = true) }
        checkJob = viewModelScope.launch {
            try {
                val release = source.latest()
                val notice = when {
                    release.versionCode <= environment.versionCode -> UpdateNotice.CURRENT
                    release.minSdk > environment.sdk -> UpdateNotice.INCOMPATIBLE
                    else -> null
                }
                if (notice != null) {
                    downloaded = null
                    mutableState.update { it.copy(release = null, ready = false, notice = notice) }
                } else {
                    if (state.value.release != release) downloaded = null
                    mutableState.update { it.copy(release = release, ready = downloaded != null) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.update { it.copy(notice = UpdateNotice.CHECK_FAILED) }
            } finally {
                mutableState.update { it.copy(checking = false) }
            }
        }
    }

    fun show() {
        if (!enabled) return
        if (state.value.release != null || state.value.busy) mutableState.update { it.copy(visible = true) }
        else check()
    }

    fun dismiss() {
        if (state.value.downloading || state.value.verifying) return
        if (state.value.checking) checkJob?.cancel()
        mutableState.update { it.copy(visible = false, handoff = null) }
    }

    fun download() {
        if (!enabled || state.value.busy) return
        val release = state.value.release ?: return
        mutableState.update { it.copy(downloading = true, downloadedBytes = 0, ready = false, notice = null) }
        downloadJob = viewModelScope.launch {
            try {
                val file = source.download(release) { bytes -> mutableState.update { it.copy(downloadedBytes = bytes) } }
                mutableState.update { it.copy(downloading = false, verifying = true) }
                environment.verify(file, release)
                downloaded = file
                mutableState.update { it.copy(ready = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                downloaded = null
                val notice = if (state.value.verifying) UpdateNotice.VERIFY_FAILED else UpdateNotice.DOWNLOAD_FAILED
                mutableState.update { it.copy(notice = notice, ready = false) }
            } finally {
                mutableState.update { it.copy(downloading = false, verifying = false) }
            }
        }
    }

    fun install() {
        if (!enabled || state.value.busy || state.value.handoff != null) return
        val release = state.value.release ?: return
        val file = downloaded ?: return
        mutableState.update { it.copy(verifying = true, notice = null) }
        downloadJob = viewModelScope.launch {
            try {
                environment.verify(file, release)
                mutableState.update { it.copy(handoff = file) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                downloaded = null
                mutableState.update { it.copy(ready = false, notice = UpdateNotice.VERIFY_FAILED) }
            } finally {
                mutableState.update { it.copy(verifying = false) }
            }
        }
    }

    fun consumeHandoff(): File? {
        val file = state.value.handoff
        mutableState.update { it.copy(handoff = null) }
        return file
    }

    fun permissionReturned(allowed: Boolean) {
        mutableState.update { it.copy(notice = if (allowed) null else UpdateNotice.PERMISSION_REQUIRED) }
    }

    fun installationFailed() {
        mutableState.update { it.copy(notice = UpdateNotice.INSTALL_FAILED) }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    fun onBackground() {
        checkJob?.cancel()
        downloadJob?.cancel()
        mutableState.update { it.copy(handoff = null) }
    }
}
