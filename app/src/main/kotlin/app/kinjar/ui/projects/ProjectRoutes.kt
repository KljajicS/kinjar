package app.kinjar.ui.projects

import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.kinjar.ui.Routes
import app.kinjar.ui.add.AddScreen
import app.kinjar.ui.add.AddViewModel

private val NavBackStackEntry.projectId: String get() = requireNotNull(arguments?.getString("id"))

fun NavGraphBuilder.projectRoutes(nav: NavHostController) {
    composable(Routes.NEW_PROJECT) {
        NewProjectScreen(onBack = { nav.popBackStack() }, onCreated = { id ->
            nav.navigate(Routes.project(id)) { popUpTo(Routes.NEW_PROJECT) { inclusive = true } }
        })
    }
    composable(Routes.PROJECT) { entry -> ProjectDetailScreen(actions(nav, entry.projectId)) }
    composable(Routes.PROJECT_COSTS) { entry -> ProjectDetailScreen(actions(nav, entry.projectId), all = true) }
    composable(Routes.PROJECT_LINK, arguments = listOf(navArgument("select") { nullable = true; defaultValue = null })) {
        ProjectLinkScreen(onBack = { nav.popBackStack() })
    }
    composable(Routes.PROJECT_PAST_COST) { PastCostScreen(onBack = { nav.popBackStack() }) }
    composable(Routes.PROJECT_REVIEW) { CostReviewScreen(onBack = { nav.popBackStack() }) }
    composable(Routes.PROJECT_SETTINGS) { entry ->
        ProjectSettingsScreen(onBack = { nav.popBackStack() }, onCurrency = { nav.navigate(Routes.projectCurrency(entry.projectId)) })
    }
    composable(Routes.PROJECT_CURRENCY) {
        CurrencyChangeScreen(onBack = { nav.popBackStack() }, onDone = { nav.popBackStack(Routes.PROJECT, inclusive = false) })
    }
    // A saved expense is not linked yet: it opens preselected in the picker, where the user confirms the link.
    composable(Routes.PROJECT_EXPENSE) { entry ->
        val project = requireNotNull(entry.arguments?.getString("project"))
        val add: AddViewModel = hiltViewModel()
        AddScreen(vm = add, onClose = {
            val saved = add.state.value.savedId
            if (saved == null) nav.popBackStack()
            else nav.navigate(Routes.projectLink(project, saved)) { popUpTo(Routes.PROJECT_EXPENSE) { inclusive = true } }
        })
    }
}

private fun actions(nav: NavHostController, id: String) = ProjectActions(
    onBack = { nav.popBackStack() },
    onSettings = { nav.navigate(Routes.projectSettings(id)) },
    onLink = { nav.navigate(Routes.projectLink(id)) },
    onNewExpense = { nav.navigate(Routes.projectExpense(id)) },
    onPastCost = { nav.navigate(Routes.projectPastCost(id)) },
    onAllCosts = { nav.navigate(Routes.projectCosts(id)) },
    onReview = { costId -> nav.navigate(Routes.projectReview(id, costId)) },
)
