package com.nuvio.app.features.voice

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * fork:voice-control - decides when a push-to-talk utterance is over, from the microphone's
 * 20 ms frames alone, so the person only presses once.
 *
 * Energy against an adaptive noise floor: the first [VoiceActivityDetector.calibrationMs] after the
 * press set the floor (the quietest frame, since the person may already be talking), and frames
 * that are not speech keep nudging it towards the room's level. Speech is [START_FRAMES] frames in
 * a row above the floor by [SPEECH_RATIO]; the utterance ends after [VoiceActivityDetector.endSilenceMs]
 * below it. This is also the gate that keeps silence away from whisper, which writes "You" for it.
 */

enum class VoiceActivity {
    /** Nothing decided yet: keep feeding frames. */
    Continue,

    /** The first frame of speech (once per utterance). */
    SpeechStarted,

    /** Speech, then [VoiceActivityDetector.endSilenceMs] of quiet: transcribe now. */
    SpeechEnded,

    /** [VoiceActivityDetector.noSpeechTimeoutMs] passed without any speech. */
    NoSpeech,

    /** Speech ran to [VoiceActivityDetector.maxUtteranceMs]: transcribe what there is. */
    TooLong,
}

class VoiceActivityDetector(
    val endSilenceMs: Int = DEFAULT_END_SILENCE_MS,
    val noSpeechTimeoutMs: Int = 4_000,
    val maxUtteranceMs: Int = 8_000,
    val calibrationMs: Int = 200,
) {
    private var elapsedMs = 0
    private var noiseFloor = -1.0
    private var aboveRun = 0
    private var belowMs = 0
    private var speaking = false

    /** True once speech has started. */
    val heardSpeech: Boolean get() = speaking

    /** Feeds one frame of [samples] lasting [frameMs]; says whether the utterance is over. */
    fun accept(samples: FloatArray, frameMs: Int): VoiceActivity {
        val energy = rms(samples)
        elapsedMs += frameMs

        if (elapsedMs <= calibrationMs) {
            noiseFloor = if (noiseFloor < 0) energy else min(noiseFloor, energy)
            // Too early to call speech: the floor is still being found.
            return VoiceActivity.Continue
        }
        if (noiseFloor < 0) noiseFloor = energy
        // A floor taken while the person was already loud would hide their speech; rooms are quieter than this.
        noiseFloor = min(noiseFloor, MAX_NOISE_FLOOR)

        val threshold = max(noiseFloor * SPEECH_RATIO, MIN_SPEECH_RMS)
        val loud = energy > threshold
        if (loud) {
            aboveRun++
            belowMs = 0
        } else {
            aboveRun = 0
            belowMs += frameMs
            // Follow the room slowly, only while nobody is talking over it.
            noiseFloor = noiseFloor * (1 - FLOOR_ADAPT) + energy * FLOOR_ADAPT
        }

        if (!speaking) {
            if (aboveRun >= START_FRAMES) {
                speaking = true
                return VoiceActivity.SpeechStarted
            }
            return if (elapsedMs >= noSpeechTimeoutMs) VoiceActivity.NoSpeech else VoiceActivity.Continue
        }
        if (belowMs >= endSilenceMs) return VoiceActivity.SpeechEnded
        if (elapsedMs >= maxUtteranceMs) return VoiceActivity.TooLong
        return VoiceActivity.Continue
    }

    companion object {
        const val DEFAULT_END_SILENCE_MS = 700

        /** The end-of-speech pauses Settings offers (very short / short / normal / long). */
        val END_SILENCE_OPTIONS_MS = listOf(300, 500, DEFAULT_END_SILENCE_MS, 1_000)

        private const val START_FRAMES = 3
        private const val SPEECH_RATIO = 3.0 // ≈ +9.5 dB over the floor
        private const val MIN_SPEECH_RMS = 0.01 // ≈ -40 dBFS: a floor of digital silence still needs a voice
        private const val MAX_NOISE_FLOOR = 0.03
        private const val FLOOR_ADAPT = 0.05

        fun rms(samples: FloatArray): Double {
            if (samples.isEmpty()) return 0.0
            var sum = 0.0
            for (s in samples) sum += s * s
            return sqrt(sum / samples.size)
        }
    }
}
