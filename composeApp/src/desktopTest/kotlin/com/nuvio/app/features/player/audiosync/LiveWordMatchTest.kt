package com.nuvio.app.features.player.audiosync

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveWordMatchTest {
    /** A made-up film: 400 lines of 4 to 8 words from a 3,000-word vocabulary, a line every 3 to 8 seconds. */
    private fun film(seed: Int): List<Triple<Long, Long, String>> {
        val random = Random(seed)
        var at = 60_000L
        return (0 until 400).map {
            at += 3_000L + random.nextLong(5_000L)
            val words = (0 until 4 + random.nextInt(5)).joinToString(" ") { "w${random.nextInt(3_000)}" }
            Triple(at, at + 2_000L + random.nextLong(1_500L), words)
        }
    }

    /** What whisper would write down for [lines] played at media = scale * film + shift, each word slightly off. */
    private fun heard(lines: List<Triple<Long, Long, String>>, scale: Double, shiftMs: Double, seed: Int): List<LiveWordMatch.Heard> {
        val random = Random(seed)
        return lines.flatMap { (start, end, text) ->
            val words = LiveWordMatch.words(text)
            words.mapIndexedNotNull { i, word ->
                if (random.nextInt(10) == 0) return@mapIndexedNotNull null // a word misheard or missed
                val film = start + (end - start) * (i + 0.5) / words.size
                LiveWordMatch.Heard((scale * film + shiftMs).toLong() + random.nextLong(-250L, 250L), word)
            }
        }
    }

    @Test
    fun `words drop sound descriptions, speaker names and apostrophes`() {
        assertEquals(listOf("dont", "go", "there"), LiveWordMatch.words("[DOOR SLAMS] JOHN: Don't go (whispers) there!"))
    }

    @Test
    fun `two minutes of words place the film at its own speed`() {
        val lines = film(1)
        val script = LiveWordMatch.Script(lines)
        val match = assertNotNull(LiveWordMatch.match(script, heard(lines.subList(200, 225), 1.0, -1_234_567.0, 2)))
        assertEquals(1.0, match.scale)
        assertTrue(abs(match.shiftMs + 1_234_567.0) <= 400.0, match.toString())
    }

    @Test
    fun `a film sped up to 25 frames is placed at that speed`() {
        val lines = film(3)
        val scale = 23.976 / 25.0
        val match = assertNotNull(LiveWordMatch.match(LiveWordMatch.Script(lines), heard(lines.subList(150, 190), scale, -900_000.0, 4)))
        // At the film's place the mapping is right to within a frame or two of speech, whichever of the 25-frame
        // ratios it picked.
        val film = lines[170].first.toDouble()
        assertTrue(abs(match.scale * film + match.shiftMs - (scale * film - 900_000.0)) <= 500.0, match.toString())
        assertTrue(abs(match.scale - 1.0) > 0.03, match.toString())
    }

    @Test
    fun `words from another film place nothing`() {
        val script = LiveWordMatch.Script(film(5))
        assertNull(LiveWordMatch.match(script, heard(film(6).subList(100, 140), 1.0, 0.0, 7)))
    }

    @Test
    fun `recent words that place the film elsewhere make a new piece, agreeing ones do not`() {
        val current = SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, 1.0, -600_000.0)))
        val same = LiveWordMatch.Match(1.0, -600_400.0, 6, 0, fromMediaMs = 300_000L)
        assertNull(LiveAudioSubtitleSync.jumped(current, same))
        val afterBreak = LiveWordMatch.Match(1.0, -420_000.0, 6, 0, fromMediaMs = 900_000L)
        val moved = assertNotNull(LiveAudioSubtitleSync.jumped(current, afterBreak))
        assertEquals(2, moved.segments.size)
        assertEquals(900_000L, moved.segments.last().fromMediaMs)
        assertEquals(-420_000.0, moved.segments.last().shiftMs)
    }

    @Test
    fun `moving a mapping to the guide's lines and back gives it back`() {
        val model = SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, 0.959, -1_000.0)))
        val back = LiveAudioSubtitleSync.unshift(LiveAudioSubtitleSync.reshift(model, 2_400_000L), 2_400_000L)
        assertTrue(abs(back.segments.single().shiftMs + 1_000.0) < 1e-6)
    }
}
