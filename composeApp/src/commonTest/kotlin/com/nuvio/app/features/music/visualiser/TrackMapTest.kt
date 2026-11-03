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

    /** Sixteenth steps of each bar where the funk bass line starts a note: on the kicks (0, 8) and off them (3, 6, 11, 14). */
    private val bassSteps = setOf(0, 3, 6, 8, 11, 14)
    private val bassNotes = doubleArrayOf(55.0, 73.4, 82.4, 65.4, 49.0, 61.7)

    /**
     * A loop starting at [startS]: with [drums], kick on beats 1 and 3 (a falling pitch and a click), snare on 2 and 4, hats
     * on every eighth; with [bass], a syncopated bass line of rich notes that also start between the kicks.
     */
    private fun loop(seconds: Double, bpm: Double, startS: Double = 0.5, drums: Boolean = true, bass: Boolean = false): FloatArray {
        val n = (seconds * rate).toInt()
        val out = FloatArray(n * 2)
        val beat = 60.0 / bpm
        val step = beat / 4
        val noise = Random(7)
        var hat1 = 0.0
        var hat2 = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / rate - startS
            var v = 0.0
            if (t >= 0) {
                val b = (t / beat).toInt()
                val inBeat = t - b * beat
                if (drums) {
                    if (b % 2 == 0) {
                        val phase = 2 * PI * (50.0 * inBeat + 100.0 / 40.0 * (1 - exp(-40 * inBeat)))
                        v += 0.8 * sin(phase) * exp(-inBeat * 12) + 0.3 * (noise.nextDouble() * 2 - 1) * exp(-inBeat * 400)
                    } else {
                        v += 0.35 * (noise.nextDouble() * 2 - 1) * exp(-inBeat * 30) + 0.2 * sin(2 * PI * 200 * inBeat) * exp(-inBeat * 25)
                    }
                    // Hats: noise with its lows taken away (a second difference), as a closed hi-hat sounds.
                    val e = beat / 2
                    val inEighth = t - (t / e).toInt() * e
                    val white = noise.nextDouble() * 2 - 1
                    v += 0.06 * (white - 2 * hat1 + hat2) * exp(-inEighth * 120)
                    hat2 = hat1; hat1 = white
                }
                if (bass) {
                    val s = (t / step).toInt()
                    // The note that is sounding: the last bass step at or before this one.
                    var start = s
                    while (start % 16 !in bassSteps) start--
                    val sinceNote = t - start * step
                    val f = bassNotes[(start / 16 * 7 + start % 16) % bassNotes.size]
                    val attack = minOf(1.0, sinceNote / 0.004)
                    var tone = 0.0
                    for (h in 1..4) tone += sin(2 * PI * f * h * sinceNote) / h
                    v += 0.3 * attack * tone * exp(-sinceNote * 2)
                }
            }
            out[2 * i] = v.toFloat()
            out[2 * i + 1] = (v * 0.8).toFloat()
        }
        return out
    }

    private fun drums(seconds: Double, bpm: Double, startS: Double = 0.5) = loop(seconds, bpm, startS)

    /** Kick hits further than 90 ms from any real kick (1 s apart from [firstMs] at 120 BPM, beats 1 and 3). */
    private fun falseKicks(map: TrackMap, firstMs: Long, everyMs: Long): Int {
        var count = 0
        for (f in 0 until map.frames) {
            if (!map.hits(f, Onset.KICK)) continue
            val ms = f.toLong() * MAP_FRAME_MS + MAP_FRAME_MS / 2
            val off = ((ms - firstMs) % everyMs + everyMs) % everyMs
            if (minOf(off, everyMs - off) > 90) count++
        }
        return count
    }

    @Test
    fun aBassLineIsNotTakenForKicks() {
        val map = build(loop(20.0, 120.0, drums = false, bass = true))
        var hits = 0
        for (f in 0 until map.frames) if (map.hits(f, Onset.KICK)) hits++
        // About 60 bass notes start in 20 s; a few may still pass, most must not.
        assertTrue(hits <= 8, "$hits kicks found in a bass line with no drums")
    }

    @Test
    fun kicksStayOnTheBeatOverAFunkBassLine() {
        val map = build(loop(24.0, 120.0, startS = 0.5, bass = true))
        var found = 0
        var total = 0
        var kickMs = 500L
        while (kickMs < 23_000L) {
            total++
            if (map.hitsBetween(Onset.KICK, kickMs - 60.0, kickMs + 90.0)) found++
            kickMs += 1_000L
        }
        assertTrue(found >= total * 0.85, "$found of $total kicks")
        val wrong = falseKicks(map, 500L, 1_000L)
        assertTrue(wrong <= total / 5, "$wrong kicks off the beat")
    }

    @Test
    fun kicksLandOnBeatsOneAndThreeOfTheGrid() {
        val map = build(loop(24.0, 120.0, startS = 0.5, bass = true))
        val beat = assertNotNull(map.beat)
        var onGrid = 0
        var kicks = 0
        for (f in 0 until map.frames) {
            if (!map.hits(f, Onset.KICK)) continue
            kicks++
            val step = beat.stepAt(f * MAP_FRAME_MS + MAP_FRAME_MS / 2.0).mod(16L).toInt()
            if (step in setOf(15, 0, 1, 7, 8, 9)) onGrid++
        }
        assertTrue(kicks > 0 && onGrid >= kicks * 0.8, "$onGrid of $kicks kicks on beats 1 and 3")
    }

    @Test
    fun snaresLandOnBeatsTwoAndFour() {
        val map = build(loop(24.0, 120.0, startS = 0.5, bass = true))
        val beat = assertNotNull(map.beat)
        var onGrid = 0
        var snares = 0
        for (f in 0 until map.frames) {
            if (!map.hits(f, Onset.SNARE)) continue
            snares++
            val step = beat.stepAt(f * MAP_FRAME_MS + MAP_FRAME_MS / 2.0).mod(16L).toInt()
            if (step in setOf(3, 4, 5, 11, 12, 13)) onGrid++
        }
        assertTrue(snares > 0 && onGrid >= snares * 0.7, "$onGrid of $snares snares on beats 2 and 4")
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
        assertTrue(abs(first.analysedMs - 10_000L) < 500L, "first ${first.analysedMs}")
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
    fun theVuRestsBelowZeroOnTheTracksOwnLevel() {
        val scale = mapScale(build(drums(12.0, 120.0)))
        assertEquals(-3.0, vuFor(scale.levelMedian, scale), 1e-9)
        // Six decibels above the middle reads +3 VU, however loud the master is.
        assertEquals(3.0, vuFor(scale.levelMedian + 6f / 48f, scale), 1e-4)
        assertTrue(scale.band(0, 1f) == 1f && scale.band(0, 0f) == 0f)
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

    private val hook = "Hold on to the line"

    private val song = lines(
        12_000L to "Out on the highway tonight",
        16_000L to "Nothing but the radio",
        20_000L to "Headlights in the rain",
        24_000L to hook,
        28_000L to hook,
        32_000L to hook,
        36_000L to "Every mile I meant to say",
        40_000L to "Every word I never said",
        44_000L to hook,
        48_000L to hook,
        75_000L to "Back on the highway",
        79_000L to "Morning on the glass",
        83_000L to hook,
        87_000L to hook,
    )

    @Test
    fun sectionsComeFromTheLyrics() {
        val parts = songSections(song, 110_000L)
        assertEquals(
            listOf(
                SectionKind.Intro, SectionKind.Verse, SectionKind.Chorus, SectionKind.Verse, SectionKind.Chorus,
                SectionKind.Instrumental, SectionKind.Verse, SectionKind.Chorus, SectionKind.Outro,
            ),
            parts.map { it.kind },
        )
        assertEquals(0L, parts.first().startMs)
        assertEquals(110_000L, parts.last().endMs)
        parts.zipWithNext().forEach { (a, b) -> assertEquals(a.endMs, b.startMs) }
    }

    @Test
    fun theNextChorusIsFound() {
        val parts = songSections(song, 110_000L)
        assertEquals(24_000L, nextChorusStartMs(parts, 14_000L))
        assertEquals(44_000L, nextChorusStartMs(parts, 38_000L))
        assertNull(nextChorusStartMs(parts, 95_000L))
        assertEquals(SectionKind.Instrumental, sectionAt(parts, 60_000L)?.kind)
    }

    @Test
    fun aHookAlternatingWithOtherLinesDoesNotFlicker() {
        // "Use the force" between other lines every four seconds: one part, not a stripe per line.
        val alternating = (0 until 12).map { i -> LyricLine(10_000L + i * 4_000L, if (i % 2 == 0) "Use the force" else "I know I'm gonna line $i") }
        val parts = songSections(alternating, 70_000L)
        val inner = parts.filter { it.kind == SectionKind.Verse || it.kind == SectionKind.Chorus }
        assertTrue(inner.all { it.endMs - it.startMs >= 8_000L }, "parts: $parts")
        assertTrue(inner.size <= 2, "parts: $parts")
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
