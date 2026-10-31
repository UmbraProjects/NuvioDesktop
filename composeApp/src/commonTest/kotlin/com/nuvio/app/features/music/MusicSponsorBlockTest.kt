package com.nuvio.app.features.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicSponsorBlockTest {
    private val body = """
        [
          {"videoID": "other", "hash": "abcd", "segments": [
            {"category": "music_offtopic", "actionType": "skip", "segment": [0.0, 30.0], "UUID": "x"}
          ]},
          {"videoID": "dQw4w9WgXcQ", "hash": "abcd", "segments": [
            {"category": "music_offtopic", "actionType": "skip", "segment": [212.5, 230.0], "UUID": "b"},
            {"category": "music_offtopic", "actionType": "skip", "segment": [0.0, 14.25], "UUID": "a"},
            {"category": "sponsor", "actionType": "skip", "segment": [40.0, 60.0], "UUID": "c"},
            {"category": "music_offtopic", "actionType": "mute", "segment": [90.0, 95.0], "UUID": "d"},
            {"category": "music_offtopic", "actionType": "skip", "segment": [100.0, 100.4], "UUID": "e"}
          ]}
        ]
    """.trimIndent()

    @Test
    fun `only the video's own non-music skips are read, in order`() {
        assertEquals(
            listOf(MusicSkipSegment(0L, 14_250L), MusicSkipSegment(212_500L, 230_000L)),
            parseSponsorBlockSegments(body, "dQw4w9WgXcQ"),
        )
    }

    @Test
    fun `a video missing from the answer, or a broken answer, has no segments`() {
        assertTrue(parseSponsorBlockSegments(body, "missing").isEmpty())
        assertTrue(parseSponsorBlockSegments("Not Found", "dQw4w9WgXcQ").isEmpty())
    }

    @Test
    fun `a position inside a segment jumps to its end, but not from its last moment`() {
        val segments = listOf(MusicSkipSegment(0L, 14_000L), MusicSkipSegment(200_000L, 230_000L))
        assertEquals(segments[0], musicSkipTarget(300L, segments))
        assertEquals(segments[1], musicSkipTarget(215_000L, segments))
        assertNull(musicSkipTarget(13_800L, segments))
        assertNull(musicSkipTarget(60_000L, segments))
    }

    @Test
    fun `a segment from the very start is an intro to open past, unless it runs to the end`() {
        val intro = MusicSkipSegment(0L, 21_800L)
        val outro = MusicSkipSegment(249_000L, 281_500L)
        assertEquals(intro, musicIntroSegment(listOf(intro, outro), 281_500L))
        // Starting a moment in still counts; a later one does not.
        assertEquals(MusicSkipSegment(900L, 12_000L), musicIntroSegment(listOf(MusicSkipSegment(900L, 12_000L)), 200_000L))
        assertNull(musicIntroSegment(listOf(MusicSkipSegment(30_000L, 40_000L)), 200_000L))
        // A video marked off-topic from start to end is not opened at its end.
        assertNull(musicIntroSegment(listOf(MusicSkipSegment(0L, 200_000L)), 200_000L))
    }
}
