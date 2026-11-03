package com.nuvio.app.features.music.visualiser

import com.nuvio.app.features.music.LyricLine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackMapTest {
    private val rate = 24_000

    /** A drum loop: kick on beats 1 and 3, snare on 2 and 4, hats on every eighth, starting at [startS]. */
    private fun drums(seconds: Double, bpm: Double, startS: Double = 0.5): FloatArray {
        val n = (seconds * rate).toInt()
        val out = FloatArray(n * 2)
        val beat = 60.0 / bpm
        val noise = Random(7)
        for (i in 0 until n) {
            val t = i.toDouble() / rate - startS
            var v = 0.0
            if (t >= 0) {
                val b = (t / beat).toInt()
                val inBeat = t - b * beat
                if (b % 2 == 0) v += 0.8 * sin(2 * PI * 55.0 * inBeat) * exp(-inBeat * 18)
                if (b % 2 == 1) v += 0.35 * (noise.nextDouble() * 2 - 1) * exp(-inBeat * 30)
                val e = beat / 2
                val inEighth = t - (t / e).toInt() * e
                v += 0.08 * (noise.nextDouble() * 2 - 1) * exp(-inEighth * 120)
            }
            out[2 * i] = v.toFloat()
            out[2 * i + 1] = (v * 0.8).toFloat()
        }
        return out
    }

    private fun build(samples: FloatArray, pieces: Int = 3): TrackMap {
        val builder = TrackMapBuilder(rate)
        val step = (samples.size / pieces) and 1.inv()
        var at = 0
        while (at < samples.size) {
            val end = minOf(samples.size, at + step)
            builder.add(samples.copyOfRange(at, end))
            at = end
        }
        return builder.finish()
    }

    @Test
    fun findsTheTempoOfASteadyLoop() {
        val map = build(drums(30.0, 120.0))
        val beat = assertNotNull(map.beat)
        assertTrue(abs(beat.bpm - 120.0) < 2.0, "bpm ${beat.bpm}")
    }

    @Test
    fun findsASlowerTempoToo() {
        val map = build(drums(30.0, 96.0))
        val beat = assertNotNull(map.beat)
        assertTrue(abs(beat.bpm - 96.0) < 2.0, "bpm ${beat.bpm}")
    }

    @Test
    fun theDownbeatLandsOnAKick() {
        val map = build(drums(30.0, 120.0, startS = 0.5))
        val beat = assertNotNull(map.beat)
        // Kicks fall on 0.5 s + whole multiples of a second (beats 1 and 3 at 120 BPM).
        val beatsFromFirstKick = (beat.downbeatMs - 500.0) / beat.periodMs
        val nearest = beatsFromFirstKick.roundToInt()
        assertTrue(abs(beatsFromFirstKick - nearest) < 0.25, "off the beat: $beatsFromFirstKick")
        assertEquals(0, ((nearest % 2) + 2) % 2, "downbeat on a snare beat: $beatsFromFirstKick")
    }

    @Test
    fun kicksAreFoundOnTheBeat() {
        val map = build(drums(20.0, 120.0, startS = 0.5))
        var found = 0
        var total = 0
        var kickMs = 500L
        while (kickMs < 19_000L) {
            total++
            if (map.hitsBetween(Onset.KICK, kickMs - 60.0, kickMs + 90.0)) found++
            kickMs += 1_000L
        }
        assertTrue(found >= total * 0.85, "$found of $total kicks")
    }

    @Test
    fun silenceHasNoBeatAndNoLevel() {
        val map = build(FloatArray(rate * 2 * 12))
        assertNull(map.beat)
        assertEquals(0f, map.level(map.frames / 2))
        assertEquals(0f, map.band(map.frames / 2, 4))
        assertFalse(map.hitsBetween(Onset.KICK, 0.0, map.analysedMs.toDouble()))
    }

    @Test
    fun framesCoverTheTrack() {
        val map = build(drums(10.0, 120.0))
        assertTrue(abs(map.analysedMs - 10_000L) <= MAP_FRAME_MS * 2, "analysed ${map.analysedMs}")
        assertTrue(map.complete)
    }

    @Test
    fun snapshotsGrowAsWindowsArrive() {
        val samples = drums(20.0, 120.0)
        val builder = TrackMapBuilder(rate)
        builder.add(samples.copyOfRange(0, samples.size / 2))
        val first = builder.snapshot()
        builder.add(samples.copyOfRange(samples.size / 2, samples.size))
        val second = builder.snapshot()
        assertFalse(first.complete)
        assertTrue(second.frames > first.frames)
        assertTrue(abs(first.analysedMs - 10_000L) < 200L, "first ${first.analysedMs}")
    }

    @Test
    fun leftAndRightAreMeasuredApart() {
        val map = build(drums(10.0, 120.0))
        var l = 0f
        var r = 0f
        for (f in 0 until map.frames) { l += map.left(f); r += map.right(f) }
        assertTrue(l > r, "left $l right $r")
    }

    @Test
    fun aMapSurvivesTheDisk() {
        val map = build(drums(12.0, 120.0))
        val back = assertNotNull(decodeTrackMap(encodeTrackMap(map)))
        assertEquals(map.frames, back.frames)
        assertEquals(map.complete, back.complete)
        assertEquals(map.beat, back.beat)
        assertTrue(map.bands.contentEquals(back.bands))
        assertTrue(map.onsets.contentEquals(back.onsets))
        assertNull(decodeTrackMap(ByteArray(40)))
    }

    @Test
    fun beatGridCountsSixteenths() {
        val grid = BeatGrid(periodMs = 500.0, downbeatMs = 1_000.0)
        assertEquals(0L, grid.stepAt(1_000.0))
        assertEquals(3L, grid.stepAt(1_499.0))
        assertEquals(16L, grid.stepAt(3_000.0))
        assertEquals(-1L, grid.stepAt(999.0))
        assertEquals(120.0, grid.bpm)
    }

    // ---- song sections ----

    private fun lines(vararg pairs: Pair<Long, String>) = pairs.map { LyricLine(it.first, it.second) }

    private val song = lines(
        10_000L to "Out on the highway tonight",
        14_000L to "Nothing but the radio",
        18_000L to "Hold on to the line",
        22_000L to "Hold on to the line",
        30_000L to "Every mile I meant to say",
        34_000L to "Hold on to the line",
        60_000L to "Back on the highway",
        64_000L to "Hold on to the line",
    )

    @Test
    fun sectionsComeFromTheLyrics() {
        val parts = songSections(song, 80_000L)
        assertEquals(
            listOf(
                SectionKind.Intro, SectionKind.Verse, SectionKind.Chorus, SectionKind.Verse, SectionKind.Chorus,
                SectionKind.Instrumental, SectionKind.Verse, SectionKind.Chorus, SectionKind.Outro,
            ),
            parts.map { it.kind },
        )
        assertEquals(0L, parts.first().startMs)
        assertEquals(80_000L, parts.last().endMs)
        parts.zipWithNext().forEach { (a, b) -> assertEquals(a.endMs, b.startMs) }
    }

    @Test
    fun theNextChorusIsFound() {
        val parts = songSections(song, 80_000L)
        assertEquals(18_000L, nextChorusStartMs(parts, 12_000L))
        assertEquals(34_000L, nextChorusStartMs(parts, 25_000L))
        assertNull(nextChorusStartMs(parts, 70_000L))
        assertEquals(SectionKind.Instrumental, sectionAt(parts, 45_000L)?.kind)
    }

    @Test
    fun noLyricsMeansNoSections() {
        assertTrue(songSections(emptyList(), 60_000L).isEmpty())
        assertTrue(songSections(song, 0L).isEmpty())
    }

    @Test
    fun clockLabelRoundsUp() {
        assertEquals("0:12", clockLabel(11_200L))
        assertEquals("1:05", clockLabel(65_000L))
    }
}
