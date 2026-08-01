package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Detection + scoring for the release-flag and edition traits, driven through the full path: a real
 * release name in, [StreamTraitDetector] classifies it, [StreamScorer] scores it. These prove the
 * name-based detectors fire where they should and stay quiet where they should not.
 */
class StreamReleaseFlagScoringTest {

    private fun stream(name: String) = StreamItem(
        name = name,
        addonName = "addon",
        addonId = "addon",
        url = "http://example.invalid/${name.hashCode()}",
        behaviorHints = StreamBehaviorHints(filename = name),
    )

    /** True when [trait] fired (contributed points) for [name]. */
    private fun fires(
        trait: StreamScoreTrait,
        name: String,
        isEpisode: Boolean = false,
    ): Boolean {
        val profile = StreamScoreProfile(enabled = true, points = mapOf(trait.id to 10))
        val context = if (isEpisode) StreamScoreContext.EPISODE else StreamScoreContext.MOVIE
        return StreamScorer.score(stream(name), profile, context).components.any { it.trait == trait }
    }

    // --- AI upscale / remaster ---

    @Test
    fun aiUpscaleIsDetected() {
        assertTrue(fires(StreamScoreTrait.AI_ENHANCED, "Movie.2024.2160p.AI.Upscaled.HDR.HEVC-GROUP"))
        assertTrue(fires(StreamScoreTrait.AI_ENHANCED, "Movie.2024.2160p.WEB-DL.AI.HDR.HEVC-GROUP"))
        assertTrue(fires(StreamScoreTrait.AI_ENHANCED, "Movie.2024.1080p.Upscaled.x265-GROUP"))
        assertTrue(fires(StreamScoreTrait.AI_ENHANCED, "Movie.2024.2160p.Regraded.HDR-GROUP"))
    }

    @Test
    fun bareAiSubstringDoesNotFalselyFireAiUpscale() {
        // "ai" appears inside a normal word; only an AI *marker* next to an upscale/HDR keyword counts.
        assertFalse(fires(StreamScoreTrait.AI_ENHANCED, "Movie.2024.1080p.BluRay.x264-CMRG"))
        assertFalse(fires(StreamScoreTrait.AI_ENHANCED, "Certain.Rain.2024.1080p.WEB-DL.x264-NTb"))
    }

    // --- Full disc ---

    @Test
    fun fullDiscIsDetected() {
        assertTrue(fires(StreamScoreTrait.FULL_DISC, "Movie.2024.2160p.UHD.BluRay.BD66-GROUP"))
        assertTrue(fires(StreamScoreTrait.FULL_DISC, "Movie.2024.Complete.BluRay-GROUP"))
        assertTrue(fires(StreamScoreTrait.FULL_DISC, "Movie.2024.BluRay.iso"))
    }

    @Test
    fun aRemuxIsNotAFullDisc() {
        assertFalse(fires(StreamScoreTrait.FULL_DISC, "Movie.2024.2160p.UHD.BluRay.REMUX.HEVC-FraMeSToR"))
    }

    // --- Season pack ---

    @Test
    fun seasonPacksAreDetectedButSingleEpisodesAreNot() {
        assertTrue(fires(StreamScoreTrait.SEASON_PACK, "Show.S01.1080p.WEB-DL.x264-NTb", isEpisode = true))
        assertTrue(fires(StreamScoreTrait.SEASON_PACK, "Show.S01-S03.1080p.BluRay-GROUP", isEpisode = true))
        assertTrue(fires(StreamScoreTrait.SEASON_PACK, "Show.Season.2.1080p.WEB-DL-GROUP", isEpisode = true))
        assertTrue(fires(StreamScoreTrait.SEASON_PACK, "Show.S01E01-E12.1080p.WEB-DL-GROUP", isEpisode = true))

        assertFalse(fires(StreamScoreTrait.SEASON_PACK, "Show.S01E05.1080p.WEB-DL.x264-NTb", isEpisode = true))
    }

    @Test
    fun explicitSeasonPackLabelOverridesResolvedEpisodeFilename() {
        val filename = "Station.Eleven.S01E01.2160p.STAN.WEB-DL.DDP5.1.HDR.HEVC-DB.mkv"
        val item = stream(filename).copy(
            description = """
                Q3 | Season Pack | Stan | 4.99 GB
                HDR,Q3,Web T2,4K,DD+,Season Pack,Stan
            """.trimIndent(),
        )
        val profile = StreamScoreProfile(
            enabled = true,
            points = mapOf(StreamScoreTrait.SEASON_PACK.id to 10),
        )

        val score = StreamScorer.score(item, profile, StreamScoreContext.EPISODE)

        assertEquals(10, score.total)
        assertTrue(score.components.any { it.trait == StreamScoreTrait.SEASON_PACK })
    }

    // --- Repack / proper ---

