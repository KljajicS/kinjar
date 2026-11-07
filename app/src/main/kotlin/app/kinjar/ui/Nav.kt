package app.kinjar.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.ui.graphics.vector.ImageVector
import app.kinjar.R

enum class Tab(val route: String, @StringRes val label: Int, val icon: ImageVector) {
    HOME("home", R.string.tab_home, Icons.Outlined.Home),
    INSIGHTS("insights", R.string.tab_insights, Icons.Outlined.BarChart),
    SCAN("scan", R.string.tab_scan, Icons.Outlined.QrCodeScanner),
    ACCOUNTS("accounts", R.string.tab_accounts, Icons.Outlined.AccountBalanceWallet),
    GOALS("goals", R.string.tab_goals, Icons.Outlined.TrackChanges),
}

object Routes {
    const val ACTIVITY = "activity"
    const val NEW_LOAN = "loans/new"
    const val LOAN = "loans/{id}"
    const val EDIT_LOAN = "loans/{id}/edit"
    fun loan(id: String) = "loans/$id"
    fun editLoan(id: String) = "loans/$id/edit"
    const val SETUP = "setup"
    const val ADD = "add?rule={rule}&due={due}"
    fun add() = "add"
    fun addBill(ruleId: String, due: java.time.LocalDate) = "add?rule=$ruleId&due=$due"
    const val EDIT_TRANSACTION = "transactions/{id}/edit"
    fun editTransaction(id: String) = "transactions/$id/edit"
    const val SETTINGS = "settings"
    const val PROFILE = "settings/profile"
    const val FAMILY = "settings/family?signIn={signIn}"
    fun family(signIn: Boolean = false) = "settings/family?signIn=$signIn"
    const val CLOUD_SETUP = "setup/cloud"
    const val BACKUP = "settings/backup"
    const val EXCHANGE_RATES = "settings/exchange-rates"
    const val CATEGORIES = "settings/categories"
    const val NEW_CATEGORY = "settings/categories/new"
    const val EDIT_CATEGORY = "settings/categories/edit/{id}"
    fun editCategory(id: String) = "settings/categories/edit/$id"
    const val NEW_ACCOUNT = "accounts/new"
    const val EDIT_ACCOUNT = "accounts/edit/{id}"
    fun editAccount(id: String) = "accounts/edit/$id"
    const val RECURRING = "settings/recurring"
    const val NEW_RECURRING = "settings/recurring/new"
    const val EDIT_RECURRING = "settings/recurring/edit/{id}"
    fun editRecurring(id: String) = "settings/recurring/edit/$id"
    const val NEW_GOAL = "goals/new"
    const val EDIT_GOAL = "goals/{id}/edit"
    fun editGoal(id: String) = "goals/$id/edit"
}
