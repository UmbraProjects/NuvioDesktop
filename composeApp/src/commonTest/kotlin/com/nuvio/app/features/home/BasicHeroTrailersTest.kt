package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Basic's hero hosts trailers on different terms from Adaptive/TV, so the thing worth pinning down
 * is which surfaces the Basic path is allowed to claim. Every mode and content mode it must stay
 * out of already has a working trailer story (or deliberately has none), and letting the Basic
 * branch reach one of them would put a full-screen video inside a hero that never asked for it.
 */
class BasicHeroTrailersTest {

    private fun allowed(
        mode: HomeDisplayMode = HomeDisplayMode.Basic,
        isDesktop: Boolean = true,
        heroVisible: Boolean = true,
        isNormalHomeMode: Boolean = true,
    ) = basicHeroTrailersAllowed(
        mode = mode,
        isDesktop = isDesktop,
        heroVisible = heroVisible,
        isNormalHomeMode = isNormalHomeMode,
    )

    @Test
    fun `basic desktop home with a hero is the one allowed case`() {
        assertTrue(allowed())
    }

    @Test
    fun `the other display modes keep their own trailer path`() {
        HomeDisplayMode.entries
            .filter { it != HomeDisplayMode.Basic }
            .forEach { mode ->
                assertFalse(allowed(mode = mode), "$mode must not use the Basic trailer path")
            }
    }

    @Test
    fun `search, library and discover are excluded`() {
        // Their heroes follow the focused result; that is heroFollowsFocusedItem's job, not this one.
        assertFalse(allowed(isNormalHomeMode = false))
    }

    @Test
    fun `a hidden hero has nowhere to play`() {
        assertFalse(allowed(heroVisible = false))
    }

    @Test
    fun `mobile is excluded`() {
        assertFalse(allowed(isDesktop = false))
    }
}
