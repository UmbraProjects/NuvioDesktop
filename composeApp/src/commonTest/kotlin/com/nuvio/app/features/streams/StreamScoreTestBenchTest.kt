package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The settings test bench builds a [StreamItem] out of nothing but a pasted release name and an
 * optional size. These lock in that such a bare probe is classified the same way a real stream would
 * be — otherwise the bench would reassure a user about behaviour that does not match production.
 */
class StreamScoreTestBenchTest {

    private fun probe(name: String, sizeGb: Double? = null) = StreamItem(
        name = name,
        addonName = "Test",
        addonId = "test",
        url = "http://example.invalid/test",
        behaviorHints = StreamBehaviorHints(
            videoSize = sizeGb?.let { (it * StreamSizeBand.BYTES_PER_GB).toLong() },
            filename = name,
        ),
    )

    private val profile = StreamScoreProfile(
        enabled = true,
        points = mapOf(
            StreamScoreTrait.QUALITY_2160P_REMUX.id to 90,
            StreamScoreTrait.HDR_DOLBY_VISION.id to 40,
            StreamScoreTrait.AUDIO_ATMOS.id to 40,
            StreamScoreTrait.AUDIO_TRUEHD.id to 25,
            StreamScoreTrait.GROUP_TRUSTED.id to 35,
            StreamScoreTrait.IMPLAUSIBLE_SIZE.id to -1000,
        ),
        minimumScore = 0,
    )

    @Test
    fun aPastedReleaseNameIsFullyClassified() {
        val traits = StreamTraitDetector.detect(
            probe("Example.Film.2024.2160p.UHD.BluRay.REMUX.DV.HDR10.TrueHD.Atmos.7.1-FraMeSToR"),
        )
        val score = StreamScorer.score(traits, profile, StreamScoreContext.MOVIE)
        // 4K Remux (the combined quality row) + DV + Atmos + TrueHD + trusted group.
        assertEquals(230, score.total)
        assertFalse(score.rejected)
    }

    @Test
    fun sizeSuppliedByTheBenchReachesTheSizeRules() {
        val traits = StreamTraitDetector.detect(
            probe("Example.Film.2024.2160p.BluRay.REMUX-FraMeSToR", sizeGb = 1.0),
        )
        assertEquals(1_000_000_000L, traits.size)
        val score = StreamScorer.score(traits, profile, StreamScoreContext.MOVIE)
        assertTrue(score.rejected, "a 1GB 2160p remux should trip the implausible-size floor")
    }

    @Test
    fun withoutASizeTheSizeRulesStaySilent() {
        val traits = StreamTraitDetector.detect(probe("Example.Film.2024.2160p.BluRay.REMUX-FraMeSToR"))
        assertEquals(null, traits.size)
        val score = StreamScorer.score(traits, profile, StreamScoreContext.MOVIE)
        assertFalse(score.rejected)
        assertTrue(score.components.none { it.trait == StreamScoreTrait.IMPLAUSIBLE_SIZE })
    }

    @Test
    fun theMovieEpisodeSwitchChangesTheSizeVerdict() {
        // 1080p WEB-DL has a 2.5 Mbps floor. With no duration in the name, the assumed 20-minute
        // episode needs ~375 MB and the assumed 60-minute film needs ~1.13 GB — so 600 MB is a
        // perfectly ordinary episode and an implausible feature.
        val traits = StreamTraitDetector.detect(probe("Show.S01E01.1080p.WEB-DL-NTb", sizeGb = 0.6))
        val sizeOnly = StreamScoreProfile(
            enabled = true,
            points = mapOf(StreamScoreTrait.IMPLAUSIBLE_SIZE.id to -1000),
        )
        assertFalse(StreamSizeSanity.isImplausiblySmall(traits, StreamScoreContext.EPISODE))
        assertTrue(StreamSizeSanity.isImplausiblySmall(traits, StreamScoreContext.MOVIE))
        assertEquals(0, StreamScorer.score(traits, sizeOnly, StreamScoreContext.EPISODE).total)
        assertEquals(-1000, StreamScorer.score(traits, sizeOnly, StreamScoreContext.MOVIE).total)
    }

