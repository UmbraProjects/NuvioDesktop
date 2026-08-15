package com.nuvio.app.features.home

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.catalog.fetchCatalogPage
import com.nuvio.app.features.collection.Collection
import com.nuvio.app.features.collection.CollectionFolder
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.collection.CollectionSource
import com.nuvio.app.features.collection.TmdbCollectionSourceResolver
import com.nuvio.app.features.collection.catalogRouteKey
import com.nuvio.app.features.collection.findCollectionCatalog
import com.nuvio.app.features.trakt.TraktPublicListSourceResolver
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Random Play normally draws from whatever Home already loaded. Collection catalogs are only
 * fetched when the user opens a folder, so with "Include Collections" on this keeps a single
 * page of every collection source warm and feeds it into the same candidate pool.
 *
 * Nothing here is rendered: the sections exist purely so [randomPlayCandidates] can read the
 * items (and, for addon sources, the row's genre) exactly as it does for Home rows.
 */
object RandomPlayCollectionPool {
    private val log = Logger.withTag("RandomPlayCollectionPool")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _sections = MutableStateFlow<List<HomeCatalogSection>>(emptyList())
    val sections: StateFlow<List<HomeCatalogSection>> = _sections.asStateFlow()

    private var activeJob: Deferred<RandomPlayCollectionLoadResult>? = null
    private var loadedRequestKey: String? = null

    /**
     * Sources already fetched under [loadedRequestKey], kept across passes so each new window adds
     * to the pool instead of replacing it.
     */
    private val loadedSections = linkedMapOf<String, HomeCatalogSection>()

    /**
     * Successful sources, deterministic traversal position, and retry cooldowns for the active key.
     * A source does not become complete until it returns a non-empty section.
     */
    private var sourceProgress: RandomPlayCollectionSourceProgress? = null

    /**
     * Fetches one page for a window of collection sources, and adds it to whatever earlier windows
     * already loaded. The request key covers the collections, the installed addons and the
     * unreleased-content filter, so any change to those starts the coverage over.
     *
     * An imported community collection can hold hundreds of sources; fetching a page of every one
     * on every pass is not something to aim at an addon. So each pass takes
     * [RANDOM_PLAY_COLLECTION_SOURCE_WINDOW] of them and the next pass continues where it stopped.
     * Structured anime catalogs are traversed first, then every remaining source in stable order.
     * Only a non-empty response completes a source; null, empty, and failed responses remain pending
     * and are retried with backoff after traversal moves on.
     */
    fun ensureLoaded(force: Boolean = false) {
        requestWindow(force)
    }

    /**
     * Requests one bounded source window and waits for it. An in-flight window is joined instead of
     * duplicated. Callers can wrap this in a timeout; [RandomPlayCollectionLoadResult] distinguishes
     * useful progress, remaining immediate work, and sources deferred behind retry cooldown.
     */
    internal suspend fun loadNextWindowAndAwait(): RandomPlayCollectionLoadResult {
        val requested = requestWindow(force = false)
        return requested?.await() ?: currentLoadResult()
    }

    private fun requestWindow(force: Boolean): Deferred<RandomPlayCollectionLoadResult>? {
        val settings = HomeCatalogSettingsRepository.snapshot()
        // Turning either option off must cancel and drop, not just decline to start: a plain return
        // leaves an in-flight pass fetching catalogs nobody asked for and keeps every loaded section
        // referenced for the rest of the session.
        if (!settings.randomPlayEnabled || !settings.randomPlayIncludeCollections) {
            clear()
            return null
        }

        val addons = AddonRepository.uiState.value.addons.enabledAddons()
        val sources = collectionSources(CollectionRepository.collections.value)
        if (sources.isEmpty()) {
            clear()
            return null
        }

        val requestKey = requestKey(
            sources = sources,
            addons = addons,
            hideUnreleasedContent = settings.hideUnreleasedContent,
        )
        if (force || requestKey != loadedRequestKey) {
            activeJob?.cancel()
            loadedSections.clear()
            _sections.value = emptyList()
            // collectionSources places structured anime catalogs first. Always beginning there
            // removes the old random cold-start dependency while traversal remains all-inclusive.
            sourceProgress = RandomPlayCollectionSourceProgress(
                sourceKeys = sources.map(PoolSource::routeKey),
                startIndex = 0,
            )
            loadedRequestKey = requestKey
        } else {
            activeJob?.takeIf { it.isActive }?.let { return it }
        }

        val progress = sourceProgress ?: return null
        val sourcesByKey = sources.associateBy(PoolSource::routeKey)
        val window = progress.nextWindow(
            nowMs = System.currentTimeMillis(),
            size = RANDOM_PLAY_COLLECTION_SOURCE_WINDOW,
        ).mapNotNull(sourcesByKey::get)
        if (window.isEmpty()) return null

        val request = scope.async {
            var loadedThisWindow = 0
            var retryableThisWindow = 0
            window.chunked(RANDOM_PLAY_COLLECTION_FETCH_BATCH_SIZE).forEach { batch ->
                if (loadedRequestKey != requestKey) return@async currentLoadResult()
                val batchResults = batch.map { source ->
                    async {
                        try {
                            SourceFetchResult(source = source, section = source.toSection(addons))
                        } catch (cancellation: CancellationException) {
                            // runCatching swallows this, which kept a cancelled pass fetching the
                            // remaining batches instead of unwinding.
                            throw cancellation
                        } catch (error: Throwable) {
                            SourceFetchResult(source = source, error = error)
                        }
                    }
                }.awaitAll()
                if (loadedRequestKey != requestKey) return@async currentLoadResult()

                val completedAtMs = System.currentTimeMillis()
                batchResults.forEach { result ->
                    val section = result.section
                    if (section != null && section.items.isNotEmpty()) {
                        loadedSections[section.key] = section
                        progress.markLoaded(result.source.routeKey)
                        loadedThisWindow++
                    } else {
                        progress.markRetryable(result.source.routeKey, completedAtMs)
                        retryableThisWindow++
                        result.error?.let { error ->
                            log.w(error) {
                                "Random Play collection source failed: ${result.source.routeKey}"
                            }
                        }
                    }
                }
                _sections.value = loadedSections.values.toList()
            }
            log.i {
                "Random Play collection pool: window of ${window.size} fetched, " +
                    "${loadedSections.size} sources loaded across ${progress.completedCount} / " +
                    "${sources.size} covered, ${loadedSections.values.sumOf { it.items.size }} items; " +
                    "$retryableThisWindow retryable"
            }
            currentLoadResult(
                attemptedSources = window.size,
                loadedSources = loadedThisWindow,
            )
        }
        activeJob = request
        return request
    }

    fun clear() {
        activeJob?.cancel()
        activeJob = null
        loadedRequestKey = null
        loadedSections.clear()
        sourceProgress = null
        _sections.value = emptyList()
    }

    fun onProfileChanged() = clear()

    private fun collectionSources(collections: List<Collection>): List<PoolSource> =
        collections
            .flatMap { collection ->
                collection.folders.flatMap { folder -> folder.poolSources(collection) }
            }
            .distinctBy(PoolSource::routeKey)
            .stableAnimeSourcesFirst { poolSource ->
                poolSource.source.addonCatalogSource()?.type
            }

    private fun CollectionFolder.poolSources(collection: Collection): List<PoolSource> =
        resolvedSources.map { source ->
            PoolSource(
                collectionId = collection.id,
                folderId = id,
                source = source,
                routeKey = source.catalogRouteKey(),
            )
        }

    private suspend fun PoolSource.toSection(addons: List<ManagedAddon>): HomeCatalogSection? {
        val key = "$RANDOM_PLAY_COLLECTION_SECTION_PREFIX$routeKey"
        val hideUnreleased = HomeCatalogSettingsRepository.snapshot().hideUnreleasedContent
        fun List<MetaPreview>.filtered(): List<MetaPreview> =
            if (hideUnreleased) filterReleasedItems(CurrentDateProvider.todayIsoDate()) else this

        if (source.isTmdb || source.isTrakt) {
            val page = if (source.isTmdb) {
                TmdbCollectionSourceResolver.resolve(source = source, page = 1)
            } else {
                TraktPublicListSourceResolver.resolve(source = source, page = 1)
            }
            return HomeCatalogSection(
                key = key,
                title = source.title.orEmpty(),
                subtitle = "",
                addonName = "",
                target = CatalogTarget.CollectionSource(
                    collectionId = collectionId,
                    folderId = folderId,
                    sourceKey = routeKey,
                    contentType = if (source.mediaType.equals("tv", ignoreCase = true)) "series" else "movie",
                ),
                items = page.items.filtered().take(RANDOM_PLAY_COLLECTION_SOURCE_ITEM_LIMIT),
            )
        }

        val catalogSource = source.addonCatalogSource() ?: return null
        val resolved = addons.findCollectionCatalog(catalogSource) ?: return null
        val page = fetchCatalogPage(
            manifestUrl = resolved.addon.manifestUrl,
            type = catalogSource.type,
            catalogId = catalogSource.catalogId,
            genre = catalogSource.genre,
            maxItems = RANDOM_PLAY_COLLECTION_SOURCE_ITEM_LIMIT,
        )
        return HomeCatalogSection(
            key = key,
            title = resolved.catalog.name,
            subtitle = resolved.addon.displayTitle,
            addonName = resolved.addon.displayTitle,
            // Addon target so the row's genre keeps feeding the Random Play genre filter, the
            // same way a genre-scoped Home row does.
            target = CatalogTarget.Addon(
                manifestUrl = resolved.addon.manifestUrl,
                contentType = catalogSource.type,
                catalogId = catalogSource.catalogId,
                genre = catalogSource.genre,
            ),
            items = page.items.filtered(),
        )
    }

    private fun requestKey(
        sources: List<PoolSource>,
        addons: List<ManagedAddon>,
        hideUnreleasedContent: Boolean,
    ): String = buildString {
        append("hideUnreleased=")
        append(hideUnreleasedContent)
        append("|sources=")
        sources.forEach { source ->
            append(source.routeKey)
            append(';')
        }
        append("|addons=")
        addons.forEach { addon ->
            append(addon.manifest?.id.orEmpty())
            append(':')
            append(addon.manifestUrl)
            append(';')
        }
    }

    private data class PoolSource(
        val collectionId: String,
        val folderId: String,
        val source: CollectionSource,
        val routeKey: String,
    )

    private data class SourceFetchResult(
        val source: PoolSource,
        val section: HomeCatalogSection? = null,
        val error: Throwable? = null,
    )

    private fun currentLoadResult(
        attemptedSources: Int = 0,
        loadedSources: Int = 0,
    ): RandomPlayCollectionLoadResult {
        val progress = sourceProgress
        return RandomPlayCollectionLoadResult(
            attemptedSources = attemptedSources,
            loadedSources = loadedSources,
            retryableSources = progress?.retryableCount ?: 0,
            completedSources = progress?.completedCount ?: 0,
            totalSources = progress?.totalCount ?: 0,
            canLoadMoreImmediately = progress?.hasEligibleSource(System.currentTimeMillis()) == true,
        )
    }
}

internal data class RandomPlayCollectionLoadResult(
    val attemptedSources: Int,
    val loadedSources: Int,
    val retryableSources: Int,
    val completedSources: Int,
    val totalSources: Int,
    val canLoadMoreImmediately: Boolean,
) {
    val madeProgress: Boolean get() = loadedSources > 0
    val hasMoreWork: Boolean get() = completedSources < totalSources
    val shouldRetryImmediately: Boolean get() = hasMoreWork && canLoadMoreImmediately
}

/**
 * Per-request-key source traversal. Successful sources are never selected again. A retryable
 * source remains in the deterministic rotation but is skipped until its backoff expires.
 */
internal class RandomPlayCollectionSourceProgress(
    sourceKeys: List<String>,
    startIndex: Int,
    private val retryBaseDelayMs: Long = RANDOM_PLAY_COLLECTION_RETRY_BASE_DELAY_MS,
    private val retryMaxDelayMs: Long = RANDOM_PLAY_COLLECTION_RETRY_MAX_DELAY_MS,
) {
    private val keys = sourceKeys.distinct()
    private val completed = linkedSetOf<String>()
    private val retries = linkedMapOf<String, RetryState>()
    private var nextIndex = if (keys.isEmpty()) 0 else startIndex.floorMod(keys.size)

    val completedCount: Int get() = completed.size
    val retryableCount: Int get() = retries.size
    val totalCount: Int get() = keys.size

    fun nextWindow(nowMs: Long, size: Int): List<String> {
        if (keys.isEmpty() || size <= 0 || completed.size >= keys.size) return emptyList()
        val selected = mutableListOf<String>()
        var inspected = 0
        var index = nextIndex
        while (inspected < keys.size && selected.size < size) {
            val key = keys[index]
            val retry = retries[key]
            if (key !in completed && (retry == null || retry.nextEligibleAtMs <= nowMs)) {
                selected += key
            }
            index = (index + 1) % keys.size
            inspected++
        }
        nextIndex = index
        return selected
    }

    fun markLoaded(key: String) {
        if (key !in keys) return
        completed += key
        retries.remove(key)
    }

    fun markRetryable(key: String, nowMs: Long) {
        if (key !in keys || key in completed) return
        val attempts = (retries[key]?.attempts ?: 0) + 1
        retries[key] = RetryState(
            attempts = attempts,
            nextEligibleAtMs = nowMs + retryDelayMs(attempts),
        )
    }

    fun hasEligibleSource(nowMs: Long): Boolean = keys.any { key ->
        key !in completed && (retries[key]?.nextEligibleAtMs ?: Long.MIN_VALUE) <= nowMs
    }

    private fun retryDelayMs(attempts: Int): Long {
        val multiplier = 1L shl (attempts - 1).coerceIn(0, 20)
        return (retryBaseDelayMs * multiplier).coerceAtMost(retryMaxDelayMs)
    }

    private data class RetryState(
        val attempts: Int,
        val nextEligibleAtMs: Long,
    )
}

/** Stable partition: structured addon catalog types containing `anime` lead, all others follow. */
internal fun <T> List<T>.stableAnimeSourcesFirst(typeOf: (T) -> String?): List<T> {
    val (anime, other) = partition { value ->
        typeOf(value)?.contains("anime", ignoreCase = true) == true
    }
    return anime + other
}

private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus

/**
 * [size] consecutive entries starting at [start], wrapping past the end. Empty list in, empty list
 * out; [size] is clamped so no entry is returned twice.
 */
internal fun <T> List<T>.rotatedWindow(start: Int, size: Int): List<T> {
    if (isEmpty() || size <= 0) return emptyList()
    val offset = ((start % this.size) + this.size) % this.size
    return List(minOf(size, this.size)) { index -> this[(offset + index) % this.size] }
}

internal const val RANDOM_PLAY_COLLECTION_SECTION_PREFIX = "nuvio:random-play-collection:"
/** Sources fetched per pass. Successive passes move on to the next window; see `ensureLoaded`. */
internal const val RANDOM_PLAY_COLLECTION_SOURCE_WINDOW = 24
private const val RANDOM_PLAY_COLLECTION_SOURCE_ITEM_LIMIT = 40
private const val RANDOM_PLAY_COLLECTION_FETCH_BATCH_SIZE = 4
internal const val RANDOM_PLAY_COLLECTION_RETRY_BASE_DELAY_MS = 30_000L
internal const val RANDOM_PLAY_COLLECTION_RETRY_MAX_DELAY_MS = 10 * 60_000L
