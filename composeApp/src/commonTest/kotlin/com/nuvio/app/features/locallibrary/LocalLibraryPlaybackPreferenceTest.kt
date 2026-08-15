package com.nuvio.app.features.locallibrary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalLibraryPlaybackPreferenceTest {
    @Test
    fun `alternate swaps source picker and local library`() {
        assertEquals(
            LocalLibraryPlaybackPreference.LOCAL_LIBRARY,
            LocalLibraryPlaybackPreference.SOURCE_PICKER.alternate(),
        )
        assertEquals(
            LocalLibraryPlaybackPreference.SOURCE_PICKER,
            LocalLibraryPlaybackPreference.LOCAL_LIBRARY.alternate(),
        )
    }

    @Test
    fun `normal and alternate actions resolve from the configured preference`() {
        assertEquals(
            LocalLibraryPlaybackPreference.SOURCE_PICKER,
            LocalLibraryPlaybackPreference.SOURCE_PICKER.behaviorFor(useAlternate = false),
        )
        assertEquals(
            LocalLibraryPlaybackPreference.LOCAL_LIBRARY,
            LocalLibraryPlaybackPreference.SOURCE_PICKER.behaviorFor(useAlternate = true),
        )
        assertEquals(
            LocalLibraryPlaybackPreference.LOCAL_LIBRARY,
            LocalLibraryPlaybackPreference.LOCAL_LIBRARY.behaviorFor(useAlternate = false),
        )
        assertEquals(
            LocalLibraryPlaybackPreference.SOURCE_PICKER,
            LocalLibraryPlaybackPreference.LOCAL_LIBRARY.behaviorFor(useAlternate = true),
        )
    }

    @Test
    fun `source picker preference only offers local alternate when a file exists`() {
        assertFalse(LocalLibraryPlaybackPreference.SOURCE_PICKER.canOfferAlternate(false))
        assertTrue(LocalLibraryPlaybackPreference.SOURCE_PICKER.canOfferAlternate(true))
    }

    @Test
    fun `local preference always offers source picker alternate`() {
        assertTrue(LocalLibraryPlaybackPreference.LOCAL_LIBRARY.canOfferAlternate(false))
        assertTrue(LocalLibraryPlaybackPreference.LOCAL_LIBRARY.canOfferAlternate(true))
    }

    @Test
    fun `local preference only overrides normal autoplay when a local file exists`() {
        assertFalse(
            LocalLibraryPlaybackPreference.SOURCE_PICKER.shouldUseManualStreamSelection(
                useAlternate = false,
                hasLocalFile = false,
            ),
        )
        assertTrue(
            LocalLibraryPlaybackPreference.SOURCE_PICKER.shouldUseManualStreamSelection(
                useAlternate = false,
                hasLocalFile = true,
            ),
        )
        assertFalse(
            LocalLibraryPlaybackPreference.LOCAL_LIBRARY.shouldUseManualStreamSelection(
                useAlternate = false,
                hasLocalFile = false,
            ),
        )
        assertFalse(
            LocalLibraryPlaybackPreference.LOCAL_LIBRARY.shouldUseManualStreamSelection(
                useAlternate = false,
                hasLocalFile = true,
            ),
        )
    }

    @Test
    fun `alternate playback falls back to picker if its local file disappears`() {
        assertTrue(
            LocalLibraryPlaybackPreference.SOURCE_PICKER.shouldUseManualStreamSelection(
                useAlternate = true,
                hasLocalFile = false,
            ),
        )
        assertFalse(
            LocalLibraryPlaybackPreference.SOURCE_PICKER.shouldUseManualStreamSelection(
                useAlternate = true,
                hasLocalFile = true,
            ),
        )
        assertTrue(
            LocalLibraryPlaybackPreference.LOCAL_LIBRARY.shouldUseManualStreamSelection(
                useAlternate = true,
                hasLocalFile = false,
            ),
        )
        assertTrue(
            LocalLibraryPlaybackPreference.LOCAL_LIBRARY.shouldUseManualStreamSelection(
                useAlternate = true,
                hasLocalFile = true,
            ),
        )
    }

    @Test
    fun `source picker can never enter the completed download shortcut`() {
        val decision = LocalLibraryPlaybackPreference.SOURCE_PICKER.resolvePlaybackRouting(
            useAlternate = false,
            hasDownloadedFile = true,
            hasLocalLibraryStream = true,
        )

        assertTrue(decision.manualSelection)
        assertFalse(decision.preferLocalStreams)
        assertFalse(decision.playDownloadedFileDirectly)
    }

    @Test
    fun `explicit local alternate may enter the completed download shortcut`() {
        val decision = LocalLibraryPlaybackPreference.SOURCE_PICKER.resolvePlaybackRouting(
            useAlternate = true,
            hasDownloadedFile = true,
            hasLocalLibraryStream = true,
        )

        assertFalse(decision.manualSelection)
        assertTrue(decision.preferLocalStreams)
        assertTrue(decision.playDownloadedFileDirectly)
    }
}
