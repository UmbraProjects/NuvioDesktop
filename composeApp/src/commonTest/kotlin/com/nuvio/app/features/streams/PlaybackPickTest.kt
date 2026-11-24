package com.nuvio.app.features.streams

import com.nuvio.app.features.debrid.DebridSettings
import com.nuvio.app.features.locallibrary.LocalLibraryPlaybackPreference
import com.nuvio.app.features.locallibrary.LocalTitlePlayback
import com.nuvio.app.features.locallibrary.localTitleRouting
import com.nuvio.app.features.player.PlayerSettingsUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Settings > Playback > What plays: the rules a pick follows, the source order, and the migration. */
class PlaybackPickTest {

    private val scoringOn = StreamScoreProfile(enabled = true)

    private fun firstPlay(
        settings: PlaybackPickSettings = PlaybackPickSettings(onPlay = PlayClick.PLAY_FOR_ME),
        manualSelection: Boolean = false,
        forcedAuto: Boolean = false,
        remembered: String? = "group-a",
        release: BingeReleaseHint? = null,
    ) = PlaybackPick.firstPlay(
        settings = settings,
        debridSettings = DebridSettings(),
        scoreProfile = StreamScoreProfile(),
        scoreContext = StreamScoreContext.MOVIE,
        manualSelection = manualSelection,
        forcedAuto = forcedAuto,
        parentMetaId = "tt1",
        season = 1,
        episode = 2,
        rememberedBingeGroup = { remembered },
        rememberedRelease = { _, _, _ -> release },
    )

    // --- First click -------------------------------------------------------------------------------------------------

    @Test
    fun `show me the sources never auto-plays a click`() {
        val pick = firstPlay(settings = PlaybackPickSettings(onPlay = PlayClick.SHOW_SOURCES))
        assertFalse(pick.isAutoPlayEnabled)
        assertNull(pick.bingeGroup)
    }

    @Test
    fun `play for me picks by priority order or best score`() {
        assertEquals(StreamAutoPlayMode.FIRST_STREAM, firstPlay().mode)
        assertEquals(
            StreamAutoPlayMode.SCORED,
            firstPlay(settings = PlaybackPickSettings(onPlay = PlayClick.PLAY_FOR_ME, pickBy = PickBy.BEST_SCORE)).mode,
        )
        assertFalse(firstPlay(manualSelection = true).isAutoPlayEnabled)
    }

    @Test
    fun `a forced Live TV pick plays even with show me the sources`() {
        val pick = firstPlay(settings = PlaybackPickSettings(onPlay = PlayClick.SHOW_SOURCES), forcedAuto = true)
        assertTrue(pick.isAutoPlayEnabled)
        assertEquals(StreamAutoPlayMode.FIRST_STREAM, pick.mode)
    }

    @Test
    fun `the remembered release applies only with same release first`() {
        assertEquals("group-a", firstPlay().bingeGroup)
        val sameRules = PlaybackPickSettings(onPlay = PlayClick.PLAY_FOR_ME, nextEpisode = NextEpisodePick.SAME_RULES_AS_PLAY)
        assertNull(firstPlay(settings = sameRules).bingeGroup)
    }

    @Test
    fun `rules pick the remembered group first and say why`() {
        val rules = firstPlay().rules(setOf("A", "B"))
        val first = stream("A", "group-b")
        val grouped = stream("B", "group-a")

        assertEquals(grouped, rules.select(listOf(first, grouped), bingeGroupOnly = true))
        assertEquals(grouped, rules.select(listOf(first, grouped)))
        assertEquals("same binge group", rules.reasonFor(grouped))
        assertEquals("priority order", rules.reasonFor(first))
        assertNull(rules.select(listOf(first), bingeGroupOnly = true))
    }

    // --- Filters -----------------------------------------------------------------------------------------------------

