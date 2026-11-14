package app.kinjar.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideOut
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import app.kinjar.ui.Routes
import app.kinjar.ui.Tab

enum class PageKind { TAB, DETAIL, FORM }

enum class Motion { NONE, PUSH, RISE }

internal val formRoutes = setOf(
    Routes.ADD, Routes.EDIT_TRANSACTION, Routes.CLOUD_SETUP,
    Routes.NEW_CATEGORY, Routes.EDIT_CATEGORY, Routes.NEW_RECURRING, Routes.EDIT_RECURRING,
    Routes.NEW_ACCOUNT, Routes.EDIT_ACCOUNT, Routes.NEW_LOAN, Routes.EDIT_LOAN,
    Routes.NEW_GOAL, Routes.EDIT_GOAL,
    Routes.NEW_PROJECT, Routes.PROJECT_LINK, Routes.PROJECT_SETTLE, Routes.PROJECT_REVIEW, Routes.PROJECT_CURRENCY,
    Routes.PROJECT_EDIT_COST,
)

internal val detailRoutes = setOf(
    Routes.SETUP, Routes.ACTIVITY, Routes.SETTINGS, Routes.PROFILE, Routes.FAMILY, Routes.BACKUP,
    Routes.EXCHANGE_RATES, Routes.CATEGORIES, Routes.RECURRING, Routes.LOAN,
    Routes.PROJECT, Routes.PROJECT_COSTS, Routes.PROJECT_SETTINGS,
)

fun pageKind(route: String?): PageKind = when {
    Tab.entries.any { it.route == route } -> PageKind.TAB
    route in formRoutes -> PageKind.FORM
    else -> PageKind.DETAIL
}

/** Pops animate by the departing page, so closing a form always drops it, whatever it reveals. */
fun navMotion(from: String?, to: String?, pop: Boolean): Motion {
    val (departing, arriving) = pageKind(from) to pageKind(to)
    return when {
        departing == PageKind.TAB && arriving == PageKind.TAB -> Motion.NONE
        (if (pop) departing else arriving) == PageKind.FORM -> Motion.RISE
        else -> Motion.PUSH
    }
}

private const val PUSH_MS = 260
private const val RISE_MS = 280

private fun <T> spec(ms: Int) = tween<T>(ms, easing = FastOutSlowInEasing)

// ExitTransition.None drops the covered page on the first frame; a zero slide keeps it drawn under the incoming one.
private fun hold(ms: Int) = slideOut(spec(ms)) { IntOffset.Zero }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.motion(pop: Boolean) =
    navMotion(initialState.destination.route, targetState.destination.route, pop)

fun AnimatedContentTransitionScope<NavBackStackEntry>.navEnter(): EnterTransition = when (motion(pop = false)) {
    Motion.NONE -> EnterTransition.None
    Motion.PUSH -> slideIntoContainer(SlideDirection.Start, spec(PUSH_MS))
    Motion.RISE -> slideIntoContainer(SlideDirection.Up, spec(RISE_MS))
}

fun AnimatedContentTransitionScope<NavBackStackEntry>.navExit(): ExitTransition = when (motion(pop = false)) {
    Motion.NONE -> ExitTransition.None
    Motion.PUSH -> hold(PUSH_MS)
    Motion.RISE -> hold(RISE_MS)
}

fun AnimatedContentTransitionScope<NavBackStackEntry>.navPopExit(): ExitTransition = when (motion(pop = true)) {
    Motion.NONE -> ExitTransition.None
    Motion.PUSH -> slideOutOfContainer(SlideDirection.End, spec(PUSH_MS))
    Motion.RISE -> slideOutOfContainer(SlideDirection.Down, spec(RISE_MS))
}
