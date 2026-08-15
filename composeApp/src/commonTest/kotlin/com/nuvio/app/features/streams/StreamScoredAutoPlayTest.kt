package com.nuvio.app.features.streams

import com.nuvio.app.features.player.PlayerSettingsUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The SCORED auto-play mode. Its most important property is the fallback: an inactive profile must
 * behave exactly like FIRST_STREAM rather than selecting nothing, otherwise turning the mode on
 * without configuring scoring would silently break auto-play.
 */
class StreamScoredAutoPlayTest {

    private fun stream(name: String) = StreamItem(
        name = name,
        addonName = "addon",
        addonId = "addon",
        url = "http://example.test/${name.replace(' ', '_')}",
    )

    private fun select(
        streams: List<StreamItem>,
        profile: StreamScoreProfile,
    ): StreamItem? = StreamAutoPlaySelector.selectAutoPlayStream(
        streams = streams,
        mode = StreamAutoPlayMode.SCORED,
        regexPattern = "",
        source = StreamAutoPlaySource.ALL_SOURCES,
        installedAddonNames = setOf("addon"),
        selectedAddons = emptySet(),
        selectedPlugins = emptySet(),
        scoreProfile = profile,
    )

    private val scoring = StreamScoreProfile(
        enabled = true,
        points = mapOf(
            StreamScoreTrait.QUALITY_2160P_REMUX.id to 90,
            StreamScoreTrait.SRC_JUNK.id to -1000,
        ),
        minimumScore = 0,
    )

    @Test
    fun picksTheHighestScoringStreamRatherThanTheFirst() {
        val streams = listOf(
            stream("Movie 1080p WEB-DL"),
            stream("Movie 2160p BluRay REMUX"),
            stream("Movie 720p HDTV"),
        )
        assertEquals(streams[1], select(streams, scoring))
    }

    @Test
    fun neverPicksAStreamBelowTheMinimumScore() {
        val streams = listOf(stream("Movie CAM"), stream("Movie 1080p WEB-DL"))
        assertEquals(streams[1], select(streams, scoring))
    }

    @Test
    fun aDisabledProfileFallsBackToFirstStreamOrder() {
        val streams = listOf(
            stream("Movie 1080p WEB-DL"),
            stream("Movie 2160p BluRay REMUX"),
        )
        assertEquals(streams[0], select(streams, scoring.copy(enabled = false)))
    }

    @Test
    fun aProfileNotAppliedToFirstStreamFallsBackToFirstStreamOrder() {
        val streams = listOf(
            stream("Movie 1080p WEB-DL"),
            stream("Movie 2160p BluRay REMUX"),
        )
        assertEquals(streams[0], select(streams, scoring.copy(useForFirstStream = false)))
    }

    // --- the settings bridge ---

    @Test
    fun scoringTurnsAnAutomaticPickIntoABestScorePick() {
        // The "Apply scoring to -> choosing the first stream" toggle has to be enough on its own.
        // Requiring the user to ALSO set Playback -> stream selection to "best score" made the
        // toggle look broken, which is exactly how it was reported.
        assertEquals(
            StreamAutoPlayMode.SCORED,
            StreamAutoPlayPolicy.effectiveMode(StreamAutoPlayMode.FIRST_STREAM, scoring),
        )
        assertEquals(
            StreamAutoPlayMode.SCORED,
            StreamAutoPlayPolicy.effectiveMode(StreamAutoPlayMode.REGEX_MATCH, scoring),
        )
    }

    @Test
    fun manualStaysManualBecauseItMeansNeverPickForMe() {
        assertEquals(
            StreamAutoPlayMode.MANUAL,
            StreamAutoPlayPolicy.effectiveMode(StreamAutoPlayMode.MANUAL, scoring),
        )
    }

    @Test
    fun anInactiveProfileLeavesTheConfiguredModeAlone() {
        val off = scoring.copy(enabled = false)
        val notForFirstStream = scoring.copy(useForFirstStream = false)
        assertEquals(
            StreamAutoPlayMode.FIRST_STREAM,
            StreamAutoPlayPolicy.effectiveMode(StreamAutoPlayMode.FIRST_STREAM, off),
        )
        assertEquals(
            StreamAutoPlayMode.REGEX_MATCH,
            StreamAutoPlayPolicy.effectiveMode(StreamAutoPlayMode.REGEX_MATCH, notForFirstStream),
        )
    }

    // --- binge group override ---

    @Test
    fun theBingeGroupOverrideOnlyFiresWhenScoringIsMakingThePick() {
        val overriding = scoring.copy(overrideBingeGroup = true)
        assertTrue(StreamAutoPlayPolicy.scoreOverridesBingeGroup(StreamAutoPlayMode.SCORED, overriding))
        // Not selected as the mode → the profile is not choosing, so binge affinity still rules.
        assertFalse(StreamAutoPlayPolicy.scoreOverridesBingeGroup(StreamAutoPlayMode.FIRST_STREAM, overriding))
        // MANUAL means never pick for me, so nothing auto-plays there and there is no pick for
        // scoring to take away from binge affinity.
        assertFalse(StreamAutoPlayPolicy.scoreOverridesBingeGroup(StreamAutoPlayMode.MANUAL, overriding))
    }

    @Test
    fun theBingeGroupTogglesDoNotAutoPlayInManualMode() {
        // Both default to on while the mode defaults to MANUAL, so treating them as auto-play made
        // a stock install start playback from Continue Watching on any show it had a cached binge
        // group for — and open the picker for every other one.
        val settings = PlayerSettingsUiState(
            streamAutoPlayMode = StreamAutoPlayMode.MANUAL,
            streamAutoPlayPreferBingeGroup = true,
            streamAutoPlayReuseBingeGroup = true,
        )
        assertFalse(StreamAutoPlayPolicy.isEffectivelyEnabled(settings))
        // Reuse Last Link is the one that does say "auto-play" on the tin.
        assertTrue(
            StreamAutoPlayPolicy.isEffectivelyEnabled(
                settings.copy(streamReuseLastLinkEnabled = true),
            ),
        )
    }

    @Test
    fun theBingeGroupOverrideIsOffUntilItIsTurnedOn() {
        assertFalse(StreamAutoPlayPolicy.scoreOverridesBingeGroup(StreamAutoPlayMode.SCORED, scoring))
        assertFalse(
            StreamAutoPlayPolicy.scoreOverridesBingeGroup(
                StreamAutoPlayMode.SCORED,
                scoring.copy(enabled = false, overrideBingeGroup = true),
            ),
        )
    }

    @Test
    fun aMatchingBingeGroupOutranksAHigherScoreWithoutTheOverride() {
        // The behaviour the override exists to change: a binge match goes to the head of the list
        // ahead of the scored order, so the lower-scoring stream plays.
        val streams = listOf(
            stream("Movie 1080p WEB-DL").copy(behaviorHints = StreamBehaviorHints(bingeGroup = "grp")),
            stream("Movie 2160p BluRay REMUX"),
        )
        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = streams,
            mode = StreamAutoPlayMode.SCORED,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("addon"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "grp",
            preferBingeGroupInSelection = true,
            scoreProfile = scoring,
        )
        assertEquals(streams[0], selected)
        // With the override the caller passes preferBingeGroupInSelection = false, and the score wins.
        assertEquals(streams[1], select(streams, scoring))
    }

    @Test
    fun everyStreamBeingRejectedSelectsNothing() {
        val streams = listOf(stream("Movie CAM"), stream("Movie TS"))
        assertNull(select(streams, scoring))
    }
}
