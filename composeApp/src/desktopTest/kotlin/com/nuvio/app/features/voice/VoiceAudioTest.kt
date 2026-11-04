package com.nuvio.app.features.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceAudioTest {

    @Test
    fun pcm16IsReadAsSignedFloatsFromTheFirstChannel() {
        // Little-endian stereo frames: (0x4000, junk), (0xC000, junk).
        val bytes = byteArrayOf(0x00, 0x40, 0x11, 0x11, 0x00, 0xC0.toByte(), 0x22, 0x22)
        val floats = VoiceAudio.pcm16ToFloats(bytes, bytes.size, channels = 2, bigEndian = false)
        assertEquals(listOf(0.5f, -0.5f), floats.toList())
        val big = VoiceAudio.pcm16ToFloats(byteArrayOf(0x40, 0x00), 2, channels = 1, bigEndian = true)
        assertEquals(0.5f, big.single())
    }

    @Test
    fun resamplingTo16kKeepsAVoiceToneAndDropsWhatIsAboveIt() {
        fun tone(hz: Double, rate: Int) = FloatArray(rate) { (0.5 * sin(2 * PI * hz * it / rate)).toFloat() }
        fun peak(samples: FloatArray) = samples.drop(200).dropLast(200).maxOf { abs(it) }

        val voice = VoiceAudio.resampleTo16k(tone(440.0, 48_000), 48_000)
        assertEquals(16_000, voice.size)
        assertTrue(abs(peak(voice) - 0.5f) < 0.03f, "440 Hz survives at ${peak(voice)}")
        // Same tone, sampled at 16 kHz directly, sample for sample.
        val direct = tone(440.0, 16_000)
        assertTrue((200 until 15_800).all { abs(voice[it] - direct[it]) < 0.03f })

        // 12 kHz is above the new 8 kHz Nyquist: filtered out rather than folded down to 4 kHz.
        val alias = VoiceAudio.resampleTo16k(tone(12_000.0, 48_000), 48_000)
        assertTrue(peak(alias) < 0.05f, "12 kHz leaks through at ${peak(alias)}")

        val cd = VoiceAudio.resampleTo16k(tone(440.0, 44_100), 44_100)
        assertTrue(abs(cd.size - 16_000) <= 1)
    }

    @Test
    fun sixteenKilohertzIsLeftAlone() {
        val input = floatArrayOf(0.1f, 0.2f, 0.3f)
        assertTrue(VoiceAudio.resampleTo16k(input, 16_000) === input)
    }
}
