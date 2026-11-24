package com.nuvio.app.features.streams

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.usenet.UsenetSettingsRepository
import com.nuvio.app.features.locallibrary.LocalLibraryPlaybackPreference
import com.nuvio.app.features.locallibrary.LocalLibraryRepository
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.servers.ServerRepository
import com.nuvio.app.features.player.PlayerSettingsUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What a plain Play click does. */
@Serializable
enum class PlayClick { PLAY_FOR_ME, SHOW_SOURCES }

/** How a stream is chosen among the sources that may auto-play. */
@Serializable
enum class PickBy {
    /** The first ready stream of the highest source in [PlaybackPickSettings.order], in that source's own order. */
    PRIORITY_ORDER,

    /** Every eligible stream ranked by the score profile; source priority breaks ties. */
    BEST_SCORE,
}

/** How the next episode's stream is chosen. */
@Serializable
enum class NextEpisodePick {
    /** The release (binge group) of the episode just watched, then the [PickBy] rule. Remembered per show. */
    SAME_RELEASE_FIRST,

    /** The [PickBy] rule alone. */
    SAME_RULES_AS_PLAY,

    /** Open the source list at every episode. */
    ASK_ME,
}

/**
 * Where the local files row sits in Source priority, as far as a pick cares (see [PlaybackPickSettings.localPlacement]).
 */
enum class LocalFilesPick {
    /** No online source that may auto-play is above it: a title's local copy plays straight away, without a search. */
    FIRST,

    /** Below at least one online source: the local copy plays when no source above it has a stream. */
    LAST,

    /** Its auto-play switch is off: listed in the source list, never auto-played. */
    NEVER,
}

/**
 * Settings > Playback > What plays: the whole answer to "which stream plays, and when". See
 * `Docs/plans/playback-priority.md`. Replaces the stream selection mode, source scope, allowed addons and plugins, the
 * binge-group switches, "Apply to next episode", the local library's play action, and scoring's "choosing the first
 * stream" and "override binge group".
 */
@Serializable
data class PlaybackPickSettings(
    val onPlay: PlayClick = PlayClick.SHOW_SOURCES,
    val pickBy: PickBy = PickBy.PRIORITY_ORDER,
    val nextEpisode: NextEpisodePick = NextEpisodePick.SAME_RELEASE_FIRST,
    /** Source keys ([PlaybackSources]) in priority order; sources missing from it take their default place. */
    val order: List<String> = emptyList(),
    /** Per-source auto-play switches; a source not listed may auto-play (an addon: [newAddonsAutoPlay]). */
    val autoPlay: Map<String, Boolean> = emptyMap(),
    /** Whether an addon with no switch of its own may auto-play (false after migrating an "allowed addons" list). */
    val newAddonsAutoPlay: Boolean = true,
    /** Only streams matching this (case-insensitive regex) are auto-played; blank = no filter. */
    val regexFilter: String = "",
) {
    fun mayAutoPlay(key: String): Boolean = autoPlay[key] ?: if (PlaybackSources.isAddon(key)) newAddonsAutoPlay else true

    /**
     * Where the local files row sits for a pick: first when no source in [present] that may auto-play is above it
     * (absent sources and switched-off ones do not count), last when one is, never when its own switch is off.
     */
    fun localPlacement(present: Set<String>, addonNames: List<String>): LocalFilesPick {
        if (!mayAutoPlay(PlaybackSources.LOCAL)) return LocalFilesPick.NEVER
        val above = PlaybackSources.effectiveOrder(order, addonNames).takeWhile { it != PlaybackSources.LOCAL }
        return if (above.none { it in present && mayAutoPlay(it) }) LocalFilesPick.FIRST else LocalFilesPick.LAST
    }
}

/** The rows of the source priority list, and which row a stream or a source group belongs to. */
object PlaybackSources {
    const val LOCAL = "local"
    const val DEBRID = "debrid"
    const val SERVERS = "servers"
    const val USENET = "usenet"
    const val PLUGINS = "plugins"
    private const val ADDON_PREFIX = "addon:"

