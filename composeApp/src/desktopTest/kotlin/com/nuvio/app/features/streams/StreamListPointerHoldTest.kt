package com.nuvio.app.features.streams

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun `a change that moves nothing under the pointer shows at once, and waits once the pointer reaches it`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val activity = ListPointerActivity()
        var live by mutableStateOf(listOf("a", "b"))
        var pointerKey by mutableStateOf<String?>("a")
        var shown: List<String> = emptyList()
        setContent {
            shown = rememberHeldWhilePointerActive(
                live = live,
                resetKey = null,
                activity = activity,
                canHold = { it.isNotEmpty() },
                movesUnderPointer = { from, to ->
                    arrangementMovesUnderPointer(from, to, anchorKey = null, pointerKey = pointerKey)
                },
                nowMs = { mainClock.currentTime },
            )
        }
        mainClock.advanceTimeByFrame()

        // Pointer moving over "a"; a source lands below it: shown straight away.
        activity.lastActiveAtMs = mainClock.currentTime
        live = listOf("a", "b", "c")
        mainClock.advanceTimeByFrame()
        assertEquals(listOf("a", "b", "c"), shown)

        // One lands above it: held.
        activity.lastActiveAtMs = mainClock.currentTime
        live = listOf("z", "a", "b", "c")
        mainClock.advanceTimeByFrame()
        assertEquals(listOf("a", "b", "c"), shown)
        mainClock.advanceTimeBy(STREAM_LIST_POINTER_HOLD_MS + 200)
        assertEquals(listOf("z", "a", "b", "c"), shown)
    }

    @Test
    fun `only rows from the anchor down to the pointer count`() {
        val shown = listOf("h1", "a", "b", "h2", "c")
        // Appended below the pointer.
        assertFalse(arrangementMovesUnderPointer(shown, shown + "d", anchorKey = null, pointerKey = "b"))
        // Inserted between rows above the pointer.
        assertTrue(arrangementMovesUnderPointer(shown, listOf("h1", "x", "a", "b", "h2", "c"), null, "b"))
        // Inserted right after the pointer row: nothing it aims at moves.
        assertFalse(arrangementMovesUnderPointer(shown, listOf("h1", "a", "b", "x", "h2", "c"), null, "b"))
        // Scrolled: a list anchored on "a" keeps it put, so a row slotted in above the viewport moves nothing.
        assertFalse(arrangementMovesUnderPointer(shown, listOf("x", "h1", "a", "b", "h2", "c"), "a", "b"))
        // Unless it lands between the anchor and the pointer.
        assertTrue(arrangementMovesUnderPointer(shown, listOf("h1", "a", "x", "b", "h2", "c"), "a", "b"))
        // The pointer's row itself gone, or the pointer not over a known row: hold.
        assertTrue(arrangementMovesUnderPointer(shown, listOf("h1", "a", "h2", "c"), null, "b"))
        assertTrue(arrangementMovesUnderPointer(shown, shown + "d", null, null))
        assertTrue(arrangementMovesUnderPointer(shown, shown + "d", null, "footer_loading"))
    }
}
