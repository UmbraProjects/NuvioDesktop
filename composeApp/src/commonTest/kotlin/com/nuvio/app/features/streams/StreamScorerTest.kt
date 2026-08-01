package com.nuvio.app.features.streams

import com.nuvio.app.features.debrid.DebridStreamAudioChannel
import com.nuvio.app.features.debrid.DebridStreamAudioTag
import com.nuvio.app.features.debrid.DebridStreamEncode
import com.nuvio.app.features.debrid.DebridStreamLanguage
import com.nuvio.app.features.debrid.DebridStreamQuality
import com.nuvio.app.features.debrid.DebridStreamResolution
import com.nuvio.app.features.debrid.DebridStreamVisualTag
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val GB = 1_000_000_000L

private val HDR_FORMAT_TRAITS = setOf(
    StreamScoreTrait.HDR_DOLBY_VISION,
    StreamScoreTrait.HDR_10_PLUS,
    StreamScoreTrait.HDR_10,
    StreamScoreTrait.HDR_HLG,
)

class StreamScorerTest {

    private fun traits(
        resolution: DebridStreamResolution = DebridStreamResolution.P1080,
        quality: DebridStreamQuality = DebridStreamQuality.WEB_DL,
        visual: List<DebridStreamVisualTag> = emptyList(),
        audio: List<DebridStreamAudioTag> = emptyList(),
        channels: List<DebridStreamAudioChannel> = emptyList(),
        encode: DebridStreamEncode = DebridStreamEncode.UNKNOWN,
        languages: List<DebridStreamLanguage> = emptyList(),
        releaseGroup: String = "",
        size: Long? = null,
        durationSeconds: Long? = null,
        cached: Boolean = false,
    ) = StreamTraits(
        resolution = resolution,
        quality = quality,
        visualTags = visual,
        audioTags = audio,
        audioChannels = channels,
        encode = encode,
        languages = languages,
        releaseGroup = releaseGroup,
        size = size,
        durationSeconds = durationSeconds,
        isDebridCached = cached,
        isTrustedGroup = StreamReleaseGroups.isTrusted(releaseGroup),
        isLowQualityGroup = StreamReleaseGroups.isLowQuality(releaseGroup),
        groupTier = StreamReleaseGroups.tierOf(releaseGroup),
    )

    private fun profile(vararg points: Pair<StreamScoreTrait, Int>) = StreamScoreProfile(
        enabled = true,
        points = points.associate { (trait, value) -> trait.id to value },
    )

    // --- additive scoring ---

    @Test
    fun scoreIsTheSumOfEveryMatchedTrait() {
        val score = StreamScorer.score(
            traits(
                resolution = DebridStreamResolution.P2160,
                quality = DebridStreamQuality.BLURAY_REMUX,
                visual = listOf(DebridStreamVisualTag.DV),
                audio = listOf(DebridStreamAudioTag.ATMOS),
            ),
            profile(
                StreamScoreTrait.QUALITY_2160P_REMUX to 90,
                StreamScoreTrait.HDR_DOLBY_VISION to 40,
                StreamScoreTrait.AUDIO_ATMOS to 40,
            ),
            StreamScoreContext.MOVIE,
        )
        assertEquals(170, score.total)
        // Three, not four: resolution and source are a single combined row now.
        assertEquals(3, score.components.size)
    }

    @Test
    fun atmosPlusTrueHdOutranksAtmosAloneWithoutACombinationRule() {
        val p = profile(StreamScoreTrait.AUDIO_ATMOS to 40, StreamScoreTrait.AUDIO_TRUEHD to 25)
        val both = StreamScorer.score(
            traits(audio = listOf(DebridStreamAudioTag.ATMOS, DebridStreamAudioTag.TRUEHD)),
            p,
            StreamScoreContext.MOVIE,
        )
        val atmosOnly = StreamScorer.score(
            traits(audio = listOf(DebridStreamAudioTag.ATMOS)),
            p,
            StreamScoreContext.MOVIE,
        )
        assertEquals(65, both.total)
        assertEquals(40, atmosOnly.total)
    }

