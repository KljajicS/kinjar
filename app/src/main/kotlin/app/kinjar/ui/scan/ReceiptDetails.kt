package app.kinjar.ui.scan

import android.content.ActivityNotFoundException
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kinjar.R
import app.kinjar.data.receipt.FiscalUrl
import app.kinjar.domain.Currency
import app.kinjar.domain.Money
import app.kinjar.domain.Receipt
import app.kinjar.ui.formatAmount
import app.kinjar.ui.formatDate
import app.kinjar.ui.theme.LocalColors
import app.kinjar.ui.theme.OutlinedBox
import app.kinjar.ui.theme.sans
import java.text.NumberFormat

@Composable
internal fun ReceiptDetails(receipt: Receipt) {
    val c = LocalColors.current
    val locale = LocalLocale.current.platformLocale
    val uriHandler = LocalUriHandler.current
    var expanded by remember(receipt) { mutableStateOf(false) }
    var openFailed by remember(receipt.fiscalUrl) { mutableStateOf(false) }
    val validUrl = remember(receipt.fiscalUrl) { runCatching { FiscalUrl.parse(receipt.fiscalUrl) }.isSuccess }
    val quantityFormat = remember(locale) { NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 340 } }
    OutlinedBox(Modifier.fillMaxWidth(), radius = 14, padding = PaddingValues(0.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = stringResource(if (expanded) R.string.receipt_hide else R.string.receipt_details)) { expanded = !expanded }
            .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.receipt_details), style = sans(14, FontWeight.SemiBold), color = c.text, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null, tint = c.muted)
        }
        if (expanded) {
            HorizontalDivider(color = c.border)
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(receipt.merchant, style = sans(14, FontWeight.Medium), color = c.text)
                        Text(stringResource(R.string.receipt_identity, receipt.issuer, receipt.invoiceNumber, formatDate(receipt.date, locale)), style = sans(12), color = c.muted)
                        HorizontalDivider(color = c.border)
                        receipt.items.forEach { item ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, style = sans(14), color = c.text)
                                    Text(stringResource(R.string.receipt_item, quantityFormat.format(item.quantity),
                                        formatAmount(Money.of(item.unitPrice, Currency.RSD), locale = locale)), style = sans(12), color = c.muted)
                                }
                                Text(stringResource(R.string.receipt_money, formatAmount(Money.of(item.total, Currency.RSD), locale = locale)), style = sans(14, FontWeight.Medium), color = c.text)
                            }
                        }
                        HorizontalDivider(color = c.border)
                        Text(stringResource(R.string.receipt_total, formatAmount(receipt.total, locale = locale)), style = sans(14, FontWeight.SemiBold), color = c.text)
                    }
                }
                TextButton(enabled = validUrl, onClick = {
                    openFailed = false
                    try {
                        uriHandler.openUri(receipt.fiscalUrl)
                    } catch (_: ActivityNotFoundException) {
                        openFailed = true
                    } catch (_: IllegalArgumentException) {
                        openFailed = true
                    } catch (_: SecurityException) {
                        openFailed = true
                    }
                }) { Text(stringResource(R.string.receipt_open), color = if (validUrl) c.primary else c.muted) }
                if (!validUrl) Text(stringResource(R.string.receipt_invalid_link), style = sans(12), color = c.orange)
                if (validUrl && !openFailed) Text("suf.purs.gov.rs", style = sans(12), color = c.muted)
                if (openFailed) {
                    SelectionContainer { Text(receipt.fiscalUrl, style = sans(12), color = c.muted) }
                    Text(stringResource(R.string.receipt_open_failed), style = sans(12), color = c.orange)
                }
            }
        }
    }
}
