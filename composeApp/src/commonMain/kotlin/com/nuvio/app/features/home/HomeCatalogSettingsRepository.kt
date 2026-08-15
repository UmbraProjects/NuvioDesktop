package com.nuvio.app.features.home

import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.collection.Collection
import com.nuvio.app.features.collection.CollectionRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

private const val DEFAULT_HERO_INFO_PRIORITY =
    "wins,gg_wins,festival,pic_noms,gg_noms,emmy_noms,studio,director,trending,cult,foreign,new_release,metacritic,true_story,stinger,short_film,mini_series,binge_ready,release_status"
/**
 * Badge slots that were added after the setting shipped, so saved priority strings predate them.
 *
 * Each is inserted into the saved string exactly once and then recorded, because a slot that is
 * re-added whenever it is missing can never be switched off: the settings page edits the saved
 * string, and the next read puts the slot straight back. [slot] is placed after the first [after]
 * anchor present, or appended.
 */
internal data class HeroInfoPrioritySlotMigration(
    val slot: String,
    val after: List<String>,
)

internal val HERO_INFO_PRIORITY_SLOT_MIGRATIONS = listOf(
    HeroInfoPrioritySlotMigration(slot = "emmy_noms", after = listOf("gg_noms", "pic_noms")),
    HeroInfoPrioritySlotMigration(slot = "stinger", after = listOf("true_story")),
)

internal data class HeroInfoPriorityMigrationResult(
    val priority: String,
    val appliedMigrations: Set<String>,
    val changed: Boolean,
)

internal fun migrateHeroInfoPrioritySlots(
    priority: String,
    appliedMigrations: Set<String>,
    migrations: List<HeroInfoPrioritySlotMigration> = HERO_INFO_PRIORITY_SLOT_MIGRATIONS,
): HeroInfoPriorityMigrationResult {
    val applied = appliedMigrations.toMutableSet()
    val slots = priority
        .split(',')
        .map(String::trim)
        .filter(String::isNotBlank)
        .toMutableList()
    var changed = false
    for (migration in migrations) {
        if (!applied.add(migration.slot)) continue
        changed = true
        if (migration.slot in slots) continue
        val insertIndex = migration.after
            .firstNotNullOfOrNull { anchor -> slots.indexOf(anchor).takeIf { it >= 0 } }
            ?.let { it + 1 }
            ?: slots.size
        slots.add(insertIndex, migration.slot)
    }
    return HeroInfoPriorityMigrationResult(
        priority = if (changed) slots.joinToString(",") else priority,
        appliedMigrations = applied,
        changed = changed,
    )
}

private const val HERO_INFO_LINES_MIN = 0
private const val HERO_INFO_LINES_MAX = 6
private const val HERO_BADGE_SCALE_MIN = 1f
private const val HERO_BADGE_SCALE_MAX = 2.5f
private const val ADAPTIVE_HERO_VERTICAL_BIAS_MIN = -1f
private const val ADAPTIVE_HERO_VERTICAL_BIAS_MAX = 1f
private const val ADAPTIVE_HERO_VERTICAL_BIAS_DEFAULT = -0.58f
private const val ADAPTIVE_HERO_HEIGHT_MULTIPLIER_MIN = 0.75f
private const val ADAPTIVE_HERO_HEIGHT_MULTIPLIER_MAX = 1.75f
private const val ADAPTIVE_HERO_HEIGHT_MULTIPLIER_DEFAULT = 1.25f

data class HomeCatalogSettingsItem(
    val key: String,
    val defaultTitle: String,
    val addonName: String,
    val customTitle: String = "",
    val markerColor: HomeCatalogMarkerColor? = null,
    val enabled: Boolean = true,
    val heroSourceEnabled: Boolean = true,
    val order: Int = 0,
    val isCollection: Boolean = false,
    val collectionId: String? = null,
    val isPinnedToTop: Boolean = false,
    // Collections only: whether the collection or at least one folder has curated hero art.
    val hasHeroBackdrop: Boolean = false,
) {
    val displayTitle: String
        get() = customTitle.ifBlank { defaultTitle }
}

data class HomeCatalogSettingsUiState(
    val heroEnabled: Boolean = true,
    val heroInfoLines: Int = 2,
    val heroInfoPriority: String = DEFAULT_HERO_INFO_PRIORITY,
    val heroBadgePlacement: HeroBadgePlacement = HeroBadgePlacement.BottomBackdrop,
    val heroBadgeScale: Float = 1f,
    val heroReleaseStatusUnavailableOnly: Boolean = true,
    val hideUnreleasedContent: Boolean = false,
    val hideCatalogUnderline: Boolean = false,
    val adaptiveHeroEnabled: Boolean = false,
    val adaptiveHeroVerticalBias: Float = ADAPTIVE_HERO_VERTICAL_BIAS_DEFAULT,
    val adaptiveHeroHeightMultiplier: Float = ADAPTIVE_HERO_HEIGHT_MULTIPLIER_DEFAULT,
    val heroAmbientBackgroundEnabled: Boolean = false,
    val tvModeEnabled: Boolean = false,
    val smoothScrollingEnabled: Boolean = true,
    val catalogSeeMoreEnabled: Boolean = false,
    val catalogRowNumbersEnabled: Boolean = false,
    val tvRowDotsEnabled: Boolean = false,
    val tvRowDotsAnchor: HomeTvRowDotsAnchor = HomeTvRowDotsAnchor.RowTitle,
    val randomPlayEnabled: Boolean = false,
    val randomPlayIncludeCollections: Boolean = false,
    val randomPlayCategories: Set<RandomPlayCategory> = RandomPlayCategory.entries.toSet(),
    val randomPlayGenres: Set<String> = RandomPlayGenres.toSet(),
    val randomPlayMinimumImdbRating: Float = 0f,
    val randomPlayAction: RandomPlayAction = RandomPlayAction.Details,
    val items: List<HomeCatalogSettingsItem> = emptyList(),
) {
    val signature: String
        get() = buildString {
            append(heroEnabled)
            append('|')
            append(heroInfoLines)
            append('|')
            append(heroInfoPriority)
            append('|')
            append(heroBadgePlacement)
            append('|')
            append(heroBadgeScale)
            append('|')
            append(heroReleaseStatusUnavailableOnly)
            append('|')
            append(hideUnreleasedContent)
            append('|')
            append(hideCatalogUnderline)
            append('|')
            append(adaptiveHeroEnabled)
            append('|')
            append(adaptiveHeroVerticalBias)
            append('|')
            append(adaptiveHeroHeightMultiplier)
            append('|')
            append(heroAmbientBackgroundEnabled)
            append('|')
            append(tvModeEnabled)
            append('|')
            append(smoothScrollingEnabled)
            append('|')
            append(catalogSeeMoreEnabled)
            append('|')
            append(catalogRowNumbersEnabled)
            append('|')
            append(tvRowDotsEnabled)
            append('|')
            append(tvRowDotsAnchor)
            append('|')
            append(randomPlayEnabled)
            append('|')
            append(randomPlayIncludeCollections)
            append('|')
            append(randomPlayCategories.joinToString())
            append('|')
            append(randomPlayGenres.joinToString())
            append('|')
            append(randomPlayMinimumImdbRating)
            append('|')
            append(randomPlayAction)
            append('|')
            append(
                items.joinToString(separator = "|") { item ->
                    "${item.key}:${item.order}:${item.enabled}:${item.heroSourceEnabled}:${item.customTitle}:${item.markerColor}"
                }
            )
        }
}

