package com.nuvio.app.features.home.components

import com.nuvio.app.core.ui.ExtraLargePosterCardWidthDp
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeContinueWatchingSectionTest {
    @Test
    fun `tv card width ignores the saved poster size`() {
        listOf(104, 140, 180).forEach { savedPosterWidthDp ->
            assertEquals(
                ExtraLargePosterCardWidthDp,
                effectiveContinueWatchingCardBaseWidthDp(
                    basePosterWidthDpOverride = null,
                    savedPosterWidthDp = savedPosterWidthDp,
                    tvModeEnabled = true,
                ),
            )
        }
    }

    @Test
    fun `explicit tv shelf width takes precedence`() {
        assertEquals(
            136,
            effectiveContinueWatchingCardBaseWidthDp(
                basePosterWidthDpOverride = 136,
                savedPosterWidthDp = 104,
                tvModeEnabled = true,
            ),
        )
    }

    @Test
    fun `tv mode respects poster continue watching style`() {
        assertEquals(
            ContinueWatchingSectionStyle.Poster,
            effectiveContinueWatchingStyle(
                requestedStyle = ContinueWatchingSectionStyle.Poster,
                tvModeEnabled = true,
                catalogLandscapeModeEnabled = false,
            ),
        )
    }

    @Test
    fun `tv mode respects every supported continue watching style`() {
        ContinueWatchingSectionStyle.entries.forEach { style ->
            assertEquals(
                style,
                effectiveContinueWatchingStyle(
                    requestedStyle = style,
                    tvModeEnabled = true,
                    catalogLandscapeModeEnabled = false,
                ),
            )
        }
    }

    @Test
    fun `tv landscape posters force continue watching to landscape cards`() {
        ContinueWatchingSectionStyle.entries.forEach { style ->
            assertEquals(
                ContinueWatchingSectionStyle.Card,
                effectiveContinueWatchingStyle(
                    requestedStyle = style,
                    tvModeEnabled = true,
                    catalogLandscapeModeEnabled = true,
                ),
            )
        }
    }

    @Test
    fun `landscape poster preference does not override continue watching outside tv mode`() {
        assertEquals(
            ContinueWatchingSectionStyle.Poster,
            effectiveContinueWatchingStyle(
                requestedStyle = ContinueWatchingSectionStyle.Poster,
                tvModeEnabled = false,
                catalogLandscapeModeEnabled = true,
            ),
        )
    }
}
