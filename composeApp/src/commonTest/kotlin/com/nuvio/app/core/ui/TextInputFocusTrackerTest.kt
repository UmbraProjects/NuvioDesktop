package com.nuvio.app.core.ui

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextInputFocusTrackerTest {
    @AfterTest
    fun cleanUp() {
        TextInputFocusTracker.reset()
    }

    @Test
    fun `a focused field anywhere in the app holds the shortcut lock`() {
        // The content search bar is not on the Settings screen, but typing in it must still stop
        // single-key navigation shortcuts from firing.
        val searchBar = Any()
        TextInputFocusTracker.register(searchBar)
        TextInputFocusTracker.setFocused(searchBar, true)

        assertTrue(TextInputFocusTracker.active.value)

        TextInputFocusTracker.setFocused(searchBar, false)

        assertFalse(TextInputFocusTracker.active.value)
    }

    @Test
    fun `a field leaving composition releases the lock`() {
        val field = Any()
        TextInputFocusTracker.register(field)
        TextInputFocusTracker.setFocused(field, true)

        TextInputFocusTracker.unregister(field)

        assertFalse(TextInputFocusTracker.active.value)
    }

    @Test
    fun `late focus callback cannot wedge shortcuts after the field is gone`() {
        val field = Any()
        TextInputFocusTracker.register(field)
        TextInputFocusTracker.unregister(field)

        TextInputFocusTracker.setFocused(field, true)

        assertFalse(TextInputFocusTracker.active.value)
    }

    @Test
    fun `one field disappearing does not release another field's lock`() {
        val gone = Any()
        val stillFocused = Any()
        TextInputFocusTracker.register(gone)
        TextInputFocusTracker.register(stillFocused)
        TextInputFocusTracker.setFocused(gone, true)
        TextInputFocusTracker.setFocused(stillFocused, true)

        TextInputFocusTracker.unregister(gone)

        assertTrue(TextInputFocusTracker.active.value)
    }
}