internal data class HomeCatalogPreference(
    val customTitle: String,
    val enabled: Boolean,
    val heroSourceEnabled: Boolean,
    val order: Int,
)

internal data class HomeCatalogSettingsSnapshot(
    val heroEnabled: Boolean,
    val heroInfoLines: Int,
    val heroInfoPriority: String,
    val heroBadgePlacement: HeroBadgePlacement,
    val heroBadgeScale: Float,
    val heroReleaseStatusUnavailableOnly: Boolean,
    val hideUnreleasedContent: Boolean,
    val hideCatalogUnderline: Boolean,
    val adaptiveHeroEnabled: Boolean,
    val adaptiveHeroVerticalBias: Float,
    val adaptiveHeroHeightMultiplier: Float,
    val heroAmbientBackgroundEnabled: Boolean,
    val tvModeEnabled: Boolean,
    val randomPlayEnabled: Boolean,
    val randomPlayIncludeCollections: Boolean,
    val randomPlayCategories: Set<RandomPlayCategory>,
    val randomPlayGenres: Set<String>,
    val randomPlayMinimumImdbRating: Float,
    val randomPlayAction: RandomPlayAction,
    val preferences: Map<String, HomeCatalogPreference>,
)

/** Where TV Mode's row-jump dots sit. See HomeTvRowDotStrip. */
@Serializable
enum class HomeTvRowDotsAnchor {
    /** On the shelf's title line, centred on the window. */
    @SerialName("row_title")
    RowTitle,

    /** Over the backdrop, in the slot the "Bottom of backdrop" hero badges occupy. */
    @SerialName("hero_backdrop")
    HeroBackdrop,
}

@Serializable
enum class HeroBadgePlacement {
    @SerialName("bottom_backdrop")
    BottomBackdrop,

    @SerialName("top_right_horizontal")
    TopRightHorizontal,

    @SerialName("top_right_vertical")
    TopRightVertical,
}

@Serializable
private data class StoredHomeCatalogPreference(
    val key: String,
    val customTitle: String = "",
    val markerColor: HomeCatalogMarkerColor? = null,
    val enabled: Boolean = true,
    val heroSourceEnabled: Boolean = true,
    val order: Int = 0,
)

@Serializable
private data class StoredHomeCatalogSettingsPayload(
    val heroEnabled: Boolean = true,
    val heroInfoLines: Int = 2,
    val heroInfoPriority: String = DEFAULT_HERO_INFO_PRIORITY,
    val heroInfoPrioritySlotMigrations: Set<String> = emptySet(),
    val heroBadgePlacement: HeroBadgePlacement = HeroBadgePlacement.BottomBackdrop,
    val heroBadgeScale: Float = 1f,
    val heroReleaseStatusUnavailableOnly: Boolean = true,
    val hideUnreleasedContent: Boolean = false,
    val hideCatalogUnderline: Boolean = false,
    @SerialName("tvModeEnabled")
    val adaptiveHeroEnabled: Boolean = false,
    val adaptiveHeroVerticalBias: Float = -0.58f,
    val adaptiveHeroHeightMultiplier: Float = 1.25f,
    val heroAmbientBackgroundEnabled: Boolean = false,
    @SerialName("immersiveCatalogModeEnabled")
    val tvModeEnabled: Boolean = false,
    val smoothScrollingEnabled: Boolean = true,
    val catalogSeeMoreEnabled: Boolean = false,
    val catalogRowNumbersEnabled: Boolean = false,
    val tvRowDotsEnabled: Boolean = false,
    val tvRowDotsAnchor: HomeTvRowDotsAnchor = HomeTvRowDotsAnchor.RowTitle,
    val randomPlayEnabled: Boolean = false,
    val randomPlayIncludeCollections: Boolean = false,
    val randomPlayCategories: Set<RandomPlayCategory> = RandomPlayCategory.entries.toSet(),
    val randomPlayGenres: Set<String> = RandomPlayGenres.toSet(),
    // False in every payload written before the anime genres joined the allow-list; see
    // [expandLegacyRandomPlayGenres].
    val randomPlayAnimeGenresMigrated: Boolean = false,
    val randomPlayMinimumImdbRating: Float = 0f,
    val randomPlayAction: RandomPlayAction = RandomPlayAction.Details,
    val items: List<StoredHomeCatalogPreference> = emptyList(),
)

object HomeCatalogSettingsRepository {
    const val HERO_SOURCE_SELECTION_LIMIT = 2

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _uiState = MutableStateFlow(HomeCatalogSettingsUiState())
    val uiState: StateFlow<HomeCatalogSettingsUiState> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var definitions: List<HomeCatalogDefinition> = emptyList()
    private var collectionDefinitions: List<CollectionCatalogDefinition> = emptyList()
    private var preferences: MutableMap<String, StoredHomeCatalogPreference> = mutableMapOf()
    private var heroEnabled = true
    private var heroInfoLines = 2
    private var heroInfoPriority = DEFAULT_HERO_INFO_PRIORITY
    private var heroInfoPrioritySlotMigrations: Set<String> = emptySet()
    private var heroBadgePlacement = HeroBadgePlacement.BottomBackdrop
    private var heroBadgeScale = 1f
    private var heroReleaseStatusUnavailableOnly = true
    private var hideUnreleasedContent = false
    private var hideCatalogUnderline = false
    private var adaptiveHeroEnabled = false
    private var adaptiveHeroVerticalBias = ADAPTIVE_HERO_VERTICAL_BIAS_DEFAULT
    private var adaptiveHeroHeightMultiplier = ADAPTIVE_HERO_HEIGHT_MULTIPLIER_DEFAULT
    private var heroAmbientBackgroundEnabled = false
    private var tvModeEnabled = false
    private var smoothScrollingEnabled = true
    private var catalogSeeMoreEnabled = false
    private var catalogRowNumbersEnabled = false
    private var tvRowDotsEnabled = false
    private var tvRowDotsAnchor = HomeTvRowDotsAnchor.RowTitle
    private var randomPlayEnabled = false
    private var randomPlayIncludeCollections = false
    private var randomPlayCategories = RandomPlayCategory.entries.toSet()
    private var randomPlayGenres = RandomPlayGenres.toSet()
    private var randomPlayMinimumImdbRating = 0f
    private var randomPlayAction = RandomPlayAction.Details

