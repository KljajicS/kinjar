package app.kinjar.ui.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kinjar.R
import app.kinjar.ui.add.AddContent
import app.kinjar.ui.add.AddViewModel
import app.kinjar.ui.theme.LocalColors
import app.kinjar.ui.theme.PrimaryButton
import app.kinjar.ui.theme.sans

@Composable
fun ScanScreen(onClose: () -> Unit, onSaved: () -> Unit, onManual: () -> Unit, onEditingChanged: (Boolean) -> Unit, vm: ScanViewModel = hiltViewModel(), add: AddViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val draft by add.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val owner = LocalLifecycleOwner.current
    fun cameraGranted() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(cameraGranted()) }
    var requested by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var editing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(draft.scanned, s.stage) {
        if (!draft.scanned && s.stage != ScanStage.REVIEW) editing = false
    }
    DisposableEffect(editing) {
        onEditingChanged(editing)
        onDispose { onEditingChanged(false) }
    }
    var scanNext by rememberSaveable { mutableStateOf(false) }
    val c = LocalColors.current

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) granted = cameraGranted() }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            if (activity?.isChangingConfigurations != true) vm.leave()
        }
    }
    LaunchedEffect(s.receipt?.id) { s.receipt?.let { add.prefillReceipt(it, s.categoryId) } }
    LaunchedEffect(s.paymentSlip) { s.paymentSlip?.let(add::prefillPaymentSlip) }
    LaunchedEffect(draft.saved) {
        if (draft.saved) {
            vm.reset(); add.resetReceipt(); editing = false
            if (!scanNext) onSaved()
            scanNext = false
        }
    }
    fun reset() { if (!draft.saving) { vm.reset(); add.resetReceipt(); editing = false } }
    fun save(next: Boolean) { if (add.state.value.canSave) { scanNext = next; add.save() } }
    fun close() { if (!draft.saving) { reset(); onClose() } }
    BackHandler { if (editing) editing = false else close() }

    if (editing && draft.scanned) {
        AddContent(draft, { editing = false }, add::setType, add::setAmount, add::setCategory, add::setAccount,
            add::setToAccount, add::setDate, add::setMerchant, add::skip, { save(false) }, onRetry = add::retryLoad)
        return
    }


    ScanLayout(s.stage == ScanStage.REVIEW, ::close, camera = {
        if (s.stage == ScanStage.CAPTURING && granted) ReceiptCamera(Modifier.fillMaxSize(), vm::scanned, vm::cameraFailed)
    }) {
            if (s.stage == ScanStage.REVIEW && draft.scanned) {
                if (draft.loadFailed) {
                    Text(stringResource(R.string.audit_ui_load_failed), color = c.orange)
                    TextButton(onClick = add::retryLoad) { Text(stringResource(R.string.audit_ui_retry)) }
                }
                ReceiptReview(draft, add::setCategory, add::setAccount, { editing = true },
                    { save(true) }, { save(false) })
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (s.stage) {
                        ScanStage.CAPTURING -> {
                            Text(stringResource(if (granted) R.string.scan_hint else R.string.scan_permission), style = sans(15), color = c.text)
                            if (!granted) {
                                PrimaryButton(stringResource(R.string.scan_allow_camera), onClick = { requested = true; permission.launch(Manifest.permission.CAMERA) })
                                if (requested) TextButton(onClick = {
                                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                                }) { Text(stringResource(R.string.scan_open_settings)) }
                            }
                        }
                        ScanStage.LOADING, ScanStage.REVIEW -> {
                            CircularProgressIndicator(color = c.primary)
                            Text(stringResource(R.string.scan_loading), style = sans(15), color = c.text)
                            TextButton(onClick = ::reset) { Text(stringResource(R.string.scan_cancel)) }
                        }
                        ScanStage.ERROR -> {
                            Text(stringResource(s.error ?: R.string.scan_parse_error), style = sans(15), color = c.orange)
                            if (s.canRetry) PrimaryButton(stringResource(R.string.scan_retry), onClick = vm::retry)
                            TextButton(onClick = ::reset) { Text(stringResource(R.string.scan_again)) }
                        }
                    }
                }
                TextButton(onClick = { reset(); onManual() }, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    Text(stringResource(R.string.scan_manual))
                }
            }
    }
}

@Composable
internal fun ScanLayout(detected: Boolean, onClose: () -> Unit, camera: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalColors.current
    Column(Modifier.fillMaxSize().background(if (c.dark) Color.Black else Color(0xFF111315))) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(0.38f)) {
            camera()
            ScanFrame(Modifier.align(Alignment.Center).width(188.dp).height((maxHeight - 112.dp).coerceIn(32.dp, 188.dp)))
            Box(Modifier.align(Alignment.TopStart).padding(20.dp)) {
                CameraButton(Icons.Default.Close, stringResource(R.string.add_close), onClose)
            }
            Text(stringResource(if (detected) R.string.scan_detected else R.string.scan_title),
                color = Color.White, style = sans(13), modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp).background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
        }
        Column(Modifier.fillMaxWidth().weight(0.62f)
            .background(if (c.dark) c.surface2 else c.bg, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).padding(horizontal = 20.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 16.dp)
                .size(40.dp, 4.dp).background(c.border, RoundedCornerShape(2.dp)))
            content()
        }
    }
}

@Composable
private fun ScanFrame(modifier: Modifier) {
    val color = LocalColors.current.primary
    Canvas(modifier) {
        val length = minOf(36.dp.toPx(), size.height / 2)
        val radius = minOf(14.dp.toPx(), length / 2)
        val stroke = 4.dp.toPx()
        listOf(Offset.Zero, Offset(size.width, 0f), Offset(0f, size.height), Offset(size.width, size.height)).forEach { p ->
            val dx = if (p.x == 0f) 1 else -1
            val dy = if (p.y == 0f) 1 else -1
            val path = Path().apply {
                moveTo(p.x + dx * length, p.y)
                lineTo(p.x + dx * radius, p.y)
                quadraticBezierTo(p.x, p.y, p.x, p.y + dy * radius)
                lineTo(p.x, p.y + dy * length)
            }
            drawPath(path, color, style = Stroke(stroke))
        }
    }
}
