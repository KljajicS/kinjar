package app.kinjar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kinjar.R
import app.kinjar.domain.Category
import app.kinjar.domain.Transaction
import app.kinjar.ui.theme.CaptionStyle
import app.kinjar.ui.theme.LabelStyle
import app.kinjar.ui.theme.LocalColors
import app.kinjar.ui.theme.mono
import app.kinjar.ui.theme.sans
import java.time.format.DateTimeFormatter

@Composable
internal fun ExpenseRows(
    expenses: List<Transaction>,
    categories: Map<String, Category>,
    accountNames: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    val c = LocalColors.current
    val locale = LocalLocale.current.platformLocale
    val dates = DateTimeFormatter.ofPattern("d MMM", monthLocale(locale))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (expenses.isEmpty()) Text(stringResource(R.string.insights_drilldown_empty), style = LabelStyle, color = c.muted)
        expenses.forEachIndexed { i, t ->
            if (i > 0) HorizontalDivider(color = c.border, thickness = 1.dp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    val title = t.merchant.ifBlank { t.note }.ifBlank {
                        t.categoryId?.let { categoryDisplayName(it, categories[it]?.name) }
                            ?: stringResource(R.string.insights_other)
                    }
                    Text(title, style = sans(14, FontWeight.Medium), color = c.text, maxLines = 1)
                    Text(listOfNotNull(t.date.format(dates), accountNames[t.accountId]).joinToString(" · "), style = CaptionStyle, color = c.muted)
                }
                Text("-${formatAmount(t.money, locale = locale)} ${t.money.currency}", style = mono(14), color = c.text)
            }
        }
    }
}