    fun onProfileChanged() {
        hasLoaded = false
        preferences.clear()
        heroEnabled = true
        heroInfoLines = 2
        heroInfoPriority = DEFAULT_HERO_INFO_PRIORITY
        // The default string already contains every migrated slot, so nothing is outstanding.
        heroInfoPrioritySlotMigrations = HERO_INFO_PRIORITY_SLOT_MIGRATIONS.mapTo(mutableSetOf()) { it.slot }
        heroBadgePlacement = HeroBadgePlacement.BottomBackdrop
        heroBadgeScale = 1f
        heroReleaseStatusUnavailableOnly = true
        hideUnreleasedContent = false
        hideCatalogUnderline = false
        adaptiveHeroEnabled = false
        adaptiveHeroVerticalBias = ADAPTIVE_HERO_VERTICAL_BIAS_DEFAULT
        adaptiveHeroHeightMultiplier = ADAPTIVE_HERO_HEIGHT_MULTIPLIER_DEFAULT
        heroAmbientBackgroundEnabled = false
        tvModeEnabled = false
        smoothScrollingEnabled = true
        catalogSeeMoreEnabled = false
        catalogRowNumbersEnabled = false
        tvRowDotsEnabled = false
        tvRowDotsAnchor = HomeTvRowDotsAnchor.RowTitle
        resetRandomPlaySettings()
        definitions = emptyList()
        collectionDefinitions = emptyList()
        lastSyncedCatalogKeys = null
        _uiState.value = HomeCatalogSettingsUiState()
    }

    fun clearLocalState() {
        hasLoaded = false
        definitions = emptyList()
        collectionDefinitions = emptyList()
        lastSyncedCatalogKeys = null
        preferences.clear()
        heroEnabled = true
        heroInfoLines = 2
        heroInfoPriority = DEFAULT_HERO_INFO_PRIORITY
        // The default string already contains every migrated slot, so nothing is outstanding.
        heroInfoPrioritySlotMigrations = HERO_INFO_PRIORITY_SLOT_MIGRATIONS.mapTo(mutableSetOf()) { it.slot }
        heroBadgePlacement = HeroBadgePlacement.BottomBackdrop
        heroBadgeScale = 1f
        heroReleaseStatusUnavailableOnly = true
        hideUnreleasedContent = false
        hideCatalogUnderline = false
        adaptiveHeroEnabled = false
        adaptiveHeroVerticalBias = ADAPTIVE_HERO_VERTICAL_BIAS_DEFAULT
        adaptiveHeroHeightMultiplier = ADAPTIVE_HERO_HEIGHT_MULTIPLIER_DEFAULT
        heroAmbientBackgroundEnabled = false
        tvModeEnabled = false
        smoothScrollingEnabled = true
        catalogSeeMoreEnabled = false
        catalogRowNumbersEnabled = false
        tvRowDotsEnabled = false
        tvRowDotsAnchor = HomeTvRowDotsAnchor.RowTitle
        resetRandomPlaySettings()
        _uiState.value = HomeCatalogSettingsUiState()
    }

    // Key set from the last syncCatalogs pass — used to force-refresh only when the catalog
    // set actually changed, not on every home (re)entry.
    private var lastSyncedCatalogKeys: List<String>? = null

    fun syncCatalogs(addons: List<ManagedAddon>) {
        ensureLoaded()
        definitions = buildHomeCatalogDefinitions(addons)
        collectionDefinitions = buildCollectionDefinitions(CollectionRepository.collections.value)
        if (definitions.isEmpty() && collectionDefinitions.isEmpty()) {
            publish()
            return
        }
        normalizePreferences()
        enforcePinnedCollectionsAtTop()
        publish()
        persist()
        // HomeScreen re-runs syncCatalogs every time the home screen enters composition. A
        // force refresh must only happen when the catalog set itself changed (addon
        // installed/removed, new genre-defaulted catalog) — an unconditional force refetched
        // every catalog from every addon on each return to home.
        val catalogKeys = definitions.map(HomeCatalogDefinition::key) +
            collectionDefinitions.map(CollectionCatalogDefinition::key)
        val catalogSetChanged = lastSyncedCatalogKeys != catalogKeys
        lastSyncedCatalogKeys = catalogKeys
        HomeRepository.refresh(addons.enabledAddons(), force = catalogSetChanged)
    }

    fun syncCollections(collections: List<Collection>) {
        ensureLoaded()
        collectionDefinitions = buildCollectionDefinitions(collections)
        normalizePreferences()
        enforcePinnedCollectionsAtTop()
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }

    internal fun snapshot(): HomeCatalogSettingsSnapshot {
        ensureLoaded()
        return HomeCatalogSettingsSnapshot(
            heroEnabled = heroEnabled,
            heroInfoLines = heroInfoLines,
            heroInfoPriority = heroInfoPriority,
            heroBadgePlacement = heroBadgePlacement,
            heroBadgeScale = heroBadgeScale,
            heroReleaseStatusUnavailableOnly = heroReleaseStatusUnavailableOnly,
            hideUnreleasedContent = hideUnreleasedContent,
            hideCatalogUnderline = hideCatalogUnderline,
            adaptiveHeroEnabled = adaptiveHeroEnabled,
            adaptiveHeroVerticalBias = adaptiveHeroVerticalBias,
            adaptiveHeroHeightMultiplier = adaptiveHeroHeightMultiplier,
            heroAmbientBackgroundEnabled = heroAmbientBackgroundEnabled,
            tvModeEnabled = tvModeEnabled,
            randomPlayEnabled = randomPlayEnabled,
            randomPlayIncludeCollections = randomPlayIncludeCollections,
            randomPlayCategories = randomPlayCategories,
            randomPlayGenres = randomPlayGenres,
            randomPlayMinimumImdbRating = randomPlayMinimumImdbRating,
            randomPlayAction = randomPlayAction,
            preferences = preferences.mapValues { (_, value) ->
                HomeCatalogPreference(
                    customTitle = value.customTitle,
                    enabled = value.enabled,
                    heroSourceEnabled = value.heroSourceEnabled,
                    order = value.order,
                )
            },
        )
    }

