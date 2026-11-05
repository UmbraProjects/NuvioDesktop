package com.nuvio.app.features.preroll

import com.nuvio.app.features.trailer.TrailerPlaybackSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoudnessPlanTest {
    @AfterTest
    fun reset() = StreamLoudnessBook.clearForTest()

    @Test
    fun youTubeFigureIsOffsetFromItsReference() {
        assertEquals(-9.0, lufsFromYouTube(5.0))
        assertEquals(-16.5, lufsFromYouTube(-2.5))
        assertNull(lufsFromYouTube(null))
        assertNull(lufsFromYouTube(Double.NaN))
        assertNull(lufsFromYouTube(80.0))
    }

    @Test
    fun gainBringsTheStreamToTheTarget() {
        // Music's target -14: a -9 LUFS master is turned down 5 dB, a -20 LUFS recording is turned up 6 dB.
        assertEquals(-5.0, planGainDb(-9.0, LoudnessClass.Music))
        assertEquals(6.0, planGainDb(-20.0, LoudnessClass.Music))
        // Trailers' target -24 (the film's): a -9 LUFS trailer is turned down 15 dB.
        assertEquals(-15.0, planGainDb(-9.0, LoudnessClass.Trailer))
    }

    @Test
    fun anUnknownStreamIsPlannedFromItsClass() {
        assertEquals(MUSIC_LOUDNESS_TARGET_LUFS - LoudnessClass.Music.assumedLufs, planGainDb(null, LoudnessClass.Music))
        assertEquals(LOUDNESS_TARGET_LUFS - LoudnessClass.Trailer.assumedLufs, planGainDb(null, LoudnessClass.Trailer))
    }

    @Test
    fun gainIsBounded() {
        assertEquals(MAX_GAIN_DB, planGainDb(-80.0, LoudnessClass.Music))
        assertEquals(MIN_GAIN_DB, planGainDb(20.0, LoudnessClass.Music))
    }

    @Test
    fun smallDifferencesAreNotWorthACorrection() {
        assertNull(gainCorrectionDb(-13.0, -13.6))
        assertNull(gainCorrectionDb(-13.0, -12.2))
        assertEquals(-3.0, gainCorrectionDb(-13.0, -16.0))
        assertEquals(2.5, gainCorrectionDb(-13.0, -10.5))
    }

    @Test
    fun aCorrectionIsNeverLargerThanTheCap() {
        assertEquals(12.0, gainCorrectionDb(-30.0, 9.0))
        assertEquals(-12.0, gainCorrectionDb(9.0, -30.0))
    }

    @Test
    fun decibelsAreWrittenInPlainNotation() {
        assertEquals("-13.0", formatDb(-13.0))
        assertEquals("2.5", formatDb(2.54))
        assertEquals("0", formatDb(0.04))
        assertEquals("0", formatDb(-0.04))
        assertEquals("-0.1", formatDb(-0.1))
        assertFalse(formatDb(0.0001).contains('E'))
    }

    @Test
    fun chainIsGainThenCompressorThenLimiter() {
        val chain = staticLoudnessChain(LoudnessClass.Music, -13.0)
        assertEquals(3, chain.size)
        assertEquals("volume=-13.0dB", chain[0])
        assertTrue(chain[1].startsWith("acompressor=threshold=-10dB:ratio=2.0"))
        assertTrue(chain[2].startsWith("alimiter="))
        // Left on, the limiter would normalise the output back up to full scale.
        assertTrue(chain[2].contains("level=disabled"))
    }

    @Test
    fun trailersAreHeldHarderThanMusic() {
        assertTrue(LoudnessClass.Trailer.compressorRatio > LoudnessClass.Music.compressorRatio)
        // Relative to each class's target, so the two thresholds compare.
        assertTrue(
            LoudnessClass.Trailer.compressorThresholdDb - LoudnessClass.Trailer.targetLufs <
                LoudnessClass.Music.compressorThresholdDb - LoudnessClass.Music.targetLufs,
        )
    }

    @Test
    fun noChainRidesTheGain() {
        val value = loudnessAfValue(LoudnessClass.Music, -13.0, extra = listOf("afade=t=in:st=0:d=0.1"))
        assertFalse(value.contains("loudnorm"))
        assertFalse(value.contains("dynaudnorm"))
        assertTrue(value.startsWith("lavfi=[volume=-13.0dB,"))
        assertTrue(value.endsWith(",afade=t=in:st=0:d=0.1]"))
    }

    @Test
    fun bookRemembersEveryUrlOfASource() {
        val source = TrailerPlaybackSource(
            videoUrl = "https://v/1",
            audioUrl = "https://a/1",
            fallback = TrailerPlaybackSource(videoUrl = "https://hls/1"),
        )
        StreamLoudnessBook.record(source.streamUrls(), 4.0)
        assertEquals(-10.0, StreamLoudnessBook.lufsFor("https://v/1"))
        assertEquals(-10.0, StreamLoudnessBook.lufsFor("https://a/1"))
        assertEquals(-10.0, StreamLoudnessBook.lufsFor("https://hls/1"))
        assertNull(StreamLoudnessBook.lufsFor("https://other"))
        assertNull(StreamLoudnessBook.lufsFor(null, "https://other"))
        assertEquals(-10.0, StreamLoudnessBook.lufsFor(null, "https://a/1"))
    }

    @Test
    fun bookKeepsNothingForAnUnbelievableFigure() {
        StreamLoudnessBook.record(listOf("https://x"), null)
        StreamLoudnessBook.record(listOf("https://x"), 500.0)
        assertNull(StreamLoudnessBook.lufsFor("https://x"))
    }

    @Test
    fun bookDropsTheOldestWhenFull() {
        repeat(350) { StreamLoudnessBook.record(listOf("https://u/$it"), 0.0) }
        assertNull(StreamLoudnessBook.lufsFor("https://u/0"))
        assertNotNull(StreamLoudnessBook.lufsFor("https://u/349"))
    }

    @Test
    fun streamUrlsSkipsBlanksAndFollowsFallbacks() {
        val source = TrailerPlaybackSource(videoUrl = "https://v", audioUrl = " ", fallback = TrailerPlaybackSource(videoUrl = "https://w"))
        assertEquals(listOf("https://v", "https://w"), source.streamUrls())
    }

    @Test
    fun trailerChainUsesYouTubesFigureWhenKnown() {
        StreamLoudnessBook.record(listOf("https://t"), 2.0)
        // -12 LUFS: 12 dB down to -24.
        assertEquals("volume=-12.0dB", trailerLoudnessChain("https://t").first())
        // Unknown: the class's assumption, -12 LUFS, the same here by design of the test below.
        assertEquals(
            "volume=${formatDb(LOUDNESS_TARGET_LUFS - LoudnessClass.Trailer.assumedLufs)}dB",
            trailerLoudnessChain("https://unknown").first(),
        )
    }
}
