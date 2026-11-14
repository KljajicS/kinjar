package app.kinjar.ui.scan

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import app.kinjar.R
import app.kinjar.data.BudgetRepository
import app.kinjar.data.receipt.FiscalUrl
import app.kinjar.data.receipt.NbsIpsParser
import app.kinjar.data.receipt.PaymentSlip
import app.kinjar.data.receipt.ReceiptLookup
import app.kinjar.data.receipt.UnsupportedPaymentSlip
import app.kinjar.data.receipt.UnsupportedReceipt
import app.kinjar.domain.Receipt
import java.io.IOException
import javax.inject.Inject

enum class ScanStage { CAPTURING, LOADING, REVIEW, ERROR }

data class ScanState(
    val stage: ScanStage = ScanStage.CAPTURING,
    val receipt: Receipt? = null,
    val categoryId: String? = null,
    @StringRes val error: Int? = null,
    val canRetry: Boolean = false,
    val paymentSlip: PaymentSlip? = null,
)

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val lookup: ReceiptLookup,
    private val repo: BudgetRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val _state = MutableStateFlow(when {
        savedState.get<Boolean>("paymentSlipPending") == true -> ScanState(ScanStage.ERROR, error = R.string.scan_ips_rescan)
        savedState.get<String>("fiscalUrl") != null -> ScanState(ScanStage.ERROR, error = R.string.scan_retry_restored, canRetry = true)
        else -> ScanState()
    })
    val state: StateFlow<ScanState> = _state
    private var request: Job? = null

    fun scanned(raw: String) {
        if (_state.value.stage != ScanStage.CAPTURING) return
        if (raw.startsWith("K:")) {
            _state.value = try {
                val slip = NbsIpsParser.parse(raw)
                savedState["paymentSlipPending"] = true
                ScanState(ScanStage.REVIEW, paymentSlip = slip)
            } catch (_: UnsupportedPaymentSlip) {
                ScanState(ScanStage.ERROR, error = R.string.scan_ips_unsupported)
            } catch (_: IllegalArgumentException) {
                ScanState(ScanStage.ERROR, error = R.string.scan_ips_invalid)
            }
            return
        }
        val url = runCatching { FiscalUrl.parse(raw).toString() }.getOrNull()
        if (url == null) {
            _state.value = ScanState(ScanStage.ERROR, error = R.string.scan_invalid_qr)
            return
        }
        savedState["fiscalUrl"] = url
        load(url)
    }

    fun retry() {
        if (!_state.value.canRetry) return
        savedState.get<String>("fiscalUrl")?.let(::load)
    }

    private fun load(url: String) {
        request?.cancel()
        _state.value = ScanState(ScanStage.LOADING)
        request = viewModelScope.launch {
            try {
                val receipt = lookup.fetch(url)
                if (repo.hasReceipt(receipt.id)) {
                    _state.value = ScanState(ScanStage.ERROR, error = R.string.scan_duplicate)
                } else {
                    val category = repo.receiptCategory(receipt.issuer)
                    _state.value = ScanState(ScanStage.REVIEW, receipt, category)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = when (e) {
                    is UnsupportedReceipt -> R.string.scan_unsupported
                    is IOException -> R.string.scan_network_error
                    else -> R.string.scan_parse_error
                }
                _state.value = ScanState(ScanStage.ERROR, error = error, canRetry = true)
            }
        }
    }

    fun cameraFailed() {
        if (_state.value.stage == ScanStage.CAPTURING) _state.value = ScanState(ScanStage.ERROR, error = R.string.scan_camera_error)
    }

    fun leave() {
        request?.cancel()
        if (_state.value.stage == ScanStage.LOADING) {
            _state.value = ScanState(ScanStage.ERROR, error = R.string.scan_retry_restored, canRetry = true)
        }
    }

    fun reset() {
        request?.cancel()
        savedState.remove<String>("fiscalUrl")
        savedState.remove<Boolean>("paymentSlipPending")
        _state.value = ScanState()
    }
}
