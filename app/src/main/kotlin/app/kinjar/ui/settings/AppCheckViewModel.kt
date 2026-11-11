package app.kinjar.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinjar.data.Clock
import app.kinjar.data.auth.AppCheckDiagnostic
import app.kinjar.data.auth.AppCheckResult
import app.kinjar.data.auth.AppCheckStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

data class AppCheckState(
    val busy: Boolean = false,
    val result: AppCheckResult? = null,
    val checkedAt: Instant? = null,
    val cooldown: Boolean = false,
)

@HiltViewModel
class AppCheckViewModel @Inject constructor(private val diagnostic: AppCheckDiagnostic, private val clock: Clock) : ViewModel() {
    private val _state = MutableStateFlow(AppCheckState())
    val state: StateFlow<AppCheckState> = _state

    fun check() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, cooldown = false) }
        viewModelScope.launch {
            try {
                val result = diagnostic.check()
                _state.update {
                    if (result.status == AppCheckStatus.COOLDOWN) it.copy(cooldown = true)
                    else it.copy(result = result, checkedAt = clock.now())
                }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}
