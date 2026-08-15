package com.nuvio.app.features.home

import kotlin.random.Random

/**
 * Resolves one Random Play click against a pool that may still be warming.
 *
 * The current rows are classified first so an unverified anime film cannot win the race against
 * its metadata lookup. When that still yields no candidate, collection windows are loaded one at a
 * time and the freshly published rows are classified before retrying the pick. Callers provide the
 * side effects so the retry policy stays deterministic and can be regression-tested without the
 * repositories or network.
 */
internal suspend fun pickRandomPlayItemWithRefill(
    category: RandomPlayCategory,
    settings: HomeCatalogSettingsUiState,
    watchedKeys: Set<String>,
    sourceSections: () -> List<HomeCatalogSection>,
    poolState: () -> RandomPlayPoolState,
    fillCandidates: suspend (List<HomeCatalogSection>) -> Unit,
    loadNextCollectionWindow: suspend () -> Boolean,
    maxCollectionWindows: Int = RANDOM_PLAY_CLICK_MAX_COLLECTION_WINDOWS,
    random: Random = Random.Default,
): MetaPreview? {
    suspend fun classifyAndPick(): MetaPreview? {
        val base = sourceSections()
        fillCandidates(base)
        return pickRandomPlayItem(
            category = category,
            sourceSections = base.withRandomPlayPool(poolState()),
            settings = settings,
            watchedKeys = watchedKeys,
            random = random,
        )
    }

    classifyAndPick()?.let { return it }
    repeat(maxCollectionWindows.coerceAtLeast(0)) {
        val hasMoreWork = loadNextCollectionWindow()
        classifyAndPick()?.let { return it }
        if (!hasMoreWork) return null
    }
    return null
}

internal const val RANDOM_PLAY_CLICK_MAX_COLLECTION_WINDOWS = 4
internal const val RANDOM_PLAY_CLICK_REFILL_TIMEOUT_MS = 10_000L
