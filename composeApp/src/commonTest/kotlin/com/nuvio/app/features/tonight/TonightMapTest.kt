package com.nuvio.app.features.tonight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TonightMapTest {
    @Test
    fun lightTitlesSitLeftAndNewOnesHigh() {
        val comedy = film("comedy", listOf("Comedy", "Family"), minutes = 92, origins = setOf(TonightOrigin.Trending))
        val war = film("war", listOf("War", "Drama"), minutes = 170, started = true)
        val spots = tonightMapSpots(listOf(comedy, war), emptyMap()).associateBy { it.title.id }
        assertTrue(spots.getValue("comedy").x < 0.3f)
        assertTrue(spots.getValue("war").x > 0.7f)
        assertTrue(spots.getValue("comedy").y < 0.3f, "new is towards the top")
        assertTrue(spots.getValue("war").y > 0.7f, "started is comfort, towards the bottom")
    }

    @Test
    fun theAiToneMovesATitleAndIsMarked() {
        val dark = film("dark", listOf("Comedy"), minutes = 100)
        val plain = tonightMapSpots(listOf(dark), emptyMap()).single()
        val read = tonightMapSpots(listOf(dark), mapOf(dark.key to -0.8f)).single()
        assertTrue(read.x > plain.x + 0.3f)
        assertTrue(read.aiRefined)
        assertTrue(!plain.aiRefined)
    }

    @Test
    fun mixingFavoursTheAiButKeepsTheRules() {
        assertEquals(0.5f, tonightMixedLightness(0.5f, null))
        assertEquals(-0.17f, tonightMixedLightness(0.5f, -0.53f), 0.01f)
    }

    @Test
    fun spotsStayOnTheFieldAndTheSameTitleSitsInTheSamePlace() {
        val pool = (1..30).map { film("f$it", listOf(listOf("Comedy", "Horror", "Drama")[it % 3])) }
        val a = tonightMapSpots(pool, emptyMap())
        val b = tonightMapSpots(pool, emptyMap())
        assertEquals(a, b)
        assertTrue(a.all { it.x in 0f..1f && it.y in 0f..1f })
        assertTrue(a.map { it.x to it.y }.toSet().size == a.size, "jitter keeps titles apart")
    }

    @Test
    fun nearestIsUnderTheLens() {
        val spots = tonightMapSpots(listOf(film("a", listOf("Comedy")), film("b", listOf("War"))), emptyMap())
        val left = spots.nearest(0f, 0.5f).first()
        assertEquals("a", left.title.id)
    }

    @Test
    fun lensStartsOnWhatYouKeep() {
        val spots = tonightMapSpots(
            listOf(film("kept", listOf("Comedy")), film("trend", listOf("War"), origins = setOf(TonightOrigin.Trending))),
            emptyMap(),
        )
        val (x, _) = tonightLensStart(spots)
        assertTrue(x < 0.5f)
    }

    @Test
    fun toneReplyIsReadInEitherKeyOrderAndOutOfRangeIsDropped() {
        val reply = """Sure! {"items":[{"i":1,"tone":0.6},{"tone":-0.4,"i":2},{"i":3,"tone":5},{"i":9,"tone":0.1}]}"""
        assertEquals(mapOf(0 to 0.6f, 1 to -0.4f), parseToneReply(reply, 3))
        assertTrue(parseToneReply("I don't know these.", 3).isEmpty())
    }

    @Test
    fun tonePromptNumbersTheTitles() {
        val prompt = tonightTonePrompt(listOf(film("Heat", listOf("Crime"), year = 1995), show("Detectorists", listOf("Comedy"))))
        assertTrue("1. Heat (1995; film; Crime)" in prompt)
        assertTrue("2. Detectorists (2022; TV show; Comedy)" in prompt)
    }

    @Test
    fun tonesSurviveStorage() {
        val text = encodeTones(mapOf("movie:tt1" to 0.456f, "series:tmdb:2" to -1f), mapOf("movie:tt3" to 123L))
        val (tones, misses) = decodeTones(text)
        assertEquals(0.45f, tones.getValue("movie:tt1"), 0.001f)
        assertEquals(-1f, tones.getValue("series:tmdb:2"))
        assertEquals(123L, misses.getValue("movie:tt3"))
        assertTrue(decodeTones(null).first.isEmpty())
    }

    @Test
    fun readingLabels() {
        val spot = MapSpot(film("x"), x = 0.2f, y = 0.2f, aiRefined = false)
        assertEquals("Light, and new to you", spot.readingLabel())
        assertEquals("Heavy, and you're part way through", MapSpot(film("y", started = true), 0.9f, 0.9f, false).readingLabel())
    }
}
