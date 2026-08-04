package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PosterCardStyleComposeTest {
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
}
