package com.nuvio.app.features.music

import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NeteaseLyricsTest {
    private val yrc = """
        {"t":0,"c":[{"tx":"Credit: "},{"tx":"Someone"}]}
        [1000,1500](1000,300,0)Hello (1300,400,0)wor(1700,500,0)ld (2400,100,0)(oh!)
        [9000,1000](9000,500,0)Again (9500,500,0)now
    """.trimIndent()

    private fun near(expected: Float, actual: Float) = assertTrue(abs(expected - actual) < 1e-3f, "expected $expected, was $actual")

    @Test
    fun yrcLinesCarryTheirWordTimes() {
        val lines = parseYrc(yrc)
        assertEquals(listOf("Hello world (oh!)", "", "Again now"), lines.map { it.text })
        assertEquals(listOf(1_000L, 3_300L, 9_000L), lines.map { it.timeMs })
        assertEquals(
            listOf(TimedWord(1_000, 1_300, "Hello"), TimedWord(1_300, 2_200, "world"), TimedWord(2_400, 2_500, "(oh!)")),
            lines[0].words,
        )
        assertEquals(listOf(TimedWord(9_000, 9_500, "Again"), TimedWord(9_500, 10_000, "now")), lines[2].words)
    }

    @Test
    fun creditLinesAndLinesWithoutWordStampsAreSkipped() {
        assertEquals(emptyList(), parseYrc("{\"t\":0,\"c\":[{\"tx\":\"x\"}]}\n[1000,500]no word stamps\nnot a line"))
    }

    @Test
    fun aShortRestIsNotAGap() {
        val lines = parseYrc("[0,500](0,500,0)one\n[2000,500](2000,500,0)two")
        assertEquals(listOf("one", "two"), lines.map { it.text })
    }

    @Test
    fun piecesOfOneWordJoinAndSeparateWordsDoNot() {
        val joined = mergeTimedSyllables(
            listOf(TimedWord(0, 100, "beau"), TimedWord(100, 200, "ti"), TimedWord(200, 400, "ful "), TimedWord(500, 700, " day")),
        )
        assertEquals(listOf(TimedWord(0, 400, "beautiful"), TimedWord(500, 700, "day")), joined)
        // Scripts written without spaces keep every character its own word.
        assertEquals(2, mergeTimedSyllables(listOf(TimedWord(0, 100, "你"), TimedWord(100, 200, "好"))).size)
    }

    @Test
    fun wordsSweepOnTheirOwnTimesNotByLength() {
        val lines = parseYrc(yrc)
        assertEquals(2_500L, sungEndMs(lines, 0))
        val words = lineWords(lines, 0)
        near(0f, words[0].from); near(0.2f, words[0].to)
        near(0.2f, words[1].from); near(0.8f, words[1].to)
        near(0.9333f, words[2].from); near(1f, words[2].to)
        // The last line has no next stamp: it ends with its last word, not four seconds after its start.
        assertEquals(10_000L, sungEndMs(lines, 2))
    }

    @Test
    fun aLineWithoutWordTimesStillUsesTheLengthEstimate() {
        val lines = parseLrc("[00:01.00] one two\n[00:09.00] three")
        assertEquals(lyricWords("one two"), lineWords(lines, 0))
    }

    @Test
    fun enhancedLrcKeepsItsWordStamps() {
        val line = parseLrc("[00:01.000] <00:01.000>hello <00:01.500>world <00:02.000>").single()
        assertEquals("hello world", line.text)
        assertEquals(listOf(TimedWord(1_000, 1_500, "hello"), TimedWord(1_500, 2_000, "world")), line.words)
    }

    @Test
    fun aRepeatedEnhancedLineMovesItsWordsWithItsStamp() {
        val lines = parseLrc("[00:10.00][00:40.00]<00:10.00>la <00:10.50>la <00:11.00>")
        assertEquals(listOf(10_000L, 40_000L), lines.map { it.timeMs })
        assertEquals(listOf(40_000L, 40_500L), lines[1].words.map { it.startMs })
    }

    @Test
    fun shiftingTheLyricsShiftsTheWordsToo() {
        val shifted = parseYrc(yrc).shiftedBy(500L)
        assertEquals(1_500L, shifted[0].timeMs)
        assertEquals(1_500L, shifted[0].words.first().startMs)
        assertEquals(3_000L, shifted[0].words.last().endMs)
    }

    private val songs = listOf(
        NeteaseSong(1, "Song One (feat. Guest)", listOf("Ren"), 200_000),
        NeteaseSong(2, "Song One - Live", listOf("Ren"), 260_000),
        NeteaseSong(3, "Song One", listOf("Someone Else"), 200_500),
        NeteaseSong(4, "Song Two", listOf("Ren"), 200_000),
    )

    @Test
    fun theMatchIsThisTitleThisArtistAndThisLength() {
        assertEquals(1L, pickNeteaseSong(songs, "Song One", "Ren", 201)?.id)
        assertEquals(2L, pickNeteaseSong(songs, "Song One", "Ren", 259)?.id)
        // Neither cut is near 230 s: no timing beats timing on the wrong recording.
        assertNull(pickNeteaseSong(songs, "Song One", "Ren", 230))
        assertNull(pickNeteaseSong(songs, "Song One", "Nobody", 201))
        assertNull(pickNeteaseSong(songs, "", "Ren", 201))
    }

    @Test
    fun searchAnswersInEitherSpellingParse() {
        val root = Json.parseToJsonElement(
            """{"result":{"songs":[
                {"id":1,"name":"Song One","ar":[{"name":"Ren"}],"dt":200000},
                {"id":2,"name":"Song Two","artists":[{"name":"Ren"},{"name":"Guest"}],"duration":180000},
                {"name":"no id"}]},"code":200}""",
        )
        assertEquals(
            listOf(
                NeteaseSong(1, "Song One", listOf("Ren"), 200_000),
                NeteaseSong(2, "Song Two", listOf("Ren", "Guest"), 180_000),
            ),
            parseNeteaseSearch(root),
        )
        assertEquals(emptyList(), parseNeteaseSearch(Json.parseToJsonElement("""{"code":-460}""")))
    }

    @Test
    fun onlyAnAnswerWithWordTimingGivesYrc() {
        assertEquals("[1,2](1,2,0)a", yrcOf(Json.parseToJsonElement("""{"yrc":{"lyric":"[1,2](1,2,0)a"},"code":200}""")))
        assertNull(yrcOf(Json.parseToJsonElement("""{"yrc":{"lyric":""},"code":200}""")))
        assertNull(yrcOf(Json.parseToJsonElement("""{"lrc":{"lyric":"[00:01.00]x"},"code":200}""")))
    }
}
