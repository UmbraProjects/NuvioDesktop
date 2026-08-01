package com.nuvio.app

import kotlin.test.Test
import kotlin.test.assertEquals

class RootTabNavigationTest {
    @Test
    fun `settings home search back target is home`() {
        var history = emptyList<AppScreenTab>()
        var current = AppScreenTab.Home

        fun navigate(target: AppScreenTab) {
            history = rootTabHistoryAfterNavigation(history, current, target)
            current = target
        }

        navigate(AppScreenTab.Settings)
        navigate(AppScreenTab.Home)
        navigate(AppScreenTab.Search)

        assertEquals(AppScreenTab.Home, history.last())
    }

    @Test
    fun `history never records a duplicate current tab`() {
        val history = listOf(AppScreenTab.Home)

        assertEquals(
            history,
            rootTabHistoryAfterNavigation(history, AppScreenTab.Home, AppScreenTab.Search),
        )
    }
}