    // --- HDR formats do not stack ---

    @Test
    fun onlyTheHighestScoringHdrFormatCounts() {
        // A "DV HDR10" release is one picture, not two bonuses.
        val score = StreamScorer.score(
            traits(visual = listOf(DebridStreamVisualTag.DV, DebridStreamVisualTag.HDR10)),
            profile(StreamScoreTrait.HDR_DOLBY_VISION to 70, StreamScoreTrait.HDR_10 to 20),
            StreamScoreContext.MOVIE,
        )
        assertEquals(70, score.total)
        assertEquals(1, score.components.count { it.trait in HDR_FORMAT_TRAITS })
    }

    @Test
    fun theHighestHdrFormatWinsEvenWhenItIsNotTheFancierOne() {
        // "Highest score wins" is literal: if HDR10 is worth more to the user than DV, it wins.
        val score = StreamScorer.score(
            traits(visual = listOf(DebridStreamVisualTag.DV, DebridStreamVisualTag.HDR10)),
            profile(StreamScoreTrait.HDR_DOLBY_VISION to 10, StreamScoreTrait.HDR_10 to 40),
            StreamScoreContext.MOVIE,
        )
        assertEquals(40, score.total)
    }

    @Test
    fun hdr10PlusAlsoSatisfiesTheHdr10Row() {
        // Collapsing replaced the old exclusion rule, so HDR10+ matching both rows is expected —
        // the higher of the two is what lands.
        val score = StreamScorer.score(
            traits(visual = listOf(DebridStreamVisualTag.HDR10_PLUS, DebridStreamVisualTag.HDR10)),
            profile(StreamScoreTrait.HDR_10_PLUS to 30, StreamScoreTrait.HDR_10 to 20),
            StreamScoreContext.MOVIE,
        )
        assertEquals(30, score.total)
    }

    @Test
    fun equalHdrScoresStillCollapseToASingleContribution() {
        // The intended PC setup: all HDR formats worth the same, so any HDR release scores once.
        val flat = profile(
            StreamScoreTrait.HDR_DOLBY_VISION to 20,
            StreamScoreTrait.HDR_10_PLUS to 20,
            StreamScoreTrait.HDR_10 to 20,
        )
        val dvHdr = StreamScorer.score(
            traits(visual = listOf(DebridStreamVisualTag.DV, DebridStreamVisualTag.HDR10)),
            flat,
            StreamScoreContext.MOVIE,
        )
        val plainHdr = StreamScorer.score(
            traits(visual = listOf(DebridStreamVisualTag.HDR10)),
            flat,
            StreamScoreContext.MOVIE,
        )
        assertEquals(20, dvHdr.total)
        assertEquals(20, plainHdr.total)
    }

    @Test
    fun nonFormatHdrTraitsStillStack() {
        // 10-bit is a bit depth, not a format, so it is additive alongside the format bonus.
        val score = StreamScorer.score(
            traits(visual = listOf(DebridStreamVisualTag.HDR10, DebridStreamVisualTag.TEN_BIT)),
            profile(StreamScoreTrait.HDR_10 to 20, StreamScoreTrait.HDR_10_BIT to 5),
            StreamScoreContext.MOVIE,
        )
        assertEquals(25, score.total)
    }

    @Test
    fun lossyDtsDoesNotFireForDtsXOrDtsHdMa() {
        val p = profile(StreamScoreTrait.AUDIO_DTS to 5, StreamScoreTrait.AUDIO_DTS_X to 30)
        val dtsX = StreamScorer.score(
            traits(audio = listOf(DebridStreamAudioTag.DTS_X, DebridStreamAudioTag.DTS)),
            p,
            StreamScoreContext.MOVIE,
        )
        assertEquals(30, dtsX.total)
    }

