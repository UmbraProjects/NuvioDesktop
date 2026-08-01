package com.nuvio.app.features.details

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

enum class MetaScreenSectionKey {
    ACTIONS,
    OVERVIEW,
    PRODUCTION,
    CAST,
    COMMENTS,
    TRAILERS,
    EPISODES,
    DETAILS,
    COLLECTION,
    MORE_LIKE_THIS,
    ;

    
    val canBeTabbed: Boolean
        get() = this != ACTIONS && this != OVERVIEW
}

data class MetaScreenSectionItem(
    val key: MetaScreenSectionKey,
    val title: String,
    val description: String,
    val enabled: Boolean,
    val order: Int,
    val tabGroup: Int? = null,
)

data class MetaScreenSettingsUiState(
    val items: List<MetaScreenSectionItem> = emptyList(),
    val cinematicBackground: Boolean = false,
    val backgroundMode: MetaScreenBackgroundMode = MetaScreenBackgroundMode.Normal,
    val discoveryBadgesEnabled: Boolean = true,
    val heroTrailerPlayback: Boolean = false,
    val heroTrailerPlaybackMode: MetaHeroTrailerPlaybackMode = MetaHeroTrailerPlaybackMode.Hero,
    val heroTrailerDelaySeconds: Int = 5,
    val heroTrailerBackgroundMode: MetaHeroTrailerBackgroundMode = MetaHeroTrailerBackgroundMode.Black,
    // Mirrors the home page's "hero trailer sound" toggle: whether a NEW auto-played hero
    // trailer starts with sound by default. The user can still interactively mute/adjust
    // volume during playback; this only seeds a fresh trailer's starting state.
    val heroTrailerSoundEnabled: Boolean = false,
    val tabLayout: Boolean = false,
    val episodeCardStyle: MetaEpisodeCardStyle = MetaEpisodeCardStyle.Horizontal,
    val blurUnwatchedEpisodes: Boolean = false,
    val episodeRatingsEnabled: Boolean = true,
)

enum class MetaScreenBackgroundMode {
    Normal,
    Cinematic,
    DominantColor,
    ;

    companion object {
        fun parse(raw: String?): MetaScreenBackgroundMode? = when (raw?.lowercase()) {
            "normal", "default" -> Normal
            "cinematic", "blurred" -> Cinematic
            "dominant_color", "dominant-color", "dominantcolor", "dominant" -> DominantColor
            else -> null
        }

        fun persist(mode: MetaScreenBackgroundMode): String = when (mode) {
            Normal -> "normal"
            Cinematic -> "cinematic"
            DominantColor -> "dominant_color"
        }
    }
}

enum class MetaHeroTrailerPlaybackMode {
    Hero,
    Fullscreen,
    ;

    companion object {
        fun parse(raw: String?): MetaHeroTrailerPlaybackMode? = when (raw?.lowercase()) {
            "hero" -> Hero
            "fullscreen", "full_screen", "full-screen", "fs" -> Fullscreen
            else -> null
        }

        fun persist(mode: MetaHeroTrailerPlaybackMode): String = when (mode) {
            Hero -> "hero"
            Fullscreen -> "fullscreen"
        }
    }
}

/**
 * Background shown around an info-screen hero trailer while it plays. [Black] ("lights out") is the
 * default — including for users who never set anything. When the trailer ends the hero reverts to
 * its normal backdrop automatically (this only applies while a trailer is on screen).
 */
enum class MetaHeroTrailerBackgroundMode {
    Theme,
    Black,
    Backdrop,
    ;

    companion object {
        fun parse(raw: String?): MetaHeroTrailerBackgroundMode? = when (raw?.lowercase()) {
            "theme", "default", "app" -> Theme
            "black", "lightsout", "lights_out", "lights-out" -> Black
            "backdrop", "wash", "backdrop_wash" -> Backdrop
            else -> null
        }

        fun persist(mode: MetaHeroTrailerBackgroundMode): String = when (mode) {
            Theme -> "theme"
            Black -> "black"
            Backdrop -> "backdrop"
        }
    }
}

enum class MetaEpisodeCardStyle {
    Horizontal,
    List,
    ;

    companion object {
        fun parse(raw: String?): MetaEpisodeCardStyle? = when (raw?.lowercase()) {
            "horizontal" -> Horizontal
            "list" -> List
            else -> null
        }

        fun persist(style: MetaEpisodeCardStyle): String = when (style) {
            Horizontal -> "horizontal"
            List -> "list"
        }
    }
}

