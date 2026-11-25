package com.nuvio.app.features.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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

    @Test
    fun `a held provisional target is never shown, and the one ending the hold shows at once`() = runComposeUiTest {
        mainClock.autoAdvance = false
        var target by mutableIntStateOf(0)
        var hold by mutableStateOf(false)
        var shown = -1
        val observed = mutableListOf<Int>()
        setContent {
            shown = rememberSettledHeroFocus(target, nowMs = { mainClock.currentTime }, hold = hold)
            if (observed.lastOrNull() != shown) observed += shown
        }
        mainClock.advanceTimeBy(1_000)

        // Wheel row change: the carried column (1) is provisional.
        target = 1
        hold = true
        mainClock.advanceTimeBy(100)
        assertEquals(0, shown)

        // The cursor's poster lands well inside the settle window, and still shows on that frame.
        target = 2
        hold = false
        mainClock.advanceTimeByFrame()
        assertEquals(2, shown)
        assertEquals(listOf(0, 2), observed)
    }

    @Test
    fun `regular double-taps stay instant while sustained mouse-wheel scrolling holds the whole hero`() = runComposeUiTest {
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

        // Regular browsing: a quick two-step move (300 ms apart) shows both immediately.
        target = 1
        mainClock.advanceTimeByFrame()
        assertEquals(1, shown)
        mainClock.advanceTimeBy(300)
        target = 2
        mainClock.advanceTimeByFrame()
        assertEquals(2, shown)

        // Sustained scrolling (notches every ~300 ms): steps 3..7 are held together until pause.
        for (next in 3..7) {
            mainClock.advanceTimeBy(300)
            target = next
            mainClock.advanceTimeByFrame()
        }
        assertEquals(2, shown)

        // Once scrolling pauses for the sustained settle window, the final target shows cleanly.
        mainClock.advanceTimeBy(HERO_SUSTAINED_SCROLL_SETTLE_MS + 50)
        assertEquals(7, shown)
        assertEquals(listOf(0, 1, 2, 7), observed)

        // After pausing, the next regular single move is instant again.
        mainClock.advanceTimeBy(HERO_SUSTAINED_SCROLL_WINDOW_MS + 100)
        target = 8
        mainClock.advanceTimeByFrame()
        assertEquals(8, shown)
    }
}
