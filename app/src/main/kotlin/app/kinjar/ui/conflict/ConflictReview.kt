package app.kinjar.ui.conflict

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kinjar.R
import app.kinjar.data.TransactionReview
import app.kinjar.domain.Transaction
import app.kinjar.domain.TransactionType
import app.kinjar.ui.WriteFeedback
import app.kinjar.ui.categoryDisplayName
import app.kinjar.ui.formatAmount
import app.kinjar.ui.formatDate
import app.kinjar.ui.theme.BodyStyle
import app.kinjar.ui.theme.CaptionStyle
import app.kinjar.ui.theme.ConfirmDialog
import app.kinjar.ui.theme.LabelStyle
import app.kinjar.ui.theme.LocalColors
import app.kinjar.ui.theme.OutlinedBox
import app.kinjar.ui.theme.RoundIconButton
import app.kinjar.ui.theme.mono
import app.kinjar.ui.theme.sans
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val CURRENT = ""

/** Replaces the edit form while a transaction has held versions, so no third value can be saved over them. */
@Composable
fun ConflictReview(state: ReviewState, review: TransactionReview, vm: TransactionConflictViewModel, onClose: () -> Unit) {
    val c = LocalColors.current
    val locale = LocalLocale.current.platformLocale
    val writes by vm.writes.state.collectAsStateWithLifecycle()
    var keeping by rememberSaveable { mutableStateOf<String?>(null) }
    val time = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale)

    Column(
        Modifier.fillMaxSize().background(c.bg).padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundIconButton(Icons.Default.Close, stringResource(R.string.add_close), { if (!writes.busy) onClose() })
            Text(stringResource(R.string.conflict_title), style = sans(15, FontWeight.Medium), color = c.text)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.failed) ConflictCheckFailed(R.string.audit_conflict_check_failed, vm::retry)
            if (review.versions.isEmpty()) {
                Text(stringResource(R.string.conflict_waiting), style = BodyStyle, color = c.muted)
                return@Column
            }
            Text(stringResource(R.string.conflict_body), style = BodyStyle, color = c.muted)
            VersionCard(stringResource(R.string.conflict_current), review.current, state, null, !writes.busy && !state.failed) { keeping = CURRENT }
            val otherPhone = stringResource(R.string.conflict_other_phone)
            val thisPhone = stringResource(R.string.conflict_this_phone)
            review.versions.forEach { v ->
                val author = listOfNotNull(v.author ?: otherPhone.takeIf { !v.thisPhone }, thisPhone.takeIf { v.thisPhone },
                    time.format(v.madeAt.atZone(ZoneId.systemDefault()))).joinToString(" · ")
                VersionCard(author, v.transaction, state, stringResource(R.string.conflict_cannot_apply).takeIf { !v.keepable }, !writes.busy && !state.failed,
                    onKeep = if (v.keepable) ({ keeping = v.operationId }) else null)
            }
            Text(stringResource(R.string.conflict_edit_after), style = CaptionStyle, color = c.muted)
        }
    }

    keeping?.takeUnless { state.failed }?.let { id ->
        ConfirmDialog(stringResource(R.string.conflict_keep_title), stringResource(R.string.conflict_keep), writes.busy,
            onConfirm = { if (id == CURRENT) vm.keepCurrent() else vm.keep(id) }, onDismiss = { keeping = null }) {
            Text(stringResource(R.string.conflict_keep_body), style = sans(14), color = c.text)
            WriteFeedback(vm.writes, R.string.conflict_failed)
        }
    }
}

/** Stands in for the edit form while it is unknown whether the transaction has held versions. */
@Composable
fun ConflictCheckFailedScreen(onRetry: () -> Unit, onClose: () -> Unit) {
    val c = LocalColors.current
    Column(
        Modifier.fillMaxSize().background(c.bg).padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        RoundIconButton(Icons.Default.Close, stringResource(R.string.add_close), onClose)
        ConflictCheckFailed(R.string.audit_conflict_check_failed, onRetry)
    }
}

@Composable
fun ConflictCheckFailed(@StringRes message: Int, onRetry: () -> Unit) {
    val c = LocalColors.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(message), style = BodyStyle, color = c.orange)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.audit_finance_retry), color = c.primary) }
    }
}

@Composable
private fun VersionCard(label: String, t: Transaction?, state: ReviewState, note: String?, enabled: Boolean, onKeep: (() -> Unit)?) {
    val c = LocalColors.current
    val locale = LocalLocale.current.platformLocale
    OutlinedBox(Modifier.fillMaxWidth()) {
        Text(label, style = CaptionStyle, color = c.muted)
        if (t == null) {
            Text(stringResource(R.string.conflict_no_transaction), style = BodyStyle, color = c.text)
        } else {
            // Versions may differ only in what arrived, so both sides of a cross-currency transfer are shown.
            val received = t.received?.let { " → ${formatAmount(it, locale = locale)} ${it.currency}" }.orEmpty()
            Text("${formatAmount(t.money, locale = locale)} ${t.money.currency}$received", style = mono(18, FontWeight.SemiBold), color = c.text)
            val type = stringResource(when (t.type) {
                TransactionType.EXPENSE -> R.string.add_expense
                TransactionType.INCOME -> R.string.add_income
                TransactionType.TRANSFER -> R.string.add_transfer
                TransactionType.LOAN_RECEIPT -> R.string.loan_received
            })
            val category = t.categoryId?.let { categoryDisplayName(it, state.categoryNames[it]) }
            val account = listOfNotNull(state.accountNames[t.accountId], t.toAccountId?.let { state.accountNames[it] }).joinToString(" → ")
            Text(listOf(type, t.merchant, category.orEmpty(), account, formatDate(t.date, locale)).filter { it.isNotBlank() }.joinToString(" · "),
                style = LabelStyle, color = c.muted)
        }
        note?.let { Text(it, style = CaptionStyle, color = c.orange) }
        if (onKeep != null) TextButton(onClick = onKeep, enabled = enabled) { Text(stringResource(R.string.conflict_keep), color = c.primary) }
    }
}