    @Test
    fun aTraitWithZeroPointsNeverAppearsInTheBreakdown() {
        val score = StreamScorer.score(
            traits(resolution = DebridStreamResolution.P2160),
            profile(StreamScoreTrait.QUALITY_2160P_WEB_DL to 0),
            StreamScoreContext.MOVIE,
        )
        assertEquals(0, score.total)
        assertTrue(score.components.isEmpty())
    }

    // --- minimum score ---

    @Test
    fun aStreamBelowTheMinimumScoreIsRejected() {
        val p = profile(StreamScoreTrait.SRC_JUNK to -1000).copy(minimumScore = 0)
        val cam = StreamScorer.score(
            traits(quality = DebridStreamQuality.CAM),
            p,
            StreamScoreContext.MOVIE,
        )
        assertTrue(cam.rejected)
    }

    @Test
    fun rankDropsRejectedStreamsAndOrdersByScore() {
        val p = profile(StreamScoreTrait.QUALITY_2160P_WEB_DL to 50, StreamScoreTrait.SRC_JUNK to -1000)
            .copy(minimumScore = 0)
        val good = stream("Movie 2160p WEB-DL")
        val cam = stream("Movie CAM")
        val plain = stream("Movie 1080p WEB-DL")

        val ranked = StreamScorer.rank(listOf(cam, plain, good), p, StreamScoreContext.MOVIE)
        assertContentEquals(listOf(good, plain), ranked)
    }

    // --- ordering guarantees ---

    @Test
    fun aDisabledProfileLeavesTheListCompletelyUntouched() {
        val streams = listOf(stream("B 1080p"), stream("A 2160p"), stream("C 720p"))
        val disabled = StreamScoreProfile(enabled = false, points = mapOf(StreamScoreTrait.QUALITY_2160P_WEB_DL.id to 50))
        assertContentEquals(streams, StreamScorer.rank(streams, disabled, StreamScoreContext.MOVIE))
    }

    @Test
    fun equalScoresKeepTheirIncomingOrder() {
        val streams = listOf(
            stream("First 1080p WEB-DL"),
            stream("Second 1080p WEB-DL"),
            stream("Third 1080p WEB-DL"),
        )
        assertContentEquals(streams, StreamScorer.rank(streams, profile(StreamScoreTrait.QUALITY_1080P_WEB_DL to 30), StreamScoreContext.MOVIE))
    }

    // --- release groups + language ---

    @Test
    fun trustedAndLowQualityGroupsAreMatchedThroughTheirDecoration() {
        val p = profile(StreamScoreTrait.GROUP_TRUSTED to 100, StreamScoreTrait.GROUP_LOW_QUALITY to -80)
        // FraMeSToR is tier 1, so it scores the full value.
        assertEquals(100, StreamScorer.score(traits(releaseGroup = "FraMeSToR"), p, StreamScoreContext.MOVIE).total)
        assertEquals(-80, StreamScorer.score(traits(releaseGroup = "[AnimeRG]"), p, StreamScoreContext.MOVIE).total)
        assertEquals(0, StreamScorer.score(traits(releaseGroup = "SomeRandomGroup"), p, StreamScoreContext.MOVIE).total)
    }

    @Test
    fun preferredLanguageScoresOnlyWhenTheContextSuppliesOne() {
        // Language preference rides on the context, not the profile — it is configured once in
        // Playback settings rather than duplicated here.
        val p = profile(StreamScoreTrait.LANGUAGE_PREFERRED to 25)
        val language = DebridStreamLanguage.entries.first()
        val t = traits(languages = listOf(language))
        assertEquals(0, StreamScorer.score(t, p, StreamScoreContext.MOVIE).total)

        val withLanguage = StreamScoreContext(preferredLanguages = listOf(language.code))
        assertEquals(25, StreamScorer.score(t, p, withLanguage).total)
    }

