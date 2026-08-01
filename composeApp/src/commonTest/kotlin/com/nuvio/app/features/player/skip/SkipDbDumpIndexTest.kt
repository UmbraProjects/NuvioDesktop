package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkipDbDumpIndexTest {

    private fun segment(
        imdbId: String = "tt0413573",
        season: Int? = 2,
        episode: Int? = 3,
        type: String = "intro",
        startMs: Long = 405_500,
        endMs: Long = 428_500,
        durationMs: Long? = 2_588_000,
        status: String? = "approved",
        score: Int = 0,
    ) = SkipDbDumpSegment(
        imdbId = imdbId,
        season = season,
        episode = episode,
        segmentType = type,
        status = status,
        startMs = startMs,
        endMs = endMs,
        durationMs = durationMs,
        score = score,
    )

    private fun indexOf(vararg segments: SkipDbDumpSegment) =
        SkipDbDumpIndex.from(SkipDbDump(segments = segments.toList()))

    @Test
    fun `finds an episode and maps every kind it holds`() {
        val index = indexOf(
            segment(type = "intro"),
            segment(type = "outro", startMs = 2_551_000, endMs = 2_588_000),
        )

        val segments = assertNotNull(index.lookup("tt0413573", season = 2, episode = 3, durationSeconds = 2588))

        assertEquals(405_500, segments.intro?.startMs)
        assertEquals(2_551_000, segments.outro?.startMs)
        assertNull(segments.recap)
        assertNull(segments.preview)
    }

    @Test
    fun `an episode the export does not cover is a miss, not an empty answer`() {
        val index = indexOf(segment())

        assertNull(index.lookup("tt0413573", season = 9, episode = 9, durationSeconds = 2588))
        assertNull(index.lookup("tt0944947", season = 1, episode = 1, durationSeconds = 2588))
    }

    @Test
    fun `runtime within two seconds is an exact match`() {
        val index = indexOf(segment(durationMs = 2_588_000))

        for (durationSeconds in listOf(2586L, 2588L, 2590L)) {
            val match = index.lookup("tt0413573", 2, 3, durationSeconds)?.intro?.match
            assertEquals(SkipDbMatch.EXACT, match, "runtime ${durationSeconds}s")
        }
    }

    @Test
    fun `runtime up to fifteen seconds out is a shifted match`() {
        val index = indexOf(segment(durationMs = 2_588_000))

        for (durationSeconds in listOf(2585L, 2591L, 2603L)) {
            val match = index.lookup("tt0413573", 2, 3, durationSeconds)?.intro?.match
            assertEquals(SkipDbMatch.SHIFTED, match, "runtime ${durationSeconds}s")
        }
    }

    @Test
    fun `runtime past fifteen seconds out is out of range and never becomes an interval`() {
        val index = indexOf(segment(durationMs = 2_588_000))

        val segments = assertNotNull(index.lookup("tt0413573", 2, 3, durationSeconds = 2604))

        assertEquals(SkipDbMatch.OUT_OF_RANGE, segments.intro?.match)
        assertTrue(segments.toSkipIntervals().isEmpty())
    }

    @Test
    fun `an unknown runtime leaves the answer agnostic rather than guessing a match`() {
        val index = indexOf(segment(durationMs = 2_588_000))

        val segments = assertNotNull(index.lookup("tt0413573", 2, 3, durationSeconds = null))

        assertEquals(SkipDbMatch.AGNOSTIC, segments.intro?.match)
        assertEquals(1, segments.toSkipIntervals().size)
    }

    @Test
    fun `timings are reported unshifted, with the offset alongside them`() {
        val index = indexOf(segment(durationMs = 2_588_000))

        val intro = assertNotNull(index.lookup("tt0413573", 2, 3, durationSeconds = 2598)?.intro)

        assertEquals(405_500, intro.startMs)
        assertEquals(10_000, intro.offsetMs)
        assertEquals(false, intro.adjusted)
    }

    @Test
    fun `the submission closest to the played runtime wins over a better voted one`() {
        val index = indexOf(
            segment(startMs = 100_000, endMs = 130_000, durationMs = 2_588_000, score = 50),
            segment(startMs = 200_000, endMs = 230_000, durationMs = 2_400_000, score = 0),
        )

        val intro = assertNotNull(index.lookup("tt0413573", 2, 3, durationSeconds = 2400)?.intro)

        assertEquals(200_000, intro.startMs)
        assertEquals(SkipDbMatch.EXACT, intro.match)
    }

    @Test
    fun `score breaks the tie when two submissions match the runtime equally well`() {
        val index = indexOf(
            segment(startMs = 100_000, endMs = 130_000, score = 1),
            segment(startMs = 200_000, endMs = 230_000, score = 7),
        )

        val intro = assertNotNull(index.lookup("tt0413573", 2, 3, durationSeconds = 2588)?.intro)

        assertEquals(200_000, intro.startMs)
    }

    @Test
    fun `a submission with no runtime loses to one that can actually be matched`() {
        val index = indexOf(
            segment(startMs = 100_000, endMs = 130_000, durationMs = null, score = 99),
            segment(startMs = 200_000, endMs = 230_000, durationMs = 2_588_000, score = 0),
        )

        val intro = assertNotNull(index.lookup("tt0413573", 2, 3, durationSeconds = 2588)?.intro)

        assertEquals(200_000, intro.startMs)
    }

    @Test
    fun `movies are keyed without a season or episode`() {
        val index = indexOf(
            segment(imdbId = "tt1074638", season = null, episode = null, durationMs = 8_590_300),
        )

        val intro = assertNotNull(index.lookup("tt1074638", null, null, durationSeconds = 8590)?.intro)

        assertEquals(405_500, intro.startMs)
    }

    @Test
    fun `only approved submissions are published`() {
        val index = indexOf(
            segment(status = "pending"),
            segment(status = "rejected"),
        )

        assertTrue(index.isEmpty)
        assertNull(index.lookup("tt0413573", 2, 3, durationSeconds = 2588))
    }

    @Test
    fun `a confirmed-absent segment is not a candidate and cannot mask a real one`() {
        val index = indexOf(
            segment(type = "intro", startMs = 0, endMs = 0, durationMs = 2_588_000),
            segment(type = "outro", startMs = 2_551_000, endMs = 2_588_000),
        )

        val segments = assertNotNull(index.lookup("tt0413573", 2, 3, durationSeconds = 2588))

        assertNull(segments.intro, "a 0/0 sentinel must read as no segment, matching the API")
        assertEquals(2_551_000, segments.outro?.startMs)
    }

    @Test
    fun `a real submission still wins when a sentinel exists for the same kind`() {
        val index = indexOf(
            segment(startMs = 0, endMs = 0, durationMs = 2_588_000, score = 99),
            segment(startMs = 405_500, endMs = 428_500, durationMs = 2_400_000),
        )

        val intro = assertNotNull(index.lookup("tt0413573", 2, 3, durationSeconds = 2588)?.intro)

        assertEquals(405_500, intro.startMs)
    }

    @Test
    fun `records missing the fields a lookup needs are dropped`() {
        val index = indexOf(
            segment(imdbId = ""),
            segment(type = ""),
            SkipDbDumpSegment(imdbId = "tt1", season = 1, episode = 1, segmentType = "intro", startMs = null, endMs = 1),
            SkipDbDumpSegment(imdbId = "tt2", season = 1, episode = 1, segmentType = "intro", startMs = 1, endMs = null),
        )

        assertTrue(index.isEmpty)
    }

    @Test
    fun `an export with nothing usable in it counts as empty so no lookup trusts it`() {
        assertTrue(SkipDbDumpIndex.from(SkipDbDump()).isEmpty)
    }

    @Test
    fun `carries the export metadata through for diagnostics`() {
        val index = SkipDbDumpIndex.from(
            SkipDbDump(segments = listOf(segment()), count = 1, generatedAt = "2026-07-27T08:49:01.801Z"),
        )

        assertEquals("2026-07-27T08:49:01.801Z", index.generatedAt)
        assertEquals(1, index.segmentCount)
    }
}
