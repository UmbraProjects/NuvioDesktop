package com.nuvio.app.features.music

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AMLL TTML DB and LRCMux: how their answers are read and when they are turned away. */
class OpenLyricSourcesTest {
    private fun json(text: String) = Json.parseToJsonElement(text)

    // region AMLL

    private val search = """
        {"status":200,"data":{"items":[
          {"id":4348298668118779,"musicNames":["Wasted Times","Wasted Times (Explicit)"],"artistNames":["Some Artist"]},
          {"id":3948672475170116,"musicNames":["Paper Boats"],"artistNames":["Someone"]},
          {"id":5,"musicNames":["Paper Boats"],"artistNames":["Somebody Else"]},
          {"musicNames":["Paper Boats"],"artistNames":["Someone"]}
        ]}}
    """.trimIndent()

    @Test
    fun searchEntriesKeepTheirBigIdsExactly() {
        val entries = parseAmllSearch(json(search))
        // The fourth item has no id and is left out.
        assertEquals(3, entries.size)
        assertEquals("4348298668118779", entries[0].id)
        assertEquals(listOf("Wasted Times", "Wasted Times (Explicit)"), entries[0].titles)
    }

    @Test
    fun onlyEntriesForThisSongAreKept() {
        val picked = pickAmllEntries(parseAmllSearch(json(search)), "Paper Boats", "Someone")
        assertEquals(listOf("3948672475170116"), picked.map { it.id })
        assertEquals(emptyList(), pickAmllEntries(parseAmllSearch(json(search)), "Paper Boats", "Nobody"))
        assertEquals(emptyList(), pickAmllEntries(parseAmllSearch(json(search)), "", "Someone"))
    }

    @Test
    fun theFileIsFoundUnderWhicheverKeyItIsOn() {
        val tt = "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body/></tt>"
        assertEquals(tt, amllTtmlOf(Json.parseToJsonElement("{\"data\":{\"lyrics\":${Json.encodeToString(kotlinx.serialization.serializer<String>(), tt)}}}")))
        assertEquals(tt, amllTtmlOf(Json.parseToJsonElement("{\"data\":{\"ttml\":${Json.encodeToString(kotlinx.serialization.serializer<String>(), tt)}}}")))
        assertNull(amllTtmlOf(json("{\"data\":{\"lyrics\":\"plain words, no markup\"}}")))
        assertNull(amllTtmlOf(json("[]")))
    }

    // endregion

    // region LRCMux

    private fun mux(
        title: String = "Paper Boats",
        duration: Int = 60,
        times: Double = 1.0,
        lines: String? = null,
    ): String {
        fun t(ms: Int) = (ms * times).let { if (times == 1.0) it.toLong().toString() else it.toString() }
        val body = lines ?: """
            [{"text":"Paper boats","start":${t(2000)},"end":${t(3300)},"words":[{"text":"Paper ","start":${t(2000)},"end":${t(2900)}},{"text":"boats","start":${t(2900)},"end":${t(3300)}}]},
             {"text":"Gone","start":${t(20000)},"end":${t(21000)},"words":[{"text":"Gone","start":${t(20000)},"end":${t(21000)}}]}]
        """.trimIndent()
        return """{"track":{"title":"$title","artist":"Someone","duration":$duration},"meta":{"source":{"id":"deezer","name":"Deezer"},"level":"word"},"lines":$body}"""
    }

    @Test
    fun aWordTimedAnswerBecomesLyrics() {
        val lyrics = lrcMuxLyricsOf(json(mux()), "Paper Boats", 60)
        assertNotNull(lyrics)
        assertEquals(listOf("Paper boats", "", "Gone"), lyrics.lines.map { it.text })
        assertEquals(listOf(2_000L, 4_100L, 20_000L), lyrics.lines.map { it.timeMs })
        assertEquals(listOf(TimedWord(2_000, 2_900, "Paper"), TimedWord(2_900, 3_300, "boats")), lyrics.lines[0].words)
        assertEquals("LRCMux (Deezer)", lyrics.source)
        assertTrue(lyrics.isWordTimed)
    }

    @Test
    fun timesInSecondsAreReadAsSeconds() {
        val lyrics = lrcMuxLyricsOf(json(mux(times = 0.001)), "Paper Boats", 60)
        assertNotNull(lyrics)
        assertEquals(listOf(2_000L, 4_100L, 20_000L), lyrics.lines.map { it.timeMs })
    }

    @Test
    fun wordsWithoutSpacesAreStillSeparateWords() {
        val lines = """[{"text":"Paper boats","start":2000,"end":3300,"words":[{"text":"Paper","start":2000,"end":2900},{"text":"boats","start":2900,"end":3300}]}]"""
        val lyrics = lrcMuxLyricsOf(json(mux(lines = lines)), "Paper Boats", 60)
        assertNotNull(lyrics)
        assertEquals("Paper boats", lyrics.lines[0].text)
        assertEquals(listOf("Paper", "boats"), lyrics.lines[0].words.map { it.text })
    }

    @Test
    fun aLineTimedAnswerIsKeptAsLineSyncedLyrics() {
        // LRCMux answers `"level":"line"` for songs its catalogue timed only by line, as for the Ren track.
        val lineTimedOnly = """[{"text":"Paper boats","start":2000,"end":3300},{"text":"Gone","start":20000,"end":21000}]"""
        val lyrics = lrcMuxLyricsOf(json(mux(lines = lineTimedOnly)), "Paper Boats", 60)
        assertNotNull(lyrics)
        assertEquals(listOf("Paper boats", "Gone"), lyrics.lines.filter { it.text.isNotBlank() }.map { it.text })
        assertFalse(lyrics.isWordTimed)
        assertEquals("Line-synced · LRCMux (Deezer)", lyrics.sourceNote())
    }

    @Test
    fun anotherSongAnotherLengthOrNoLinesIsTurnedAway() {
        assertNull(lrcMuxLyricsOf(json(mux(title = "Something Else")), "Paper Boats", 60))
        // A remix as long as the song is caught by the title; one of a different length by the length.
        assertNull(lrcMuxLyricsOf(json(mux(duration = 120)), "Paper Boats", 60))
        assertNotNull(lrcMuxLyricsOf(json(mux(duration = 62)), "Paper Boats", 60))
        assertNull(lrcMuxLyricsOf(json(mux(lines = "[]")), "Paper Boats", 60))
        assertNull(lrcMuxLyricsOf(json("{\"track\":{\"title\":\"Paper Boats\"}}"), "Paper Boats", 60))
        assertNull(lrcMuxLyricsOf(null, "Paper Boats", 60))
    }

    @Test
    fun lyricsSungAfterTheTrackEndedAreRejected() {
        // The last line starts at 20 s.
        assertNull(lrcMuxLyricsOf(json(mux(duration = 10)), "Paper Boats", 10))
    }

    // endregion
}
