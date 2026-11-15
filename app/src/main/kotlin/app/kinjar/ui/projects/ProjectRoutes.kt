package app.kinjar.ui.projects

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import app.kinjar.ui.Routes

private val NavBackStackEntry.projectId: String get() = requireNotNull(arguments?.getString("id"))

fun NavGraphBuilder.projectRoutes(nav: NavHostController) {
    composable(Routes.PROJECT_PAYMENTS) { entry ->
        if (entry.arguments?.getString("mode") == "settle") {
            ProjectLinkScreen(onBack = { nav.popBackStack() }, initialTab = 2)
        } else ProjectPaymentsScreen(onBack = { nav.popBackStack() })
    }
    composable(Routes.NEW_PROJECT) {
        NewProjectScreen(onBack = { nav.popBackStack() }, onCreated = { id ->
            nav.navigate(Routes.project(id)) { popUpTo(Routes.NEW_PROJECT) { inclusive = true } }
        })
    }
    composable(Routes.PROJECT) { entry -> ProjectDetailScreen(actions(nav, entry.projectId)) }
    composable(Routes.PROJECT_COSTS) { entry -> ProjectDetailScreen(actions(nav, entry.projectId), all = true) }
    composable(Routes.PROJECT_LINK) {
        ProjectLinkScreen(onBack = { nav.popBackStack() })
    }
    composable(Routes.PROJECT_SETTLE) { ProjectPaymentScreen(onBack = { nav.popBackStack() }) }
    composable(Routes.PROJECT_REVIEW) { CostReviewScreen(onBack = { nav.popBackStack() }) }
    composable(Routes.PROJECT_EDIT_COST) { PastCostScreen(onBack = { nav.popBackStack() }, tabs = {}) }
    composable(Routes.PROJECT_SETTINGS) { entry ->
        ProjectSettingsScreen(onBack = { nav.popBackStack() }, onCurrency = { nav.navigate(Routes.projectCurrency(entry.projectId)) })
    }
    composable(Routes.PROJECT_CURRENCY) {
        CurrencyChangeScreen(onBack = { nav.popBackStack() }, onDone = { nav.popBackStack(Routes.PROJECT, inclusive = false) })
    }
}

private fun actions(nav: NavHostController, id: String) = ProjectActions(
    onBack = { nav.popBackStack() },
    onBulkSettle = { selected -> nav.navigate(Routes.projectPayments(id, "settle", selected)) },
    onPayments = { nav.navigate(Routes.projectPayments(id)) },
    onSettings = { nav.navigate(Routes.projectSettings(id)) },
    onLink = { nav.navigate(Routes.projectLink(id)) },
    onSettle = { costId -> nav.navigate(Routes.projectSettle(id, costId)) },
    onAllCosts = { nav.navigate(Routes.projectCosts(id)) },
    onReview = { costId -> nav.navigate(Routes.projectReview(id, costId)) },
    onEdit = { cost -> nav.navigate(cost.transactionId?.let(Routes::editTransaction) ?: Routes.projectEditCost(id, cost.id)) },
)
