package com.nuvio.app.features.streams

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class StreamListPointerHoldTest {
    @Test
    fun `a new arrangement waits while the mouse is in use, and a reset shows at once`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val activity = ListPointerActivity()
        var live by mutableStateOf(listOf("a", "b"))
        var filter by mutableStateOf("all")
        var shown: List<String> = emptyList()
        setContent {
            shown = rememberHeldWhilePointerActive(
                live = live,
                resetKey = filter,
                activity = activity,
                canHold = { it.isNotEmpty() },
                nowMs = { mainClock.currentTime },
            )
        }
        mainClock.advanceTimeBy(2_000)

        // Pointer idle: an arriving source shows straight away.
        live = listOf("c", "a", "b")
        mainClock.advanceTimeByFrame()
        assertEquals(listOf("c", "a", "b"), shown)

        // Pointer moving over the list: the next arrival waits.
        activity.lastActiveAtMs = mainClock.currentTime
        live = listOf("d", "c", "a", "b")
        mainClock.advanceTimeByFrame()
        assertEquals(listOf("c", "a", "b"), shown)
        // Still moving 500 ms later: still held.
        mainClock.advanceTimeBy(500)
        activity.lastActiveAtMs = mainClock.currentTime
        mainClock.advanceTimeBy(400)
        assertEquals(listOf("c", "a", "b"), shown)
        // Still for the hold window: applied.
        mainClock.advanceTimeBy(STREAM_LIST_POINTER_HOLD_MS + 100)
        assertEquals(listOf("d", "c", "a", "b"), shown)

        // A chip click (reset key) is the user's own change: shown at once, pointer active or not.
        activity.lastActiveAtMs = mainClock.currentTime
        filter = "addonB"
        live = listOf("x")
        mainClock.advanceTimeByFrame()
        assertEquals(listOf("x"), shown)
    }
}
