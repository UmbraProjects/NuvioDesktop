package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end sanity checks on realistic candidate sets: real release names in, ranked order out.
 *
 * The per-trait tests elsewhere prove each rule in isolation. These prove the *combination* picks
 * what a human would pick, which is the only thing that actually matters — a profile can be correct
 * rule by rule and still rank badly once the numbers interact.
 */
class StreamScoringScenarioTest {

    /** A quality-first setup: prioritise quality, advanced audio gear; other questions left default. */
    private val bestQuality = StreamScoreProfile(
        enabled = true,
        points = ScoreAnswers(
            sizeQuality = SizeQualityPreference.QUALITY,
            audioDevice = AudioDeviceSupport.ADVANCED,
        ).toPointsMap(),
        minimumScore = 0,
    )

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

    private fun rank(vararg names: Pair<String, Double?>): List<String> =
        StreamScorer.rank(
            names.map { (name, size) -> stream(name, size) },
            bestQuality,
            StreamScoreContext.MOVIE,
        ).map { it.name.orEmpty() }

    private fun scoreOf(name: String, sizeGb: Double? = null): Int =
        StreamScorer.score(stream(name, sizeGb), bestQuality, StreamScoreContext.MOVIE).total

    @Test
    fun aTrustedRemuxBeatsAWebDlOfTheSameResolution() {
        val ranked = rank(
            "Movie.2024.2160p.WEB-DL.DDP5.1.Atmos.HDR.HEVC-NTb" to 18.0,
            "Movie.2024.2160p.UHD.BluRay.REMUX.DV.HDR.TrueHD.Atmos.7.1-FraMeSToR" to 75.0,
        )
        assertEquals("Movie.2024.2160p.UHD.BluRay.REMUX.DV.HDR.TrueHD.Atmos.7.1-FraMeSToR", ranked.first())
    }

    @Test
    fun a2160pWebDlBeatsA1080pRemux() {
        // Resolution outweighs source in the quality-first preset, which is the intent: a 4K web
        // release looks better on a 4K display than a 1080p disc rip.
        val ranked = rank(
            "Movie.2024.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1-FraMeSToR" to 30.0,
            "Movie.2024.2160p.WEB-DL.DDP5.1.Atmos.HDR.HEVC-NTb" to 18.0,
        )
        assertEquals("Movie.2024.2160p.WEB-DL.DDP5.1.Atmos.HDR.HEVC-NTb", ranked.first())
    }

    @Test
    fun aLowQualityGroupLosesToAnUnknownGroupOfEqualSpec() {
        val ranked = rank(
            "Movie.2024.2160p.WEB-DL.HDR.HEVC-GalaxyRG" to 8.0,
            "Movie.2024.2160p.WEB-DL.HDR.HEVC-SOMEONE" to 8.0,
        )
        assertEquals("Movie.2024.2160p.WEB-DL.HDR.HEVC-SOMEONE", ranked.first())
    }

    @Test
    fun junkRipsAreRemovedEntirelyRatherThanRankedLast() {
        val ranked = rank(
            "Movie.2024.CAM.x264-JUNK" to 2.0,
            "Movie.2024.1080p.WEB-DL.x264-NTb" to 6.0,
        )
        assertEquals(listOf("Movie.2024.1080p.WEB-DL.x264-NTb"), ranked)
    }

    @Test
    fun aFakeSizedRemuxIsRejected() {
        // The exact case the size floor exists for: claims 2160p REMUX, carries 1.5 GB.
        val ranked = rank(
            "Movie.2024.2160p.UHD.BluRay.REMUX.HEVC-FraMeSToR" to 1.5,
            "Movie.2024.1080p.WEB-DL.x264-NTb" to 6.0,
        )
        assertEquals(listOf("Movie.2024.1080p.WEB-DL.x264-NTb"), ranked)
    }

    @Test
    fun anAv1ReleaseRanksBelowAnEquivalentHevcOne() {
        val ranked = rank(
            "Movie.2024.2160p.WEB-DL.HDR.AV1-SOMEONE" to 8.0,
            "Movie.2024.2160p.WEB-DL.HDR.HEVC-SOMEONE" to 8.0,
        )
        assertEquals("Movie.2024.2160p.WEB-DL.HDR.HEVC-SOMEONE", ranked.first())
    }

    @Test
    fun twoTrustedRemuxesAreSeparatedByTheirTier() {
        // Both are 2160p DV remuxes; only the group differs. FraMeSToR is T1, PlayBD is T2.
        val top = scoreOf("Movie.2024.2160p.UHD.BluRay.REMUX.DV.HDR.TrueHD.Atmos.7.1-FraMeSToR", 75.0)
        val lower = scoreOf("Movie.2024.2160p.UHD.BluRay.REMUX.DV.HDR.TrueHD.Atmos.7.1-PlayBD", 75.0)
        assertTrue(top > lower, "expected the higher-tier group to win, got $top vs $lower")
    }

    @Test
    fun dolbyVisionDoesNotOutrankAnOtherwiseBetterRelease() {
        // HDR formats no longer stack, so DV alone cannot carry a weaker release past a stronger
        // one. A DV web-dl should still lose to a trusted remux with equivalent HDR.
        val ranked = rank(
            "Movie.2024.2160p.WEB-DL.DV.HDR.HEVC-SOMEONE" to 15.0,
            "Movie.2024.2160p.UHD.BluRay.REMUX.HDR.TrueHD.Atmos.7.1-FraMeSToR" to 75.0,
        )
        assertEquals("Movie.2024.2160p.UHD.BluRay.REMUX.HDR.TrueHD.Atmos.7.1-FraMeSToR", ranked.first())
    }

    @Test
    fun atmosAndSurroundLiftAnOtherwiseIdenticalRelease() {
        val withAtmos = scoreOf("Movie.2024.2160p.WEB-DL.HDR.HEVC.TrueHD.Atmos.7.1-NTb", 18.0)
        val stereo = scoreOf("Movie.2024.2160p.WEB-DL.HDR.HEVC.AAC.2.0-NTb", 18.0)
        assertTrue(withAtmos > stereo, "expected Atmos+7.1 to win, got $withAtmos vs $stereo")
    }

    @Test
    fun theOverallOrderMatchesWhatAQualityFirstUserWouldPick() {
        val ranked = rank(
            "Movie.2024.1080p.WEBRip.x265-YTS" to 2.5,
            "Movie.2024.2160p.UHD.BluRay.REMUX.DV.HDR.TrueHD.Atmos.7.1-FraMeSToR" to 75.0,
            "Movie.2024.720p.HDTV.x264-SOMEONE" to 1.5,
            "Movie.2024.2160p.WEB-DL.DDP5.1.Atmos.HDR.HEVC-NTb" to 18.0,
            "Movie.2024.1080p.BluRay.x264.DTS-HD.MA.5.1-CMRG" to 12.0,
        )
        assertEquals(
            listOf(
                "Movie.2024.2160p.UHD.BluRay.REMUX.DV.HDR.TrueHD.Atmos.7.1-FraMeSToR",
                "Movie.2024.2160p.WEB-DL.DDP5.1.Atmos.HDR.HEVC-NTb",
                "Movie.2024.1080p.BluRay.x264.DTS-HD.MA.5.1-CMRG",
            ),
            ranked,
            "expected 4K remux > 4K web > 1080p bluray, with the YTS rip and the 720p HDTV dropped",
        )
    }
}