    @Test
    fun `a source switched off is never picked`() {
        val settings = PlaybackPickSettings(onPlay = PlayClick.PLAY_FOR_ME, autoPlay = mapOf(PlaybackSources.addon("A") to false))
        val rules = firstPlay(settings = settings, remembered = null).rules(setOf("A", "B"))
        val a = stream("A", null)
        val b = stream("B", null)
        assertEquals(b, rules.select(listOf(a, b)))
    }

    @Test
    fun `usenet and plugins have their own switches`() {
        val usenet = stream("Built-in Usenet", null).copy(addonId = "usenet:built-in")
        val plugin = stream("Some scraper", null).copy(addonId = "plugin:x")
        val settings = PlaybackPickSettings(autoPlay = mapOf(PlaybackSources.USENET to false))
        val rules = PlaybackPickRules(mode = StreamAutoPlayMode.FIRST_STREAM, installedAddonNames = setOf("A"), sources = settings)
        assertEquals(plugin, rules.select(listOf(usenet, plugin)))
        assertEquals(PlaybackSources.USENET, PlaybackSources.keyFor(usenet, setOf("A")))
        assertEquals(PlaybackSources.PLUGINS, PlaybackSources.keyFor(plugin, setOf("A")))
    }

    @Test
    fun `the regex filter keeps non-matching streams out of the pick`() {
        val settings = PlaybackPickSettings(onPlay = PlayClick.PLAY_FOR_ME, regexFilter = "2160p")
        val rules = firstPlay(settings = settings, remembered = null).rules(setOf("A"))
        val hd = stream("A", null, "1080p")
        val uhd = stream("A", null, "2160p")
        assertEquals(uhd, rules.select(listOf(hd, uhd)))
        assertNull(rules.select(listOf(hd)))
    }

    @Test
    fun `best score ranks across sources and priority breaks ties`() {
        val rules = PlaybackPickRules(mode = StreamAutoPlayMode.SCORED, installedAddonNames = setOf("A", "B"), scoreProfile = scoringOn)
        val hd = stream("A", null, "Show.S01E02.1080p.WEB-DL", filename = "Show.S01E02.1080p.WEB-DL.mkv")
        val uhd = stream("B", null, "Show.S01E02.2160p.WEB-DL", filename = "Show.S01E02.2160p.WEB-DL.mkv")
        assertEquals(uhd, rules.select(listOf(hd, uhd)))
        // Priority order ignores the score.
        assertEquals(hd, rules.copy(mode = StreamAutoPlayMode.FIRST_STREAM).select(listOf(hd, uhd)))
    }

    // --- Source order ------------------------------------------------------------------------------------------------

    @Test
    fun `the default order is debrid, addons, servers, usenet, plugins`() {
        assertEquals(
            listOf("debrid", "addon:A", "addon:B", "servers", "usenet", "plugins", "local"),
            PlaybackSources.effectiveOrder(emptyList(), listOf("A", "B")),
        )
    }

    @Test
    fun `a stored order keeps its places and new sources take theirs`() {
        val stored = listOf("usenet", "addon:B", "gone", "debrid")
        assertEquals(
            listOf("usenet", "addon:B", "addon:A", "debrid", "servers", "plugins", "local"),
            PlaybackSources.effectiveOrder(stored, listOf("A", "B")),
        )
    }

    @Test
    fun `groups are ordered by the priority list, local files at their row`() {
        val groups = listOf(
            AddonStreamGroup(addonName = "Plugin", addonId = "plugin:p", streams = emptyList()),
            AddonStreamGroup(addonName = "Built-in Usenet", addonId = "usenet:built-in", streams = emptyList()),
            AddonStreamGroup(addonName = "Local library", addonId = "local-library", streams = emptyList()),
            AddonStreamGroup(addonName = "B", addonId = "addon:b", streams = emptyList()),
            AddonStreamGroup(addonName = "A", addonId = "addon:a", streams = emptyList()),
        )
        val usenetFirst = PlaybackPickSettings(order = listOf("usenet"))
        assertEquals(
            listOf("usenet:built-in", "addon:a", "addon:b", "plugin:p", "local-library"),
            PlaybackSources.orderGroups(groups, listOf("A", "B"), usenetFirst).map { it.addonId },
        )
        val localSecond = PlaybackPickSettings(order = listOf("usenet", "local"))
        assertEquals(
            listOf("usenet:built-in", "local-library", "addon:a", "addon:b", "plugin:p"),
            PlaybackSources.orderGroups(groups, listOf("A", "B"), localSecond).map { it.addonId },
        )
    }

