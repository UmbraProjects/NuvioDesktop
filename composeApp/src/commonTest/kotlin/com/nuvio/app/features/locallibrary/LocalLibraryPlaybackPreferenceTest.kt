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
}
