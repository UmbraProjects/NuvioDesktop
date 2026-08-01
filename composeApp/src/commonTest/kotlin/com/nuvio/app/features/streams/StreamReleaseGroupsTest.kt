package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Release-group matching runs over the whole release name, not just the trailing `-GROUP` token,
 * because groups routinely appear mid-string. The TRaSH lists contain ordinary English words
 * (`don`, `apex`, `bloom`, `dracula`), so most of these pin down that a title word is not mistaken
 * for a group.
 */
class StreamReleaseGroupsTest {

    private fun detect(name: String) = StreamTraitDetector.detect(
        StreamItem(name = name, addonName = "a", addonId = "a", url = "http://x/test"),
    )

    @Test
    fun matchesAGroupInTheUsualTrailingPosition() {
        assertTrue(detect("Film.2024.2160p.BluRay.REMUX-FraMeSToR").isTrustedGroup)
        assertTrue(detect("Film.2024.1080p.WEBRip.x265-YTS").isLowQualityGroup)
        assertTrue(detect("Film.2024.1080p.BluRay.x264-YIFY").isLowQualityGroup)
    }

    @Test
    fun matchesAGroupInTheMiddleOfTheName() {
        assertTrue(detect("Film-FraMeSToR.2024.2160p.BluRay.REMUX.mkv").isTrustedGroup)
        assertTrue(detect("[SubsPlease] Show - 01 (1080p) [ABCD1234].mkv").isTrustedGroup)
        assertTrue(detect("Film.2024.1080p-RARBG.extra.tags.mkv").isLowQualityGroup)
    }

    @Test
    fun doesNotMatchAGroupNameHiddenInsideAnOrdinaryWord() {
        assertFalse(detect("Fluxx.2024.1080p.WEB-DL").isTrustedGroup)
        assertFalse(detect("Psalms.2024.1080p.WEB-DL").isLowQualityGroup)
    }

    @Test
    fun doesNotMatchAnOrdinaryTitleWordThatHappensToBeAGroupName() {
        // The TRaSH lists include real words. Matching only after a group marker is what stops a
        // film called Dracula or Apex from being scored as a top-tier release.
        assertFalse(detect("Dracula.2020.1080p.WEB-DL.x264").isTrustedGroup)
        assertFalse(detect("Apex.2021.2160p.WEB-DL").isTrustedGroup)
        assertFalse(detect("The.Bloom.2019.1080p.BluRay").isTrustedGroup)
        // ...but the same name in an actual group position is matched.
        assertTrue(detect("Dracula.2020.1080p.BluRay-Dracula").isTrustedGroup)
    }

    @Test
    fun tierIsResolvedAndWeightsTheTrustedScore() {
        val topTier = detect("Film.2024.2160p.BluRay.REMUX-FraMeSToR")
        val lowerTier = detect("[SubsPlease] Show - 01 (1080p).mkv")
        assertEquals(1, topTier.groupTier)
        assertTrue((lowerTier.groupTier ?: 0) > 1)

        val profile = StreamScoreProfile(
            enabled = true,
            points = mapOf(StreamScoreTrait.GROUP_TRUSTED.id to 100),
        )
        val top = StreamScorer.score(topTier, profile, StreamScoreContext.MOVIE).total
        val lower = StreamScorer.score(lowerTier, profile, StreamScoreContext.EPISODE).total
        assertEquals(100, top)
        assertTrue(lower in 1 until top, "expected a lower-tier group to score less, got $lower")
    }

    @Test
    fun anUnknownGroupMatchesNeitherList() {
        val traits = detect("Film.2024.1080p.WEB-DL-SOMEGROUP")
        assertFalse(traits.isTrustedGroup)
        assertFalse(traits.isLowQualityGroup)
    }

    @Test
    fun decoratedGroupTokensStillResolve() {
        assertTrue(StreamReleaseGroups.isLowQuality("[YTS.MX]"))
        assertTrue(StreamReleaseGroups.isTrusted("-FraMeSToR"))
        assertTrue(StreamReleaseGroups.isTrusted("FRAMESTOR"))
    }

    @Test
    fun theSourceTypeRemuxIsNotTreatedAsAGroupName() {
        // "remux" appears in the TRaSH tier lists but is also in essentially every remux release
        // name, so it is denied — otherwise every remux would score as a trusted group.
        assertFalse(detect("Film.2024.2160p.UHD.BluRay.REMUX.HEVC-NOBODY").isTrustedGroup)
    }
}

/**
 * The HDR10 trait deliberately accepts a bare "HDR" tag: the overwhelming majority of releases
 * labelled only "HDR" are HDR10, and without this the trait missed most real HDR releases.
 */
class StreamHdrMatchingTest {

    private fun detect(name: String) = StreamTraitDetector.detect(
        StreamItem(name = name, addonName = "a", addonId = "a", url = "http://x/test"),
    )

    private val profile = StreamScoreProfile(
        enabled = true,
        points = mapOf(
            StreamScoreTrait.HDR_10.id to 20,
            StreamScoreTrait.HDR_10_PLUS.id to 30,
            StreamScoreTrait.HDR_HLG.id to 10,
        ),
    )

    private fun score(name: String) = StreamScorer.score(detect(name), profile, StreamScoreContext.MOVIE)

    @Test
    fun aBareHdrTagScoresAsHdr10() {
        assertTrue(score("Film.2024.2160p.WEB-DL.HDR.HEVC-NTb").components.any {
            it.trait == StreamScoreTrait.HDR_10
        })
    }

    @Test
    fun anExplicitHdr10TagStillScores() {
        assertTrue(score("Film.2024.2160p.WEB-DL.HDR10.HEVC-NTb").components.any {
            it.trait == StreamScoreTrait.HDR_10
        })
    }

    @Test
    fun hdr10PlusDoesNotAlsoScoreTheHdr10Row() {
        val components = score("Film.2024.2160p.WEB-DL.HDR10+.HEVC-NTb").components.map { it.trait }
        assertTrue(StreamScoreTrait.HDR_10_PLUS in components)
        assertFalse(StreamScoreTrait.HDR_10 in components)
    }

    @Test
    fun anHlgOnlyReleaseIsNotTreatedAsHdr10() {
        val components = score("Film.2024.2160p.HLG.HEVC-NTb").components.map { it.trait }
        assertTrue(StreamScoreTrait.HDR_HLG in components)
        assertFalse(StreamScoreTrait.HDR_10 in components)
    }

    @Test
    fun anSdrReleaseScoresNoHdrTrait() {
        val components = score("Film.2024.1080p.WEB-DL.x264-NTb").components.map { it.trait }
        assertFalse(StreamScoreTrait.HDR_10 in components)
        assertFalse(StreamScoreTrait.HDR_10_PLUS in components)
        assertFalse(StreamScoreTrait.HDR_HLG in components)
    }
}
