package com.nuvio.app.features.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class HeroFocusSettleTest {
    @Test
    fun `an isolated change shows at once, a fast run holds until focus stops`() = runComposeUiTest {
        mainClock.autoAdvance = false
        var target by mutableIntStateOf(0)
        var shown = -1
        val observed = mutableListOf<Int>()
        setContent {
            shown = rememberSettledHeroFocus(target, nowMs = { mainClock.currentTime })
            if (observed.lastOrNull() != shown) observed += shown
        }
        mainClock.advanceTimeBy(1_000)
        assertEquals(0, shown)

        // A single press after a pause: on the same frame.
        target = 1
        mainClock.advanceTimeByFrame()
        assertEquals(1, shown)

        // Key held: the first step (2) is an ordinary press and shows at once; the repeats 3..6,
        // ~32 ms apart, are held and never shown.
        mainClock.advanceTimeBy(500)
        repeat(5) {
            target += 1
            mainClock.advanceTimeBy(32)
        }
        assertEquals(2, shown)
        // Shown once focus has stayed put for the settle window, and nothing in between was.
        mainClock.advanceTimeBy(HERO_FOCUS_SETTLE_MS + 50)
        assertEquals(6, shown)
        assertEquals(listOf(0, 1, 2, 6), observed)
    }
}
