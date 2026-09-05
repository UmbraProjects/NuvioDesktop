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
    fun `alternate label follows the action rather than the configured preference`() {
        // No local file: whatever the preference, the alternate is the source picker — which is
        // why the entry is offered even under SOURCE_PICKER, where it overrides stream auto-play.
        assertTrue(LocalLibraryPlaybackPreference.SOURCE_PICKER.alternateOpensSourcePicker(false))
        assertTrue(LocalLibraryPlaybackPreference.LOCAL_LIBRARY.alternateOpensSourcePicker(false))
        // With a local file the alternate is whichever route the preference is not already taking.
        assertFalse(LocalLibraryPlaybackPreference.SOURCE_PICKER.alternateOpensSourcePicker(true))
        assertTrue(LocalLibraryPlaybackPreference.LOCAL_LIBRARY.alternateOpensSourcePicker(true))
    }

    @Test
    fun `every choose-source entry point actually reaches the picker`() {
        // The "Choose source" rows and the details Play button's secondary gesture all pass
        // `alternateOpensSourcePicker(hasLocalFile)` as their useAlternate value. That is only
        // correct if it lands on manual selection in every combination — including the one that
        // caught this out, Source picker + a local file, where the picker is the NORMAL route and
        // useAlternate=true would have played the local file instead.
        for (preference in LocalLibraryPlaybackPreference.entries) {
            for (hasLocalFile in listOf(false, true)) {
                val useAlternate = preference.alternateOpensSourcePicker(hasLocalFile)
                val decision = preference.resolvePlaybackRouting(
                    useAlternate = useAlternate,
                    hasDownloadedFile = hasLocalFile,
                    hasLocalLibraryStream = false,
                )

                assertTrue(
                    decision.manualSelection,
                    "$preference with hasLocalFile=$hasLocalFile should open the picker",
                )
                assertFalse(
                    decision.playDownloadedFileDirectly,
                    "$preference with hasLocalFile=$hasLocalFile must not play the file instead",
                )
            }
        }
    }

    @Test
    fun `every play-local-file entry point actually plays the file`() {
        // Its mirror: the local-file row passes useAlternate=true, and is only shown when the
        // normal click is not already playing the file (Source picker + a local file).
        val decision = LocalLibraryPlaybackPreference.SOURCE_PICKER.resolvePlaybackRouting(
            useAlternate = true,
            hasDownloadedFile = true,
            hasLocalLibraryStream = false,
        )

        assertFalse(decision.manualSelection)
        assertTrue(decision.preferLocalStreams)
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
