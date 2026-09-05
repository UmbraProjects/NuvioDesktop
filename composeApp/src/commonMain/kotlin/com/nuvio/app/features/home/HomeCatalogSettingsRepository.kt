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
import com.nuvio.app.features.discover.CUSTOM_DISCOVER_REF_LIMIT
import com.nuvio.app.features.discover.CUSTOM_DISCOVER_ROW_LIMIT
import com.nuvio.app.features.discover.CustomDiscoverRow
import com.nuvio.app.features.discover.IMPORTED_DISCOVER_ITEM_LIMIT
import com.nuvio.app.features.discover.IMPORTED_DISCOVER_ROW_LIMIT
import com.nuvio.app.features.discover.AI_DISCOVER_ROW_LIMIT
import com.nuvio.app.features.discover.AiDiscoverRow
import com.nuvio.app.features.discover.aiDiscoverEntryId
import com.nuvio.app.features.discover.capped
import com.nuvio.app.features.discover.ImportedDiscoverRow
import com.nuvio.app.features.discover.importedDiscoverEntryId
import com.nuvio.app.features.discover.DiscoverGenreNames
import com.nuvio.app.features.discover.DiscoverRowFamily
import com.nuvio.app.features.discover.canonicalDiscoverGenreName
import com.nuvio.app.features.discover.customDiscoverEntryId
import com.nuvio.app.features.discover.normalizeDiscoverRowOrder
import org.jetbrains.compose.resources.getString

/**
 * How many "Because you watched …" rows the Discover tab generates.
 *
 * Each row is one seed and therefore one TMDB request per refresh, so the ceiling is a rate-limit
 * decision as much as a layout one.
 */
val DISCOVER_BECAUSE_ROWS_RANGE: IntRange = 0..8
const val DISCOVER_BECAUSE_ROWS_DEFAULT = 4

/**
 * How many "Trending in <genre>" rows the Discover tab generates, one per genre the user's history
 * leans on. Zero turns them off — there is no separate switch, the same way the "Because you
 * watched" slider owns its own off state.
 */
val DISCOVER_TRENDING_GENRE_ROWS_RANGE: IntRange = 0..4
const val DISCOVER_TRENDING_GENRE_ROWS_DEFAULT = 1

/**
 * Days a part-watched title must sit untouched before "Finish what you started" offers it back.
 *
 * Below this it is still in flight and Continue Watching owns it; the row exists for what has
 * dropped off the end of that list.
 *
 * The floor is 3 days, not the 30 this shipped with, because watch *progress* has far less history
 * behind it than watch *history* does: entries are written per playback and cleared as titles are
 * finished or dismissed, so the store holds weeks, not months. On a real install the oldest
 * in-progress title was 13 days old — a 30-day floor could not match anything, and the row was
 * always empty however long the user waited.
 */
val DISCOVER_FINISH_IDLE_DAYS_RANGE: IntRange = 3..180
const val DISCOVER_FINISH_IDLE_DAYS_DEFAULT = 14

/**
 * Genre names as [DiscoverGenreNames] spells them, dropping anything that resolves to nothing.
 *
 * Stored names are canonicalised on every read and write because a name that does not match the
 * list selects nothing, under a label no settings page can render — a setting that silently stopped
 * working. [canonicalDiscoverGenreName] also carries the migration off the old raw-TMDB vocabulary,
 * so a saved "Sci-Fi & Fantasy" becomes "Science Fiction" rather than being discarded.
 */
private fun Set<String>.canonicalDiscoverGenres(): Set<String> =
    mapNotNullTo(mutableSetOf(), ::canonicalDiscoverGenreName)

/**
 * A custom row with everything the model cannot enforce for itself brought back in range.
 *
 * Applied on **both** the write and the read, not just the write: the limits also have to hold for a
 * row that was saved by a build with different ones, or edited on disk. The picker already caps what
 * can be added, but a cap that lives only in the UI is a cap the query layer cannot rely on — and
 * these lists become the length of a TMDB URL.
 */
