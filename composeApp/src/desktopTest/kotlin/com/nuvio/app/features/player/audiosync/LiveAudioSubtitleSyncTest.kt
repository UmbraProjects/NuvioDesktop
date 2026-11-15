package com.nuvio.app.features.player.audiosync

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LiveAudioSubtitleSyncTest {
    private val lines = listOf(
        Triple(10_000L, 12_000L, "early"),
        Triple(2_399_000L, 2_401_000L, "straddles the tune-in"),
        Triple(2_500_000L, 2_503_000L, "after"),
    )

    @Test
    fun `lines are moved by the guide's offset and those already past are dropped`() {
        val moved = LiveAudioSubtitleSync.shifted(lines, 2_400_000L)
        assertEquals(listOf(Triple(0L, 1_000L, "straddles the tune-in"), Triple(100_000L, 103_000L, "after")), moved)
    }

    @Test
    fun `the guesses cover the guide's offset at the film's speed and sped up to 25 frames`() {
        val guesses = LiveAudioSubtitleSync.guesses(2_400_000L)
        assertEquals(2, guesses.size)
        for ((guess, onGuide) in guesses.zip(listOf(2_400_000L, 2_502_502L))) {
            // The guide's own offset and an hour's worth of late start and ads behind it are both in the search.
            val plain = guess.offsetMs - onGuide
            assertTrue(plain <= 0 && guess.minShiftMs <= plain - 5 * 60_000 && guess.maxShiftMs >= 0, guess.toString())
            assertTrue(guess.offsetMs - (onGuide - 15 * 60_000L) >= guess.minShiftMs.toLong(), "15 min of ads: $guess")
        }
    }

    @Test
    fun `the search reaches far more than a minute once a film has been on a while`() {
        val guess = LiveAudioSubtitleSync.guesses(60 * 60_000L).first()
        // An hour in: a 5-minute late start plus up to 15 minutes of ads behind the guide, 5 minutes ahead of it.
        assertTrue(-guess.minShiftMs >= 25 * 60_000.0, guess.toString())
        // Every line still to be heard stays in: nothing plausible is before the moved lines' 0.
        assertTrue(guess.offsetMs <= 60 * 60_000L - 20 * 60_000L, guess.toString())
    }

    @Test
    fun `speech heard is kept for another subtitle on the same tune-in, not after tuning in again`() {
        val first = LiveAudioSubtitleSync.heardFor("chan@1000", 600_000L)
        assertTrue(first === LiveAudioSubtitleSync.heardFor("chan@1000", 602_000L))
        assertTrue(first !== LiveAudioSubtitleSync.heardFor("chan@1000", 900_000L))
        assertTrue(LiveAudioSubtitleSync.heardFor("other@1000", 900_000L) !== LiveAudioSubtitleSync.heardFor("chan@2000", 900_000L))
    }

    @Test
    fun `a film twelve minutes behind the guide locks`() {
        // Speech exactly where the lines say, 12 minutes behind the guide's guess (ad breaks before tuning in).
        val guideOffset = 45 * 60_000L
        val truth = guideOffset - 12 * 60_000L
        val random = Random(7)
        var at0 = 0L
        val film = (0 until 700).map { i ->
            at0 += 2_500L + random.nextLong(9_000L)
            Triple(at0, at0 + 1_200L + random.nextLong(2_500L), "line $i")
        }
        val guess = LiveAudioSubtitleSync.guesses(guideOffset).first()
        val tracker = AudioSyncTracker(
            track = SubtitleSpeechTrack.fromCues(LiveAudioSubtitleSync.shifted(film, guess.offsetMs)),
            searchMinShiftMs = guess.minShiftMs,
            searchMaxShiftMs = guess.maxShiftMs,
            jumpAheadMs = LiveAudioSubtitleSync.JUMP_AHEAD_MS,
        )
        val timeline = SpeechTimeline()
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        val heardMs = 4 * 60_000L
        val probabilities = FloatArray((heardMs / frameMs).toInt()) { f ->
            val filmMs = truth + (f * frameMs).toLong()
            if (film.any { (a, b, _) -> filmMs in a until b }) 0.95f else 0.05f
        }
        probabilities.forEachIndexed { frame, p -> timeline.record(frame, p) }
        var locked: SubtitleSyncModel? = null
        repeat(4) { if (locked == null) (tracker.update(timeline, heardMs) as? AudioSyncTracker.Outcome.Locked)?.let { locked = it.model } }
        val model = LiveAudioSubtitleSync.unshift(assertNotNull(locked), guess.offsetMs)
        val at = model.mediaTimeUs((truth + 60_000L) * 1_000L)!!
        assertTrue(abs(at - 60_000_000L) <= 200_000L, "line a minute in shows at ${at / 1_000} ms")
    }

    @Test
    fun `a mapping found on moved lines places the film's own lines the same`() {
        val offset = 2_400_000L
        val scale = 23.976 / 25.0
        val onMoved = SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, scale, 1_500.0)))
        val onFilm = LiveAudioSubtitleSync.unshift(onMoved, offset)
        for (moved in listOf(0L, 60_000L, 600_000L)) {
            val film = moved + offset
            val expected = onMoved.mediaTimeUs(moved * 1_000L)!!
            val actual = onFilm.mediaTimeUs(film * 1_000L)!!
            assertTrue(abs(expected - actual) <= 1_000L, "at $moved: $expected vs $actual")
        }
    }
}
