package app.kinjar.ui.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalLocale
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.format.DateTimeFormatter
import app.kinjar.ui.monthLocale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kinjar.R
import app.kinjar.domain.Currency
import app.kinjar.ui.add.AccountBox
import app.kinjar.ui.add.AddState
import app.kinjar.ui.categoryDisplayName
import app.kinjar.ui.theme.*

@Composable
internal fun ColumnScope.ReceiptReview(
    s: AddState, onCategory: (String?) -> Unit, onAccount: (String) -> Unit,
    onEdit: () -> Unit, onSaveNext: () -> Unit, onSave: () -> Unit,
) {
    val c = LocalColors.current
    val locale = LocalLocale.current.platformLocale
    val amount = s.amount.value?.let { DecimalFormat("#,##0.00", DecimalFormatSymbols(locale)).format(it) } ?: s.amount.text
    val date = s.date.format(DateTimeFormatter.ofPattern("d MMM yyyy", monthLocale(locale)))
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(amount, style = mono(36, FontWeight.SemiBold), color = c.text, modifier = Modifier.weight(1f, fill = false))
            Text("RSD", style = sans(14), color = c.muted, modifier = Modifier.padding(bottom = 6.dp))
        }
        Text(listOf(date, s.merchant).filter { it.isNotBlank() }.joinToString(" · "), style = sans(14), color = c.muted)
        if (s.scanned && s.merchant.isBlank()) {
            Text(stringResource(R.string.scan_merchant_missing), style = sans(13), color = c.orange)
        }
        s.paymentSlip?.let { slip ->
            Text(stringResource(R.string.scan_ips_record), style = sans(13), color = c.muted)
            Text(stringResource(R.string.scan_ips_recipient, slip.recipient), style = sans(13), color = c.text)
            Text(stringResource(R.string.scan_ips_account, slip.account), style = sans(13), color = c.muted)
            Text(stringResource(R.string.scan_ips_code, slip.paymentCode), style = sans(13), color = c.muted)
            slip.payer?.let { Text(stringResource(R.string.scan_ips_payer, it), style = sans(13), color = c.muted) }
            if (slip.note.isNotBlank()) Text(slip.note, style = sans(13), color = c.text)
        }
        Text(stringResource(R.string.scan_category), style = sans(13), color = c.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            s.pickableCategories.forEach { category ->
                val active = category.id == s.categoryId
                Text(categoryDisplayName(category.id, category.name), style = sans(14, FontWeight.Medium),
                    color = if (active) c.onPrimary else c.text,
                    modifier = Modifier.background(if (active) c.primary else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(22.dp))
                        .border(1.dp, if (active) c.primary else c.border, RoundedCornerShape(22.dp))
                        .semantics { selected = active }
                        .clickable(enabled = !s.saving, role = Role.RadioButton) { onCategory(category.id) }
                        .padding(horizontal = 14.dp, vertical = 11.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AccountBox(stringResource(R.string.add_account), s.accounts.filter { it.currency == Currency.RSD }, s.accountId, onAccount, Modifier.weight(1f))
            OutlinedBox(Modifier.weight(1f), radius = 14, padding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.scan_paid_by), style = CaptionStyle, color = c.muted)
                Text(s.paidByName, style = sans(14, FontWeight.Medium), color = c.text, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (s.accounts.none { it.id == s.accountId && it.currency == Currency.RSD }) {
            Text(stringResource(R.string.scan_rsd_account), style = sans(13), color = c.orange)
        }
        if (s.categoryId != null && s.categories.none { it.id == s.categoryId }) {
            Text(stringResource(R.string.scan_category_changed), style = sans(13), color = c.orange)
        }
        s.saveError?.let { Text(stringResource(it), style = sans(13), color = c.orange) }
        TextButton(onClick = onEdit, enabled = !s.saving) { Text(stringResource(R.string.scan_edit)) }
    }
    Row(Modifier.padding(top = 12.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f).height(54.dp).background(if (c.dark) c.track else c.surface2, RoundedCornerShape(16.dp))
            .clickable(enabled = s.canSave, role = Role.Button, onClick = onSaveNext), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.scan_save_next), style = sans(14, FontWeight.SemiBold), color = if (s.canSave) c.text else c.muted)
        }
        Box(Modifier.weight(1f)) { PrimaryButton(stringResource(R.string.scan_save), enabled = s.canSave, onClick = onSave) }
    }
}