private fun CustomDiscoverRow.sanitizedForStorage(): CustomDiscoverRow = copy(
    genres = genres.canonicalDiscoverGenres(),
    // Deduplicated by id rather than by name: two records can share a name, and it is the id that
    // reaches TMDB.
    companies = companies.distinctBy { it.id }.take(CUSTOM_DISCOVER_REF_LIMIT),
    cast = cast.distinctBy { it.id }.take(CUSTOM_DISCOVER_REF_LIMIT),
    crew = crew.distinctBy { it.id }.take(CUSTOM_DISCOVER_REF_LIMIT),
)

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
    val discoverHideWatched: Boolean = true,
    val discoverBecauseYouWatchedRows: Int = DISCOVER_BECAUSE_ROWS_DEFAULT,
    val discoverFinishWhatYouStartedEnabled: Boolean = true,
    val discoverFinishIdleDays: Int = DISCOVER_FINISH_IDLE_DAYS_DEFAULT,
    val discoverMoreLikeFavouritesEnabled: Boolean = true,
    val discoverHiddenGemsEnabled: Boolean = true,
    val discoverTrendingGenreRows: Int = DISCOVER_TRENDING_GENRE_ROWS_DEFAULT,
    val discoverExcludedGenres: Set<String> = emptySet(),
    /**
     * Entry ids in render order — family ids plus `custom:<id>`. Stored raw and reconciled with
     * [normalizeDiscoverRowOrder] on read, never on write, so a row temporarily missing (a custom
     * row mid-edit, a family added by a later version) cannot quietly rewrite the saved order.
     */
    val discoverRowOrder: List<String> = emptyList(),
    val discoverCustomRows: List<CustomDiscoverRow> = emptyList(),
    val discoverImportedRows: List<ImportedDiscoverRow> = emptyList(),
    val discoverAiRows: List<AiDiscoverRow> = emptyList(),
    val hideCatalogUnderline: Boolean = false,
    val catalogRowShuffleEnabled: Boolean = false,
    val adaptiveHeroEnabled: Boolean = false,
    val adaptiveHeroVerticalBias: Float = ADAPTIVE_HERO_VERTICAL_BIAS_DEFAULT,
    val adaptiveHeroHeightMultiplier: Float = ADAPTIVE_HERO_HEIGHT_MULTIPLIER_DEFAULT,
    val heroAmbientBackgroundEnabled: Boolean = false,
    val tvModeEnabled: Boolean = false,
    val smoothScrollingEnabled: Boolean = true,
    val hoverPreviewBasicEnabled: Boolean = true,
    val hoverPreviewAdaptiveEnabled: Boolean = false,
    val catalogSeeMoreEnabled: Boolean = false,
    val catalogRowNumbersEnabled: Boolean = false,
    val tvRowDotsEnabled: Boolean = false,
    val tvRowDotsAnchor: HomeTvRowDotsAnchor = HomeTvRowDotsAnchor.RowTitle,
    val tvFullBackdropEnabled: Boolean = false,
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
            append(catalogRowShuffleEnabled)
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
            append(hoverPreviewBasicEnabled)
            append('|')
            append(hoverPreviewAdaptiveEnabled)
            append('|')
            append(catalogSeeMoreEnabled)
            append('|')
            append(catalogRowNumbersEnabled)
            append('|')
            append(tvRowDotsEnabled)
            append('|')
            append(tvRowDotsAnchor)
            append('|')
            append(tvFullBackdropEnabled)
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
    val discoverHideWatched: Boolean,
    val discoverBecauseYouWatchedRows: Int,
    val discoverFinishWhatYouStartedEnabled: Boolean,
    val discoverFinishIdleDays: Int,
    val discoverMoreLikeFavouritesEnabled: Boolean,
    val discoverHiddenGemsEnabled: Boolean,
    val discoverTrendingGenreRows: Int,
    val discoverExcludedGenres: Set<String>,
    /** Already normalised — see [HomeCatalogSettingsUiState.discoverRowOrder]. */
    val discoverRowOrder: List<String>,
    val discoverCustomRows: List<CustomDiscoverRow>,
    val discoverImportedRows: List<ImportedDiscoverRow>,
    val discoverAiRows: List<AiDiscoverRow>,
    val hideCatalogUnderline: Boolean,
    val catalogRowShuffleEnabled: Boolean,
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

/**
 * Single "how the home screen presents itself" choice, consolidating adaptive cropping, ambient
 * background, and TV mode into one control instead of three independent switches.
 *
 * The three backing flags are not free to vary: TV mode excludes both of the others (see
 * [normalizeHeroModes]), and ambient only means anything on top of the adaptive hero. This enum is
 * the only representation the UI should reason about — read it with [homeDisplayModeOf], write it
 * with [HomeCatalogSettingsRepository.setDisplayMode].
 */
enum class HomeDisplayMode {
    Basic,
    Adaptive,
    AdaptiveAmbient,
    TvMode,
}

fun homeDisplayModeOf(
    adaptiveHeroEnabled: Boolean,
    heroAmbientBackgroundEnabled: Boolean,
    tvModeEnabled: Boolean,
): HomeDisplayMode = when {
    tvModeEnabled -> HomeDisplayMode.TvMode
    adaptiveHeroEnabled && heroAmbientBackgroundEnabled -> HomeDisplayMode.AdaptiveAmbient
    adaptiveHeroEnabled -> HomeDisplayMode.Adaptive
    else -> HomeDisplayMode.Basic
}

/** The three backing flags a [HomeDisplayMode] corresponds to. */
data class HomeDisplayModeFlags(
    val adaptiveHeroEnabled: Boolean,
    val heroAmbientBackgroundEnabled: Boolean,
    val tvModeEnabled: Boolean,
)

/**
 * The mode → flags half of the mapping, kept pure so it can be round-tripped against
 * [homeDisplayModeOf] in a test. Everything that writes a display mode goes through this.
 */
fun HomeDisplayMode.toFlags(): HomeDisplayModeFlags = HomeDisplayModeFlags(
    adaptiveHeroEnabled = this == HomeDisplayMode.Adaptive || this == HomeDisplayMode.AdaptiveAmbient,
    heroAmbientBackgroundEnabled = this == HomeDisplayMode.AdaptiveAmbient,
    tvModeEnabled = this == HomeDisplayMode.TvMode,
)

fun homeDisplayModeOf(flags: HomeDisplayModeFlags): HomeDisplayMode = homeDisplayModeOf(
    adaptiveHeroEnabled = flags.adaptiveHeroEnabled,
    heroAmbientBackgroundEnabled = flags.heroAmbientBackgroundEnabled,
    tvModeEnabled = flags.tvModeEnabled,
)

/**
 * Whether the poster hover preview is on for [mode].
 *
 * TV Mode is not a stored preference and never will be: the shelf there is driven by focus
 * rather than the pointer, and a popup card over the backdrop is the wrong shape for it. The
 * other two modes each keep their own switch, which is why switching modes does not carry a
 * choice made for the other one across.
 */
fun HomeCatalogSettingsUiState.hoverPreviewEnabledFor(
    mode: HomeDisplayMode = homeDisplayModeOf(this),
): Boolean = when (mode) {
    HomeDisplayMode.Basic -> hoverPreviewBasicEnabled
    HomeDisplayMode.Adaptive, HomeDisplayMode.AdaptiveAmbient -> hoverPreviewAdaptiveEnabled
    HomeDisplayMode.TvMode -> false
}

