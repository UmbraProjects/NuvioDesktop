package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PosterCardStyleComposeTest {
    @Test
    fun `an unknown stored highlight mode falls back to off`() {
        // Off is also the default for installs saved before the setting existed, whose payload
        // carries no highlight field at all.
        assertEquals(PosterHighlightMode.Off, PosterHighlightMode.fromStoredName(null))
        assertEquals(PosterHighlightMode.Off, PosterHighlightMode.fromStoredName("Nonsense"))
        assertEquals(PosterHighlightMode.Accent, PosterHighlightMode.fromStoredName("Accent"))
    }

    @Test
    fun `tv mode hides poster labels regardless of saved preference`() {
        assertTrue(
            effectiveHomePosterCardStyle(
                base = PosterCardStyleUiState(hideLabelsEnabled = false),
                tvModeEnabled = true,
            ).hideLabelsEnabled,
        )
    }

    @Test
    fun `non tv mode preserves saved poster label preference`() {
        assertFalse(
            effectiveHomePosterCardStyle(
                base = PosterCardStyleUiState(hideLabelsEnabled = false),
                tvModeEnabled = false,
            ).hideLabelsEnabled,
        )
    }

    @Test
    fun `collections keep portrait posters when the opt-out is on`() {
        assertFalse(
            effectiveHomePosterCardStyle(
                base = PosterCardStyleUiState(
                    catalogLandscapeModeEnabled = true,
                    collectionsPortraitPostersEnabled = true,
                ),
                tvModeEnabled = false,
                isCollectionsSurface = true,
            ).catalogLandscapeModeEnabled,
        )
    }

    @Test
    fun `collections follow landscape mode when the opt-out is off`() {
        assertTrue(
            effectiveHomePosterCardStyle(
                base = PosterCardStyleUiState(
                    catalogLandscapeModeEnabled = true,
                    collectionsPortraitPostersEnabled = false,
                ),
                tvModeEnabled = false,
                isCollectionsSurface = true,
            ).catalogLandscapeModeEnabled,
        )
    }

    @Test
    fun `non collection surfaces ignore the collections portrait opt-out`() {
        assertTrue(
            effectiveHomePosterCardStyle(
                base = PosterCardStyleUiState(
                    catalogLandscapeModeEnabled = true,
                    collectionsPortraitPostersEnabled = true,
                ),
                tvModeEnabled = false,
                isCollectionsSurface = false,
            ).catalogLandscapeModeEnabled,
        )
    }
}
