package com.nuvio.app.features.streams

import com.nuvio.app.features.debrid.DebridSettings
import com.nuvio.app.features.player.PlayerSettingsUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Step 1 of the playback-priority plan: PlaybackPick must decide exactly what the old inline code decided. */
class PlaybackPickTest {

    private val scoringOn = StreamScoreProfile(enabled = true, useForFirstStream = true)

    private fun firstPlay(
        settings: PlayerSettingsUiState = PlayerSettingsUiState(),
        scoreProfile: StreamScoreProfile = StreamScoreProfile(),
        manualSelection: Boolean = false,
        forcedAuto: Boolean = false,
        remembered: String? = "group-a",
        release: BingeReleaseHint? = null,
    ) = PlaybackPick.firstPlay(
        settings = settings,
        debridSettings = DebridSettings(),
        scoreProfile = scoreProfile,
        scoreContext = StreamScoreContext.MOVIE,
        manualSelection = manualSelection,
        forcedAuto = forcedAuto,
        parentMetaId = "tt1",
        season = 1,
        episode = 2,
        rememberedBingeGroup = { remembered },
        rememberedRelease = { _, _, _ -> release },
    )

    @Test
    fun `manual mode does not auto-play`() {
        val pick = firstPlay(settings = PlayerSettingsUiState(streamAutoPlayMode = StreamAutoPlayMode.MANUAL))
        assertFalse(pick.isAutoPlayEnabled)
    }

    @Test
    fun `scoring for the first stream turns first stream into best score`() {
        val pick = firstPlay(
            settings = PlayerSettingsUiState(streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM),
            scoreProfile = scoringOn,
        )
        assertEquals(StreamAutoPlayMode.SCORED, pick.mode)
        assertTrue(pick.isAutoPlayEnabled)
    }

    @Test
    fun `a forced Live TV pick plays first stream even in manual`() {
        val pick = firstPlay(
            settings = PlayerSettingsUiState(streamAutoPlayMode = StreamAutoPlayMode.MANUAL),
            forcedAuto = true,
        )
        assertEquals(StreamAutoPlayMode.FIRST_STREAM, pick.mode)
        assertTrue(pick.isAutoPlayEnabled)
    }

    @Test
    fun `an unconfigured regex does not auto-play`() {
        val pick = firstPlay(settings = PlayerSettingsUiState(streamAutoPlayMode = StreamAutoPlayMode.REGEX_MATCH))
        assertFalse(pick.isAutoPlayEnabled)
    }

    @Test
    fun `the remembered binge group needs both prefer and reuse`() {
        val both = PlayerSettingsUiState(
            streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM,
            streamAutoPlayPreferBingeGroup = true,
            streamAutoPlayReuseBingeGroup = true,
        )
        assertEquals("group-a", firstPlay(settings = both).bingeGroup)
        assertNull(firstPlay(settings = both.copy(streamAutoPlayReuseBingeGroup = false)).bingeGroup)
        assertNull(firstPlay(settings = both.copy(streamAutoPlayPreferBingeGroup = false)).bingeGroup)
    }

    @Test
    fun `a score that overrides binge groups drops the remembered group`() {
        val pick = firstPlay(
            settings = PlayerSettingsUiState(
                streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM,
                streamAutoPlayPreferBingeGroup = true,
                streamAutoPlayReuseBingeGroup = true,
            ),
            scoreProfile = scoringOn.copy(overrideBingeGroup = true),
        )
        assertNull(pick.bingeGroup)
    }

    @Test
    fun `rules pick the remembered group first and say why`() {
        val pick = firstPlay(
            settings = PlayerSettingsUiState(
                streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM,
                streamAutoPlayPreferBingeGroup = true,
                streamAutoPlayReuseBingeGroup = true,
            ),
        )
        val rules = pick.rules(setOf("A", "B"))
        val first = stream("A", "group-b")
        val grouped = stream("B", "group-a")

        assertEquals(grouped, rules.select(listOf(first, grouped), bingeGroupOnly = true))
        assertEquals(grouped, rules.select(listOf(first, grouped)))
        assertEquals("same binge group", rules.reasonFor(grouped))
        assertEquals("first stream", rules.reasonFor(first))
        // Before the timeout only the group may fire.
        assertNull(rules.select(listOf(first), bingeGroupOnly = true))
    }

    @Test
    fun `rules match the selector they wrap`() {
        val settings = PlayerSettingsUiState(
            streamAutoPlayMode = StreamAutoPlayMode.REGEX_MATCH,
            streamAutoPlayRegex = "2160p",
            streamAutoPlaySelectedAddons = setOf("B"),
        )
        val streams = listOf(stream("A", null, "2160p"), stream("B", null, "1080p"), stream("B", null, "2160p"))
        val rules = firstPlay(settings = settings, remembered = null).rules(setOf("A", "B"))
        val direct = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = streams,
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = "2160p",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("A", "B"),
            selectedAddons = setOf("B"),
            selectedPlugins = emptySet(),
            debridEnabled = DebridSettings().canResolvePlayableLinks,
            activeResolverProviderId = DebridSettings().activeResolverProviderId,
        )
        assertEquals(streams[2], direct)
        assertEquals(direct, rules.select(streams))
        assertEquals("regex", rules.reasonFor(streams[2]))
    }

    @Test
    fun `the first-stream fallback drops regex and scoring but keeps the scope`() {
        val rules = PlaybackPickRules(
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regex = "nomatch",
            installedAddonNames = setOf("A", "B"),
            selectedAddons = setOf("B"),
            scoreProfile = scoringOn,
        )
        val streams = listOf(stream("A", null), stream("B", null))
        assertNull(rules.select(streams))
        val fallback = rules.firstStreamFallback()
        assertEquals(streams[1], fallback.select(streams))
        assertEquals("first stream", fallback.reasonFor(streams[1]))
        assertFalse(fallback.scoreProfile.enabled)
    }

    @Test
    fun `the release watched last is named`() {
        val release = BingeReleaseHint(expectedFilename = "Show.S01E02.2160p.mkv", infoHash = null)
        val rules = PlaybackPickRules(mode = StreamAutoPlayMode.FIRST_STREAM, bingeGroup = "g", release = release)
        val same = stream("A", "g", filename = "Show.S01E02.2160p.mkv")
        val other = stream("A", "g", filename = "Other.S01E02.720p.mkv")
        assertEquals("same release", rules.reasonFor(same))
        assertEquals("same binge group", rules.reasonFor(other))
    }

    private fun stream(addon: String, bingeGroup: String?, name: String = "x", filename: String? = null) = StreamItem(
        name = name,
        url = "https://example.com/$addon/$name/${filename.orEmpty()}",
        addonName = addon,
        addonId = "addon:$addon",
        behaviorHints = StreamBehaviorHints(bingeGroup = bingeGroup, filename = filename),
    )
}