    @Test
    fun preferredLanguageIsMatchedCaseInsensitively() {
        val p = profile(StreamScoreTrait.LANGUAGE_PREFERRED to 25)
        val language = DebridStreamLanguage.entries.first()
        val t = traits(languages = listOf(language))
        val context = StreamScoreContext(preferredLanguages = listOf(language.code.uppercase()))
        assertEquals(25, StreamScorer.score(t, p, context).total)
    }

    // --- unknown group ---

    @Test
    fun theUnknownGroupTraitFiresOnlyWhenNeitherListMatches() {
        val p = profile(
            StreamScoreTrait.GROUP_TRUSTED to 50,
            StreamScoreTrait.GROUP_UNKNOWN to -20,
            StreamScoreTrait.GROUP_LOW_QUALITY to -80,
        )
        assertEquals(50, StreamScorer.score(traits(releaseGroup = "FraMeSToR"), p, StreamScoreContext.MOVIE).total)
        assertEquals(-80, StreamScorer.score(traits(releaseGroup = "AnimeRG"), p, StreamScoreContext.MOVIE).total)
        assertEquals(-20, StreamScorer.score(traits(releaseGroup = "NeverHeardOfIt"), p, StreamScoreContext.MOVIE).total)
    }

    @Test
    fun aReleaseWithNoGroupAtAllCountsAsUnknown() {
        val p = profile(StreamScoreTrait.GROUP_UNKNOWN to -20)
        assertEquals(-20, StreamScorer.score(traits(releaseGroup = ""), p, StreamScoreContext.MOVIE).total)
    }

    @Test
    fun theUnknownGroupTraitCanBePositive() {
        // The default is 0 precisely so it can be pushed either way; a user who distrusts the
        // curated list can reward unknown groups instead.
        val p = profile(StreamScoreTrait.GROUP_UNKNOWN to 30)
        assertEquals(30, StreamScorer.score(traits(releaseGroup = "SomeIndieGroup"), p, StreamScoreContext.MOVIE).total)
    }

    // --- score badge gating ---

    @Test
    fun theScoreBadgeNeedsBothScoringEnabledAndTheBadgeToggle() {
        val base = profile(StreamScoreTrait.QUALITY_2160P_WEB_DL to 50)
        // The card shows a badge only when both are true; these mirror that condition so a change
        // to either flag's meaning shows up here rather than as a silently blank column.
        assertFalse(base.enabled && base.showScoreOnStreams)
        assertTrue(base.copy(showScoreOnStreams = true).let { it.enabled && it.showScoreOnStreams })
        assertFalse(
            base.copy(enabled = false, showScoreOnStreams = true)
                .let { it.enabled && it.showScoreOnStreams },
        )
    }

    @Test
    fun theBadgeToggleDoesNotAffectRanking() {
        val streams = listOf(stream("Movie 1080p WEB-DL"), stream("Movie 2160p WEB-DL"))
        val quiet = profile(StreamScoreTrait.QUALITY_2160P_WEB_DL to 50)
        val loud = quiet.copy(showScoreOnStreams = true)
        assertContentEquals(
            StreamScorer.rank(streams, quiet, StreamScoreContext.MOVIE),
            StreamScorer.rank(streams, loud, StreamScoreContext.MOVIE),
        )
    }

    private fun stream(name: String) = StreamItem(name = name, addonName = "addon", addonId = "a", url = "http://x/$name")
}

class StreamSizeSanityTest {

    private fun traits(
        resolution: DebridStreamResolution,
        quality: DebridStreamQuality,
        size: Long?,
        durationSeconds: Long? = null,
    ) = StreamTraits(
        resolution = resolution,
        quality = quality,
        visualTags = emptyList(),
        audioTags = emptyList(),
        audioChannels = emptyList(),
        encode = DebridStreamEncode.UNKNOWN,
        languages = emptyList(),
        releaseGroup = "",
        size = size,
        durationSeconds = durationSeconds,
        isDebridCached = false,
        isTrustedGroup = false,
        isLowQualityGroup = false,
        groupTier = null,
    )