    @Test
    fun `local placement counts only sources that exist and may auto-play`() {
        val names = listOf("A")
        val present = setOf("local", "addon:A")
        // Debrid is above local here, but this install has no debrid, so local is in effect first.
        val behindDebrid = PlaybackPickSettings(order = listOf("debrid", "local", "addon:A"))
        assertEquals(LocalFilesPick.FIRST, behindDebrid.localPlacement(present, names))
        val behindAddon = PlaybackPickSettings(order = listOf("addon:A", "local"))
        assertEquals(LocalFilesPick.LAST, behindAddon.localPlacement(present, names))
        // An addon above it that never auto-plays does not count.
        val addonOff = behindAddon.copy(autoPlay = mapOf("addon:A" to false))
        assertEquals(LocalFilesPick.FIRST, addonOff.localPlacement(present, names))
        val localOff = behindAddon.copy(autoPlay = mapOf("local" to false))
        assertEquals(LocalFilesPick.NEVER, localOff.localPlacement(present, names))
    }

    @Test
    fun `a source still answering above holds back an early pick`() {
        val a = stream("A", null)
        val b = stream("B", null)
        val loadingA = AddonStreamGroup(addonName = "A", addonId = "addon:a", streams = emptyList(), isLoading = true)
        val doneB = AddonStreamGroup(addonName = "B", addonId = "addon:b", streams = listOf(b))
        assertFalse(PlaybackPick.outranksPending(listOf(loadingA, doneB), b))
        val doneA = loadingA.copy(streams = listOf(a), isLoading = false)
        assertTrue(PlaybackPick.outranksPending(listOf(doneA, doneB), b))
        // A source still answering has no streams in the pick yet.
        val partial = loadingA.copy(streams = listOf(a))
        assertEquals(listOf(b), PlaybackPick.answered(listOf(partial, doneB)))
    }

    // --- Local routing -----------------------------------------------------------------------------------------------

    @Test
    fun `local routing follows what plays and the title override`() {
        val play = PlayClick.PLAY_FOR_ME
        assertEquals(LocalLibraryPlaybackPreference.LOCAL_LIBRARY, localTitleRouting(null, play, LocalFilesPick.FIRST).preference)
        assertTrue(localTitleRouting(null, play, LocalFilesPick.LAST).ignoresLocalFile)
        val show = localTitleRouting(null, PlayClick.SHOW_SOURCES, LocalFilesPick.FIRST)
        assertEquals(LocalLibraryPlaybackPreference.SOURCE_PICKER, show.preference)
        assertFalse(show.ignoresLocalFile)
        assertEquals(
            LocalLibraryPlaybackPreference.LOCAL_LIBRARY,
            localTitleRouting(LocalTitlePlayback.LOCAL_FILE, play, LocalFilesPick.NEVER).preference,
        )
        assertTrue(localTitleRouting(LocalTitlePlayback.ONLINE_SOURCE, play, LocalFilesPick.FIRST).ignoresLocalFile)
    }

    // --- Migration ---------------------------------------------------------------------------------------------------

    private fun migrate(
        player: PlayerSettingsUiState = PlayerSettingsUiState(),
        local: LocalLibraryPlaybackPreference = LocalLibraryPlaybackPreference.SOURCE_PICKER,
        score: StreamScoreProfile = StreamScoreProfile(),
        serversFirst: Boolean = false,
    ) = PlaybackSources.migrate(player, local, score, serversFirst)

    @Test
    fun `a default install migrates to show me the sources and same release first`() {
        val migrated = migrate()
        assertEquals(PlayClick.SHOW_SOURCES, migrated.onPlay)
        assertEquals(PickBy.PRIORITY_ORDER, migrated.pickBy)
        assertEquals(NextEpisodePick.SAME_RELEASE_FIRST, migrated.nextEpisode)
        assertTrue(migrated.order.isEmpty())
        assertTrue(migrated.autoPlay.isEmpty())
    }