    private val LOCAL_GROUP_IDS = setOf("local-library", "locallibrary", "embedded")
    private const val USENET_GROUP_ID = "usenet:built-in"

    fun addon(name: String): String = ADDON_PREFIX + name
    fun isAddon(key: String): Boolean = key.startsWith(ADDON_PREFIX)
    fun addonName(key: String): String? = key.takeIf(::isAddon)?.removePrefix(ADDON_PREFIX)

    fun keyForGroup(addonId: String, addonName: String, installedAddonNames: Set<String>): String = when {
        addonId in LOCAL_GROUP_IDS -> LOCAL
        addonId.startsWith("debrid:") -> DEBRID
        addonId.startsWith("server:") -> SERVERS
        addonId == USENET_GROUP_ID -> USENET
        addonName in installedAddonNames -> addon(addonName)
        else -> PLUGINS
    }

    fun keyFor(stream: StreamItem, installedAddonNames: Set<String>): String = when {
        stream.serverTarget != null -> SERVERS
        stream.streamType.equals("local", ignoreCase = true) -> LOCAL
        else -> keyForGroup(stream.addonId, stream.addonName, installedAddonNames)
    }

    /**
     * The rows in priority order: [stored] first (entries for sources that are gone dropped), then any source it does
     * not mention yet. A new addon goes after the last addon listed (after the debrid row when none is); anything else
     * new goes at the end. The default, with nothing stored: debrid, the addons in their install order, media servers,
     * built-in Usenet, plugins, local files.
     */
    fun effectiveOrder(stored: List<String>, addonNames: List<String>): List<String> {
        val defaults = buildList {
            add(DEBRID)
            addonNames.distinct().forEach { add(addon(it)) }
            add(SERVERS)
            add(USENET)
            add(PLUGINS)
            add(LOCAL)
        }
        if (stored.isEmpty()) return defaults
        val result = stored.filter { it in defaults }.distinct().toMutableList()
        for (key in defaults) {
            if (key in result) continue
            if (isAddon(key)) {
                val anchor = result.indexOfLast(::isAddon).takeIf { it >= 0 } ?: result.indexOf(DEBRID)
                result.add(anchor + 1, key)
            } else {
                result.add(key)
            }
        }
        return result
    }

    /**
     * Orders source groups by the priority list, so what the source list shows is what auto-play walks. Groups in
     * [preferredGroupIds] (a media server whose "use its catalog" setting is on, or a title opened from a server's
     * catalog) go first; the rest keep their arrival order within a row.
     */
    fun orderGroups(
        groups: List<AddonStreamGroup>,
        installedAddonNames: List<String>,
        settings: PlaybackPickSettings,
        preferredGroupIds: Set<String> = emptySet(),
    ): List<AddonStreamGroup> {
        if (groups.isEmpty()) return groups
        val names = installedAddonNames.toSet()
        val rank = effectiveOrder(settings.order, installedAddonNames).withIndex().associate { it.value to it.index }
        return groups.withIndex().sortedWith(
            compareBy<IndexedValue<AddonStreamGroup>> { if (it.value.addonId in preferredGroupIds) 0 else 1 }
                .thenBy { (_, group) -> rank[keyForGroup(group.addonId, group.addonName, names)] ?: Int.MAX_VALUE }
                .thenBy { it.index },
        ).map { it.value }
    }

    /** The enabled addons that serve streams, by the name their streams carry, in install order. */
    fun installedStreamAddonNames(): List<String> = AddonRepository.uiState.value.addons.enabledAddons()
        .filter { addon -> addon.manifest?.resources?.any { it.name == "stream" } == true }
        .map { addon -> addon.displayTitle.ifBlank { addon.manifest?.name.orEmpty() } }
        .filter { it.isNotBlank() }
        .distinct()

    /** The rows this install has: local files, each addon, and debrid, servers, Usenet and plugins when set up. */
    fun presentKeys(addonNames: List<String> = installedStreamAddonNames()): Set<String> = buildSet {
        add(LOCAL)
        if (DebridSettingsRepository.snapshot().canResolvePlayableLinks) add(DEBRID)
        if (ServerRepository.enabledConnections().isNotEmpty()) add(SERVERS)
        if (UsenetSettingsRepository.sources.ready) add(USENET)
        if (AppFeaturePolicy.pluginsEnabled && PluginRepository.uiState.value.scrapers.any { it.enabled }) add(PLUGINS)
        addonNames.forEach { add(addon(it)) }
    }