    /**
     * Regression: an episode judged with a movie context lands outside the movie size band and is
     * marked "far from preferred size" at every realistic episode size. That is what made the
     * settings preview show -50 on every row after opening a TV episode — it scored the sample with
     * a hardcoded `isEpisode = false`. Anything scoring real streams must pass the true content type.
     */
    @Test
    fun anEpisodeScoredWithAMovieContextIsWronglyMarkedFarFromPreferredSize() {
        val banded = StreamScoreProfile(
            enabled = true,
            points = mapOf(
                StreamScoreTrait.SIZE_IN_BAND.id to 20,
                StreamScoreTrait.SIZE_FAR_OUT.id to -50,
            ),
            sizeBand = StreamSizeBand(enabled = true),
        )
        fun scoreAt(gb: Double, context: StreamScoreContext): Int =
            StreamScorer.score(
                StreamTraitDetector.detect(probe("Show.S01E05.1080p.WEB-DL.x264-NTb", sizeGb = gb)),
                banded,
                context,
            ).total

        // Ordinary episode sizes, all comfortably inside the default 1-8 GB episode band.
        listOf(1.5, 2.0, 3.5, 6.0).forEach { gb ->
            assertEquals(20, scoreAt(gb, StreamScoreContext.EPISODE), "${gb}GB should be in band as an episode")
        }
        // Measured against the movie band (8-40 GB) the same files are penalised — anything under the
        // 4 GB grace floor outright, which is most episodes.
        listOf(1.5, 2.0, 3.5).forEach { gb ->
            assertEquals(-50, scoreAt(gb, StreamScoreContext.MOVIE), "${gb}GB is far out under the movie band")
        }
        // 6 GB lands in the movie band's grace margin, so it scores neutral rather than -50 — still
        // wrong (it is a perfectly good episode) but a reminder the penalty is not uniform.
        assertEquals(0, scoreAt(6.0, StreamScoreContext.MOVIE))
    }

    /**
     * The card badge and the right-click breakdown must be the same number. They diverged once: the
     * badge scored with the real episode context while the desktop context menu used the implicit
     * MOVIE default, so an episode's badge read +459 while its breakdown totalled +389 and claimed
     * "far from preferred size". Both now take the context from one shared value.
     */
    @Test
    fun theBadgeAndTheBreakdownAgreeForAnEpisode() {
        val banded = StreamScoreProfile(
            enabled = true,
            points = mapOf(
                StreamScoreTrait.QUALITY_1080P_WEB_DL.id to 400,
                StreamScoreTrait.GROUP_TRUSTED.id to 35,
                StreamScoreTrait.SIZE_IN_BAND.id to 20,
                StreamScoreTrait.SIZE_FAR_OUT.id to -50,
            ),
            sizeBand = StreamSizeBand(enabled = true, episodeMinGb = 1.0, episodeMaxGb = 30.0),
        )
        val traits = StreamTraitDetector.detect(
            probe("The.Rookie.S06E06.1080p.AMZN.WEB-DL.DDP5.1.H.264-NTb", sizeGb = 3.38),
        )
        val context = StreamScoreContext.EPISODE
        val badge = StreamScorer.score(traits, banded, context)
        val breakdown = StreamScorer.score(traits, banded, context)

        assertEquals(badge.total, breakdown.total)
        // 3.38 GB against a 1-30 GB episode band is in band, not far out.
        assertTrue(
            badge.components.any { it.trait == StreamScoreTrait.SIZE_IN_BAND },
            "expected in-band, got ${badge.components.map { it.trait }}",
        )
        assertTrue(badge.components.none { it.trait == StreamScoreTrait.SIZE_FAR_OUT })
    }

    @Test
    fun aJunkReleaseNameIsRejectedByTheBench() {
        val withJunk = profile.copy(points = profile.points + (StreamScoreTrait.SRC_JUNK.id to -1000))
        val traits = StreamTraitDetector.detect(probe("Example.Film.2024.CAM.x264-JUNK"))
        assertTrue(StreamScorer.score(traits, withJunk, StreamScoreContext.MOVIE).rejected)
    }
}