    fun setHeroEnabled(enabled: Boolean) {
        ensureLoaded()
        heroEnabled = enabled
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }


    fun setHeroInfoPriority(priority: String) {
        if (heroInfoPriority == priority) return
        heroInfoPriority = priority
        publish()
        persist()
    }
    fun setHeroInfoLines(lines: Int) {
        ensureLoaded()
        val normalizedLines = lines.coerceIn(HERO_INFO_LINES_MIN, HERO_INFO_LINES_MAX)
        if (heroInfoLines == normalizedLines) return
        heroInfoLines = normalizedLines
        publish()
        persist()
    }

    fun setHeroBadgePlacement(placement: HeroBadgePlacement) {
        ensureLoaded()
        if (heroBadgePlacement == placement) return
        heroBadgePlacement = placement
        publish()
        persist()
    }

    fun setHeroBadgeScale(scale: Float) {
        ensureLoaded()
        val normalized = normalizeHeroBadgeScale(scale)
        if (heroBadgeScale == normalized) return
        heroBadgeScale = normalized
        publish()
        persist()
    }

    fun setHeroReleaseStatusUnavailableOnly(enabled: Boolean) {
        ensureLoaded()
        if (heroReleaseStatusUnavailableOnly == enabled) return
        heroReleaseStatusUnavailableOnly = enabled
        publish()
        persist()
    }

    fun setHideUnreleasedContent(enabled: Boolean) {
        ensureLoaded()
        if (hideUnreleasedContent == enabled) return
        hideUnreleasedContent = enabled
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }

    fun setHideCatalogUnderline(enabled: Boolean) {
        ensureLoaded()
        if (hideCatalogUnderline == enabled) return
        hideCatalogUnderline = enabled
        publish()
        persist()
    }

    fun setAdaptiveHeroEnabled(enabled: Boolean) {
        ensureLoaded()
        if (adaptiveHeroEnabled == enabled && !(enabled && tvModeEnabled)) return
        adaptiveHeroEnabled = enabled
        if (enabled) {
            tvModeEnabled = false
        }
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }

    fun setAdaptiveHeroVerticalBias(bias: Float) {
        ensureLoaded()
        val normalized = normalizeAdaptiveHeroVerticalBias(bias)
        if (adaptiveHeroVerticalBias == normalized) return
        adaptiveHeroVerticalBias = normalized
        publish()
        persist()
    }

    fun setAdaptiveHeroHeightMultiplier(multiplier: Float) {
        ensureLoaded()
        val normalized = normalizeAdaptiveHeroHeightMultiplier(multiplier)
        if (adaptiveHeroHeightMultiplier == normalized) return
        adaptiveHeroHeightMultiplier = normalized
        publish()
        persist()
    }

    fun setHeroAmbientBackgroundEnabled(enabled: Boolean) {
        ensureLoaded()
        val next = enabled && !tvModeEnabled
        if (heroAmbientBackgroundEnabled == next) return
        heroAmbientBackgroundEnabled = next
        publish()
        persist()
    }

    fun setTvModeEnabled(enabled: Boolean) {
        ensureLoaded()
        if (tvModeEnabled == enabled && !(enabled && (adaptiveHeroEnabled || heroAmbientBackgroundEnabled))) return
        tvModeEnabled = enabled
        if (enabled) {
            adaptiveHeroEnabled = false
            heroAmbientBackgroundEnabled = false
        }
        publish()
        persist()
    }

    fun setSmoothScrollingEnabled(enabled: Boolean) {
        ensureLoaded()
        if (smoothScrollingEnabled == enabled) return
        smoothScrollingEnabled = enabled
        publish()
        persist()
    }

    fun setCatalogSeeMoreEnabled(enabled: Boolean) {
        ensureLoaded()
        if (catalogSeeMoreEnabled == enabled) return
        catalogSeeMoreEnabled = enabled
        publish()
        persist()
    }

    fun setCatalogRowNumbersEnabled(enabled: Boolean) {
        ensureLoaded()
        if (catalogRowNumbersEnabled == enabled) return
        catalogRowNumbersEnabled = enabled
        publish()
        persist()
    }

    fun setTvRowDotsEnabled(enabled: Boolean) {
        ensureLoaded()
        if (tvRowDotsEnabled == enabled) return
        tvRowDotsEnabled = enabled
        publish()
        persist()
    }

    fun setTvRowDotsAnchor(anchor: HomeTvRowDotsAnchor) {
        ensureLoaded()
        if (tvRowDotsAnchor == anchor) return
        tvRowDotsAnchor = anchor
        publish()
        persist()
    }

    fun setHeroSourceEnabled(key: String, enabled: Boolean) {
        updatePreference(key) { preference ->
            if (!enabled) {
                preference.copy(heroSourceEnabled = false)
            } else if (!isHeroSourceEligible(key)) {
                preference.copy(heroSourceEnabled = false)
            } else if (selectedHeroSourceCount(excludingKey = key) >= HERO_SOURCE_SELECTION_LIMIT) {
                preference
            } else {
                preference.copy(heroSourceEnabled = true)
            }
        }
    }

    fun setEnabled(key: String, enabled: Boolean) {
        ensureLoaded()
        val current = preferences[key] ?: return
        if (current.enabled == enabled) return
        preferences[key] = current.copy(enabled = enabled)
        publish()
        persist()
        if (enabled && !key.startsWith("collection_")) {
            HomeRepository.refresh(AddonRepository.uiState.value.addons.enabledAddons(), force = true)
        } else {
            HomeRepository.applyCurrentSettings()
        }
    }

    fun setCustomTitle(key: String, title: String) {
        updatePreference(key) { preference ->
            preference.copy(customTitle = title)
        }
    }

    fun setRandomPlayEnabled(enabled: Boolean) {
        ensureLoaded()
        if (randomPlayEnabled == enabled) return
        randomPlayEnabled = enabled
        publishAndPersistRandomPlay()
        if (enabled && randomPlayIncludeCollections) RandomPlayCollectionPool.ensureLoaded()
        if (!enabled) RandomPlayCandidatePool.clear()
    }

