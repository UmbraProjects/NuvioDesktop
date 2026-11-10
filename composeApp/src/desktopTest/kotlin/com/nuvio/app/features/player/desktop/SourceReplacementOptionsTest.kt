package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceReplacementOptionsTest {

    private val episodeOne = listOf(
        "${MPV_TRACE_ID_OPTION_PREFIX}7",
        "ytdl=no",
        "network-timeout=20",
        "@nuvio-config-mode=add",
        "cache-secs=600",
        "force-media-title=Show S01E01",
        "speed=1.0",
    )

    @Test
    fun nextEpisodeWithTheSameSetupReusesThePlayer() {
        val episodeTwo = episodeOne.map {
            when {
                it.startsWith(MPV_TRACE_ID_OPTION_PREFIX) -> "${MPV_TRACE_ID_OPTION_PREFIX}8"
                it.startsWith("force-media-title=") -> "force-media-title=Show S01E02"
                else -> it
            }
        }
        assertTrue(mpvOptionsAllowSourceReplacement(episodeOne, episodeTwo))
    }

    @Test
    fun aDifferentInstanceOptionRecreatesThePlayer() {
        val fasterSpeed = episodeOne.map { if (it.startsWith("speed=")) "speed=1.5" else it }
        assertFalse(mpvOptionsAllowSourceReplacement(episodeOne, fasterSpeed))
    }

    @Test
    fun anOptionOnlyOneSideHasRecreatesThePlayer() {
        // A web host after a debrid CDN: no direct-media timeout, so mpv would keep the old one.
        val withoutTimeout = episodeOne.filterNot { it.startsWith("network-timeout=") }
        assertFalse(mpvOptionsAllowSourceReplacement(episodeOne, withoutTimeout))
        assertFalse(mpvOptionsAllowSourceReplacement(withoutTimeout, episodeOne))
    }

    @Test
    fun optionOrderCounts() {
        val reordered = listOf(episodeOne[2], episodeOne[1]) + episodeOne.drop(3) + episodeOne[0]
        assertFalse(mpvOptionsAllowSourceReplacement(episodeOne, reordered))
    }
}