    /** Where local files sit right now, with the current settings and sources. */
    fun currentLocalPlacement(): LocalFilesPick {
        val names = installedStreamAddonNames()
        return PlaybackPickSettingsRepository.current.localPlacement(presentKeys(names), names)
    }

    /** [orderGroups] with the current What plays settings, for the stream repositories. */
    fun orderAddonStreams(
        groups: List<AddonStreamGroup>,
        installedOrder: List<String>,
        preferredGroupIds: Set<String> = emptySet(),
    ): List<AddonStreamGroup> = orderGroups(groups, installedOrder, PlaybackPickSettingsRepository.current, preferredGroupIds)

    /**
     * Migrates the old scattered settings into [PlaybackPickSettings] so that what played before mostly plays now:
     *
     * - Manual -> Show me the sources; any automatic mode -> Play for me.
     * - Best score, or scoring "applied to the first stream" -> Best score; otherwise priority order. A regex mode's
     *   pattern becomes the auto-play filter.
     * - Manual with "Apply to next episode" -> Ask me; binge preference off, or scoring overriding it -> Same rules as
     *   Play; otherwise Same release first.
     * - The local library's "Local file" play action -> the local files row at the top; "Source picker" -> at the end.
     * - Source scope and the allowed lists -> the auto-play switches. Built-in Usenet counted as a plugin, and no
     *   plugin list ever offered it, so a plugin list switches it off, as it was.
     */
    fun migrate(
        player: PlayerSettingsUiState,
        localPreference: LocalLibraryPlaybackPreference,
        score: StreamScoreProfile,
        serversListedFirst: Boolean,
        /** Manifest name -> the display title streams carry; the old allowed-addons list stored manifest names. */
        addonTitlesByManifestName: Map<String, String> = emptyMap(),
    ): PlaybackPickSettings {
        val mode = player.streamAutoPlayMode
        val scored = mode == StreamAutoPlayMode.SCORED || score.appliesToFirstStream()
        val nextEpisode = when {
            mode == StreamAutoPlayMode.MANUAL && player.streamAutoPlayManualNextEpisode -> NextEpisodePick.ASK_ME
            !player.streamAutoPlayPreferBingeGroup -> NextEpisodePick.SAME_RULES_AS_PLAY
            scored && score.appliesToBingeGroupOverride() -> NextEpisodePick.SAME_RULES_AS_PLAY
            else -> NextEpisodePick.SAME_RELEASE_FIRST
        }
        val autoPlay = buildMap {
            when (player.streamAutoPlaySource) {
                StreamAutoPlaySource.ALL_SOURCES -> Unit
                StreamAutoPlaySource.INSTALLED_ADDONS_ONLY -> {
                    put(PLUGINS, false)
                    put(USENET, false)
                }
                StreamAutoPlaySource.ENABLED_PLUGINS_ONLY -> put(DEBRID, false)
            }
            if (player.streamAutoPlaySource != StreamAutoPlaySource.INSTALLED_ADDONS_ONLY &&
                player.streamAutoPlaySelectedPlugins.isNotEmpty()
            ) {
                put(USENET, false)
            }
            player.streamAutoPlaySelectedAddons.forEach { name ->
                put(addon(name), true)
                addonTitlesByManifestName[name]?.let { put(addon(it), true) }
            }
        }
        val addonsAllowed = player.streamAutoPlaySource != StreamAutoPlaySource.ENABLED_PLUGINS_ONLY &&
            player.streamAutoPlaySelectedAddons.isEmpty()
        return PlaybackPickSettings(
            onPlay = if (mode == StreamAutoPlayMode.MANUAL) PlayClick.SHOW_SOURCES else PlayClick.PLAY_FOR_ME,
            pickBy = if (scored) PickBy.BEST_SCORE else PickBy.PRIORITY_ORDER,
            nextEpisode = nextEpisode,
            order = buildList {
                if (localPreference == LocalLibraryPlaybackPreference.LOCAL_LIBRARY) add(LOCAL)
                if (serversListedFirst) {
                    add(SERVERS)
                    add(DEBRID)
                }
            },
            autoPlay = autoPlay,
            newAddonsAutoPlay = addonsAllowed,
            regexFilter = if (mode == StreamAutoPlayMode.REGEX_MATCH) player.streamAutoPlayRegex.trim() else "",
        )
    }
}

