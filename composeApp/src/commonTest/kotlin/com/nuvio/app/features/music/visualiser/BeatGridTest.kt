package com.nuvio.app.features.music.visualiser

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The beat grid made from the Beat This! network's beats and downbeats, and the plain-Kotlin parts of that pipeline. */
class BeatGridTest {
    private fun steady(bpm: Double, seconds: Double, from: Double = 0.5): List<Double> {
        val beat = 60.0 / bpm
        return generateSequence(from) { it + beat }.takeWhile { it < seconds }.toList()
    }

    /** The bar position (0 = beat one) of the beat nearest [s] seconds. */
    private fun BeatGrid.positionAt(s: Double): Int = stepAt(s * 1_000.0 + 1.0).floorDiv(4L).mod(4L).toInt()

    @Test
    fun theBarLineFollowsTheNetworksDownbeatsNotTheFirstBeat() {
        // Billie Jean at 117: the song starts on beat three (the network's first downbeat is the third beat it heard).
        val beats = steady(117.0, 60.0)
        val downs = beats.drop(2).filterIndexed { i, _ -> i % 4 == 0 }
        val grid = assertNotNull(beatGridFrom(beats, downs))
        assertEquals(117.0, grid.bpm, 0.5)
        assertEquals(4, grid.beatsPerBar)
        for (d in downs) assertEquals(0, grid.positionAt(d), "downbeat at $d")
        assertEquals(2, grid.positionAt(beats[0]))
        // Before the first downbeat is the count-in.
        assertTrue(grid.stepAt(beats[0] * 1_000.0 + 1.0) < 0)
    }

    @Test
    fun beatsThatFlipToHalfTimeForAFewBarsDoNotMoveTheBar() {
        // A hip-hop loop at 136: the network hears every other beat for two bars, then every beat again.
        val all = steady(136.0, 40.0)
        val beats = all.filterIndexed { i, _ -> i !in 8 until 16 || i % 2 == 0 }
        val downs = all.filterIndexed { i, _ -> i % 4 == 0 }
        val grid = assertNotNull(beatGridFrom(beats, downs))
        assertEquals(136.0, grid.bpm, 1.0)
        for (d in downs) assertEquals(0, grid.positionAt(d))
        // The missing beats are back on the grid.
        assertTrue(grid.beatsMs.size >= all.size - 1, "${grid.beatsMs.size} beats for ${all.size}")
    }

    @Test
    fun aStrayDownbeatIsIgnored() {
        val beats = steady(120.0, 30.0)
        val downs = beats.filterIndexed { i, _ -> i % 4 == 0 }.toMutableList()
        downs += beats[21] // one doubtful downbeat on beat two of a bar
        downs.sort()
        val grid = assertNotNull(beatGridFrom(beats, downs))
        assertEquals(1, grid.positionAt(beats[21]))
        assertEquals(0, grid.positionAt(beats[24]))
    }

    @Test
    fun aWaltzHasThreeBeatsABar() {
        val beats = steady(90.0, 40.0)
        val downs = beats.filterIndexed { i, _ -> i % 3 == 0 }
        val grid = assertNotNull(beatGridFrom(beats, downs))
        assertEquals(3, grid.beatsPerBar)
        assertEquals(90.0, grid.bpm, 0.5)
        // The grid's fourth beat is skipped, so every bar starts on the left.
        for (d in downs.drop(1).dropLast(1)) assertEquals(0, grid.positionAt(d))
        val inBar = grid.stepAt(downs[2] * 1_000.0 + 1.0)
        assertFalse(grid.hasStep(inBar + 12), "the fourth beat of a bar of three")
        assertTrue(grid.hasStep(inBar + 8))
    }

    @Test
    fun barsOfTwoArePairedIntoBarsOfFour() {
        // A two-step at 88: the network puts a downbeat on every other beat.
        val beats = steady(88.0, 60.0)
        val downs = beats.filterIndexed { i, _ -> i % 2 == 0 }
        val grid = assertNotNull(beatGridFrom(beats, downs))
        assertEquals(88.0, grid.bpm, 0.5)
        assertEquals(4, grid.beatsPerBar)
    }

    @Test
    fun aSongThatSpeedsUpIsFollowedBarByBar() {
        val beats = ArrayList<Double>()
        var t = 0.5
        var beat = 0.6
        repeat(120) { beats += t; t += beat; beat *= 0.997 }
        val downs = beats.filterIndexed { i, _ -> i % 4 == 0 }
        val grid = assertNotNull(beatGridFrom(beats, downs))
        // One tempo over the whole song would be bars out by the end; this stays on the bar line.
        for (d in downs) assertEquals(0, grid.positionAt(d), "downbeat at $d")
        assertTrue(grid.stepMsAt(beats[110] * 1_000.0) < grid.stepMsAt(beats[5] * 1_000.0) * 0.8)
    }

    @Test
    fun tooFewBeatsIsNoGrid() {
        assertNull(beatGridFrom(listOf(1.0, 2.0, 3.0), listOf(1.0)))
    }

    @Test
    fun withoutDownbeatsTheBeatsAreCountedInFours() {
        val grid = assertNotNull(beatGridFrom(steady(100.0, 20.0), emptyList()))
        assertEquals(100.0, grid.bpm, 0.5)
    }

