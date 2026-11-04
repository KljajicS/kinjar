package app.kinjar.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kinjar.R


@Composable
fun AddButton(label: String, onClick: () -> Unit) {
    RoundIconButton(Icons.Default.Add, label, onClick)
}

@Composable
fun RoundIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    val c = LocalColors.current
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(c.surface2).clickable(onClick = onClick, role = Role.Button),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription, tint = c.text, modifier = Modifier.size(22.dp)) }
}

@Composable
fun OutlinedBox(
    modifier: Modifier = Modifier,
    radius: Int = 18,
    padding: PaddingValues = PaddingValues(16.dp),
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(radius.dp)
    Column(
        modifier
            .clip(shape)
            .then(if (selected) Modifier.background(c.primarySoft, shape) else Modifier)
            .border(1.dp, if (selected) c.primary else c.border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = LocalColors.current
    Box(
        Modifier.fillMaxWidth().height(54.dp)
            .background(if (enabled) c.primary else c.surface2, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick, role = Role.Button),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = sans(16, FontWeight.SemiBold), color = if (enabled) c.onPrimary else c.muted) }
}

@Composable
fun PaceBar(ratio: Float, pace: Float, color: Color, height: Dp, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val markerColor = if (height >= 10.dp) c.text else c.muted
    Box(modifier.fillMaxWidth().height(height + 8.dp), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(height).background(c.track, RoundedCornerShape(height / 2)))
        Box(Modifier.fillMaxWidth(ratio.coerceIn(0f, 1f)).height(height).background(color, RoundedCornerShape(height / 2)))
        Box(Modifier.fillMaxWidth(pace.coerceIn(0f, 1f)), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.width(2.dp).height(height + 8.dp).background(markerColor))
        }
    }
}

@Composable
fun FieldBox(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
             visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None) {
    val c = LocalColors.current
    OutlinedBox(modifier.fillMaxWidth().heightIn(min = 48.dp), radius = 14, padding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
        Text(label, style = CaptionStyle, color = c.muted, modifier = Modifier.clearAndSetSemantics {})
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            singleLine = true,
            textStyle = sans(14, FontWeight.Medium).copy(color = c.text),
            cursorBrush = SolidColor(c.primary),
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp).semantics { contentDescription = label },
        )
    }
}

/** The pill stays 34dp tall while its touch target reserves 48dp. */
@Composable
fun SelectPill(label: String, selected: Boolean, onClick: () -> Unit, style: TextStyle = sans(13, FontWeight.Medium)) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(17.dp)
    Box(
        Modifier.selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .minimumInteractiveComponentSize().widthIn(min = 64.dp).height(34.dp)
            .clip(shape)
            .background(if (selected) c.text else Color.Transparent, shape)
            .border(1.dp, if (selected) c.text else c.border, shape)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = style, color = if (selected) c.bg else c.muted) }
}

@Composable
fun DangerLink(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = LocalColors.current
    Box(Modifier.heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.CenterStart) {
        Text(text, style = sans(14, FontWeight.Medium), color = if (enabled) c.orange else c.muted)
    }
}

/** Callers keep it open while [busy] so a failed write shows in [body] and can be retried. */
@Composable
fun ConfirmDialog(title: String, confirm: String, busy: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit, body: @Composable ColumnScope.() -> Unit) {
    val c = LocalColors.current
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = c.bg,
        title = { Text(title, style = sans(18, FontWeight.SemiBold), color = c.text) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = body) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) { Text(confirm, color = if (busy) c.muted else c.orange) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.dialog_cancel), color = if (busy) c.muted else c.text) }
        },
    )
}

@Composable
fun LoadingOrError(failed: Boolean, onRetry: () -> Unit) {
    val c = LocalColors.current
    Box(Modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.Center) {
        if (failed) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.audit_ui_load_failed), style = BodyStyle, color = c.orange)
                TextButton(onClick = onRetry) { Text(stringResource(R.string.audit_ui_retry)) }
            }
        } else {
            CircularProgressIndicator(color = c.primary)
        }
    }
}
