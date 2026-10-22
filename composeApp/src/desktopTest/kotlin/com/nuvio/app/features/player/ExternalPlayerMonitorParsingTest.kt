package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalPlayerMonitorParsingTest {
    @Test
    fun `vlc status maps seconds and state`() {
        val sample = parseVlcStatus("""{"state":"paused","time":125,"length":3600,"position":0.0347}""")
        assertEquals(ExternalPlaybackSample(positionMs = 125_000L, durationMs = 3_600_000L, isPaused = true), sample)
        assertNull(parseVlcStatus("""{"state":"stopped","time":0,"length":0}"""))
    }

    @Test
    fun `mpc variables map milliseconds and directshow state`() {
        val body = """
            <p id="file">movie.mkv</p>
            <p id="state">2</p>
            <p id="statestring">Playing</p>
            <p id="position">754000</p>
            <p id="duration">7200000</p>
        """.trimIndent()
        assertEquals(ExternalPlaybackSample(positionMs = 754_000L, durationMs = 7_200_000L, isPaused = false), parseMpcVariables(body))
        assertNull(parseMpcVariables("""<p id="state">-1</p><p id="position">0</p><p id="duration">0</p>"""))
    }
}
