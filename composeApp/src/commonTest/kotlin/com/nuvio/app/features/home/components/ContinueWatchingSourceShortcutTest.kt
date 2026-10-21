package com.nuvio.app.features.home.components

import com.nuvio.app.features.cloud.CloudLibraryContentType
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.ContinueWatchingSourceShortcut
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContinueWatchingSourceShortcutTest {
    private fun item(progressFraction: Float, type: String = "movie") = ContinueWatchingItem(
        parentMetaId = "tt1",
        parentMetaType = type,
        videoId = "tt1",
        title = "Film",
        subtitle = "",
        imageUrl = null,
        seasonNumber = null,
        episodeNumber = null,
        episodeTitle = null,
        resumePositionMs = 0L,
        durationMs = 0L,
        progressFraction = progressFraction,
    )

    @Test
    fun `reuse last link on offers the source picker`() {
        assertEquals(
            ContinueWatchingSourceShortcutKind.OpenSourcePicker,
            continueWatchingSourceShortcutKind(item(0.4f), ContinueWatchingSourceShortcut.CORNER, reuseLastLinkEnabled = true),
        )
        // Up Next has no link to reuse, but can still pick a source.
        assertEquals(
            ContinueWatchingSourceShortcutKind.OpenSourcePicker,
            continueWatchingSourceShortcutKind(item(0f), ContinueWatchingSourceShortcut.CENTER, reuseLastLinkEnabled = true),
        )
    }

    @Test
    fun `reuse last link off offers the last link only once played`() {
        assertEquals(
            ContinueWatchingSourceShortcutKind.ReuseLastLink,
            continueWatchingSourceShortcutKind(item(0.4f), ContinueWatchingSourceShortcut.CORNER, reuseLastLinkEnabled = false),
        )
        assertNull(continueWatchingSourceShortcutKind(item(0f), ContinueWatchingSourceShortcut.CORNER, reuseLastLinkEnabled = false))
    }

    @Test
    fun `off and cloud library items get no shortcut`() {
        assertNull(continueWatchingSourceShortcutKind(item(0.4f), ContinueWatchingSourceShortcut.OFF, reuseLastLinkEnabled = true))
        assertNull(
            continueWatchingSourceShortcutKind(item(0.4f, type = CloudLibraryContentType), ContinueWatchingSourceShortcut.CORNER, reuseLastLinkEnabled = true),
        )
    }
}