    @Test
    fun repackAndProperAreDetected() {
        assertTrue(fires(StreamScoreTrait.REPACK_PROPER, "Movie.2024.1080p.BluRay.REPACK.x264-GROUP"))
        assertTrue(fires(StreamScoreTrait.REPACK_PROPER, "Movie.2024.1080p.WEB-DL.PROPER.x264-GROUP"))
        assertTrue(fires(StreamScoreTrait.REPACK_PROPER, "Movie.2024.1080p.WEB-DL.Repack2.x264-GROUP"))
        assertFalse(fires(StreamScoreTrait.REPACK_PROPER, "Movie.2024.1080p.WEB-DL.x264-GROUP"))
    }

    // --- Hybrid ---

    @Test
    fun hybridIsDetected() {
        assertTrue(fires(StreamScoreTrait.HYBRID, "Movie.2024.2160p.Hybrid.BluRay.REMUX-FraMeSToR"))
        assertFalse(fires(StreamScoreTrait.HYBRID, "Movie.2024.2160p.BluRay.REMUX-FraMeSToR"))
    }

    // --- 3D ---

    @Test
    fun threeDIsDetected() {
        assertTrue(fires(StreamScoreTrait.THREE_D, "Movie.2024.1080p.BluRay.3D.Half-SBS.x264-GROUP"))
        assertTrue(fires(StreamScoreTrait.THREE_D, "Movie.2024.1080p.3D.H-OU.x264-GROUP"))
        assertFalse(fires(StreamScoreTrait.THREE_D, "Movie.2024.1080p.BluRay.x264-GROUP"))
    }

    // --- Unlabelled release ---

    @Test
    fun releaseWithNeitherResolutionNorSourceIsFlagged() {
        assertTrue(fires(StreamScoreTrait.MISSING_METADATA, "Some.Movie.2024.x264-GROUP"))
    }

    @Test
    fun releaseMissingOnlyOneTagIsNotFlagged() {
        // Has a resolution but no source — extremely common and usually fine.
        assertFalse(fires(StreamScoreTrait.MISSING_METADATA, "Movie.2024.1080p.x264-GROUP"))
        // Has a source but no resolution.
        assertFalse(fires(StreamScoreTrait.MISSING_METADATA, "Movie.2024.WEB-DL.x264-GROUP"))
    }

    // --- Editions & extras ---

    @Test
    fun alternateCutsAreDetected() {
        assertTrue(fires(StreamScoreTrait.SPECIAL_EDITION, "Movie.2024.Extended.1080p.BluRay.x264-GROUP"))
        assertTrue(fires(StreamScoreTrait.SPECIAL_EDITION, "Movie.2024.Directors.Cut.1080p.BluRay.x264-GROUP"))
        assertTrue(fires(StreamScoreTrait.SPECIAL_EDITION, "Movie.2024.Theatrical.1080p.BluRay.x264-GROUP"))
        assertFalse(fires(StreamScoreTrait.SPECIAL_EDITION, "Movie.2024.1080p.BluRay.x264-GROUP"))
    }

    @Test
    fun bonusExtrasAreDetected() {
        assertTrue(fires(StreamScoreTrait.EXTRAS_BONUS, "Movie.2024.Extras.1080p.BluRay.x264-GROUP"))
        assertTrue(fires(StreamScoreTrait.EXTRAS_BONUS, "Movie.2024.Deleted.Scenes.1080p.WEB-DL-GROUP"))
        assertFalse(fires(StreamScoreTrait.EXTRAS_BONUS, "Movie.2024.1080p.BluRay.x264-GROUP"))
    }

    // --- interaction ---

    @Test
    fun anAiUpscaleIsPenalisedUnderTheDefaultProfile() {
        val default = StreamScoreProfile(enabled = true, points = ScoreAnswers().toPointsMap())
        val native = StreamScorer.score(
            stream("Movie.2024.1080p.WEB-DL.x264-NTb"),
            default,
            StreamScoreContext.MOVIE,
        ).total
        val upscale = StreamScorer.score(
            stream("Movie.2024.2160p.AI.Upscaled.HDR.HEVC-GROUP"),
            default,
            StreamScoreContext.MOVIE,
        ).total
        assertTrue(upscale < native, "expected the AI upscale to score below a native 1080p, got $upscale vs $native")
    }

    @Test
    fun baseTraitsAreInTheDefaultProfileButZeroDefaultsAreNot() {
        val points = ScoreAnswers().toPointsMap()
        // The four penalty tiers: -4000 unplayable, -3000 junk, -2000 AI upscale, -1000 the rest.
        assertEquals(-4000, points[StreamScoreTrait.FULL_DISC.id])
        assertEquals(-4000, points[StreamScoreTrait.IMPLAUSIBLE_SIZE.id])
        assertEquals(-3000, points[StreamScoreTrait.SRC_JUNK.id])
        assertEquals(-2000, points[StreamScoreTrait.AI_ENHANCED.id])
        assertEquals(-1000, points[StreamScoreTrait.CODEC_AV1.id])
        assertEquals(10, points[StreamScoreTrait.HYBRID.id])
        // Detect-only traits now carry a nominal 1 rather than being absent.
        assertEquals(1, points[StreamScoreTrait.SEASON_PACK.id])
        assertEquals(1, points[StreamScoreTrait.SPECIAL_EDITION.id])
    }
}