internal expect object PlaybackPickSettingsStorage {
    fun load(): String?
    fun save(value: String)
}

/** The one owner of [PlaybackPickSettings]; on first use it migrates the old settings. */
object PlaybackPickSettingsRepository {
    private val log = Logger.withTag("PlaybackPickSettings")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private var hasLoaded = false

    private val _uiState = MutableStateFlow(PlaybackPickSettings())
    val uiState: StateFlow<PlaybackPickSettings> = _uiState.asStateFlow()

    /** Builds the migrated settings when nothing is stored yet (replaceable in tests). */
    var migrationSource: () -> PlaybackPickSettings = {
        PlayerSettingsRepository.ensureLoaded()
        PlaybackSources.migrate(
            player = PlayerSettingsRepository.uiState.value,
            localPreference = LocalLibraryRepository.currentPlaybackPreference(),
            score = StreamScoreRepository.profile,
            serversListedFirst = ServerRepository.enabledConnections().any { it.useCatalogMetadata },
            addonTitlesByManifestName = AddonRepository.uiState.value.addons.mapNotNull { addon ->
                val manifestName = addon.manifest?.name ?: return@mapNotNull null
                manifestName to addon.displayTitle.ifBlank { manifestName }
            }.toMap(),
        )
    }

    val current: PlaybackPickSettings
        get() {
            ensureLoaded()
            return _uiState.value
        }

    fun ensureLoaded() {
        val needsMigration = synchronized(lock) {
            if (hasLoaded) return
            hasLoaded = true
            val stored = runCatching { PlaybackPickSettingsStorage.load() }.getOrNull()
            val decoded = stored?.takeIf { it.isNotBlank() }?.let { text ->
                runCatching { json.decodeFromString(PlaybackPickSettings.serializer(), text) }
                    .onFailure { log.w(it) { "Discarding unreadable playback pick settings" } }
                    .getOrNull()
            }
            if (decoded != null) _uiState.value = decoded
            decoded == null
        }
        if (needsMigration) {
            val migrated = runCatching(migrationSource)
                .onFailure { log.w(it) { "Migrating the old auto-play settings failed; using defaults" } }
                .getOrDefault(PlaybackPickSettings())
            log.i { "Migrated the old auto-play settings: $migrated" }
            update { migrated }
        }
    }

    /** Reloads from disk: the active profile changed. */
    fun reload() {
        synchronized(lock) { hasLoaded = false }
        _uiState.value = PlaybackPickSettings()
        ensureLoaded()
    }

    fun update(transform: (PlaybackPickSettings) -> PlaybackPickSettings) {
        synchronized(lock) {
            // Written even when unchanged: a migration to the defaults must still be stored, so it runs once.
            val next = transform(_uiState.value)
            _uiState.value = next
            runCatching { PlaybackPickSettingsStorage.save(json.encodeToString(PlaybackPickSettings.serializer(), next)) }
                .onFailure { log.w(it) { "Failed to save playback pick settings" } }
        }
    }

    fun setOnPlay(value: PlayClick) = update { it.copy(onPlay = value) }
    fun setPickBy(value: PickBy) = update { it.copy(pickBy = value) }
    fun setNextEpisode(value: NextEpisodePick) = update { it.copy(nextEpisode = value) }
    fun setRegexFilter(value: String) = update { it.copy(regexFilter = value.trim()) }
    fun setOrder(order: List<String>) = update { it.copy(order = order) }
    fun setAutoPlay(key: String, enabled: Boolean) = update { it.copy(autoPlay = it.autoPlay + (key to enabled)) }
}
