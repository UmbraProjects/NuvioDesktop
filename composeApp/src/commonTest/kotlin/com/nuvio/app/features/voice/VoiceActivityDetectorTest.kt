package com.nuvio.app.features.voice

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceActivityDetectorTest {
    private val frameMs = 20
    private val frameSize = 320 // 20 ms at 16 kHz
    private val random = Random(3)

    private fun noise(amplitude: Float) = FloatArray(frameSize) { (random.nextFloat() * 2 - 1) * amplitude }

    private fun voice(amplitude: Float, noiseAmplitude: Float = 0.003f): FloatArray {
        val n = noise(noiseAmplitude)
        return FloatArray(frameSize) { n[it] + amplitude * sin(2 * PI * 220 * it / 16_000).toFloat() }
    }

    /** Feeds [frames] and returns every non-Continue event with the time it came at. */
    private fun run(detector: VoiceActivityDetector, frames: List<FloatArray>): List<Pair<VoiceActivity, Int>> {
        val events = mutableListOf<Pair<VoiceActivity, Int>>()
        frames.forEachIndexed { index, frame ->
            val event = detector.accept(frame, frameMs)
            if (event != VoiceActivity.Continue) events += event to (index + 1) * frameMs
            if (event == VoiceActivity.SpeechEnded || event == VoiceActivity.NoSpeech || event == VoiceActivity.TooLong) {
                return events
            }
        }
        return events
    }

    private fun frames(ms: Int, make: () -> FloatArray) = List(ms / frameMs) { make() }

    @Test
    fun aCommandBetweenQuietStretchesEndsAfterThePause() {
        val events = run(
            VoiceActivityDetector(endSilenceMs = 700),
            frames(400) { noise(0.003f) } + frames(1_500) { voice(0.2f) } + frames(2_000) { noise(0.003f) },
        )
        assertEquals(VoiceActivity.SpeechStarted, events[0].first)
        assertTrue(events[0].second in 440..480, "speech found at ${events[0].second} ms")
        assertEquals(VoiceActivity.SpeechEnded, events[1].first)
        assertEquals(1_900 + 700, events[1].second)
    }

    @Test
    fun shortGapsBetweenWordsDoNotEndIt() {
        val words = frames(300) { voice(0.2f) } + frames(300) { noise(0.003f) }
        val events = run(
            VoiceActivityDetector(endSilenceMs = 700),
            frames(300) { noise(0.003f) } + words + words + words + frames(1_000) { noise(0.003f) },
        )
        assertEquals(listOf(VoiceActivity.SpeechStarted, VoiceActivity.SpeechEnded), events.map { it.first })
        // Ended 700 ms after the last word, not in one of the 300 ms gaps.
        assertEquals(300 + 3 * 600 - 300 + 700, events.last().second)
    }

    @Test
    fun silenceTimesOutWithoutSpeech() {
        val events = run(VoiceActivityDetector(noSpeechTimeoutMs = 4_000), frames(6_000) { noise(0.003f) })
        assertEquals(listOf(VoiceActivity.NoSpeech to 4_000), events)
    }

    @Test
    fun digitalSilenceIsNotSpeechEither() {
        val events = run(VoiceActivityDetector(), frames(5_000) { FloatArray(frameSize) })
        assertEquals(VoiceActivity.NoSpeech, events.single().first)
    }

    @Test
    fun aSteadyNoisyRoomIsNotSpeechButAVoiceOverItIs() {
        val events = run(
            VoiceActivityDetector(),
            frames(1_000) { noise(0.02f) } + frames(1_000) { voice(0.25f, noiseAmplitude = 0.02f) } + frames(1_000) { noise(0.02f) },
        )
        assertEquals(listOf(VoiceActivity.SpeechStarted, VoiceActivity.SpeechEnded), events.map { it.first })
        assertTrue(events[0].second > 1_000)
    }

    @Test
    fun talkingFromThePressStillCounts() {
        // No quiet stretch first: the floor must not be learned from the voice itself.
        val events = run(VoiceActivityDetector(), frames(1_200) { voice(0.2f) } + frames(1_000) { noise(0.003f) })
        assertEquals(listOf(VoiceActivity.SpeechStarted, VoiceActivity.SpeechEnded), events.map { it.first })
    }

    @Test
    fun endlessSpeechIsCutOff() {
        val events = run(VoiceActivityDetector(maxUtteranceMs = 8_000), frames(12_000) { voice(0.2f) })
        assertEquals(VoiceActivity.TooLong to 8_000, events.last())
    }

    @Test
    fun aSingleClickIsNotSpeech() {
        val click = FloatArray(frameSize) { if (it < 20) 0.9f else 0f }
        val events = run(
            VoiceActivityDetector(noSpeechTimeoutMs = 2_000),
            frames(500) { noise(0.003f) } + listOf(click) + frames(2_000) { noise(0.003f) },
        )
        assertEquals(listOf(VoiceActivity.NoSpeech), events.map { it.first })
    }
}