    /**
     * Widens the Random Play pool from the loaded Home rows to every catalog configured in
     * Collections. Collection catalogs are only fetched when a folder is opened, so this also
     * arms [RandomPlayCollectionPool] to keep a first page of each collection source warm.
     */
    fun setRandomPlayIncludeCollections(enabled: Boolean) {
        ensureLoaded()
        if (randomPlayIncludeCollections == enabled) return
        randomPlayIncludeCollections = enabled
        publishAndPersistRandomPlay()
        if (enabled) RandomPlayCollectionPool.ensureLoaded() else RandomPlayCollectionPool.clear()
    }

    fun setRandomPlayCategoryEnabled(category: RandomPlayCategory, enabled: Boolean) {
        ensureLoaded()
        val next = randomPlayCategories.toMutableSet().apply {
            if (enabled) add(category) else remove(category)
        }.toSet()
        if (next == randomPlayCategories) return
        randomPlayCategories = next
        publishAndPersistRandomPlay()
    }

    fun setRandomPlayGenreEnabled(genre: String, enabled: Boolean) {
        ensureLoaded()
        val canonical = RandomPlayGenres.firstOrNull { it.equals(genre, ignoreCase = true) } ?: return
        val next = randomPlayGenres.toMutableSet().apply {
            if (enabled) add(canonical) else remove(canonical)
        }.toSet()
        if (next == randomPlayGenres) return
        randomPlayGenres = next
        publishAndPersistRandomPlay()
    }

    fun setRandomPlayMinimumImdbRating(rating: Float) {
        ensureLoaded()
        val normalized = if (rating.isNaN()) 0f else (rating * 2f).toInt().div(2f).coerceIn(0f, 10f)
        if (randomPlayMinimumImdbRating == normalized) return
        randomPlayMinimumImdbRating = normalized
        publishAndPersistRandomPlay()
    }

    fun setRandomPlayAction(action: RandomPlayAction) {
        ensureLoaded()
        if (randomPlayAction == action) return
        randomPlayAction = action
        publishAndPersistRandomPlay()
    }

    fun setMarkerColor(key: String, markerColor: HomeCatalogMarkerColor?) {
        updatePreference(key) { preference ->
            preference.copy(markerColor = markerColor)
        }
    }

    fun resetToDefaults() {
        ensureLoaded()
        heroEnabled = true
        heroInfoLines = 2
        heroInfoPriority = DEFAULT_HERO_INFO_PRIORITY
        // The default string already contains every migrated slot, so nothing is outstanding.
        heroInfoPrioritySlotMigrations = HERO_INFO_PRIORITY_SLOT_MIGRATIONS.mapTo(mutableSetOf()) { it.slot }
        heroBadgePlacement = HeroBadgePlacement.BottomBackdrop
        heroBadgeScale = 1f
        heroReleaseStatusUnavailableOnly = true
        hideUnreleasedContent = false
        hideCatalogUnderline = false
        adaptiveHeroEnabled = false
        adaptiveHeroVerticalBias = ADAPTIVE_HERO_VERTICAL_BIAS_DEFAULT
        adaptiveHeroHeightMultiplier = ADAPTIVE_HERO_HEIGHT_MULTIPLIER_DEFAULT
        heroAmbientBackgroundEnabled = false
        tvModeEnabled = false
        smoothScrollingEnabled = true
        catalogSeeMoreEnabled = false
        catalogRowNumbersEnabled = false
        tvRowDotsEnabled = false
        tvRowDotsAnchor = HomeTvRowDotsAnchor.RowTitle
        resetRandomPlaySettings()
        preferences.clear()
        normalizePreferences()
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }

    fun moveUp(key: String) {
        move(key = key, direction = -1)
    }

    fun moveDown(key: String) {
        move(key = key, direction = 1)
    }

    fun moveByIndex(fromIndex: Int, toIndex: Int) {
        ensureLoaded()
        val allKeys = allOrderedKeys()
        if (allKeys.isEmpty()) return
        if (fromIndex !in allKeys.indices || toIndex !in allKeys.indices) return
        if (fromIndex == toIndex) return
        val orderedKeys = allKeys.toMutableList()
        orderedKeys.add(toIndex, orderedKeys.removeAt(fromIndex))
        orderedKeys.forEachIndexed { index, itemKey ->
            val current = preferences[itemKey] ?: return@forEachIndexed
            preferences[itemKey] = current.copy(order = index)
        }
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }

    fun moveToTop(key: String) {
        ensureLoaded()
        val allKeys = allOrderedKeys()
        if (key !in allKeys) return

        val pinnedCollectionKeys = collectionDefinitions
            .asSequence()
            .filter { it.isPinnedToTop }
            .map { it.key }
            .toSet()
        if (key in pinnedCollectionKeys) return

        val targetIndex = allKeys.count { it in pinnedCollectionKeys }
        val fromIndex = allKeys.indexOf(key)
        if (fromIndex == targetIndex) return

        val orderedKeys = allKeys.toMutableList()
        orderedKeys.add(targetIndex, orderedKeys.removeAt(fromIndex))
        orderedKeys.forEachIndexed { index, itemKey ->
            val current = preferences[itemKey] ?: return@forEachIndexed
            preferences[itemKey] = current.copy(order = index)
        }
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }

    private fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true

        val payload = HomeCatalogSettingsStorage.loadPayload().orEmpty().trim()
        if (payload.isEmpty()) return

        val parsedPayload = runCatching {
            json.decodeFromString<StoredHomeCatalogSettingsPayload>(payload)
        }.getOrNull()

