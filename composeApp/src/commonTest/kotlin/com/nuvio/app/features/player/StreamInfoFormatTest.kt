package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreamInfoFormatTest {
    @Test
    fun `numbers read the way a person expects`() {
        assertEquals("23.976 fps", formatFps(23.976023))
        assertEquals("24 fps", formatFps(24.0))
        assertEquals("18.4 Mb/s", formatBitrate(18_456_000))
        assertEquals("640 kb/s", formatBitrate(640_000))
        assertEquals("48 kHz", formatSampleRate(48_000))
        assertEquals("44.1 kHz", formatSampleRate(44_100))
        assertEquals("5.1", channelsLabel(6))
    }

    @Test
    fun `resolution and dynamic range`() {
        assertEquals("3840 × 1608 (4K)", resolutionLabel(3840, 1608))
        assertNull(resolutionLabel(null, 1080))
        assertEquals("Dolby Vision (profile 8) + HDR10", dynamicRangeLabel(PlayingMediaFacts(width = 3840, isHdr = true, dolbyVisionProfile = 8)))
        assertEquals("Dolby Vision (profile 5)", dynamicRangeLabel(PlayingMediaFacts(width = 3840, isHdr = true, dolbyVisionProfile = 5)))
        assertEquals("SDR", dynamicRangeLabel(PlayingMediaFacts(width = 1920)))
        assertNull(dynamicRangeLabel(PlayingMediaFacts()))
    }
}
