package com.nuvio.app.features.music

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnisonLyricsTest {
    /**
     * Invented lyrics in the shape the real files have: syllables are spans that touch, a space in the source is the
     * word break, `&apos;` is an entity, and a line can carry a background vocal (`x-bg`) and translations
     * (`x-translation`) that are not part of the sung words.
     */
    private val ttml = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal"><head><metadata><ttm:agent type="person" xml:id="v1"/></metadata></head><body dur="1:10.000"><div begin="2.000" end="1:10.000">
        <p begin="2.000" end="4.000" ttm:agent="v1" itunes:key="L1"><span begin="2.000" end="2.400">Pa</span><span begin="2.400" end="2.900">per</span> <span begin="2.900" end="3.300">boats</span> <span begin="3.300" end="4.000">don&apos;t</span><span ttm:role="x-translation" xml:lang="fr">Les bateaux</span></p>
        <p begin="4.500" end="6.000" ttm:agent="v1" itunes:key="L2"><span begin="4.500" end="5.000">drift</span> <span begin="5.000" end="5.500">home</span><span ttm:role="x-bg" begin="5.200" end="5.800">(ooh)<span ttm:role="x-translation" xml:lang="fr">oh</span></span><span ttm:role="x-translation" xml:lang="fr">derivent</span></p>
        <p begin="30.000" end="32.000" ttm:agent="v1" itunes:key="L3"><span begin="30.000" end="31.000">Later</span> <span begin="31.000" end="32.000">on</span></p>
        <p begin="40.000" end="42.000" ttm:agent="v1" itunes:key="L4">Only &amp; line timed</p>
        </div></body></tt>
    """.trimIndent()

    @Test
    fun ttmlTimesInEveryFormTheFilesUse() {
        assertEquals(14_465L, parseTtmlTime("14.465"))
        assertEquals(169_480L, parseTtmlTime("2:49.480"))
        assertEquals(200_611L, parseTtmlTime("03:20.611"))
        assertEquals(3_723_500L, parseTtmlTime("1:02:03.5"))
        assertEquals(14_465L, parseTtmlTime("14.465s"))
        assertEquals(1_500L, parseTtmlTime("1500ms"))
        assertNull(parseTtmlTime(""))
        assertNull(parseTtmlTime("00:00:12:10"))
        assertNull(parseTtmlTime("soon"))
    }

    @Test
    fun entitiesBecomeCharacters() {
        assertEquals("don't & <this> \"that\"", decodeXmlEntities("don&apos;t &amp; &lt;this&gt; &quot;that&quot;"))
        assertEquals("it's", decodeXmlEntities("it&#39;s"))
        assertEquals("it's", decodeXmlEntities("it&#x27;s"))
        assertEquals("😀", decodeXmlEntities("&#x1F600;"))
        assertEquals("&unknown; &", decodeXmlEntities("&unknown; &amp;"))
    }

    @Test
    fun syllablesJoinIntoWordsAndKeepTheirTimes() {
        val lines = parseTtml(ttml).lines
        val first = lines.first()
        assertEquals("Paper boats don't", first.text)
        assertEquals(2_000L, first.timeMs)
        assertEquals(
            listOf(TimedWord(2_000, 2_900, "Paper"), TimedWord(2_900, 3_300, "boats"), TimedWord(3_300, 4_000, "don't")),
            first.words,
        )
    }

    @Test
    fun translationsAndBackgroundVocalsAreNotPartOfTheLine() {
        val second = parseTtml(ttml).lines[1]
        assertEquals("drift home", second.text)
        assertEquals(listOf(TimedWord(4_500, 5_000, "drift"), TimedWord(5_000, 5_500, "home")), second.words)
    }

    @Test
    fun aLongRestBecomesABlankLineAndALineTimedParagraphIsKept() {
        val lines = parseTtml(ttml).lines
        assertEquals(listOf("Paper boats don't", "drift home", "", "Later on", "", "Only & line timed"), lines.map { it.text })
        assertEquals(listOf(2_000L, 4_500L, 6_300L, 30_000L, 32_800L, 40_000L), lines.map { it.timeMs })
        assertTrue(lines.last().words.isEmpty())
    }

    @Test
    fun aFileItCannotReadGivesNoLines() {
        assertEquals(emptyList(), parseTtml("").lines)
        assertEquals(emptyList(), parseTtml("not xml at all").lines)
        assertEquals(emptyList(), parseTtml("<tt><body><div><p begin=\"1.0\"></p></div></body></tt>").lines)
    }

    private fun answer(
        song: String = "Paper Boats",
        artist: String = "Someone",
        format: String = "ttml",
        lyrics: String = ttml,
        score: Double = 0.0,
        hidden: Boolean = false,
        videoId: String = "aaaaaaaaaaa",
    ): JsonElement = buildJsonObject {
        put("success", true)
        put(
            "data",
            buildJsonObject {
                put("id", 7)
                put("videoId", videoId)
                put("song", song)
                put("artist", artist)
                put("lyrics", lyrics)
                put("format", format)
                put("effectiveScore", score)
                put("hidden", hidden)
            },
        )
    }

    @Test
    fun aMatchingAnswerBecomesWordTimedLyrics() {
        val lyrics = unisonLyricsOf(answer(song = "Paper Boats (feat. Somebody)"), "Paper Boats", "Someone", 60)
        assertNotNull(lyrics)
        assertTrue(lyrics.isWordTimed)
        assertEquals("Unison", lyrics.source)
        assertEquals(60_000L, lyrics.durationMs)
        assertEquals("Word-synced · Unison", lyrics.sourceNote())
    }

    @Test
    fun anEnhancedLrcAnswerKeepsItsWordStamps() {
        val lrc = "[00:01.00]<00:01.00>Hello <00:01.50>world\n[00:05.00]<00:05.00>Again"
        val lyrics = unisonLyricsOf(answer(format = "lrc", lyrics = lrc), "Paper Boats", "Someone", 60)
        assertNotNull(lyrics)
        assertEquals(listOf("Hello world", "Again"), lyrics.lines.map { it.text })
        assertTrue(lyrics.isWordTimed)
    }

    @Test
    fun aLineTimedAnswerIsLineSynced() {
        val lyrics = unisonLyricsOf(answer(format = "lrc", lyrics = "[00:01.00]One\n[00:05.00]Two"), "Paper Boats", "Someone", 60)
        assertNotNull(lyrics)
        assertFalse(lyrics.isWordTimed)
        assertEquals("Line-synced · Unison", lyrics.sourceNote())
    }

    @Test
    fun answersThatAreNotThisTrackAreTurnedAway() {
        // Another song, another artist, hidden, down-voted, plain words only, nothing in it, not an answer at all.
        assertNull(unisonLyricsOf(answer(song = "Other Song"), "Paper Boats", "Someone", 60))
        assertNull(unisonLyricsOf(answer(artist = "Somebody Else"), "Paper Boats", "Someone", 60))
        assertNull(unisonLyricsOf(answer(hidden = true), "Paper Boats", "Someone", 60))
        assertNull(unisonLyricsOf(answer(score = -3.0), "Paper Boats", "Someone", 60))
        assertNull(unisonLyricsOf(answer(format = "plain", lyrics = "just words"), "Paper Boats", "Someone", 60))
        assertNull(unisonLyricsOf(answer(lyrics = ""), "Paper Boats", "Someone", 60))
        assertNull(unisonLyricsOf(JsonNull, "Paper Boats", "Someone", 60))
        assertNull(unisonLyricsOf(Json.parseToJsonElement("{\"success\":false,\"code\":\"NOT_FOUND\"}"), "Paper Boats", "Someone", 60))
    }

    @Test
    fun aLyricOnTheSameVideoIsKeptWhateverTheArtistIsCalled() {
        // A soundtrack credits "Someone"; Unison files the song under "The Band" but on this very video.
        val sameVideo = answer(artist = "The Band", videoId = "2S6aXFg7CdA")
        assertNotNull(unisonLyricsOf(sameVideo, "Paper Boats", "Someone", 60, videoId = "2S6aXFg7CdA"))
        // Another video, or no video id to compare, still needs the artist to agree.
        assertNull(unisonLyricsOf(sameVideo, "Paper Boats", "Someone", 60, videoId = "zzzzzzzzzzz"))
        assertNull(unisonLyricsOf(sameVideo, "Paper Boats", "Someone", 60))
        // The title has to agree even on the same video.
        assertNull(unisonLyricsOf(answer(song = "Other Song", artist = "The Band", videoId = "2S6aXFg7CdA"), "Paper Boats", "Someone", 60, videoId = "2S6aXFg7CdA"))
    }

    @Test
    fun lyricsSungAfterTheTrackEndedAreAnotherCut() {
        // The last line starts at 40 s: a 30 s track cannot have it, a 38 s one can (4 s of slack), a track of unknown length takes it.
        assertNull(unisonLyricsOf(answer(), "Paper Boats", "Someone", 30))
        assertNotNull(unisonLyricsOf(answer(), "Paper Boats", "Someone", 38))
        assertNotNull(unisonLyricsOf(answer(), "Paper Boats", "Someone", 0))
    }

    @Test
    fun namesAreComparedWithoutDecorations() {
        assertTrue(unisonNamesTrack("Paper Boats", "Someone", "Paper Boats - Remastered 2011", "Someone"))
        assertTrue(unisonNamesTrack("paper boats", "Someone & Friends", "Paper Boats", "Someone"))
        assertFalse(unisonNamesTrack("Paper Boat", "Someone", "Paper Boats", "Someone"))
        assertFalse(unisonNamesTrack(null, "Someone", "Paper Boats", "Someone"))
    }

    @Test
    fun theBreakerPausesAfterRepeatedFailuresAndForgivesAfterASuccess() {
        val breaker = LookupBreaker(failuresBeforePause = 3, pause = kotlin.time.Duration.parse("10m"))
        breaker.failed(); breaker.failed()
        assertFalse(breaker.paused())
        breaker.succeeded()
        breaker.failed(); breaker.failed()
        assertFalse(breaker.paused())
        breaker.failed()
        assertTrue(breaker.paused())
    }

    @Test
    fun theBreakerCanBePausedAtOnceAndLetsGoWhenThePauseIsOver() {
        val now = LookupBreaker(failuresBeforePause = 3, pause = kotlin.time.Duration.parse("10m"))
        now.pauseNow()
        assertTrue(now.paused())
        val none = LookupBreaker(failuresBeforePause = 1, pause = kotlin.time.Duration.ZERO)
        none.failed()
        assertFalse(none.paused())
    }
}