    @Test
    fun aOneGigabyteFourKRemuxIsImplausible() {
        // 25 Mbps floor over a 120-minute film needs ~22.5 GB.
        val t = traits(DebridStreamResolution.P2160, DebridStreamQuality.BLURAY_REMUX, 1 * GB, durationSeconds = 7200)
        assertTrue(StreamSizeSanity.isImplausiblySmall(t, StreamScoreContext.MOVIE))
    }

    @Test
    fun aRealFourKRemuxPasses() {
        val t = traits(DebridStreamResolution.P2160, DebridStreamQuality.BLURAY_REMUX, 60 * GB, durationSeconds = 7200)
        assertFalse(StreamSizeSanity.isImplausiblySmall(t, StreamScoreContext.MOVIE))
    }

    @Test
    fun aLegitimatelySmallEpisodeIsNotFlagged() {
        // 1080p WEB-DL at 2.5 Mbps over 40 minutes needs ~750 MB; a 1.4 GB episode clears it easily.
        val t = traits(DebridStreamResolution.P1080, DebridStreamQuality.WEB_DL, 1_400_000_000L, durationSeconds = 2400)
        assertFalse(StreamSizeSanity.isImplausiblySmall(t, StreamScoreContext.EPISODE))
    }

    @Test
    fun anUnknownResolutionDisablesTheCheckRatherThanGuessing() {
        val t = traits(DebridStreamResolution.UNKNOWN, DebridStreamQuality.BLURAY_REMUX, 1_000L)
        assertFalse(StreamSizeSanity.isImplausiblySmall(t, StreamScoreContext.MOVIE))
    }

    @Test
    fun anUnknownSizeDisablesTheCheck() {
        val t = traits(DebridStreamResolution.P2160, DebridStreamQuality.BLURAY_REMUX, null)
        assertFalse(StreamSizeSanity.isImplausiblySmall(t, StreamScoreContext.MOVIE))
    }

    @Test
    fun theFallbackDurationBiasesShortSoAShortRuntimeIsNotFalselyFlagged() {
        // A genuine 22-minute 1080p WEB-DL episode with no duration metadata: the assumed 20-minute
        // fallback must not demand more bytes than the real content contains.
        val t = traits(DebridStreamResolution.P1080, DebridStreamQuality.WEB_DL, 420_000_000L)
        assertFalse(StreamSizeSanity.isImplausiblySmall(t, StreamScoreContext.EPISODE))
    }

    @Test
    fun aNormalAnimeEpisodeIsFlaggedAsLiveActionButPassesAsAnimation() {
        // 300 MB for a 24-minute 1080p WEB-DL episode: routine for anime, below the 2.5 Mbps
        // live-action floor (which wants ~450 MB) and inside the halved 1.25 Mbps animation floor.
        val t = traits(DebridStreamResolution.P1080, DebridStreamQuality.WEB_DL, 300_000_000L, durationSeconds = 1440)
        assertTrue(StreamSizeSanity.isImplausiblySmall(t, StreamScoreContext.EPISODE))
        assertFalse(
            StreamSizeSanity.isImplausiblySmall(t, StreamScoreContext(isEpisode = true, isAnimation = true)),
        )
    }

    @Test
    fun animationStillRejectsAFakeFourKRemux() {
        // Halving the floor is a tolerance, not an exemption: 500 MB for a two-hour 4K remux is a
        // lie whether it is animated or not (12.5 Mbps still needs ~11 GB).
        val t = traits(DebridStreamResolution.P2160, DebridStreamQuality.BLURAY_REMUX, 500_000_000L, durationSeconds = 7200)
        assertTrue(
            StreamSizeSanity.isImplausiblySmall(t, StreamScoreContext(isEpisode = false, isAnimation = true)),
        )
    }