@Serializable
private data class StoredMetaScreenSectionPreference(
    val key: String,
    val enabled: Boolean = true,
    val order: Int = 0,
    val tabGroup: Int? = null,
)

@Serializable
private data class StoredMetaScreenSettingsPayload(
    val items: List<StoredMetaScreenSectionPreference> = emptyList(),
    val cinematicBackground: Boolean = false,
    @SerialName("background_mode")
    val backgroundMode: String? = null,
    @SerialName("discovery_badges_enabled")
    val discoveryBadgesEnabled: Boolean = true,
    @SerialName("hero_trailer_playback")
    val heroTrailerPlayback: Boolean = false,
    @SerialName("hero_trailer_playback_mode")
    val heroTrailerPlaybackMode: String = "hero",
    @SerialName("hero_trailer_delay_seconds")
    val heroTrailerDelaySeconds: Int = 5,
    @SerialName("hero_trailer_background_mode")
    val heroTrailerBackgroundMode: String = "black",
    @SerialName("hero_trailer_sound_enabled")
    val heroTrailerSoundEnabled: Boolean = false,
    @SerialName("tvStyleLayout")
    val tabLayout: Boolean = false,
    val episodeCardStyle: String = "horizontal",
    @SerialName("blur_unwatched_episodes")
    val blurUnwatchedEpisodes: Boolean = false,
    @SerialName("episode_ratings_enabled")
    val episodeRatingsEnabled: Boolean = true,
)

private data class MetaScreenSectionDefinition(
    val key: MetaScreenSectionKey,
    val titleRes: StringResource,
    val descriptionRes: StringResource,
)

object MetaScreenSettingsRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val definitions = listOf(
        MetaScreenSectionDefinition(
            key = MetaScreenSectionKey.ACTIONS,
            titleRes = Res.string.meta_section_actions_title,
            descriptionRes = Res.string.meta_section_actions_description,
        ),
        MetaScreenSectionDefinition(
            key = MetaScreenSectionKey.OVERVIEW,
            titleRes = Res.string.meta_section_overview_title,
            descriptionRes = Res.string.meta_section_overview_description,
        ),
        MetaScreenSectionDefinition(
            key = MetaScreenSectionKey.PRODUCTION,
            titleRes = Res.string.meta_section_production_title,
            descriptionRes = Res.string.meta_section_production_description,
        ),
        MetaScreenSectionDefinition(
            key = MetaScreenSectionKey.CAST,
            titleRes = Res.string.settings_meta_cast,
            descriptionRes = Res.string.meta_section_cast_description,
        ),
        MetaScreenSectionDefinition(
            key = MetaScreenSectionKey.COMMENTS,
            titleRes = Res.string.settings_meta_comments,
            descriptionRes = Res.string.meta_section_comments_description,
        ),
        MetaScreenSectionDefinition(
            key = MetaScreenSectionKey.TRAILERS,
            titleRes = Res.string.settings_meta_trailers,
            descriptionRes = Res.string.meta_section_trailers_description,
        ),
        MetaScreenSectionDefinition(
            key = MetaScreenSectionKey.EPISODES,
            titleRes = Res.string.settings_meta_episodes,
            descriptionRes = Res.string.meta_section_episodes_description,
        ),
        MetaScreenSectionDefinition(
            key = MetaScreenSectionKey.DETAILS,
            titleRes = Res.string.meta_section_details_title,
            descriptionRes = Res.string.meta_section_details_description,
        ),
        MetaScreenSectionDefinition(
            key = MetaScreenSectionKey.COLLECTION,
            titleRes = Res.string.meta_section_collection_title,
            descriptionRes = Res.string.meta_section_collection_description,
        ),
        MetaScreenSectionDefinition(
            key = MetaScreenSectionKey.MORE_LIKE_THIS,
            titleRes = Res.string.meta_section_more_like_this_title,
            descriptionRes = Res.string.meta_section_more_like_this_description,
        ),
    )

    private val _uiState = MutableStateFlow(MetaScreenSettingsUiState())
    val uiState: StateFlow<MetaScreenSettingsUiState> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var preferences: MutableMap<MetaScreenSectionKey, StoredMetaScreenSectionPreference> = mutableMapOf()
    private var cinematicBackground: Boolean = false
    private var backgroundMode: MetaScreenBackgroundMode = MetaScreenBackgroundMode.Normal
    private var discoveryBadgesEnabled: Boolean = true
    private var heroTrailerPlayback: Boolean = false
    private var heroTrailerPlaybackMode: MetaHeroTrailerPlaybackMode = MetaHeroTrailerPlaybackMode.Hero
    private var heroTrailerDelaySeconds: Int = 5
    private var heroTrailerBackgroundMode: MetaHeroTrailerBackgroundMode = MetaHeroTrailerBackgroundMode.Black
    private var heroTrailerSoundEnabled: Boolean = false
    private var tabLayout: Boolean = false
    private var episodeCardStyle: MetaEpisodeCardStyle = MetaEpisodeCardStyle.Horizontal
    private var blurUnwatchedEpisodes: Boolean = false
    private var episodeRatingsEnabled: Boolean = true
    private fun localizedString(resource: StringResource): String = runBlocking { getString(resource) }

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true

        val payload = MetaScreenSettingsStorage.loadPayload().orEmpty().trim()
        if (payload.isNotEmpty()) {
            val parsed = runCatching {
                json.decodeFromString<StoredMetaScreenSettingsPayload>(payload)
            }.getOrNull()
            if (parsed != null) {
                backgroundMode = MetaScreenBackgroundMode.parse(parsed.backgroundMode)
                    ?: if (parsed.cinematicBackground) MetaScreenBackgroundMode.DominantColor else MetaScreenBackgroundMode.Normal
                cinematicBackground = backgroundMode != MetaScreenBackgroundMode.Normal
                discoveryBadgesEnabled = parsed.discoveryBadgesEnabled
                heroTrailerPlayback = parsed.heroTrailerPlayback
                heroTrailerPlaybackMode = MetaHeroTrailerPlaybackMode.parse(parsed.heroTrailerPlaybackMode)
                    ?: MetaHeroTrailerPlaybackMode.Hero
                heroTrailerDelaySeconds = parsed.heroTrailerDelaySeconds.coerceIn(0, 15)
                heroTrailerBackgroundMode = MetaHeroTrailerBackgroundMode.parse(parsed.heroTrailerBackgroundMode)
                    ?: MetaHeroTrailerBackgroundMode.Black
                heroTrailerSoundEnabled = parsed.heroTrailerSoundEnabled
                tabLayout = false
                episodeCardStyle = MetaEpisodeCardStyle.Horizontal
                blurUnwatchedEpisodes = parsed.blurUnwatchedEpisodes
                episodeRatingsEnabled = parsed.episodeRatingsEnabled
                preferences = parsed.items.mapNotNull { item ->
                    val key = runCatching { MetaScreenSectionKey.valueOf(item.key) }.getOrNull() ?: return@mapNotNull null
                    key to item
                }.toMap().toMutableMap()
            }
        }

        normalizePreferences()
        publish()
        persist()
    }

    fun onProfileChanged() {
        hasLoaded = false
        preferences.clear()
        cinematicBackground = false
        backgroundMode = MetaScreenBackgroundMode.Normal
        discoveryBadgesEnabled = true
        heroTrailerPlayback = false
        heroTrailerPlaybackMode = MetaHeroTrailerPlaybackMode.Hero
        heroTrailerDelaySeconds = 5
        heroTrailerBackgroundMode = MetaHeroTrailerBackgroundMode.Black
        heroTrailerSoundEnabled = false
        tabLayout = false
        episodeCardStyle = MetaEpisodeCardStyle.Horizontal
        blurUnwatchedEpisodes = false
        episodeRatingsEnabled = true
        _uiState.value = MetaScreenSettingsUiState()
        ensureLoaded()
    }

    fun setCinematicBackground(enabled: Boolean) {
        ensureLoaded()
        backgroundMode = if (enabled) MetaScreenBackgroundMode.DominantColor else MetaScreenBackgroundMode.Normal
        cinematicBackground = enabled
        publish()
        persist()
    }

    fun setBackgroundMode(mode: MetaScreenBackgroundMode) {
        ensureLoaded()
        backgroundMode = mode
        cinematicBackground = mode != MetaScreenBackgroundMode.Normal
        publish()
        persist()
    }

    fun setDiscoveryBadgesEnabled(enabled: Boolean) {
        ensureLoaded()
        discoveryBadgesEnabled = enabled
        publish()
        persist()
    }

    fun setHeroTrailerPlayback(enabled: Boolean) {
        ensureLoaded()
        heroTrailerPlayback = enabled
        publish()
        persist()
    }

    fun setHeroTrailerPlaybackMode(mode: MetaHeroTrailerPlaybackMode) {
        ensureLoaded()
        heroTrailerPlaybackMode = mode
        publish()
        persist()
    }

    fun setHeroTrailerDelaySeconds(seconds: Int) {
        ensureLoaded()
        // 0 == "Manual": the hero trailer never auto-plays, but a trailer click still plays
        // it in the hero.
        heroTrailerDelaySeconds = seconds.coerceIn(0, 15)
        publish()
        persist()
    }

    fun setHeroTrailerBackgroundMode(mode: MetaHeroTrailerBackgroundMode) {
        ensureLoaded()
        heroTrailerBackgroundMode = mode
        publish()
        persist()
    }

    fun setHeroTrailerSoundEnabled(enabled: Boolean) {
        ensureLoaded()
        heroTrailerSoundEnabled = enabled
        publish()
        persist()
    }

    fun setTabLayout(enabled: Boolean) {
        ensureLoaded()
        tabLayout = false
        publish()
        persist()
    }

    fun setEpisodeCardStyle(style: MetaEpisodeCardStyle) {
        ensureLoaded()
        episodeCardStyle = MetaEpisodeCardStyle.Horizontal
        publish()
        persist()
    }

    fun setBlurUnwatchedEpisodes(enabled: Boolean) {
        ensureLoaded()
        blurUnwatchedEpisodes = enabled
        publish()
        persist()
    }

    fun setEpisodeRatingsEnabled(enabled: Boolean) {
        ensureLoaded()
        episodeRatingsEnabled = enabled
        publish()
        persist()
    }

    fun setTabGroup(key: MetaScreenSectionKey, groupId: Int?) {
        ensureLoaded()
        normalizePreferences()
        publish()
        persist()
    }

    fun clearLocalState() {
        hasLoaded = false
        preferences.clear()
        cinematicBackground = false
        backgroundMode = MetaScreenBackgroundMode.Normal
        discoveryBadgesEnabled = true
        heroTrailerPlayback = false
        heroTrailerPlaybackMode = MetaHeroTrailerPlaybackMode.Hero
        heroTrailerDelaySeconds = 5
        heroTrailerBackgroundMode = MetaHeroTrailerBackgroundMode.Black
        heroTrailerSoundEnabled = false
        tabLayout = false
        episodeCardStyle = MetaEpisodeCardStyle.Horizontal
        blurUnwatchedEpisodes = false
        episodeRatingsEnabled = true
        _uiState.value = MetaScreenSettingsUiState()
    }

    internal fun applyFromSync(
        items: List<MetaScreenSectionItem>,
        cinematicBackground: Boolean,
        discoveryBadgesEnabled: Boolean = true,
        heroTrailerPlayback: Boolean = false,
        heroTrailerPlaybackMode: MetaHeroTrailerPlaybackMode = MetaHeroTrailerPlaybackMode.Hero,
        heroTrailerDelaySeconds: Int = 5,
        heroTrailerBackgroundMode: MetaHeroTrailerBackgroundMode = MetaHeroTrailerBackgroundMode.Black,
        heroTrailerSoundEnabled: Boolean = false,
        tabLayout: Boolean,
        episodeCardStyle: MetaEpisodeCardStyle = MetaEpisodeCardStyle.Horizontal,
        blurUnwatchedEpisodes: Boolean = false,
    ) {
        ensureLoaded()
        this.backgroundMode = if (cinematicBackground) MetaScreenBackgroundMode.DominantColor else MetaScreenBackgroundMode.Normal
        this.cinematicBackground = cinematicBackground
        this.discoveryBadgesEnabled = discoveryBadgesEnabled
        this.heroTrailerPlayback = heroTrailerPlayback
        this.heroTrailerPlaybackMode = heroTrailerPlaybackMode
        this.heroTrailerDelaySeconds = heroTrailerDelaySeconds.coerceIn(0, 15)
        this.heroTrailerBackgroundMode = heroTrailerBackgroundMode
        this.heroTrailerSoundEnabled = heroTrailerSoundEnabled
        this.tabLayout = false
        this.episodeCardStyle = MetaEpisodeCardStyle.Horizontal
        this.blurUnwatchedEpisodes = blurUnwatchedEpisodes
        preferences = items.associate { item ->
            item.key to StoredMetaScreenSectionPreference(
                key = item.key.name,
                enabled = item.enabled,
                order = item.order,
                tabGroup = item.tabGroup,
            )
        }.toMutableMap()
        normalizePreferences()
        publish()
        persist()
    }

    fun setEnabled(key: MetaScreenSectionKey, enabled: Boolean) {
        ensureLoaded()
        normalizePreferences()
        publish()
        persist()
    }

    fun resetToDefaults() {
        ensureLoaded()
        preferences.clear()
        cinematicBackground = false
        backgroundMode = MetaScreenBackgroundMode.Normal
        discoveryBadgesEnabled = true
        heroTrailerPlayback = false
        heroTrailerPlaybackMode = MetaHeroTrailerPlaybackMode.Hero
        heroTrailerDelaySeconds = 5
        heroTrailerBackgroundMode = MetaHeroTrailerBackgroundMode.Black
        heroTrailerSoundEnabled = false
        tabLayout = false
        episodeCardStyle = MetaEpisodeCardStyle.Horizontal
        blurUnwatchedEpisodes = false
        episodeRatingsEnabled = true
        normalizePreferences()
        publish()
        persist()
    }

    fun moveByIndex(fromIndex: Int, toIndex: Int) {
        ensureLoaded()
        normalizePreferences()
        publish()
        persist()
    }

    private fun updatePreference(
        key: MetaScreenSectionKey,
        transform: (StoredMetaScreenSectionPreference) -> StoredMetaScreenSectionPreference,
    ) {
        ensureLoaded()
        val current = preferences[key] ?: return
        preferences[key] = transform(current)
        publish()
        persist()
    }

    private fun normalizePreferences() {
        val normalized = mutableMapOf<MetaScreenSectionKey, StoredMetaScreenSectionPreference>()
        definitions.forEachIndexed { index, definition ->
            normalized[definition.key] = StoredMetaScreenSectionPreference(
                key = definition.key.name,
                enabled = true,
                order = index,
                tabGroup = null,
            )
        }
        preferences = normalized
    }

    private fun publish() {
        _uiState.value = MetaScreenSettingsUiState(
            items = definitions
                .sortedBy { definition -> preferences[definition.key]?.order ?: Int.MAX_VALUE }
                .map { definition ->
                    val preference = preferences[definition.key]
                    MetaScreenSectionItem(
                        key = definition.key,
                        title = localizedString(definition.titleRes),
                        description = localizedString(definition.descriptionRes),
                        enabled = preference?.enabled ?: true,
                        order = preference?.order ?: 0,
                        tabGroup = preference?.tabGroup,
                    )
                },
            cinematicBackground = cinematicBackground,
            backgroundMode = backgroundMode,
            discoveryBadgesEnabled = discoveryBadgesEnabled,
            heroTrailerPlayback = heroTrailerPlayback,
            heroTrailerPlaybackMode = heroTrailerPlaybackMode,
            heroTrailerDelaySeconds = heroTrailerDelaySeconds,
            heroTrailerBackgroundMode = heroTrailerBackgroundMode,
            heroTrailerSoundEnabled = heroTrailerSoundEnabled,
            tabLayout = false,
            episodeCardStyle = MetaEpisodeCardStyle.Horizontal,
            blurUnwatchedEpisodes = blurUnwatchedEpisodes,
            episodeRatingsEnabled = episodeRatingsEnabled,
        )
    }

    private fun persist() {
        MetaScreenSettingsStorage.savePayload(
            json.encodeToString(
                StoredMetaScreenSettingsPayload(
                    items = preferences.values.sortedBy { it.order },
                    cinematicBackground = cinematicBackground,
                    backgroundMode = MetaScreenBackgroundMode.persist(backgroundMode),
                    discoveryBadgesEnabled = discoveryBadgesEnabled,
                    heroTrailerPlayback = heroTrailerPlayback,
                    heroTrailerPlaybackMode = MetaHeroTrailerPlaybackMode.persist(heroTrailerPlaybackMode),
                    heroTrailerDelaySeconds = heroTrailerDelaySeconds,
                    heroTrailerBackgroundMode = MetaHeroTrailerBackgroundMode.persist(heroTrailerBackgroundMode),
                    heroTrailerSoundEnabled = heroTrailerSoundEnabled,
                    tabLayout = false,
                    episodeCardStyle = MetaEpisodeCardStyle.persist(MetaEpisodeCardStyle.Horizontal),
                    blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                    episodeRatingsEnabled = episodeRatingsEnabled,
                ),
            ),
        )
    }
}