    @Test
    fun `automatic modes migrate to play for me with their pick`() {
        val first = PlayerSettingsUiState(streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM)
        assertEquals(PlayClick.PLAY_FOR_ME, migrate(first).onPlay)
        assertEquals(PickBy.PRIORITY_ORDER, migrate(first).pickBy)
        assertEquals(PickBy.BEST_SCORE, migrate(first, score = scoringOn).pickBy)
        val regex = PlayerSettingsUiState(streamAutoPlayMode = StreamAutoPlayMode.REGEX_MATCH, streamAutoPlayRegex = " 2160p ")
        assertEquals("2160p", migrate(regex).regexFilter)
        assertEquals(PickBy.BEST_SCORE, migrate(PlayerSettingsUiState(streamAutoPlayMode = StreamAutoPlayMode.SCORED)).pickBy)
    }

    @Test
    fun `next episode choices migrate from the binge switches`() {
        val askMe = PlayerSettingsUiState(streamAutoPlayManualNextEpisode = true)
        assertEquals(NextEpisodePick.ASK_ME, migrate(askMe).nextEpisode)
        val noBinge = PlayerSettingsUiState(streamAutoPlayPreferBingeGroup = false)
        assertEquals(NextEpisodePick.SAME_RULES_AS_PLAY, migrate(noBinge).nextEpisode)
        val scoreWins = PlayerSettingsUiState(streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM)
        assertEquals(NextEpisodePick.SAME_RULES_AS_PLAY, migrate(scoreWins, score = scoringOn.copy(overrideBingeGroup = true)).nextEpisode)
    }

    @Test
    fun `source scope and allowed lists migrate to switches`() {
        val addonsOnly = migrate(PlayerSettingsUiState(streamAutoPlaySource = StreamAutoPlaySource.INSTALLED_ADDONS_ONLY))
        assertFalse(addonsOnly.mayAutoPlay(PlaybackSources.PLUGINS))
        assertFalse(addonsOnly.mayAutoPlay(PlaybackSources.USENET))
        assertTrue(addonsOnly.mayAutoPlay(PlaybackSources.addon("A")))

        val allowed = migrate(PlayerSettingsUiState(streamAutoPlaySelectedAddons = setOf("A")))
        assertTrue(allowed.mayAutoPlay(PlaybackSources.addon("A")))
        assertFalse(allowed.mayAutoPlay(PlaybackSources.addon("B")))

        val pluginList = migrate(PlayerSettingsUiState(streamAutoPlaySelectedPlugins = setOf("X")))
        assertFalse(pluginList.mayAutoPlay(PlaybackSources.USENET))

        // The old list held manifest names; streams carry the display title.
        val renamed = PlaybackSources.migrate(
            PlayerSettingsUiState(streamAutoPlaySelectedAddons = setOf("Torrentio")),
            LocalLibraryPlaybackPreference.SOURCE_PICKER,
            StreamScoreProfile(),
            serversListedFirst = false,
            addonTitlesByManifestName = mapOf("Torrentio" to "Torrentio RD"),
        )
        assertTrue(renamed.mayAutoPlay(PlaybackSources.addon("Torrentio RD")))

        assertEquals(listOf("local"), migrate(local = LocalLibraryPlaybackPreference.LOCAL_LIBRARY).order)
        assertEquals(listOf("servers", "debrid"), migrate(serversFirst = true).order)
    }

    private fun stream(addon: String, bingeGroup: String?, name: String = "x", filename: String? = null) = StreamItem(
        name = name,
        url = "https://example.com/$addon/${name.replace(' ', '_')}/${filename.orEmpty()}",
        addonName = addon,
        addonId = "addon:$addon",
        behaviorHints = StreamBehaviorHints(bingeGroup = bingeGroup, filename = filename),
    )
}