    @Test
    fun movingBeatOneByHand() {
        val beats = steady(120.0, 20.0)
        val grid = assertNotNull(beatGridFrom(beats, beats.filterIndexed { i, _ -> i % 4 == 0 }))
        assertEquals(0, grid.positionAt(beats[8]))
        val moved = grid.shifted(1)
        assertEquals(1, moved.barShift)
        // Beat one is now the beat after the network's.
        assertEquals(0, moved.positionAt(beats[7]))
        assertEquals(1, moved.positionAt(beats[8]))
        assertEquals(grid, moved.shifted(3))
    }

    @Test
    fun aGridSurvivesTheDiskWithItsShift() {
        val beats = steady(117.0, 30.0)
        val grid = assertNotNull(beatGridFrom(beats, beats.filterIndexed { i, _ -> i % 4 == 2 })).shifted(2)
        val map = TrackMap(
            frames = 4, bands = ByteArray(4 * MAP_BANDS), level = ByteArray(4), left = ByteArray(4), right = ByteArray(4),
            onsets = ByteArray(4), beat = grid, complete = true,
        )
        val back = assertNotNull(decodeTrackMap(encodeTrackMap(map)))
        assertEquals(grid, back.beat)
        assertEquals(2, back.beat?.barShift)
        assertNull(decodeTrackMap(encodeTrackMap(map).copyOf(30)))
    }

    @Test
    fun stepsStartWhereTheyAreCounted() {
        val beats = steady(120.0, 20.0)
        val grid = assertNotNull(beatGridFrom(beats, beats.filterIndexed { i, _ -> i % 4 == 0 }))
        for (ms in listOf(600.0, 1_234.0, 5_000.0, 9_876.0)) {
            val step = grid.stepAt(ms)
            assertTrue(grid.stepStartMs(step) <= ms + 1e-6 && ms < grid.stepStartMs(step + 1) + 1e-6, "at $ms step $step")
        }
    }

    // ---- the pipeline's plain parts ----

    @Test
    fun chunksStartAsBeatThisStartsThem() {
        // The values beat-this-rs's tests check against the original.
        assertEquals(listOf(-6), chunkStarts(100))
        assertEquals(listOf(-6, 6), chunkStarts(1500))
        assertEquals(listOf(-6, 506), chunkStarts(2000))
        assertEquals(listOf(-6, 1482, 2970, 3506), chunkStarts(5000))
        // While the track is read, the last chunk stays on the stride so the ones before it are kept.
        assertEquals(listOf(-6, 1482, 2970, 4458), chunkStarts(5000, final = false))
    }

    @Test
    fun peaksArePickedAsBeatThisPicksThem() {
        assertEquals(listOf(3.0 / 50), pickPeaks(floatArrayOf(0f, 0f, 0.5f, 1f, 0.5f, 0f, 0f)))
        assertTrue(pickPeaks(floatArrayOf(-1f, -0.5f, -2f, -0.1f)).isEmpty())
        val two = FloatArray(20).also { it[3] = 2f; it[15] = 1.5f }
        assertEquals(listOf(3.0 / 50, 15.0 / 50), pickPeaks(two))
        // Equal neighbours merge into their mean.
        assertEquals(listOf(1.5 / 50), pickPeaks(floatArrayOf(0f, 1f, 1f, 0f)))
    }

    @Test
    fun theResamplerKeepsATonesLevelAndTheLength() {
        val from = 48_000
        val to = 22_050
        val input = FloatArray(from * 3) { (0.5 * sin(2 * PI * 1_000.0 * it / from)).toFloat() }
        val r = Resampler(from, to)
        // Fed in odd-sized pieces, as the decode windows are.
        val parts = ArrayList<FloatArray>()
        var i = 0
        while (i < input.size) { val e = minOf(input.size, i + 7_919); parts += r.push(input.copyOfRange(i, e)); i = e }
        parts += r.finish()
        val out = FloatArray(parts.sumOf { it.size }).also { var at = 0; for (p in parts) { p.copyInto(it, at); at += p.size } }
        assertEquals(input.size.toLong() * to / from, out.size.toLong())
        var sum = 0.0
        for (k in 2_000 until out.size - 2_000) sum += out[k] * out[k]
        val rms = sqrt(sum / (out.size - 4_000))
        assertEquals(0.5 / sqrt(2.0), rms, 0.01)
        // The tone is still in time: output sample k is the input at k / 22.05 kHz.
        assertTrue(abs(out[5_000] - (0.5 * sin(2 * PI * 1_000.0 * (5_000 / to.toDouble())))) < 0.02)
    }

    @Test
    fun theResamplerStopsAboveTheNewNyquist() {
        val from = 48_000
        val input = FloatArray(from) { (0.5 * sin(2 * PI * 15_000.0 * it / from)).toFloat() }
        val r = Resampler(from, 22_050)
        val out = r.push(input) + r.finish()
        var peak = 0f
        for (k in 1_000 until out.size - 1_000) peak = maxOf(peak, abs(out[k]))
        assertTrue(peak < 0.01f, "15 kHz leaked through at $peak")
    }
}
