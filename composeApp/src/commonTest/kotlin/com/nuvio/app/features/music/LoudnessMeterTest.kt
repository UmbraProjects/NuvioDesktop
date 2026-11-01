package com.nuvio.app.features.music

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoudnessMeterTest {
    private val rate = 24_000

    /** Interleaved stereo sine, the same in both channels. */
    private fun sine(seconds: Double, amplitude: Double, hz: Double = 1000.0, startSample: Int = 0): FloatArray {
        val frames = (seconds * rate).toInt()
        val out = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val v = (amplitude * sin(2.0 * PI * hz * (startSample + i) / rate)).toFloat()
            out[i * 2] = v
            out[i * 2 + 1] = v
        }
        return out
    }

    private fun silence(seconds: Double) = FloatArray((seconds * rate).toInt() * 2)

    private fun measure(vararg parts: FloatArray): Double? {
        val meter = LoudnessMeter(rate, channels = 2)
        parts.forEach(meter::add)
        return meter.integratedLufs()
    }

    private fun close(expected: Double, actual: Double?, tolerance: Double = 0.3) {
        assertNotNull(actual)
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected but read $actual")
    }

    @Test
    fun aStereoSineReadsItsOwnLevel() {
        // BS.1770 is calibrated so a 1 kHz sine of amplitude A in both channels reads 20 log10(A) LUFS.
        close(-20.0, measure(sine(20.0, 0.1)))
        close(-26.0, measure(sine(20.0, 0.05)))
        close(-23.0, measure(sine(20.0, 0.0708)))
    }

    @Test
    fun silenceIsGatedOut() {
        close(-20.0, measure(silence(10.0), sine(20.0, 0.1), silence(5.0)))
    }

    @Test
    fun aQuietPassageBelowTheRelativeGateDoesNotPullTheAverageDown() {
        // Twenty seconds at -20 and ten at -40: the quiet part is more than 10 LU under the rest, so it is left out.
        close(-20.0, measure(sine(20.0, 0.1), sine(10.0, 0.01)), tolerance = 0.4)
    }

    @Test
    fun theAnswerDoesNotDependOnHowTheAudioArrives() {
        val whole = sine(24.0, 0.1)
        val oneGo = measure(whole)
        val meter = LoudnessMeter(rate, channels = 2)
        // Pieces that do not line up with the 100 ms step.
        var at = 0
        val sizes = intArrayOf(7_001, 13_337, 40_000, 3, 19_999)
        var i = 0
        while (at < whole.size) {
            val take = minOf(sizes[i % sizes.size] * 2, whole.size - at)
            meter.add(whole.copyOfRange(at, at + take))
            at += take
            i++
        }
        val pieces = meter.integratedLufs()
        assertNotNull(oneGo)
        assertNotNull(pieces)
        assertEquals(oneGo, pieces, absoluteTolerance = 0.01)
    }

    @Test
    fun nothingAudibleReadsNull() {
        assertNull(measure(silence(10.0)))
        assertNull(measure(sine(0.2, 0.1)))
        assertNull(LoudnessMeter(rate, 2).integratedLufs())
    }

    @Test
    fun secondsCountsWhatWasTakenIn() {
        val meter = LoudnessMeter(rate, channels = 2)
        meter.add(sine(2.5, 0.1))
        assertEquals(2.5, meter.seconds, absoluteTolerance = 0.001)
    }

    @Test
    fun cacheTextRoundTrips() {
        val text = encodeMeasuredLoudness(MeasuredLoudness(-13.64, 1_700_000_000_000L))
        assertEquals("-13.6|1700000000000", text)
        assertEquals(MeasuredLoudness(-13.6, 1_700_000_000_000L), decodeMeasuredLoudness(text))
        assertEquals(-7.5, decodeMeasuredLoudness(encodeMeasuredLoudness(MeasuredLoudness(-7.5, 1L)))?.lufs)
    }

    @Test
    fun cacheRejectsWhatIsNotALoudness() {
        assertNull(decodeMeasuredLoudness(null))
        assertNull(decodeMeasuredLoudness(""))
        assertNull(decodeMeasuredLoudness("-13.6"))
        assertNull(decodeMeasuredLoudness("abc|12"))
        assertNull(decodeMeasuredLoudness("-13.6|abc"))
        assertNull(decodeMeasuredLoudness("5.0|12"))
        assertNull(decodeMeasuredLoudness("-99.0|12"))
        assertNull(decodeMeasuredLoudness("NaN|12"))
    }

    @Test
    fun cacheDropsTheOldestFirst() {
        val ages = mapOf("a" to 5L, "b" to 1L, "c" to 9L, "d" to 3L)
        assertEquals(emptyList(), loudnessKeysToEvict(ages, 4))
        assertEquals(listOf("b"), loudnessKeysToEvict(ages, 3))
        assertEquals(listOf("b", "d"), loudnessKeysToEvict(ages, 2))
    }
}
