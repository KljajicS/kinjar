package app.kinjar.ui.navigation

import app.kinjar.ui.Routes
import app.kinjar.ui.Tab
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavMotionTest {
    @Test
    fun everyRoutePatternIsClassifiedExactlyOnce() {
        val patterns = Routes::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.get(null) as String }
        assertTrue(patterns.isNotEmpty())
        patterns.forEach { route ->
            assertTrue("$route must be either a form or a detail", (route in formRoutes) xor (route in detailRoutes))
        }
        assertEquals(patterns.toSet(), formRoutes + detailRoutes)
        assertTrue(Tab.entries.none { it.route in formRoutes || it.route in detailRoutes })
    }

    @Test
    fun classifiesQueryAndProjectPatterns() {
        assertEquals(PageKind.FORM, pageKind("add?rule={rule}&due={due}"))
        assertEquals(PageKind.FORM, pageKind("settings/categories/new?kind={kind}"))
        assertEquals(PageKind.DETAIL, pageKind("settings/family?signIn={signIn}"))
        assertEquals(PageKind.DETAIL, pageKind(Routes.PROJECT))
        assertEquals(PageKind.DETAIL, pageKind(Routes.PROJECT_COSTS))
        assertEquals(PageKind.FORM, pageKind(Routes.PROJECT_SETTLE))
        assertEquals(PageKind.FORM, pageKind(Routes.PROJECT_CURRENCY))
        Tab.entries.forEach { assertEquals(PageKind.TAB, pageKind(it.route)) }
    }

    @Test
    fun tabSwitchesAreInstantBothWays() {
        Tab.entries.forEach { from ->
            Tab.entries.forEach { to ->
                assertEquals(Motion.NONE, navMotion(from.route, to.route, pop = false))
                assertEquals(Motion.NONE, navMotion(from.route, to.route, pop = true))
            }
        }
    }

    @Test
    fun forwardMotionFollowsArrivingPage() {
        assertEquals(Motion.PUSH, navMotion(Tab.HOME.route, Routes.SETTINGS, pop = false))
        assertEquals(Motion.PUSH, navMotion(Routes.SETTINGS, Routes.CATEGORIES, pop = false))
        assertEquals(Motion.RISE, navMotion(Tab.HOME.route, Routes.ADD, pop = false))
        assertEquals(Motion.RISE, navMotion(Tab.SCAN.route, Routes.ADD, pop = false))
        assertEquals(Motion.RISE, navMotion(Routes.RECURRING, Routes.ADD, pop = false))
        assertEquals(Motion.RISE, navMotion(Routes.PROJECT, Routes.PROJECT_LINK, pop = false))
        assertEquals(Motion.PUSH, navMotion(Routes.NEW_PROJECT, Routes.PROJECT, pop = false))
        assertEquals(Motion.PUSH, navMotion(Routes.SETUP, Tab.HOME.route, pop = false))
    }

    @Test
    fun popMotionFollowsDepartingPage() {
        assertEquals(Motion.RISE, navMotion(Routes.ADD, Tab.HOME.route, pop = true))
        assertEquals(Motion.RISE, navMotion(Routes.ADD, Tab.SCAN.route, pop = true))
        assertEquals(Motion.RISE, navMotion(Routes.EDIT_LOAN, Routes.LOAN, pop = true))
        assertEquals(Motion.RISE, navMotion(Routes.PROJECT_CURRENCY, Routes.PROJECT, pop = true))
        assertEquals(Motion.PUSH, navMotion(Routes.LOAN, Tab.ACCOUNTS.route, pop = true))
        assertEquals(Motion.PUSH, navMotion(Routes.PROJECT_SETTINGS, Routes.PROJECT, pop = true))
    }
}
