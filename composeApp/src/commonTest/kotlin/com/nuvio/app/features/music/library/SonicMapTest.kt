package com.nuvio.app.features.music.library

import com.nuvio.app.features.music.visualiser.SoundPrint
import com.nuvio.app.features.music.visualiser.decodeSoundPrint
import com.nuvio.app.features.music.visualiser.encodeSoundPrint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SonicMapTest {
    private val quiet = SoundPrint(loudness = 0.2f, hitRate = 0.5f, bpm = null, brightness = 0.3f, lowEnd = 0.4f)
    private val loud = SoundPrint(loudness = 0.8f, hitRate = 4f, bpm = 128f, brightness = 0.7f, lowEnd = 0.3f)
    private val mid = SoundPrint(loudness = 0.5f, hitRate = 2f, bpm = 100f, brightness = 0.5f, lowEnd = 0.35f)

    @Test
    fun printsSurviveStorage() {
        assertEquals(loud, decodeSoundPrint(encodeSoundPrint(loud)))
        assertEquals(quiet, decodeSoundPrint(encodeSoundPrint(quiet))) // no tempo
        assertNull(decodeSoundPrint("1|2"))
    }

    @Test
    fun measuredSongsSpreadOverTheMapAndEstimatesFillTheRest() {
        val places = sonicPlaces(listOf(quiet, loud, mid, null, null), listOf(null, SonicGuess(0f, 0f), null, SonicGuess(0.9f, 0.1f), null))
        val q = places[0]!!; val l = places[1]!!; val m = places[2]!!
        assertTrue(q.energy < m.energy && m.energy < l.energy)
        assertEquals(0.07f, q.energy, 1e-4f) // the calmest at the bottom
        assertEquals(0.93f, l.energy, 1e-4f) // the loudest at the top
        assertTrue(l.measured) // a print wins over a guess
        val g = places[3]!!
        assertFalse(g.measured)
        assertTrue(g.energy > 0.8f && g.bright < 0.2f)
        assertNull(places[4])
    }

    @Test
    fun oneMeasuredSongSitsInTheMiddle() {
        val p = sonicPlaces(listOf(mid), listOf(null)).single()!!
        assertEquals(0.5f, p.energy)
        assertEquals(0.5f, p.bright)
    }

    @Test
    fun cornersAndWalks() {
        val places = listOf(
            SonicPoint(0.1f, 0.2f, true), // calm warm
            SonicPoint(0.9f, 0.8f, true), // energetic bright
            SonicPoint(0.6f, 0.3f, true), // energetic warm
            SonicPoint(0.3f, 0.9f, false), // calm bright
            null,
        )
        assertEquals(listOf(2), cornerSongs(places, SonicCorner.EnergeticWarm))
        assertEquals(listOf(3), cornerSongs(places, SonicCorner.CalmBright))
        assertEquals(listOf(0, 3, 2, 1), walkSongs(places, SonicWalk.Rise))
        assertEquals(listOf(1, 2, 3, 0), walkSongs(places, SonicWalk.Fall))
        val drift = walkSongs(places, SonicWalk.Drift)
        assertEquals(0, drift.first()) // starts at the calmest
        assertEquals(4, drift.size)
        assertEquals(4, walkSongs(places, SonicWalk.Loop).size)
        assertTrue(walkSongs(listOf(null), SonicWalk.Rise).isEmpty())
    }

    @Test
    fun theAiReplyIsReadAndChecked() {
        val g = parseSonicGuesses("ok {\"songs\": [{\"n\": 2, \"energy\": 80, \"bright\": 150}, {\"n\": 9, \"energy\": 1, \"bright\": 1}, {\"n\": 1, \"energy\": \"20\", \"bright\": 40}]}", 3)
        assertNotNull(g)
        assertEquals(SonicGuess(0.8f, 1f), g[1])
        assertEquals(SonicGuess(0.2f, 0.4f), g[0]) // a number sent as text still reads
        assertNull(g[2])
        assertNull(parseSonicGuesses("{\"songs\": []}", 3))
    }
}
