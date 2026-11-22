package app.kinjar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.BusinessCenter
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.kinjar.R
import app.kinjar.data.AccountAppearance
import app.kinjar.data.AccountAppearances
import app.kinjar.data.AccountSymbol
import app.kinjar.data.AccountTint
import app.kinjar.ui.theme.LocalColors

val LocalAccountAppearances = staticCompositionLocalOf { AccountAppearances() }

fun AccountSymbol.icon() = when (this) {
    AccountSymbol.WALLET -> Icons.Outlined.AccountBalanceWallet
    AccountSymbol.CARD -> Icons.Outlined.CreditCard
    AccountSymbol.BANK -> Icons.Outlined.AccountBalance
    AccountSymbol.SAVINGS -> Icons.Outlined.Savings
    AccountSymbol.BUSINESS -> Icons.Outlined.BusinessCenter
    AccountSymbol.TRAVEL -> Icons.Outlined.Flight
    AccountSymbol.HOME -> Icons.Outlined.Home
}

fun AccountSymbol.label() = when (this) {
    AccountSymbol.WALLET -> R.string.account_icon_wallet
    AccountSymbol.CARD -> R.string.account_icon_card
    AccountSymbol.BANK -> R.string.account_icon_bank
    AccountSymbol.SAVINGS -> R.string.account_icon_savings
    AccountSymbol.BUSINESS -> R.string.account_icon_business
    AccountSymbol.TRAVEL -> R.string.account_icon_travel
    AccountSymbol.HOME -> R.string.account_icon_home
}

fun AccountTint.label() = when (this) {
    AccountTint.PURPLE -> R.string.account_color_purple
    AccountTint.GREEN -> R.string.account_color_green
    AccountTint.BLUE -> R.string.account_color_blue
    AccountTint.ORANGE -> R.string.account_color_orange
    AccountTint.PINK -> R.string.account_color_pink
    AccountTint.GRAY -> R.string.account_color_gray
    AccountTint.RED -> R.string.account_color_red
    AccountTint.TEAL -> R.string.account_color_teal
    AccountTint.CYAN -> R.string.account_color_cyan
    AccountTint.YELLOW -> R.string.account_color_yellow
    AccountTint.LIME -> R.string.account_color_lime
    AccountTint.INDIGO -> R.string.account_color_indigo
}

@Composable
fun AccountTint.tint(): Color = LocalColors.current.let { c -> when (this) {
    AccountTint.PURPLE -> c.primary
    AccountTint.GREEN -> c.green
    AccountTint.BLUE -> if (c.dark) Color(0xFF69B8F2) else Color(0xFF176BA0)
    AccountTint.ORANGE -> c.orange
    AccountTint.PINK -> if (c.dark) Color(0xFFF088B9) else Color(0xFFA5366B)
    AccountTint.GRAY -> c.muted
    AccountTint.RED -> if (c.dark) Color(0xFFFF7777) else Color(0xFFBD3030)
    AccountTint.TEAL -> if (c.dark) Color(0xFF4DD0B7) else Color(0xFF007D6B)
    AccountTint.CYAN -> if (c.dark) Color(0xFF55D6EF) else Color(0xFF00788D)
    AccountTint.YELLOW -> if (c.dark) Color(0xFFF4D35E) else Color(0xFF8A6800)
    AccountTint.LIME -> if (c.dark) Color(0xFFB9DB65) else Color(0xFF587A12)
    AccountTint.INDIGO -> if (c.dark) Color(0xFFB5A0FF) else Color(0xFF6342B5)
} }

@Composable
fun AccountBadge(id: String, modifier: Modifier = Modifier) {
    AccountBadge(LocalAccountAppearances.current.appearance(id), modifier)
}

@Composable
fun AccountBadge(appearance: AccountAppearance, modifier: Modifier = Modifier) {
    val tint = appearance.color.tint()
    Box(modifier.size(36.dp).background(tint.copy(alpha = LocalColors.current.tintAlpha), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        Icon(appearance.icon.icon(), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}
