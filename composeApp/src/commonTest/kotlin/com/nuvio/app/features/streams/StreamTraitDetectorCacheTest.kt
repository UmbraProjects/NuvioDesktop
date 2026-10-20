package com.nuvio.app.features.streams

import com.nuvio.app.features.debrid.DebridStreamResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class StreamTraitDetectorCacheTest {
    private fun stream(name: String) = StreamItem(
        name = name,
        description = "$name 2160p HDR10 DDP5.1 x265",
        addonName = "addon",
        addonId = "addon",
        url = "http://example.invalid/${name.hashCode()}",
        behaviorHints = StreamBehaviorHints(filename = "$name.2160p.HDR10.DDP5.1.x265.mkv"),
    )

    @Test
    fun `a stream is scanned once and its traits reused`() {
        val first = stream("Movie.2024")
        val traits = StreamTraitDetector.detect(first)
        assertSame(traits, StreamTraitDetector.detect(first))
        // An equal stream (the same release re-sent by the next addon answer) is a hit too.
        assertSame(traits, StreamTraitDetector.detect(stream("Movie.2024")))
        assertEquals(DebridStreamResolution.P2160, traits.resolution)
    }

    @Test
    fun `a different stream is not served another stream's traits`() {
        val uhd = StreamTraitDetector.detect(stream("Show.S01E01"))
        val hd = StreamTraitDetector.detect(
            stream("Show.S01E01").copy(
                description = "Show S01E01 1080p WEB-DL",
                behaviorHints = StreamBehaviorHints(filename = "Show.S01E01.1080p.WEB-DL.mkv"),
            ),
        )
        assertNotEquals(uhd.resolution, hd.resolution)
    }
}