        if (parsedPayload != null) {
            heroEnabled = parsedPayload.heroEnabled
            heroInfoLines = normalizeHeroInfoLines(parsedPayload.heroInfoLines)
            heroInfoPriority = normalizeHeroInfoPriority(parsedPayload.heroInfoPriority)
            heroBadgePlacement = parsedPayload.heroBadgePlacement
            heroBadgeScale = normalizeHeroBadgeScale(parsedPayload.heroBadgeScale)
            heroReleaseStatusUnavailableOnly = parsedPayload.heroReleaseStatusUnavailableOnly
            hideUnreleasedContent = parsedPayload.hideUnreleasedContent
            hideCatalogUnderline = parsedPayload.hideCatalogUnderline
            adaptiveHeroEnabled = parsedPayload.adaptiveHeroEnabled
            adaptiveHeroVerticalBias = normalizeAdaptiveHeroVerticalBias(parsedPayload.adaptiveHeroVerticalBias)
            adaptiveHeroHeightMultiplier = normalizeAdaptiveHeroHeightMultiplier(parsedPayload.adaptiveHeroHeightMultiplier)
            heroAmbientBackgroundEnabled = parsedPayload.heroAmbientBackgroundEnabled
            tvModeEnabled = parsedPayload.tvModeEnabled
            smoothScrollingEnabled = parsedPayload.smoothScrollingEnabled
            catalogSeeMoreEnabled = parsedPayload.catalogSeeMoreEnabled
            catalogRowNumbersEnabled = parsedPayload.catalogRowNumbersEnabled
            tvRowDotsEnabled = parsedPayload.tvRowDotsEnabled
            tvRowDotsAnchor = parsedPayload.tvRowDotsAnchor
            randomPlayEnabled = parsedPayload.randomPlayEnabled
            randomPlayIncludeCollections = parsedPayload.randomPlayIncludeCollections
            randomPlayCategories = parsedPayload.randomPlayCategories
            val storedGenres = parsedPayload.randomPlayGenres
                .mapNotNullTo(linkedSetOf()) { stored ->
                    RandomPlayGenres.firstOrNull { it.equals(stored, ignoreCase = true) }
                }
            randomPlayGenres = if (parsedPayload.randomPlayAnimeGenresMigrated) {
                storedGenres
            } else {
                expandLegacyRandomPlayGenres(storedGenres)
            }
            val migratedAnimeGenres = !parsedPayload.randomPlayAnimeGenresMigrated
            randomPlayMinimumImdbRating = parsedPayload.randomPlayMinimumImdbRating.coerceIn(0f, 10f)
            randomPlayAction = parsedPayload.randomPlayAction
            heroInfoPrioritySlotMigrations = parsedPayload.heroInfoPrioritySlotMigrations
            val migratedPriority = applyHeroInfoPrioritySlotMigrations()
            normalizeHeroModes()
            preferences = parsedPayload.items.associateBy { it.key }.toMutableMap()
            publish()
            if (migratedPriority || migratedAnimeGenres) persist()
            return
        }

        val legacyItems = runCatching {
            json.decodeFromString<List<StoredHomeCatalogPreference>>(payload)
        }.getOrDefault(emptyList())

