package app.kinjar.ui

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import app.kinjar.R
import app.kinjar.data.DiagnosticOperation
import app.kinjar.data.Diagnostics
import app.kinjar.ui.theme.LocalColors

data class WriteState(val busy: Boolean = false, val failed: Boolean = false, val finished: Boolean = false)

class WriteOperation(private val scope: CoroutineScope, private val diagnosticOperation: DiagnosticOperation = DiagnosticOperation.UI_WRITE) {
    private val mutableState = MutableStateFlow(WriteState())
    val state: StateFlow<WriteState> = mutableState

    fun clearFailure() {
        if (!mutableState.value.busy) mutableState.value = mutableState.value.copy(failed = false)
    }

    fun run(finish: Boolean = true, action: suspend () -> Unit) {
        val current = mutableState.value
        if (current.busy || current.finished) return
        mutableState.value = WriteState(busy = true)
        scope.launch {
            try {
                action()
                mutableState.value = WriteState(finished = finish)
            } catch (e: CancellationException) {
                mutableState.value = WriteState()
                throw e
            } catch (e: Exception) {
                Diagnostics.report(diagnosticOperation, e)
                mutableState.value = WriteState(failed = true)
            }
        }
    }
}

@Composable
fun WriteFeedback(operation: WriteOperation, failureText: Int = R.string.write_failed) {
    val state by operation.state.collectAsStateWithLifecycle()
    if (state.busy) LinearProgressIndicator()
    if (state.failed) Text(stringResource(failureText), color = LocalColors.current.orange)
}
