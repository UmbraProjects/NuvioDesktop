package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The combined resolution × source quality matrix, which replaced the separate resolution and source
 * scores. It is the **baseline** of the model: rows carry hundreds of points and everything else
 * nudges a stream up or down from the row it lands on.
 */
class StreamQualityMatrixTest {

    private fun stream(name: String, sizeGb: Double? = null) = StreamItem(
        name = name,
        addonName = "addon",
        addonId = "addon",
        url = "http://example.invalid/${name.hashCode()}",
        behaviorHints = StreamBehaviorHints(
            filename = name,
            videoSize = sizeGb?.let { (it * StreamSizeBand.BYTES_PER_GB).toLong() },
        ),
    )

    private val default = StreamScoreProfile(enabled = true, points = ScoreAnswers().toPointsMap())

    private fun firedQualityRow(name: String): StreamScoreTrait? =
        StreamScorer.score(stream(name), default, StreamScoreContext.MOVIE)
            .components.map { it.trait }
            .firstOrNull { it.group == StreamScoreTraitGroup.QUALITY }

    // --- both axes required ---

    @Test
    fun aRowFiresOnlyWhenBothResolutionAndSourceAreDeclared() {
        assertEquals(
            StreamScoreTrait.QUALITY_2160P_WEB_DL,
            firedQualityRow("Movie.2024.2160p.WEB-DL.x265-NTb"),
        )
        // Resolution but no source.
        assertEquals(null, firedQualityRow("Movie.2024.2160p.x265-NTb"))
        // Source but no resolution.
        assertEquals(null, firedQualityRow("Movie.2024.WEB-DL.x265-NTb"))
        // Neither.
        assertEquals(null, firedQualityRow("Movie.2024.x265-NTb"))
    }

    @Test
    fun resolutionsAndSourcesOutsideTheMatrixScoreNothingRatherThanBeingPenalised() {
        // SD and HDRip have no row; 0 already ranks last against a baseline in the hundreds, so
        // there is deliberately no explicit penalty.
        assertEquals(null, firedQualityRow("Movie.2024.480p.WEB-DL.x264-GROUP"))
        assertEquals(null, firedQualityRow("Movie.2024.1080p.HDRip.x264-GROUP"))
    }

    @Test
    fun onlyOneQualityRowCanEverFire() {
        val rows = StreamScorer.score(
            stream("Movie.2024.2160p.UHD.BluRay.REMUX.HEVC-FraMeSToR"),
            default,
            StreamScoreContext.MOVIE,
        ).components.filter { it.trait.group == StreamScoreTraitGroup.QUALITY }
        assertEquals(1, rows.size, "expected exactly one matrix row, got ${rows.map { it.trait }}")
        assertEquals(StreamScoreTrait.QUALITY_2160P_REMUX, rows.single().trait)
    }

    @Test
    fun fourteenFortyRidesWithTheTenEightyRow() {
        assertEquals(
            StreamScoreTrait.QUALITY_1080P_WEB_DL,
            firedQualityRow("Movie.2024.1440p.WEB-DL.x265-NTb"),
        )
    }

    // --- the matrix matches the spreadsheet ---

    @Test
    fun theDefaultMatrixMatchesTheBalancedColumn() {
        val p = ScoreAnswers().toPointsMap()
        assertEquals(350, p[StreamScoreTrait.QUALITY_2160P_REMUX.id])
        assertEquals(900, p[StreamScoreTrait.QUALITY_2160P_BLURAY.id])
        assertEquals(800, p[StreamScoreTrait.QUALITY_2160P_WEB_DL.id])
        assertEquals(700, p[StreamScoreTrait.QUALITY_2160P_WEBRIP.id])
        assertEquals(775, p[StreamScoreTrait.QUALITY_1080P_REMUX.id])
        assertEquals(600, p[StreamScoreTrait.QUALITY_1080P_BLURAY.id])
        assertEquals(500, p[StreamScoreTrait.QUALITY_1080P_WEB_DL.id])
        assertEquals(400, p[StreamScoreTrait.QUALITY_1080P_WEBRIP.id])
        assertEquals(300, p[StreamScoreTrait.QUALITY_720P_REMUX.id])
        assertEquals(200, p[StreamScoreTrait.QUALITY_720P_BLURAY.id])
        assertEquals(150, p[StreamScoreTrait.QUALITY_720P_WEB_DL.id])
        assertEquals(100, p[StreamScoreTrait.QUALITY_720P_WEBRIP.id])
    }

