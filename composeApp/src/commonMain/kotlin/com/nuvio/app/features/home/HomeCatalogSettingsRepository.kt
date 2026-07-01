package com.nuvio.app.features.home

import com.nuvio.app.features.addons.ManagedAddon
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
    "wins,gg_wins,festival,pic_noms,gg_noms,emmy_noms,studio,director,trending,cult,foreign,new_release,metacritic,true_story,short_film,mini_series,binge_ready,release_status"
private const val HERO_INFO_LINES_MIN = 0
private const val HERO_INFO_LINES_MAX = 6
private const val HERO_BADGE_SCALE_MIN = 1f
private const val HERO_BADGE_SCALE_MAX = 2.5f
private const val ADAPTIVE_HERO_VERTICAL_BIAS_MIN = -1f
private const val ADAPTIVE_HERO_VERTICAL_BIAS_MAX = 1f
private const val ADAPTIVE_HERO_VERTICAL_BIAS_DEFAULT = -0.58f

data class HomeCatalogSettingsItem(
    val key: String,
    val defaultTitle: String,
    val addonName: String,
    val customTitle: String = "",
    val enabled: Boolean = true,
    val heroSourceEnabled: Boolean = true,
    val order: Int = 0,
    val isCollection: Boolean = false,
    val collectionId: String? = null,
    val isPinnedToTop: Boolean = false,
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
    val heroAmbientBackgroundEnabled: Boolean = false,
    val tvModeEnabled: Boolean = false,
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
            append(heroAmbientBackgroundEnabled)
            append('|')
            append(tvModeEnabled)
            append('|')
            append(
                items.joinToString(separator = "|") { item ->
                    "${item.key}:${item.order}:${item.enabled}:${item.heroSourceEnabled}:${item.customTitle}"
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
    val heroAmbientBackgroundEnabled: Boolean,
    val tvModeEnabled: Boolean,
    val preferences: Map<String, HomeCatalogPreference>,
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
    val enabled: Boolean = true,
    val heroSourceEnabled: Boolean = true,
    val order: Int = 0,
)

@Serializable
private data class StoredHomeCatalogSettingsPayload(
    val heroEnabled: Boolean = true,
    val heroInfoLines: Int = 2,
    val heroInfoPriority: String = DEFAULT_HERO_INFO_PRIORITY,
    val heroBadgePlacement: HeroBadgePlacement = HeroBadgePlacement.BottomBackdrop,
    val heroBadgeScale: Float = 1f,
    val heroReleaseStatusUnavailableOnly: Boolean = true,
    val hideUnreleasedContent: Boolean = false,
    val hideCatalogUnderline: Boolean = false,
    @SerialName("tvModeEnabled")
    val adaptiveHeroEnabled: Boolean = false,
    val adaptiveHeroVerticalBias: Float = -0.58f,
    val heroAmbientBackgroundEnabled: Boolean = false,
    @SerialName("immersiveCatalogModeEnabled")
    val tvModeEnabled: Boolean = false,
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
    private var heroBadgePlacement = HeroBadgePlacement.BottomBackdrop
    private var heroBadgeScale = 1f
    private var heroReleaseStatusUnavailableOnly = true
    private var hideUnreleasedContent = false
    private var hideCatalogUnderline = false
    private var adaptiveHeroEnabled = false
    private var adaptiveHeroVerticalBias = ADAPTIVE_HERO_VERTICAL_BIAS_DEFAULT
    private var heroAmbientBackgroundEnabled = false
    private var tvModeEnabled = false

    fun onProfileChanged() {
        hasLoaded = false
        preferences.clear()
        heroEnabled = true
        heroInfoLines = 2
        heroInfoPriority = DEFAULT_HERO_INFO_PRIORITY
        heroBadgePlacement = HeroBadgePlacement.BottomBackdrop
        heroBadgeScale = 1f
        heroReleaseStatusUnavailableOnly = true
        hideUnreleasedContent = false
        hideCatalogUnderline = false
        adaptiveHeroEnabled = false
        adaptiveHeroVerticalBias = ADAPTIVE_HERO_VERTICAL_BIAS_DEFAULT
        heroAmbientBackgroundEnabled = false
        tvModeEnabled = false
        definitions = emptyList()
        collectionDefinitions = emptyList()
        _uiState.value = HomeCatalogSettingsUiState()
    }

    fun clearLocalState() {
        hasLoaded = false
        definitions = emptyList()
        collectionDefinitions = emptyList()
        preferences.clear()
        heroEnabled = true
        heroInfoLines = 2
        heroInfoPriority = DEFAULT_HERO_INFO_PRIORITY
        heroBadgePlacement = HeroBadgePlacement.BottomBackdrop
        heroBadgeScale = 1f
        heroReleaseStatusUnavailableOnly = true
        hideUnreleasedContent = false
        hideCatalogUnderline = false
        adaptiveHeroEnabled = false
        adaptiveHeroVerticalBias = ADAPTIVE_HERO_VERTICAL_BIAS_DEFAULT
        heroAmbientBackgroundEnabled = false
        tvModeEnabled = false
        _uiState.value = HomeCatalogSettingsUiState()
    }

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
            heroAmbientBackgroundEnabled = heroAmbientBackgroundEnabled,
            tvModeEnabled = tvModeEnabled,
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

    fun setHeroSourceEnabled(key: String, enabled: Boolean) {
        updatePreference(key) { preference ->
            if (!enabled) {
                preference.copy(heroSourceEnabled = false)
            } else if (selectedHeroSourceCount(excludingKey = key) >= HERO_SOURCE_SELECTION_LIMIT) {
                preference
            } else {
                preference.copy(heroSourceEnabled = true)
            }
        }
    }

    fun setEnabled(key: String, enabled: Boolean) {
        updatePreference(key) { preference ->
            preference.copy(enabled = enabled)
        }
    }

    fun setCustomTitle(key: String, title: String) {
        updatePreference(key) { preference ->
            preference.copy(customTitle = title)
        }
    }

    fun resetToDefaults() {
        ensureLoaded()
        heroEnabled = true
        heroInfoLines = 2
        heroInfoPriority = DEFAULT_HERO_INFO_PRIORITY
        heroBadgePlacement = HeroBadgePlacement.BottomBackdrop
        heroBadgeScale = 1f
        heroReleaseStatusUnavailableOnly = true
        hideUnreleasedContent = false
        hideCatalogUnderline = false
        adaptiveHeroEnabled = false
        adaptiveHeroVerticalBias = ADAPTIVE_HERO_VERTICAL_BIAS_DEFAULT
        heroAmbientBackgroundEnabled = false
        tvModeEnabled = false
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
            heroAmbientBackgroundEnabled = parsedPayload.heroAmbientBackgroundEnabled
            tvModeEnabled = parsedPayload.tvModeEnabled
            normalizeHeroModes()
            preferences = parsedPayload.items.associateBy { it.key }.toMutableMap()
            publish()
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
        data class UnifiedEntry(val key: String, val isCollection: Boolean)
        val catalogEntries = definitions.map { UnifiedEntry(it.key, false) }
        val collectionEntries = collectionDefinitions.map { UnifiedEntry(it.key, true) }
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
            val heroSourceEnabled = if (entry.isCollection) {
                false
            } else {
                (stored?.heroSourceEnabled ?: true) &&
                    enabledHeroSourceCount < HERO_SOURCE_SELECTION_LIMIT
            }
            if (heroSourceEnabled) {
                enabledHeroSourceCount += 1
            }
            normalized[entry.key] = StoredHomeCatalogPreference(
                key = entry.key,
                customTitle = stored?.customTitle.orEmpty(),
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
                enabled = preference?.enabled ?: true,
                heroSourceEnabled = false,
                order = preference?.order ?: 0,
                isCollection = true,
                collectionId = colDef.collectionId,
                isPinnedToTop = colDef.isPinnedToTop,
            )
        }

        val items = (catalogItems + collectionItems)
            .sortedBy { it.order }

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
            heroAmbientBackgroundEnabled = heroAmbientBackgroundEnabled,
            tvModeEnabled = tvModeEnabled,
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

    private fun normalizeHeroInfoPriority(priority: String): String {
        val slots = priority
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .toMutableList()
        if ("emmy_noms" in slots) return slots.joinToString(",")

        val insertIndex = slots.indexOf("gg_noms").takeIf { it >= 0 }
            ?.let { it + 1 }
            ?: slots.indexOf("pic_noms").takeIf { it >= 0 }?.let { it + 1 }
            ?: slots.size
        slots.add(insertIndex, "emmy_noms")
        return slots.joinToString(",")
    }

    private fun persist() {
        HomeCatalogSettingsStorage.savePayload(
            json.encodeToString(
                StoredHomeCatalogSettingsPayload(
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
                    heroAmbientBackgroundEnabled = heroAmbientBackgroundEnabled,
                    tvModeEnabled = tvModeEnabled,
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

    private fun selectedHeroSourceCount(excludingKey: String? = null): Int {
        val catalogKeys = definitions.mapTo(mutableSetOf()) { it.key }
        return preferences.count { (itemKey, preference) ->
            itemKey != excludingKey && itemKey in catalogKeys && preference.heroSourceEnabled
        }
    }

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
                    isCollection = true,
                    collectionId = pref.key.removePrefix("collection_"),
                )
            } else {
                SyncCatalogItem(
                    addonId = parts.getOrElse(0) { "" },
                    type = parts.getOrElse(1) { "" },
                    catalogId = parts.getOrElse(2) { "" },
                    enabled = pref.enabled,
                    order = pref.order,
                    customTitle = pref.customTitle,
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
            preferences = payload.items.associate { item ->
                val key = if (item.isCollection) {
                    "collection_${item.collectionId}"
                } else {
                    "${item.addonId}:${item.type}:${item.catalogId}"
                }
                key to StoredHomeCatalogPreference(
                    key = key,
                    customTitle = item.customTitle,
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
)

internal fun buildCollectionDefinitions(collections: List<Collection>): List<CollectionCatalogDefinition> =
    collections.filter { it.folders.isNotEmpty() }.map { collection ->
        CollectionCatalogDefinition(
            key = "collection_${collection.id}",
            collectionId = collection.id,
            title = collection.title,
            subtitle = runBlocking { getString(Res.string.collections_folder_count, collection.folders.size) },
            isPinnedToTop = collection.pinToTop,
        )
    }