        preferences = legacyItems.associateBy { it.key }.toMutableMap()
        publish()
    }

    private fun normalizePreferences() {
        val current = preferences
        data class UnifiedEntry(val key: String, val isCollection: Boolean, val hasHeroBackdrop: Boolean)
        val catalogEntries = definitions.map { UnifiedEntry(it.key, false, hasHeroBackdrop = true) }
        val collectionEntries = collectionDefinitions.map {
            UnifiedEntry(it.key, isCollection = true, hasHeroBackdrop = it.hasHeroBackdrop)
        }
        val allEntries = catalogEntries + collectionEntries
        val knownKeys = allEntries.mapTo(linkedSetOf(), UnifiedEntry::key)
        var nextOrder = (current.values.maxOfOrNull(StoredHomeCatalogPreference::order) ?: -1) + 1

        val orderedEntries = allEntries.mapIndexed { defaultIndex, entry ->
            Triple(
                entry,
                current[entry.key]?.order ?: (nextOrder + defaultIndex),
                defaultIndex,
            )
        }.sortedWith(
            compareBy<Triple<UnifiedEntry, Int, Int>>(
                { it.second },
                { it.third },
            ),
        ).map { it.first }

        val normalized = current
            .filterKeys { it !in knownKeys }
            .toMutableMap()
        var enabledHeroSourceCount = 0
        orderedEntries.forEach { entry ->
            val stored = current[entry.key]
            val heroSourceEnabled = when {
                !entry.hasHeroBackdrop -> false
                // Opt-in: unlike catalogs, a collection never defaults to being a hero
                // source on its own — an existing collection shouldn't suddenly start
                // appearing in the hero rotation without the user asking for it.
                entry.isCollection ->
                    (stored?.heroSourceEnabled ?: false) &&
                        enabledHeroSourceCount < HERO_SOURCE_SELECTION_LIMIT
                else ->
                    (stored?.heroSourceEnabled ?: true) &&
                        enabledHeroSourceCount < HERO_SOURCE_SELECTION_LIMIT
            }
            if (heroSourceEnabled) {
                enabledHeroSourceCount += 1
            }
            normalized[entry.key] = StoredHomeCatalogPreference(
                key = entry.key,
                customTitle = stored?.customTitle.orEmpty(),
                markerColor = stored?.markerColor,
                enabled = stored?.enabled ?: true,
                heroSourceEnabled = heroSourceEnabled,
                order = stored?.order ?: nextOrder++,
            )
        }
        preferences = normalized
    }

    private fun publish() {
        normalizeHeroModes()
        val collectionMap = collectionDefinitions.associateBy { it.key }
        val catalogItems = definitions
            .map { definition ->
                val preference = preferences[definition.key]
                HomeCatalogSettingsItem(
                    key = definition.key,
                    defaultTitle = definition.defaultTitle,
                    addonName = definition.addonName,
                    customTitle = preference?.customTitle.orEmpty(),
                    markerColor = preference?.markerColor,
                    enabled = preference?.enabled ?: true,
                    heroSourceEnabled = preference?.heroSourceEnabled ?: true,
                    order = preference?.order ?: 0,
                )
            }

        val collectionItems = collectionDefinitions.map { colDef ->
            val preference = preferences[colDef.key]
            HomeCatalogSettingsItem(
                key = colDef.key,
                defaultTitle = colDef.title,
                addonName = colDef.subtitle,
                customTitle = preference?.customTitle.orEmpty(),
                markerColor = preference?.markerColor,
                enabled = preference?.enabled ?: true,
                heroSourceEnabled = preference?.heroSourceEnabled ?: false,
                order = preference?.order ?: 0,
                isCollection = true,
                collectionId = colDef.collectionId,
                isPinnedToTop = colDef.isPinnedToTop,
                hasHeroBackdrop = colDef.hasHeroBackdrop,
            )
        }

        // Guard against two entries resolving to the same key (e.g. a duplicated collection):
        // the Home LazyColumn keys rows directly by this key via `item(key = settingsItem.key)`,
        // and a collision throws "Key … was already used", which crashes Compose Desktop.
        val items = (catalogItems + collectionItems)
            .sortedBy { it.order }
            .distinctBy { it.key }

        _uiState.value = HomeCatalogSettingsUiState(
            heroEnabled = heroEnabled,
            heroInfoLines = normalizeHeroInfoLines(heroInfoLines),
            heroInfoPriority = heroInfoPriority,
            heroBadgePlacement = heroBadgePlacement,
            heroBadgeScale = heroBadgeScale,
            heroReleaseStatusUnavailableOnly = heroReleaseStatusUnavailableOnly,
            hideUnreleasedContent = hideUnreleasedContent,
            // TV Mode's shelf rows don't read well with the underline accent — force it off
            // while active without touching the user's actual saved preference, so it comes
            // back exactly as they left it if they turn TV Mode back off.
            hideCatalogUnderline = hideCatalogUnderline || tvModeEnabled,
            adaptiveHeroEnabled = adaptiveHeroEnabled,
            adaptiveHeroVerticalBias = adaptiveHeroVerticalBias,
            adaptiveHeroHeightMultiplier = adaptiveHeroHeightMultiplier,
            heroAmbientBackgroundEnabled = heroAmbientBackgroundEnabled,
            tvModeEnabled = tvModeEnabled,
            smoothScrollingEnabled = smoothScrollingEnabled,
            catalogSeeMoreEnabled = catalogSeeMoreEnabled,
            catalogRowNumbersEnabled = catalogRowNumbersEnabled,
            // Reported raw (not && tvModeEnabled) so the settings row keeps showing what the user
            // saved while the toggle sits disabled outside TV Mode; the shelf gates on the mode.
            tvRowDotsEnabled = tvRowDotsEnabled,
            tvRowDotsAnchor = tvRowDotsAnchor,
            randomPlayEnabled = randomPlayEnabled,
            randomPlayIncludeCollections = randomPlayIncludeCollections,
            randomPlayCategories = randomPlayCategories,
            randomPlayGenres = randomPlayGenres,
            randomPlayMinimumImdbRating = randomPlayMinimumImdbRating,
            randomPlayAction = randomPlayAction,
            items = items,
        )
    }

    private fun normalizeHeroModes() {
        if (tvModeEnabled) {
            adaptiveHeroEnabled = false
            heroAmbientBackgroundEnabled = false
        }
        heroInfoLines = normalizeHeroInfoLines(heroInfoLines)
        heroInfoPriority = normalizeHeroInfoPriority(heroInfoPriority)
    }

    private fun normalizeHeroInfoLines(lines: Int): Int =
        lines.coerceIn(HERO_INFO_LINES_MIN, HERO_INFO_LINES_MAX)

    private fun normalizeHeroBadgeScale(scale: Float): Float =
        if (scale.isNaN()) 1f else scale.coerceIn(HERO_BADGE_SCALE_MIN, HERO_BADGE_SCALE_MAX)

    private fun normalizeAdaptiveHeroVerticalBias(bias: Float): Float =
        if (bias.isNaN()) ADAPTIVE_HERO_VERTICAL_BIAS_DEFAULT
        else bias.coerceIn(ADAPTIVE_HERO_VERTICAL_BIAS_MIN, ADAPTIVE_HERO_VERTICAL_BIAS_MAX)

    private fun normalizeAdaptiveHeroHeightMultiplier(multiplier: Float): Float =
        if (multiplier.isNaN()) ADAPTIVE_HERO_HEIGHT_MULTIPLIER_DEFAULT
        else multiplier.coerceIn(ADAPTIVE_HERO_HEIGHT_MULTIPLIER_MIN, ADAPTIVE_HERO_HEIGHT_MULTIPLIER_MAX)

    private fun normalizeHeroInfoPriority(priority: String): String =
        priority
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(",")

    /**
     * Adds any never-applied slot from [HERO_INFO_PRIORITY_SLOT_MIGRATIONS] to the saved priority
     * string, once. Returns whether anything changed so the caller can persist the result — the
     * point of the migration is that it lands in storage, so the settings page shows the slot as
     * enabled and turning it off actually sticks.
     */
    private fun applyHeroInfoPrioritySlotMigrations(): Boolean {
        val result = migrateHeroInfoPrioritySlots(
            priority = heroInfoPriority,
            appliedMigrations = heroInfoPrioritySlotMigrations,
        )
        if (!result.changed) return false
        heroInfoPriority = result.priority
        heroInfoPrioritySlotMigrations = result.appliedMigrations
        return true
    }

    private fun persist() {
        HomeCatalogSettingsStorage.savePayload(
            json.encodeToString(
                StoredHomeCatalogSettingsPayload(
                    heroEnabled = heroEnabled,
                    heroInfoLines = heroInfoLines,
                    heroInfoPriority = heroInfoPriority,
                    heroInfoPrioritySlotMigrations = heroInfoPrioritySlotMigrations,
                    heroBadgePlacement = heroBadgePlacement,
                    heroBadgeScale = heroBadgeScale,
                    heroReleaseStatusUnavailableOnly = heroReleaseStatusUnavailableOnly,
                    hideUnreleasedContent = hideUnreleasedContent,
                    hideCatalogUnderline = hideCatalogUnderline,
                    adaptiveHeroEnabled = adaptiveHeroEnabled,
                    adaptiveHeroVerticalBias = adaptiveHeroVerticalBias,
                    adaptiveHeroHeightMultiplier = adaptiveHeroHeightMultiplier,
                    heroAmbientBackgroundEnabled = heroAmbientBackgroundEnabled,
                    tvModeEnabled = tvModeEnabled,
                    smoothScrollingEnabled = smoothScrollingEnabled,
                    catalogSeeMoreEnabled = catalogSeeMoreEnabled,
                    catalogRowNumbersEnabled = catalogRowNumbersEnabled,
                    tvRowDotsEnabled = tvRowDotsEnabled,
                    tvRowDotsAnchor = tvRowDotsAnchor,
                    randomPlayEnabled = randomPlayEnabled,
                    randomPlayIncludeCollections = randomPlayIncludeCollections,
                    randomPlayCategories = randomPlayCategories,
                    randomPlayGenres = randomPlayGenres,
                    // Always true once written: the in-memory set has been through
                    // [expandLegacyRandomPlayGenres] and must not be expanded a second time.
                    randomPlayAnimeGenresMigrated = true,
                    randomPlayMinimumImdbRating = randomPlayMinimumImdbRating,
                    randomPlayAction = randomPlayAction,
                    items = preferences.values.sortedBy { it.order },
                ),
            ),
        )
    }

    private fun updatePreference(
        key: String,
        transform: (StoredHomeCatalogPreference) -> StoredHomeCatalogPreference,
    ) {
        ensureLoaded()
        val current = preferences[key] ?: return
        preferences[key] = transform(current)
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }

    private fun publishAndPersistRandomPlay() {
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }

    private fun resetRandomPlaySettings() {
        randomPlayEnabled = false
        randomPlayIncludeCollections = false
        randomPlayCategories = RandomPlayCategory.entries.toSet()
        randomPlayGenres = RandomPlayGenres.toSet()
        randomPlayMinimumImdbRating = 0f
        randomPlayAction = RandomPlayAction.Details
    }

    private fun selectedHeroSourceCount(excludingKey: String? = null): Int {
        return preferences.count { (itemKey, preference) ->
            itemKey != excludingKey && isHeroSourceEligible(itemKey) && preference.heroSourceEnabled
        }
    }

    private fun isHeroSourceEligible(key: String): Boolean =
        definitions.any { it.key == key } ||
            collectionDefinitions.any { it.key == key && it.hasHeroBackdrop }

    private fun move(
        key: String,
        direction: Int,
    ) {
        ensureLoaded()
        val orderedKeys = allOrderedKeys().toMutableList()
        if (orderedKeys.isEmpty()) return

        val currentIndex = orderedKeys.indexOf(key)
        if (currentIndex == -1) return

        val targetIndex = currentIndex + direction
        if (targetIndex !in orderedKeys.indices) return

        val movingKey = orderedKeys.removeAt(currentIndex)
        orderedKeys.add(targetIndex, movingKey)

        orderedKeys.forEachIndexed { index, itemKey ->
            val current = preferences[itemKey] ?: return@forEachIndexed
            preferences[itemKey] = current.copy(order = index)
        }

        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }

    fun exportToSyncPayload(): SyncHomeCatalogPayload {
        ensureLoaded()
        val items = preferences.values.sortedBy { it.order }.map { pref ->
            val parts = pref.key.split(":")
            val isCollection = pref.key.startsWith("collection_")
            if (isCollection) {
                SyncCatalogItem(
                    addonId = "",
                    type = "",
                    catalogId = "",
                    enabled = pref.enabled,
                    order = pref.order,
                    customTitle = pref.customTitle,
                    markerColor = pref.markerColor?.storageValue.orEmpty(),
                    isCollection = true,
                    collectionId = pref.key.removePrefix("collection_"),
                )
            } else {
                SyncCatalogItem(
                    addonId = parts.getOrElse(0) { "" },
                    type = parts.getOrElse(1) { "" },
                    catalogId = parts.drop(2).joinToString(":"),
                    enabled = pref.enabled,
                    order = pref.order,
                    customTitle = pref.customTitle,
                    markerColor = pref.markerColor?.storageValue.orEmpty(),
                    isCollection = false,
                )
            }
        }
        return SyncHomeCatalogPayload(
            hideUnreleasedContent = hideUnreleasedContent,
            hideCatalogUnderline = hideCatalogUnderline,
            items = items,
        )
    }

    fun applyFromRemote(payload: SyncHomeCatalogPayload) {
        ensureLoaded()
        hideUnreleasedContent = payload.hideUnreleasedContent
        hideCatalogUnderline = payload.hideCatalogUnderline
        if (payload.items.isNotEmpty()) {
            val existingHeroState = preferences.mapValues { it.value.heroSourceEnabled }
            val existingMarkerColors = preferences.mapValues { it.value.markerColor }
            preferences = payload.items.associate { item ->
                val key = if (item.isCollection) {
                    "collection_${item.collectionId}"
                } else {
                    "${item.addonId}:${item.type}:${item.catalogId}"
                }
                key to StoredHomeCatalogPreference(
                    key = key,
                    customTitle = item.customTitle,
                    markerColor = if (item.markerColor == null) {
                        existingMarkerColors[key]
                    } else {
                        HomeCatalogMarkerColor.fromStorageValue(item.markerColor)
                    },
                    enabled = item.enabled,
                    heroSourceEnabled = existingHeroState[key] ?: true,
                    order = item.order,
                )
            }.toMutableMap()
        }
        hasLoaded = true
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }

    private fun allOrderedKeys(): List<String> {
        val catalogKeys = definitions.map { it.key }
        val collectionKeys = collectionDefinitions.map { it.key }
        return (catalogKeys + collectionKeys)
            .sortedBy { key -> preferences[key]?.order ?: Int.MAX_VALUE }
    }

    private fun enforcePinnedCollectionsAtTop() {
        val orderedKeys = allOrderedKeys()
        if (orderedKeys.isEmpty()) return

        val pinnedCollectionKeys = collectionDefinitions
            .asSequence()
            .filter { it.isPinnedToTop }
            .map { it.key }
            .toSet()
        if (pinnedCollectionKeys.isEmpty()) return

        val pinnedKeys = orderedKeys.filter { it in pinnedCollectionKeys }
        if (pinnedKeys.isEmpty()) return

        val nonPinnedKeys = orderedKeys.filterNot { it in pinnedCollectionKeys }
        val reorderedKeys = pinnedKeys + nonPinnedKeys
        if (reorderedKeys == orderedKeys) return

        reorderedKeys.forEachIndexed { index, itemKey ->
            val current = preferences[itemKey] ?: return@forEachIndexed
            preferences[itemKey] = current.copy(order = index)
        }
    }
}

internal data class CollectionCatalogDefinition(
    val key: String,
    val collectionId: String,
    val title: String,
    val subtitle: String,
    val isPinnedToTop: Boolean,
    val hasHeroBackdrop: Boolean = false,
)

internal fun buildCollectionDefinitions(collections: List<Collection>): List<CollectionCatalogDefinition> =
    collections.filter { it.folders.isNotEmpty() }.map { collection ->
        CollectionCatalogDefinition(
            key = "collection_${collection.id}",
            collectionId = collection.id,
            title = collection.title,
            subtitle = runBlocking { getString(Res.string.collections_folder_count, collection.folders.size) },
            isPinnedToTop = collection.pinToTop,
            hasHeroBackdrop = collection.backdropImageUrl?.trim()?.isNotEmpty() == true ||
                collection.folders.any { folder -> folder.heroBackdropUrl?.trim()?.isNotEmpty() == true },
        )
    }