    @Test
    fun prioritiseQualityPutsFourKRemuxOnTop() {
        val p = StreamScoreProfile().withSizeQuality(SizeQualityPreference.QUALITY).points
        val top = p.filterKeys { key ->
            StreamScoreTrait.fromId(key)?.group == StreamScoreTraitGroup.QUALITY
        }.maxBy { it.value }
        assertEquals(StreamScoreTrait.QUALITY_2160P_REMUX.id, top.key)
        assertEquals(900, top.value)
    }

    @Test
    fun prioritiseSmallerSizePutsTenEightyWebripOnTopAndRemuxAtTheBottom() {
        val p = StreamScoreProfile().withSizeQuality(SizeQualityPreference.SMALL).points
        assertEquals(900, p[StreamScoreTrait.QUALITY_1080P_WEBRIP.id])
        // A 4K remux is worth nothing when bytes are what matter.
        assertEquals(null, p[StreamScoreTrait.QUALITY_2160P_REMUX.id])
    }

    // --- the four penalty tiers ---

    @Test
    fun theFourPenaltyTiersAreOrderedWorstFirst() {
        val p = ScoreAnswers().toPointsMap()
        val unplayable = listOf(
            p[StreamScoreTrait.IMPLAUSIBLE_SIZE.id]!!,
            p[StreamScoreTrait.FULL_DISC.id]!!,
            p[StreamScoreTrait.THREE_D.id]!!,
        )
        assertTrue(unplayable.all { it == -4000 }, "unplayable tier should all be -4000, got $unplayable")
        assertTrue(p[StreamScoreTrait.SRC_JUNK.id]!! == -3000)
        assertTrue(p[StreamScoreTrait.AI_ENHANCED.id]!! == -2000)
        assertTrue(p[StreamScoreTrait.CODEC_AV1.id]!! == -1000)
        assertTrue(p[StreamScoreTrait.GROUP_LOW_QUALITY.id]!! == -1000)
    }

    @Test
    fun anUnplayableStreamLosesToEvenTheWorstPlayableRow() {
        // The whole point of the tiers: no combination of good traits rescues an unplayable file.
        val best = StreamScorer.score(
            stream("Movie.2024.2160p.UHD.BluRay.REMUX.DV.HDR.TrueHD.Atmos.7.1-FraMeSToR", sizeGb = 1.0),
            default,
            StreamScoreContext.MOVIE,
        ).total
        val worst = StreamScorer.score(
            stream("Movie.2024.720p.WEBRip.x264-SOMEONE", sizeGb = 2.0),
            default,
            StreamScoreContext.MOVIE,
        ).total
        assertTrue(best < worst, "a 1GB '4K remux' must lose to a real 720p WEBRip, got $best vs $worst")
    }

    @Test
    fun theQualityBaselineOutweighsTheSmallSwayTraits() {
        // A 4K BluRay with nothing else beats a 720p WEBRip carrying every small bonus going.
        val baseline = StreamScorer.score(
            stream("Movie.2024.2160p.BluRay.x265-SOMEONE"),
            default,
            StreamScoreContext.MOVIE,
        ).total
        val loaded = StreamScorer.score(
            stream("Movie.2024.720p.WEBRip.REPACK.Hybrid.Extended.TrueHD.Atmos.7.1-NTb"),
            default,
            StreamScoreContext.MOVIE,
        ).total
        assertTrue(baseline > loaded, "quality baseline should dominate, got $baseline vs $loaded")
    }
}
