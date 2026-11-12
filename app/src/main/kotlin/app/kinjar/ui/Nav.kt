package app.kinjar.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.ui.graphics.vector.ImageVector
import app.kinjar.R

enum class Tab(val route: String, @StringRes val label: Int, val icon: ImageVector) {
    HOME("home", R.string.tab_home, Icons.Outlined.Home),
    INSIGHTS("insights", R.string.tab_insights, Icons.Outlined.BarChart),
    SCAN("scan", R.string.tab_scan, Icons.Outlined.QrCodeScanner),
    ACCOUNTS("accounts", R.string.tab_accounts, Icons.Outlined.AccountBalanceWallet),
    /** Projects and savings goals share this tab; the route keeps its old name so saved back stacks still restore. */
    GOALS("goals", R.string.tab_projects, Icons.Outlined.Handyman),
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
    const val NEW_CATEGORY = "settings/categories/new?kind={kind}"
    fun newCategory(kind: app.kinjar.domain.CategoryKind) = "settings/categories/new?kind=${kind.name}"
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
    const val NEW_PROJECT = "projects/new"
    const val PROJECT = "projects/{id}"
    fun project(id: String) = "projects/$id"
    const val PROJECT_COSTS = "projects/{id}/costs"
    fun projectCosts(id: String) = "projects/$id/costs"
    const val PROJECT_LINK = "projects/{id}/link?select={select}"
    fun projectLink(id: String, select: String? = null) = if (select == null) "projects/$id/link" else "projects/$id/link?select=$select"
    const val PROJECT_PAST_COST = "projects/{id}/past"
    fun projectPastCost(id: String) = "projects/$id/past"
    const val PROJECT_REVIEW = "projects/{id}/review/{costId}"
    fun projectReview(id: String, costId: String) = "projects/$id/review/$costId"
    const val PROJECT_SETTINGS = "projects/{id}/settings"
    fun projectSettings(id: String) = "projects/$id/settings"
    const val PROJECT_CURRENCY = "projects/{id}/currency"
    fun projectCurrency(id: String) = "projects/$id/currency"
    /** Not `{id}`: AddScreen reads an `id` argument as the transaction to edit. */
    const val PROJECT_EXPENSE = "projects/{project}/expense"
    fun projectExpense(id: String) = "projects/$id/expense"
}