    @Test
    fun runtimeFromContextIsUsedWhenTheStreamHasNoDuration() {
        val t = traits(DebridStreamResolution.P2160, DebridStreamQuality.BLURAY_REMUX, 5 * GB)
        // 180 minutes at a 25 Mbps floor needs ~33.75 GB, so 5 GB is implausible.
        val context = StreamScoreContext(isEpisode = false, runtimeMinutes = 180)
        assertTrue(StreamSizeSanity.isImplausiblySmall(t, context))
        assertEquals(10_800L, StreamSizeSanity.durationSeconds(t, context))
    }

    @Test
    fun durationUnitIsInferredFromMagnitude() {
        assertEquals(7200L, StreamTraitDetector.durationSeconds(7_200_000L))  // milliseconds
        assertEquals(7200L, StreamTraitDetector.durationSeconds(7_200L))      // seconds
        assertEquals(7200L, StreamTraitDetector.durationSeconds(120L))        // minutes
    }

    @Test
    fun implausibleDurationsAreDiscardedRatherThanTrusted() {
        assertEquals(null, StreamTraitDetector.durationSeconds(0L))
        assertEquals(null, StreamTraitDetector.durationSeconds(-5L))
        // 20 hours in seconds — beyond anything believable, so unusable.
        assertEquals(null, StreamTraitDetector.durationSeconds(72_000L))
    }
}

class StreamSizeBandTest {

    private fun traitsOfSize(size: Long) = StreamTraits(
        resolution = DebridStreamResolution.P1080,
        quality = DebridStreamQuality.WEB_DL,
        visualTags = emptyList(),
        audioTags = emptyList(),
        audioChannels = emptyList(),
        encode = DebridStreamEncode.UNKNOWN,
        languages = emptyList(),
        releaseGroup = "",
        size = size,
        durationSeconds = 7200,
        isDebridCached = false,
        isTrustedGroup = false,
        isLowQualityGroup = false,
        groupTier = null,
    )

    private val band = StreamSizeBand(
        enabled = true,
        movieMinGb = 8.0,
        movieMaxGb = 40.0,
        graceFraction = 0.5,
    )

    private fun profileWithBand() = StreamScoreProfile(
        enabled = true,
        points = mapOf(
            StreamScoreTrait.SIZE_IN_BAND.id to 20,
            StreamScoreTrait.SIZE_FAR_OUT.id to -40,
        ),
        sizeBand = band,
    )

    @Test
    fun insideTheBandScoresTheInBandValue() {
        val score = StreamScorer.score(traitsOfSize(20 * GB), profileWithBand(), StreamScoreContext.MOVIE)
        assertEquals(20, score.total)
    }

    @Test
    fun withinTheGraceMarginScoresNeutral() {
        // 50 GB is past the 40 GB ceiling but inside the 50% grace margin (60 GB).
        val score = StreamScorer.score(traitsOfSize(50 * GB), profileWithBand(), StreamScoreContext.MOVIE)
        assertEquals(0, score.total)
    }

    @Test
    fun farOutsideTheBandScoresTheFarOutValue() {
        val score = StreamScorer.score(traitsOfSize(120 * GB), profileWithBand(), StreamScoreContext.MOVIE)
        assertEquals(-40, score.total)
    }

    @Test
    fun aDisabledBandScoresNeitherSizeTrait() {
        val profile = profileWithBand().copy(sizeBand = band.copy(enabled = false))
        assertEquals(0, StreamScorer.score(traitsOfSize(120 * GB), profile, StreamScoreContext.MOVIE).total)
    }

    @Test
    fun episodesUseTheEpisodeBoundsNotTheMovieOnes() {
        // 4 GB is inside the episode band (1–8) and below the movie band's 8 GB floor.
        val profile = profileWithBand()
        assertEquals(20, StreamScorer.score(traitsOfSize(4 * GB), profile, StreamScoreContext.EPISODE).total)
        assertEquals(0, StreamScorer.score(traitsOfSize(4 * GB), profile, StreamScoreContext.MOVIE).total)
    }
}