/**
 * Whether Basic's hero may host a trailer.
 *
 * Basic is the only mode whose hero neither follows focus nor floats above the rows: it is a
 * static rotation living inside the rows list. That is why it gets trailers on different terms
 * from Adaptive/TV — full screen only, and manual or autoplay only while it is actually on
 * screen. [isNormalHomeMode] keeps Search, Library and Discover out: their heroes follow the
 * focused result, which is a different feature answering to `heroFollowsFocusedItem`.
 */
fun basicHeroTrailersAllowed(
    mode: HomeDisplayMode,
    isDesktop: Boolean,
    heroVisible: Boolean,
    isNormalHomeMode: Boolean,
): Boolean = isDesktop && heroVisible && isNormalHomeMode && mode == HomeDisplayMode.Basic

fun homeDisplayModeOf(state: HomeCatalogSettingsUiState): HomeDisplayMode = homeDisplayModeOf(
    adaptiveHeroEnabled = state.adaptiveHeroEnabled,
    heroAmbientBackgroundEnabled = state.heroAmbientBackgroundEnabled,
    tvModeEnabled = state.tvModeEnabled,
)

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
    val discoverHideWatched: Boolean = true,
    val discoverBecauseYouWatchedRows: Int = DISCOVER_BECAUSE_ROWS_DEFAULT,
    val discoverFinishWhatYouStartedEnabled: Boolean = true,
    val discoverFinishIdleDays: Int = DISCOVER_FINISH_IDLE_DAYS_DEFAULT,
    val discoverMoreLikeFavouritesEnabled: Boolean = true,
    val discoverHiddenGemsEnabled: Boolean = true,
    val discoverTrendingGenreRows: Int = DISCOVER_TRENDING_GENRE_ROWS_DEFAULT,
    val discoverExcludedGenres: Set<String> = emptySet(),
    /**
     * Entry ids in render order — family ids plus `custom:<id>`. Stored raw and reconciled with
     * [normalizeDiscoverRowOrder] on read, never on write, so a row temporarily missing (a custom
     * row mid-edit, a family added by a later version) cannot quietly rewrite the saved order.
     */
    val discoverRowOrder: List<String> = emptyList(),
    val discoverCustomRows: List<CustomDiscoverRow> = emptyList(),
    val discoverImportedRows: List<ImportedDiscoverRow> = emptyList(),
    val discoverAiRows: List<AiDiscoverRow> = emptyList(),
    val hideCatalogUnderline: Boolean = false,
    val catalogRowShuffleEnabled: Boolean = false,
    @SerialName("tvModeEnabled")
    val adaptiveHeroEnabled: Boolean = false,
    val adaptiveHeroVerticalBias: Float = -0.58f,
    val adaptiveHeroHeightMultiplier: Float = 1.25f,
    val heroAmbientBackgroundEnabled: Boolean = false,
    @SerialName("immersiveCatalogModeEnabled")
    val tvModeEnabled: Boolean = false,
    val smoothScrollingEnabled: Boolean = true,
    val hoverPreviewBasicEnabled: Boolean = true,
    val hoverPreviewAdaptiveEnabled: Boolean = false,
    val catalogSeeMoreEnabled: Boolean = false,
    val catalogRowNumbersEnabled: Boolean = false,
    val tvRowDotsEnabled: Boolean = false,
    val tvRowDotsAnchor: HomeTvRowDotsAnchor = HomeTvRowDotsAnchor.RowTitle,
    val tvFullBackdropEnabled: Boolean = false,
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
    private var discoverHideWatched = true
    private var discoverBecauseYouWatchedRows = DISCOVER_BECAUSE_ROWS_DEFAULT
    private var discoverFinishWhatYouStartedEnabled = true
    private var discoverFinishIdleDays = DISCOVER_FINISH_IDLE_DAYS_DEFAULT
    private var discoverMoreLikeFavouritesEnabled = true
    private var discoverHiddenGemsEnabled = true
    private var discoverTrendingGenreRows = DISCOVER_TRENDING_GENRE_ROWS_DEFAULT
    private var discoverExcludedGenres: Set<String> = emptySet()
    private var discoverRowOrder: List<String> = emptyList()
    private var discoverCustomRows: List<CustomDiscoverRow> = emptyList()
    private var discoverImportedRows: List<ImportedDiscoverRow> = emptyList()
    private var discoverAiRows: List<AiDiscoverRow> = emptyList()

    /**
     * The count a slider-backed row family had before it was switched off, so switching it back on
     * restores what the user chose rather than the shipped default.
     *
     * Session-scoped on purpose: persisting it would mean a second stored representation of "how
     * many rows", and the whole point of mapping the switch onto the count is that there is only
     * one. Losing it across a restart costs the user one slider drag.
     */
    private val discoverRowCountBeforeOff = mutableMapOf<DiscoverRowFamily, Int>()
    private var hideCatalogUnderline = false
    private var catalogRowShuffleEnabled = false
    private var adaptiveHeroEnabled = false
    private var adaptiveHeroVerticalBias = ADAPTIVE_HERO_VERTICAL_BIAS_DEFAULT
    private var adaptiveHeroHeightMultiplier = ADAPTIVE_HERO_HEIGHT_MULTIPLIER_DEFAULT
    private var heroAmbientBackgroundEnabled = false
    private var tvModeEnabled = false
    private var smoothScrollingEnabled = true
    private var hoverPreviewBasicEnabled = true
    private var hoverPreviewAdaptiveEnabled = false
    private var catalogSeeMoreEnabled = false
    private var catalogRowNumbersEnabled = false
    private var tvRowDotsEnabled = false
    private var tvRowDotsAnchor = HomeTvRowDotsAnchor.RowTitle
    private var tvFullBackdropEnabled = false
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
        discoverHideWatched = true
        discoverBecauseYouWatchedRows = DISCOVER_BECAUSE_ROWS_DEFAULT
        discoverFinishWhatYouStartedEnabled = true
        discoverFinishIdleDays = DISCOVER_FINISH_IDLE_DAYS_DEFAULT
        discoverMoreLikeFavouritesEnabled = true
        discoverHiddenGemsEnabled = true
        discoverTrendingGenreRows = DISCOVER_TRENDING_GENRE_ROWS_DEFAULT
        discoverExcludedGenres = emptySet()
        discoverRowOrder = emptyList()
        discoverCustomRows = emptyList()
        discoverImportedRows = emptyList()
        discoverAiRows = emptyList()
        discoverRowCountBeforeOff.clear()
        hideCatalogUnderline = false
        catalogRowShuffleEnabled = false
        adaptiveHeroEnabled = false
        adaptiveHeroVerticalBias = ADAPTIVE_HERO_VERTICAL_BIAS_DEFAULT
        adaptiveHeroHeightMultiplier = ADAPTIVE_HERO_HEIGHT_MULTIPLIER_DEFAULT
        heroAmbientBackgroundEnabled = false
        tvModeEnabled = false
        smoothScrollingEnabled = true
        hoverPreviewBasicEnabled = true
        hoverPreviewAdaptiveEnabled = false
        catalogSeeMoreEnabled = false
        catalogRowNumbersEnabled = false
        tvRowDotsEnabled = false
        tvRowDotsAnchor = HomeTvRowDotsAnchor.RowTitle
        tvFullBackdropEnabled = false
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
        discoverHideWatched = true
        discoverBecauseYouWatchedRows = DISCOVER_BECAUSE_ROWS_DEFAULT
        discoverFinishWhatYouStartedEnabled = true
        discoverFinishIdleDays = DISCOVER_FINISH_IDLE_DAYS_DEFAULT
        discoverMoreLikeFavouritesEnabled = true
        discoverHiddenGemsEnabled = true
        discoverTrendingGenreRows = DISCOVER_TRENDING_GENRE_ROWS_DEFAULT
        discoverExcludedGenres = emptySet()
        discoverRowOrder = emptyList()
        discoverCustomRows = emptyList()
        discoverImportedRows = emptyList()
        discoverAiRows = emptyList()
        discoverRowCountBeforeOff.clear()
        hideCatalogUnderline = false
        catalogRowShuffleEnabled = false
        adaptiveHeroEnabled = false
        adaptiveHeroVerticalBias = ADAPTIVE_HERO_VERTICAL_BIAS_DEFAULT
        adaptiveHeroHeightMultiplier = ADAPTIVE_HERO_HEIGHT_MULTIPLIER_DEFAULT
        heroAmbientBackgroundEnabled = false
        tvModeEnabled = false
        smoothScrollingEnabled = true
        hoverPreviewBasicEnabled = true
        hoverPreviewAdaptiveEnabled = false
        catalogSeeMoreEnabled = false
        catalogRowNumbersEnabled = false
        tvRowDotsEnabled = false
        tvRowDotsAnchor = HomeTvRowDotsAnchor.RowTitle
        tvFullBackdropEnabled = false
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
            discoverHideWatched = discoverHideWatched,
            discoverBecauseYouWatchedRows = discoverBecauseYouWatchedRows,
            discoverFinishWhatYouStartedEnabled = discoverFinishWhatYouStartedEnabled,
            discoverFinishIdleDays = discoverFinishIdleDays,
            discoverMoreLikeFavouritesEnabled = discoverMoreLikeFavouritesEnabled,
            discoverHiddenGemsEnabled = discoverHiddenGemsEnabled,
            discoverTrendingGenreRows = discoverTrendingGenreRows,
            discoverExcludedGenres = discoverExcludedGenres,
            discoverRowOrder = normalizeDiscoverRowOrder(
                savedOrder = discoverRowOrder,
                customRowIds = discoverCustomRows.map { it.id },
                importedRowIds = discoverImportedRows.map { it.id },
                aiRowIds = discoverAiRows.map { it.id },
            ),
            discoverCustomRows = discoverCustomRows,
            discoverImportedRows = discoverImportedRows,
            discoverAiRows = discoverAiRows,
            hideCatalogUnderline = hideCatalogUnderline,
            catalogRowShuffleEnabled = catalogRowShuffleEnabled,
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

    fun setDiscoverBecauseYouWatchedRows(count: Int) {
        ensureLoaded()
        val clamped = count.coerceIn(DISCOVER_BECAUSE_ROWS_RANGE.first, DISCOVER_BECAUSE_ROWS_RANGE.last)
        if (discoverBecauseYouWatchedRows == clamped) return
        discoverBecauseYouWatchedRows = clamped
        publish()
        persist()
    }

    fun setDiscoverHideWatched(enabled: Boolean) {
        ensureLoaded()
        if (discoverHideWatched == enabled) return
        discoverHideWatched = enabled
        publish()
        persist()
    }

    fun setDiscoverFinishWhatYouStartedEnabled(enabled: Boolean) {
        ensureLoaded()
        if (discoverFinishWhatYouStartedEnabled == enabled) return
        discoverFinishWhatYouStartedEnabled = enabled
        publish()
        persist()
    }

    fun setDiscoverFinishIdleDays(days: Int) {
        ensureLoaded()
        val clamped = days.coerceIn(DISCOVER_FINISH_IDLE_DAYS_RANGE.first, DISCOVER_FINISH_IDLE_DAYS_RANGE.last)
        if (discoverFinishIdleDays == clamped) return
        discoverFinishIdleDays = clamped
        publish()
        persist()
    }

    fun setDiscoverMoreLikeFavouritesEnabled(enabled: Boolean) {
        ensureLoaded()
        if (discoverMoreLikeFavouritesEnabled == enabled) return
        discoverMoreLikeFavouritesEnabled = enabled
        publish()
        persist()
    }

    fun setDiscoverGenreExcluded(genre: String, excluded: Boolean) {
        ensureLoaded()
        val canonical = canonicalDiscoverGenreName(genre) ?: return
        val next = discoverExcludedGenres.toMutableSet().apply {
            if (excluded) add(canonical) else remove(canonical)
        }.toSet()
        if (next == discoverExcludedGenres) return
        discoverExcludedGenres = next
        publish()
        persist()
    }

    fun setDiscoverHiddenGemsEnabled(enabled: Boolean) {
        ensureLoaded()
        if (discoverHiddenGemsEnabled == enabled) return
        discoverHiddenGemsEnabled = enabled
        publish()
        persist()
    }

    fun setDiscoverTrendingGenreRows(count: Int) {
        ensureLoaded()
        val clamped = count.coerceIn(
            DISCOVER_TRENDING_GENRE_ROWS_RANGE.first,
            DISCOVER_TRENDING_GENRE_ROWS_RANGE.last,
        )
        if (discoverTrendingGenreRows == clamped) return
        discoverTrendingGenreRows = clamped
        publish()
        persist()
    }

    /**
     * Whether a built-in row family renders at all, from the row-management list.
     *
     * The two slider-backed families have no separate enabled flag and deliberately never gain one:
     * a switch beside a count is two stored ways to say "off", and they drift. The switch is
     * therefore mapped onto the count, restoring [discoverRowCountBeforeOff] — or the shipped
     * default — when it comes back on.
     */
    fun setDiscoverRowFamilyEnabled(family: DiscoverRowFamily, enabled: Boolean) {
        ensureLoaded()
        when (family) {
            DiscoverRowFamily.Finish -> setDiscoverFinishWhatYouStartedEnabled(enabled)
            DiscoverRowFamily.Favourites -> setDiscoverMoreLikeFavouritesEnabled(enabled)
            DiscoverRowFamily.Gems -> setDiscoverHiddenGemsEnabled(enabled)
            DiscoverRowFamily.Because -> setDiscoverBecauseYouWatchedRows(
                countForFamilyToggle(family, enabled, discoverBecauseYouWatchedRows, DISCOVER_BECAUSE_ROWS_DEFAULT),
            )
            DiscoverRowFamily.Trending -> setDiscoverTrendingGenreRows(
                countForFamilyToggle(
                    family,
                    enabled,
                    discoverTrendingGenreRows,
                    DISCOVER_TRENDING_GENRE_ROWS_DEFAULT,
                ),
            )
        }
    }

    private fun countForFamilyToggle(
        family: DiscoverRowFamily,
        enabled: Boolean,
        current: Int,
        default: Int,
    ): Int {
        if (!enabled) {
            if (current > 0) discoverRowCountBeforeOff[family] = current
            return 0
        }
        if (current > 0) return current
        return discoverRowCountBeforeOff.remove(family)?.takeIf { it > 0 } ?: default
    }

    /**
     * Moves a row entry, by its index in the *normalised* order the settings page is displaying.
     *
     * Written back normalised, unlike every other order write: the drag came from that list, so the
     * indices only mean anything against it.
     */
    fun moveDiscoverRowByIndex(fromIndex: Int, toIndex: Int) {
        ensureLoaded()
        val current = normalizeDiscoverRowOrder(
            savedOrder = discoverRowOrder,
            customRowIds = discoverCustomRows.map { it.id },
            importedRowIds = discoverImportedRows.map { it.id },
            aiRowIds = discoverAiRows.map { it.id },
        )
        if (fromIndex !in current.indices || toIndex !in current.indices || fromIndex == toIndex) return
        val reordered = current.toMutableList()
        reordered.add(toIndex, reordered.removeAt(fromIndex))
        discoverRowOrder = reordered
        publish()
        persist()
    }

    /** Returns the new row, or null when the limit is already reached. */
    fun addDiscoverCustomRow(id: String): CustomDiscoverRow? {
        ensureLoaded()
        if (discoverCustomRows.size >= CUSTOM_DISCOVER_ROW_LIMIT) return null
        if (discoverCustomRows.any { it.id == id } || id.isBlank()) return null
        val row = CustomDiscoverRow(id = id)
        discoverCustomRows = discoverCustomRows + row
        publish()
        persist()
        return row
    }

    fun updateDiscoverCustomRow(row: CustomDiscoverRow) {
        ensureLoaded()
        val index = discoverCustomRows.indexOfFirst { it.id == row.id }
        if (index < 0) return
        val canonical = row.sanitizedForStorage()
        if (discoverCustomRows[index] == canonical) return
        discoverCustomRows = discoverCustomRows.toMutableList().apply { set(index, canonical) }
        publish()
        persist()
    }

    /**
     * Stores a list imported from a `nuvio-discover-catalog` file.
     *
     * Items are truncated to [IMPORTED_DISCOVER_ITEM_LIMIT] here rather than at the import UI: this
     * payload is persisted in full and round-trips through the settings file on every save, so the
     * cap has to hold wherever the row came from. Returns false when the limit is already reached,
     * so the caller can say why instead of appearing to succeed.
     */
    fun addDiscoverImportedRow(row: ImportedDiscoverRow): Boolean {
        ensureLoaded()
        if (row.id.isBlank() || row.items.isEmpty()) return false
        if (discoverImportedRows.size >= IMPORTED_DISCOVER_ROW_LIMIT) return false
        if (discoverImportedRows.any { it.id == row.id }) return false
        discoverImportedRows = discoverImportedRows + row.copy(
            items = row.items.take(IMPORTED_DISCOVER_ITEM_LIMIT),
        )
        publish()
        persist()
        return true
    }

    fun updateDiscoverImportedRow(row: ImportedDiscoverRow) {
        ensureLoaded()
        val index = discoverImportedRows.indexOfFirst { it.id == row.id }
        if (index < 0 || discoverImportedRows[index] == row) return
        discoverImportedRows = discoverImportedRows.toMutableList().apply { set(index, row) }
        publish()
        persist()
    }

    /**
     * Adds an empty AI row. Returns null when the limit is reached, so the caller can say why
     * rather than appearing to succeed — same contract as the custom-row adder.
     */
    fun addDiscoverAiRow(id: String): AiDiscoverRow? {
        ensureLoaded()
        if (id.isBlank()) return null
        if (discoverAiRows.size >= AI_DISCOVER_ROW_LIMIT) return null
        if (discoverAiRows.any { it.id == id }) return null
        val row = AiDiscoverRow(id = id)
        discoverAiRows = discoverAiRows + row
        publish()
        persist()
        return row
    }

    fun updateDiscoverAiRow(row: AiDiscoverRow) {
        ensureLoaded()
        val index = discoverAiRows.indexOfFirst { it.id == row.id }
        if (index < 0) return
        // Capped on write as well as read: a generation that came back long would otherwise sit in
        // the settings payload at full length and be rewritten on every save.
        val next = row.capped()
        if (discoverAiRows[index] == next) return
        discoverAiRows = discoverAiRows.toMutableList().apply { set(index, next) }
        publish()
        persist()
    }

    fun removeDiscoverAiRow(id: String) {
        ensureLoaded()
        if (discoverAiRows.none { it.id == id }) return
        discoverAiRows = discoverAiRows.filterNot { it.id == id }
        // Same reasoning as the other two removers: drop the saved position too, so a later row
        // reusing this id does not inherit a deleted one's place in the order.
        discoverRowOrder = discoverRowOrder.filterNot { it == aiDiscoverEntryId(id) }
        publish()
        persist()
    }

    fun removeDiscoverImportedRow(id: String) {
        ensureLoaded()
        if (discoverImportedRows.none { it.id == id }) return
        discoverImportedRows = discoverImportedRows.filterNot { it.id == id }
        // Same reasoning as removeDiscoverCustomRow: drop the entry id too, so a later import
        // reusing this id does not inherit a deleted row's saved position.
        discoverRowOrder = discoverRowOrder.filterNot { it == importedDiscoverEntryId(id) }
        publish()
        persist()
    }

    fun removeDiscoverCustomRow(id: String) {
        ensureLoaded()
        if (discoverCustomRows.none { it.id == id }) return
        discoverCustomRows = discoverCustomRows.filterNot { it.id == id }
        // The entry id goes too, so that recreating a row with the same id later does not inherit
        // the deleted row's position from a stale saved order.
        discoverRowOrder = discoverRowOrder.filterNot { it == customDiscoverEntryId(id) }
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

    /**
     * Shows the per-row shuffle control on home catalog rows.
     *
     * Local-only: it stays out of [SyncHomeCatalogPayload] because that schema is shared with the
     * TV and mobile clients, which have no such control. Turning it off also drops any shuffles
     * already rolled, so the rows go back to catalog order rather than being stranded in a random
     * arrangement the user can no longer re-roll or clear.
     */
    fun setCatalogRowShuffleEnabled(enabled: Boolean) {
        ensureLoaded()
        if (catalogRowShuffleEnabled == enabled) return
        catalogRowShuffleEnabled = enabled
        if (!enabled) HomeRowShuffleState.clearAll()
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

    /**
     * Moves the home screen to [mode] in one write.
     *
     * Deliberately assigns all three backing flags rather than delegating to
     * [setAdaptiveHeroEnabled]/[setHeroAmbientBackgroundEnabled]/[setTvModeEnabled]: those clear
     * each other, so calling them in sequence is order-dependent — ambient set before the adaptive
     * hero, or while TV mode is still on, is silently coerced back to false. Going through them
     * would also cost up to three persists and a Home rebuild through a transient state that is
     * none of the four modes.
     */
    fun setDisplayMode(mode: HomeDisplayMode) {
        ensureLoaded()
        val next = mode.toFlags()
        if (adaptiveHeroEnabled == next.adaptiveHeroEnabled &&
            heroAmbientBackgroundEnabled == next.heroAmbientBackgroundEnabled &&
            tvModeEnabled == next.tvModeEnabled
        ) {
            return
        }
        adaptiveHeroEnabled = next.adaptiveHeroEnabled
        heroAmbientBackgroundEnabled = next.heroAmbientBackgroundEnabled
        tvModeEnabled = next.tvModeEnabled
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
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

    /**
     * Whether TV Mode's backdrop runs to the bottom of the screen instead of stopping above the
     * shelf. Stored regardless of the mode being on, same as the row dots, so the settings row
     * reports what the user chose rather than what is currently in effect.
     */
    fun setTvFullBackdropEnabled(enabled: Boolean) {
        ensureLoaded()
        if (tvFullBackdropEnabled == enabled) return
        tvFullBackdropEnabled = enabled
        publish()
        persist()
    }

    /**
     * Whether hovering a poster opens the preview card, stored per display mode rather than
     * globally: the preview suits Basic, where the page is otherwise static, and fights the
     * Adaptive hero, which is already reacting to the row under the pointer. TV Mode has no
     * setting at all — see [hoverPreviewEnabledFor].
     */
    fun setHoverPreviewEnabled(mode: HomeDisplayMode, enabled: Boolean) {
        ensureLoaded()
        when (mode) {
            HomeDisplayMode.Basic -> {
                if (hoverPreviewBasicEnabled == enabled) return
                hoverPreviewBasicEnabled = enabled
            }
            HomeDisplayMode.Adaptive, HomeDisplayMode.AdaptiveAmbient -> {
                if (hoverPreviewAdaptiveEnabled == enabled) return
                hoverPreviewAdaptiveEnabled = enabled
            }
            HomeDisplayMode.TvMode -> return
        }
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
        discoverHideWatched = true
        discoverBecauseYouWatchedRows = DISCOVER_BECAUSE_ROWS_DEFAULT
        discoverFinishWhatYouStartedEnabled = true
        discoverFinishIdleDays = DISCOVER_FINISH_IDLE_DAYS_DEFAULT
        discoverMoreLikeFavouritesEnabled = true
        discoverHiddenGemsEnabled = true
        discoverTrendingGenreRows = DISCOVER_TRENDING_GENRE_ROWS_DEFAULT
        discoverExcludedGenres = emptySet()
        discoverRowOrder = emptyList()
        discoverCustomRows = emptyList()
        discoverImportedRows = emptyList()
        discoverAiRows = emptyList()
        discoverRowCountBeforeOff.clear()
        hideCatalogUnderline = false
        catalogRowShuffleEnabled = false
        adaptiveHeroEnabled = false
        adaptiveHeroVerticalBias = ADAPTIVE_HERO_VERTICAL_BIAS_DEFAULT
        adaptiveHeroHeightMultiplier = ADAPTIVE_HERO_HEIGHT_MULTIPLIER_DEFAULT
        heroAmbientBackgroundEnabled = false
        tvModeEnabled = false
        smoothScrollingEnabled = true
        hoverPreviewBasicEnabled = true
        hoverPreviewAdaptiveEnabled = false
        catalogSeeMoreEnabled = false
        catalogRowNumbersEnabled = false
        tvRowDotsEnabled = false
        tvRowDotsAnchor = HomeTvRowDotsAnchor.RowTitle
        tvFullBackdropEnabled = false
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
            discoverHideWatched = parsedPayload.discoverHideWatched
            discoverBecauseYouWatchedRows = parsedPayload.discoverBecauseYouWatchedRows
                .coerceIn(DISCOVER_BECAUSE_ROWS_RANGE.first, DISCOVER_BECAUSE_ROWS_RANGE.last)
            discoverFinishWhatYouStartedEnabled = parsedPayload.discoverFinishWhatYouStartedEnabled
            discoverFinishIdleDays = parsedPayload.discoverFinishIdleDays
                .coerceIn(DISCOVER_FINISH_IDLE_DAYS_RANGE.first, DISCOVER_FINISH_IDLE_DAYS_RANGE.last)
            discoverMoreLikeFavouritesEnabled = parsedPayload.discoverMoreLikeFavouritesEnabled
            discoverHiddenGemsEnabled = parsedPayload.discoverHiddenGemsEnabled
            discoverTrendingGenreRows = parsedPayload.discoverTrendingGenreRows
                .coerceIn(DISCOVER_TRENDING_GENRE_ROWS_RANGE.first, DISCOVER_TRENDING_GENRE_ROWS_RANGE.last)
            // Canonicalised on load so a stored name that no longer matches the list is dropped
            // rather than silently excluding nothing under a name the settings page cannot show.
            discoverExcludedGenres = parsedPayload.discoverExcludedGenres.canonicalDiscoverGenres()
            // Custom rows are canonicalised the same way, and a row whose id is blank or duplicated
            // is dropped: the id is the lazy-list key on the settings page and the entry id in the
            // order, so a collision is a crash rather than a cosmetic problem.
            discoverCustomRows = parsedPayload.discoverCustomRows
                .filter { it.id.isNotBlank() }
                .distinctBy { it.id }
                .map { row -> row.sanitizedForStorage() }
                .take(CUSTOM_DISCOVER_ROW_LIMIT)
            // Imported lists get the same treatment for the same reason, plus the per-row item cap:
            // these carry their contents in the settings payload, so a file with ten thousand items
            // would otherwise become ten thousand items written back on every settings save.
            discoverImportedRows = parsedPayload.discoverImportedRows
            discoverAiRows = parsedPayload.discoverAiRows.map { it.capped() }
                .filter { it.id.isNotBlank() && it.items.isNotEmpty() }
                .distinctBy { it.id }
                .map { row -> row.copy(items = row.items.take(IMPORTED_DISCOVER_ITEM_LIMIT)) }
                .take(IMPORTED_DISCOVER_ROW_LIMIT)
            discoverRowOrder = parsedPayload.discoverRowOrder
            hideCatalogUnderline = parsedPayload.hideCatalogUnderline
            catalogRowShuffleEnabled = parsedPayload.catalogRowShuffleEnabled
            adaptiveHeroEnabled = parsedPayload.adaptiveHeroEnabled
            adaptiveHeroVerticalBias = normalizeAdaptiveHeroVerticalBias(parsedPayload.adaptiveHeroVerticalBias)
            adaptiveHeroHeightMultiplier = normalizeAdaptiveHeroHeightMultiplier(parsedPayload.adaptiveHeroHeightMultiplier)
            heroAmbientBackgroundEnabled = parsedPayload.heroAmbientBackgroundEnabled
            tvModeEnabled = parsedPayload.tvModeEnabled
            smoothScrollingEnabled = parsedPayload.smoothScrollingEnabled
            hoverPreviewBasicEnabled = parsedPayload.hoverPreviewBasicEnabled
            hoverPreviewAdaptiveEnabled = parsedPayload.hoverPreviewAdaptiveEnabled
            catalogSeeMoreEnabled = parsedPayload.catalogSeeMoreEnabled
            catalogRowNumbersEnabled = parsedPayload.catalogRowNumbersEnabled
            tvRowDotsEnabled = parsedPayload.tvRowDotsEnabled
            tvRowDotsAnchor = parsedPayload.tvRowDotsAnchor
            tvFullBackdropEnabled = parsedPayload.tvFullBackdropEnabled
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
            // Discover's knobs are read by the repository through `snapshot()`, but the settings
            // page renders from this state — leaving them out here left the rows slider and the
            // hide-watched switch showing their defaults no matter what was saved.
            discoverHideWatched = discoverHideWatched,
            discoverBecauseYouWatchedRows = discoverBecauseYouWatchedRows,
            discoverFinishWhatYouStartedEnabled = discoverFinishWhatYouStartedEnabled,
            discoverFinishIdleDays = discoverFinishIdleDays,
            discoverMoreLikeFavouritesEnabled = discoverMoreLikeFavouritesEnabled,
            discoverHiddenGemsEnabled = discoverHiddenGemsEnabled,
            discoverTrendingGenreRows = discoverTrendingGenreRows,
            discoverExcludedGenres = discoverExcludedGenres,
            discoverRowOrder = normalizeDiscoverRowOrder(
                savedOrder = discoverRowOrder,
                customRowIds = discoverCustomRows.map { it.id },
                importedRowIds = discoverImportedRows.map { it.id },
                aiRowIds = discoverAiRows.map { it.id },
            ),
            discoverCustomRows = discoverCustomRows,
            discoverImportedRows = discoverImportedRows,
            discoverAiRows = discoverAiRows,
            // TV Mode's shelf rows don't read well with the underline accent — force it off
            // while active without touching the user's actual saved preference, so it comes
            // back exactly as they left it if they turn TV Mode back off.
            hideCatalogUnderline = hideCatalogUnderline || tvModeEnabled,
            catalogRowShuffleEnabled = catalogRowShuffleEnabled,
            adaptiveHeroEnabled = adaptiveHeroEnabled,
            adaptiveHeroVerticalBias = adaptiveHeroVerticalBias,
            adaptiveHeroHeightMultiplier = adaptiveHeroHeightMultiplier,
            heroAmbientBackgroundEnabled = heroAmbientBackgroundEnabled,
            tvModeEnabled = tvModeEnabled,
            smoothScrollingEnabled = smoothScrollingEnabled,
            // Reported raw for the same reason the TV row dots are: the settings row shows
            // what the user saved for that mode, and the preview itself gates on the mode.
            hoverPreviewBasicEnabled = hoverPreviewBasicEnabled,
            hoverPreviewAdaptiveEnabled = hoverPreviewAdaptiveEnabled,
            catalogSeeMoreEnabled = catalogSeeMoreEnabled,
            catalogRowNumbersEnabled = catalogRowNumbersEnabled,
            // Reported raw (not && tvModeEnabled) so the settings row keeps showing what the user
            // saved while the toggle sits disabled outside TV Mode; the shelf gates on the mode.
            tvRowDotsEnabled = tvRowDotsEnabled,
            tvRowDotsAnchor = tvRowDotsAnchor,
            tvFullBackdropEnabled = tvFullBackdropEnabled,
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
                    discoverHideWatched = discoverHideWatched,
                    discoverBecauseYouWatchedRows = discoverBecauseYouWatchedRows,
                    discoverFinishWhatYouStartedEnabled = discoverFinishWhatYouStartedEnabled,
                    discoverFinishIdleDays = discoverFinishIdleDays,
                    discoverMoreLikeFavouritesEnabled = discoverMoreLikeFavouritesEnabled,
                    discoverHiddenGemsEnabled = discoverHiddenGemsEnabled,
                    discoverTrendingGenreRows = discoverTrendingGenreRows,
                    discoverExcludedGenres = discoverExcludedGenres,
                    // Saved raw, not normalised — see HomeCatalogSettingsUiState.discoverRowOrder.
                    discoverRowOrder = discoverRowOrder,
                    discoverCustomRows = discoverCustomRows,
                    discoverImportedRows = discoverImportedRows,
                    discoverAiRows = discoverAiRows,
                    hideCatalogUnderline = hideCatalogUnderline,
                    catalogRowShuffleEnabled = catalogRowShuffleEnabled,
                    adaptiveHeroEnabled = adaptiveHeroEnabled,
                    adaptiveHeroVerticalBias = adaptiveHeroVerticalBias,
                    adaptiveHeroHeightMultiplier = adaptiveHeroHeightMultiplier,
                    heroAmbientBackgroundEnabled = heroAmbientBackgroundEnabled,
                    tvModeEnabled = tvModeEnabled,
                    smoothScrollingEnabled = smoothScrollingEnabled,
                    hoverPreviewBasicEnabled = hoverPreviewBasicEnabled,
                    hoverPreviewAdaptiveEnabled = hoverPreviewAdaptiveEnabled,
                    catalogSeeMoreEnabled = catalogSeeMoreEnabled,
                    catalogRowNumbersEnabled = catalogRowNumbersEnabled,
                    tvRowDotsEnabled = tvRowDotsEnabled,
                    tvFullBackdropEnabled = tvFullBackdropEnabled,
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
