package com.nuvio.app.features.music

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** LyricsPlus: how its answer is read (made-up words in the shape of a real answer), and the lookup rules around it. */
class LyricsPlusLyricsTest {
    private fun json(text: String) = Json.parseToJsonElement(text)

    private fun answer(
        title: String = "Paper Boats",
        artist: String = "Someone",
        total: String = "1:00.000",
        type: String = "Word",
        lyrics: String = LINES,
    ) = """{"type":"$type","metadata":{"source":"qApple","title":"$title","artist":"$artist","totalDuration":"$total"},"lyrics":[$lyrics]}"""

    @Test
    fun syllablesBecomeWordsAndTheSpaceAfterAPieceEndsTheWord() {
        val lyrics = lyricsPlusLyricsOf(json(answer()), "Paper Boats", "Someone", 60)
        assertNotNull(lyrics)
        assertEquals(LYRICSPLUS_SOURCE, lyrics.source)
        assertTrue(lyrics.isWordTimed)
        val first = lyrics.lines.first()
        assertEquals(listOf("We", "sail", "on"), first.words.map { it.text })
        assertEquals("We sail on", first.text)
        assertEquals(1_000L, first.timeMs)
        assertEquals(1_000L, first.words.first().startMs)
    }

    @Test
    fun backgroundVocalsAreLeftOutAndTheTextIsRebuiltWithoutThem() {
        val first = lyricsPlusLyricsOf(json(answer()), "Paper Boats", "Someone", 60)!!.lines.first()
        assertFalse(first.words.any { it.text.contains("hey") })
        assertFalse(first.text.contains("hey"))
    }

    @Test
    fun piecesListedOutOfTurnAreOrderedByTime() {
        val out = """
            {"time":9000,"duration":900,"text":"b a","syllabus":[
              {"time":9500,"duration":300,"text":"a "},{"time":9000,"duration":400,"text":"b "}]}
        """.trimIndent()
        val line = lyricsPlusLyricsOf(json(answer(lyrics = out)), "Paper Boats", "Someone", 60)!!.lines.first()
        assertEquals(listOf("b", "a"), line.words.map { it.text })
    }

    @Test
    fun theAnswersOwnTypeIsNotTrustedAPieceCountsAsWordTiming() {
        val lyrics = lyricsPlusLyricsOf(json(answer(type = "Line")), "Paper Boats", "Someone", 60)
        assertTrue(lyrics!!.isWordTimed)
    }

    @Test
    fun aLineWithNoPiecesIsKeptTimedAsAWholeAndAnswerOfOnlyThoseIsLineTimed() {
        val plain = """{"time":4000,"duration":1500,"text":"Just a line","syllabus":[]},{"time":7000,"duration":1500,"text":"Another"}"""
        val lyrics = lyricsPlusLyricsOf(json(answer(lyrics = plain)), "Paper Boats", "Someone", 60)!!
        assertFalse(lyrics.isWordTimed)
        assertEquals(listOf("Just a line", "Another"), lyrics.lines.map { it.text })
        assertEquals(listOf(4_000L, 7_000L), lyrics.lines.map { it.timeMs })
    }

    @Test
    fun anotherSongOrAnotherLengthOrAnEmptyAnswerIsTurnedAway() {
        assertNull(lyricsPlusLyricsOf(json(answer(title = "Something Else")), "Paper Boats", "Someone", 60))
        assertNull(lyricsPlusLyricsOf(json(answer(artist = "Somebody Else Entirely")), "Paper Boats", "Someone", 60))
        assertNull(lyricsPlusLyricsOf(json(answer(total = "2:00.000")), "Paper Boats", "Someone", 60))
        assertNotNull(lyricsPlusLyricsOf(json(answer(total = "1:02.000")), "Paper Boats", "Someone", 60))
        assertNull(lyricsPlusLyricsOf(json(answer(lyrics = "")), "Paper Boats", "Someone", 60))
        assertNull(lyricsPlusLyricsOf(json("""{"error":{"message":"Lyrics not found","status":404}}"""), "Paper Boats", "Someone", 60))
        assertNull(lyricsPlusLyricsOf(null, "Paper Boats", "Someone", 60))
    }

    @Test
    fun aLyricTimedPastTheEndOfTheTrackIsTurnedAway() {
        val late = """{"time":200000,"duration":900,"text":"x","syllabus":[{"time":200000,"duration":900,"text":"x"}]}"""
        assertNull(lyricsPlusLyricsOf(json(answer(total = "", lyrics = late)), "Paper Boats", "Someone", 60))
    }

    @Test
    fun clockTimesInBothLayoutsAreRead() {
        assertEquals(203_808L, parseTtmlTime("3:23.808"))
        assertEquals(227_080L, parseTtmlTime("03:47.08"))
    }

    // region fallback rules

    @Test
    fun aWordTimedSourceBeatsLrclibAndTheFirstWordTimedOneWins() {
        val lrclib = Lyrics.Synced(listOf(LyricLine(1_000L, "a")), 60.0, "LRCLIB")
        val lineOnly = Lyrics.Synced(listOf(LyricLine(1_000L, "a")), 60.0, "Line source")
        val first = Lyrics.Synced(listOf(LyricLine(1_000L, "a", listOf(TimedWord(1_000L, 1_500L, "a")))), 60.0, "First")
        val second = Lyrics.Synced(listOf(LyricLine(1_000L, "a", listOf(TimedWord(1_000L, 1_400L, "a")))), 60.0, "Second")
        assertSame(first, bestLyrics(lrclib, listOf(lineOnly, first, second)))
    }

    @Test
    fun aLineTimedSourceIsUsedOnlyWhenLrclibHasNothing() {
        val lrclib = Lyrics.Synced(listOf(LyricLine(1_000L, "a")), 60.0, "LRCLIB")
        val lineOnly = Lyrics.Synced(listOf(LyricLine(2_000L, "b")), 60.0, "Line source")
        assertSame(lrclib, bestLyrics(lrclib, listOf(lineOnly)))
        assertSame(lineOnly, bestLyrics(null, listOf(lineOnly)))
        assertNull(bestLyrics(null, emptyList()))
    }

    @Test
    fun anInstrumentalStaysInstrumentalWhateverTheOthersSay() {
        val word = Lyrics.Synced(listOf(LyricLine(1_000L, "a", listOf(TimedWord(1_000L, 1_500L, "a")))), 60.0, "Word")
        assertSame(Lyrics.Instrumental, bestLyrics(Lyrics.Instrumental, listOf(word)))
    }

    @Test
    fun aFirstFailureRestsTheSourceBriefly() {
        val breaker = LookupBreaker(failuresBeforePause = 3, pause = 10.minutes, firstFailurePause = 1.hours)
        assertFalse(breaker.paused())
        breaker.failed()
        assertTrue(breaker.paused())
    }

    @Test
    fun withoutABriefRestFailuresShortOfTheLimitDoNotPause() {
        val breaker = LookupBreaker(failuresBeforePause = 3, pause = 10.minutes, firstFailurePause = Duration.ZERO)
        breaker.failed(); breaker.failed()
        assertFalse(breaker.paused())
        breaker.failed()
        assertTrue(breaker.paused())
    }

    @Test
    fun aShortRestForARateLimitEndsAtOnceWhenItIsZeroLong() {
        val breaker = LookupBreaker(failuresBeforePause = 3, pause = 10.minutes)
        breaker.pauseFor(Duration.ZERO)
        assertFalse(breaker.paused())
        breaker.pauseFor(1.hours)
        assertTrue(breaker.paused())
    }

    // endregion

    private companion object {
        val LINES = """
            {"time":1000,"duration":2600,"text":"We sail on (hey)","syllabus":[
              {"time":1000,"duration":200,"text":"We "},
              {"time":1200,"duration":300,"text":"sai"},
              {"time":1500,"duration":400,"text":"l "},
              {"time":1900,"duration":700,"text":"on"},
              {"time":2200,"duration":500,"text":"(hey)","isBackground":true}],
             "element":{"key":"","singer":"v1","songPartIndex":0}},
            {"time":5000,"duration":1800,"text":"Paper boats","syllabus":[
              {"time":5000,"duration":400,"text":"Pa"},
              {"time":5400,"duration":300,"text":"per "},
              {"time":5700,"duration":900,"text":"boats"}]}
        """.trimIndent()
    }
}
