package com.nuvio.app.features.tonight

import co.touchlab.kermit.Logger
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.parseRuntimeMinutes
import com.nuvio.app.features.discover.DiscoverNotInterestedStorage
import com.nuvio.app.features.discover.DiscoverRecommendationsRepository
import com.nuvio.app.features.discover.TmdbBrowse
import com.nuvio.app.features.discover.TmdbBrowseMedia
import com.nuvio.app.features.discover.TmdbBrowseQuery
import com.nuvio.app.features.discover.TmdbBrowseSort
import com.nuvio.app.features.discover.tmdbBrowseRegion
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.watchedItemKey
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/*
 * fork:tonight - gathers the titles the picker draws from (user, Nov 10): the library and
 * watchlist, Continue watching / Next up, and trending films and shows; plus, when turned on in
 * Settings (user, Nov 12), Discover's Top picks, Because you watched and More like your favourites
 * rows. Not the Home rows. Titles the user marked "Not interested" (shared with Discover) are left out.
 */

data class TonightPoolState(
    val titles: List<TonightTitle> = emptyList(),
    val loading: Boolean = false,
    val loadedAtEpochMs: Long = 0L,
    /** Whether the library had loaded when this pool was gathered (its started and watched titles are in it). */
    val withLibrary: Boolean = false,
)

object TonightPool {
    private val log = Logger.withTag("Tonight")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(TonightPoolState())
    val state: StateFlow<TonightPoolState> = _state.asStateFlow()
    private var job: Job? = null

    private const val REFRESH_AFTER_MS = 10 * 60_000L
    private const val TRENDING_PER_KIND = 20
    private const val ENRICH_MAX = 90
    private const val DISCOVER_WAIT_MS = 20_000L
    private const val RECOMMENDED_PER_ROW = 15
    private const val RECOMMENDED_MAX = 80
    private const val ENRICH_PARALLEL = 6

    /**
     * Builds the pool, or rebuilds it once it is ten minutes old, or when it was gathered before the
     * library had loaded and now it has. Safe to call on every visit.
     *
     * It used to rebuild on every visit once the library was loaded (a forced refresh keyed on the
     * library being loaded, which it nearly always is when the picker opens). A rebuild can wait up
     * to 20 s for Discover, and Three Questions held its deal back while one ran, so three quick
     * answers could sit on "Dealing your cards" until it finished (user, Nov 23).
     */
    fun refresh(nowEpochMs: Long, libraryLoaded: Boolean, force: Boolean = false) {
        val current = _state.value
        if (job?.isActive == true) {
            // The library finished loading while a build without it runs: build again once that one is done, or the pool
            // stays without the library until the picker is opened again (nothing else asks).
            if (libraryLoaded && !runningWithLibrary) rebuildWithLibrary = true
            return
        }
        val fresh = current.titles.isNotEmpty() && nowEpochMs - current.loadedAtEpochMs < REFRESH_AFTER_MS
        if (!force && fresh && (current.withLibrary || !libraryLoaded)) return
        _state.value = current.copy(loading = true)
        runningWithLibrary = libraryLoaded
        job = scope.launch {
            val titles = runCatching {
                build(
                    publishIntermediate = { partial ->
                        if (partial.isNotEmpty() && (_state.value.titles.isEmpty() || partial.size > _state.value.titles.size)) {
                            _state.value = _state.value.copy(titles = partial, loading = true)
                        }
                    },
                )
            }
                .onFailure { log.w { "Tonight: could not gather titles: ${it.message}" } }
                .getOrDefault(current.titles)
            log.i { "Tonight: pool of ${titles.size} (" + titles.groupingBy { it.kind }.eachCount() + ")" }
            _state.value = TonightPoolState(titles = titles, loading = false, loadedAtEpochMs = nowEpochMs, withLibrary = libraryLoaded)
        }.also { started ->
            started.invokeOnCompletion {
                if (rebuildWithLibrary) {
                    rebuildWithLibrary = false
                    refresh(System.currentTimeMillis(), libraryLoaded = true)
                }
            }
        }
    }

    @Volatile
    private var runningWithLibrary = false

    @Volatile
    private var rebuildWithLibrary = false

    /** Runs [block] off the screen's lifetime (a cache to drop after the screen moved on). */
    internal fun background(block: suspend () -> Unit) {
        scope.launch { runCatching { block() } }
    }

    /** Gathers again on the next visit (a source was turned on or off). */
    fun markStale() {
        _state.value = _state.value.copy(loadedAtEpochMs = 0L)
    }

    /** Drops a title the user said they never want to see (it is already in Not interested). */
    fun forget(key: String) {
        _state.value = _state.value.copy(titles = _state.value.titles.filterNot { it.key == key })
    }

    private suspend fun build(publishIntermediate: (List<TonightTitle>) -> Unit = {}): List<TonightTitle> {
        LibraryRepository.ensureLoaded()
        WatchedRepository.ensureLoaded()
        WatchProgressRepository.ensureLoaded()
        TonightSettingsRepository.ensureLoaded()

        val watchedKeys = WatchedRepository.uiState.value.watchedKeys
        fun isWatched(type: String, id: String) = watchedItemKey(type = type, id = id) in watchedKeys

        val merged = linkedMapOf<String, TonightTitle>()
        fun add(title: TonightTitle) {
            val sameTmdb = title.tmdbId?.let { tmdb ->
                merged.values.firstOrNull { it.tmdbId == tmdb && it.kind == title.kind }
            }
            val existing = merged[title.key] ?: sameTmdb
            if (existing == null) {
                merged[title.key] = title
            } else {
                merged[existing.key] = existing.mergedWith(title)
            }
        }

        val notInterested = runCatching { DiscoverNotInterestedStorage.load() }.getOrDefault(emptySet())
        // Watched is judged for every title, not only the library's: a film from trending or Discover that is in the
        // history was dealt as "New to you" (user, Nov 23). The same title-level matcher as Hide watched content, which
        // also answers `tmdb:` ids (trending's) and ids the history knows under another name.
        val watchedTitles = com.nuvio.app.features.home.WatchedContentFilter.forHistory(WatchedRepository.uiState.value.items)

        fun snapshotFilteredAndPeeked(): List<TonightTitle> = merged.values
            .filter { it.key !in notInterested && it.type in setOf("movie", "series") }
            .map { raw ->
                val watchedFixed = if (!raw.watched && watchedTitles.isWatched(raw.type, raw.id)) raw.copy(watched = true) else raw
                hydrateFromMemoryCache(watchedFixed)
            }

        // Stage 1 (instant): publish titles already in memory (Continue watching, Library, and any
        // already-loaded Discover rows), hydrated from the in-memory lightweight meta cache so the
        // launcher tiles and modes get real backdrops immediately instead of waiting on network I/O.
        val continuing = runCatching { WatchProgressRepository.continueWatching() }.getOrDefault(emptyList())
        continuing.forEach { add(it.toTonightTitle()) }
        LibraryRepository.uiState.value.items.forEach { add(it.toTonightTitle(isWatched(it.type, it.id))) }
        val discoverSources = TonightSettingsRepository.state.value.discoverSources
        extractDiscoverTitles(DiscoverRecommendationsRepository.uiState.value, discoverSources).forEach { add(it) }
        publishIntermediate(snapshotFilteredAndPeeked())

        // Stage 2 (parallel network): fetch trending and wait for Discover concurrently, then
        // publish again before the slower lightweight-meta enrichment pass.
        val (trendingTitles, recommendedTitles) = coroutineScope {
            val trendingDeferred = async { trending() }
            val recommendedDeferred = async { recommended(discoverSources) }
            trendingDeferred.await() to recommendedDeferred.await()
        }
        trendingTitles.forEach { add(it) }
        recommendedTitles.forEach { add(it) }

        val kept = snapshotFilteredAndPeeked()
        publishIntermediate(kept)
        return enrich(kept)
    }

    /** Fills missing fields synchronously from the in-memory meta cache without any network I/O. */
    private fun hydrateFromMemoryCache(title: TonightTitle): TonightTitle {
        val needs = title.genres.isEmpty() || title.runtimeMinutes == null || title.year == null ||
            title.poster.isNullOrBlank() || title.backdrop.isNullOrBlank()
        if (!needs) return title
        val meta = MetaDetailsRepository.peekAny(title.type, title.id) ?: return title
        return title.copy(
            genres = title.genres.ifEmpty { meta.genres },
            runtimeMinutes = title.runtimeMinutes ?: meta.runtime?.let(::parseRuntimeMinutes),
            year = title.year ?: meta.releaseInfo.yearOrNull(),
            rating = title.rating ?: meta.imdbRating?.toDoubleOrNull(),
            poster = title.poster ?: meta.poster,
            backdrop = title.backdrop ?: meta.background,
            tmdbId = title.tmdbId ?: meta.tmdbId,
        )
    }

    private fun extractDiscoverTitles(
        state: com.nuvio.app.features.discover.DiscoverRecommendationsUiState,
        sources: Set<TonightDiscoverSource>,
    ): List<TonightTitle> {
        if (sources.isEmpty()) return emptyList()
        val byFamily = sources.associateBy { it.family.entryId }
        return state.rows
            .filter { !it.isPlaceholder && it.entryId in byFamily }
            .flatMap { row ->
                val why = byFamily.getValue(row.entryId).why(row.title)
                row.items.take(RECOMMENDED_PER_ROW).map {
                    it.toTonightTitle(TonightOrigin.Recommended).copy(recommendedBy = why)
                }
            }
            .distinctBy { it.key }
            .take(RECOMMENDED_MAX)
    }

    private suspend fun trending(): List<TonightTitle> = coroutineScope {
        val region = runCatching { tmdbBrowseRegion() }.getOrDefault("US")
        listOf(TmdbBrowseMedia.Movie, TmdbBrowseMedia.Tv).map { media ->
            async {
                runCatching {
                    TmdbBrowse.browse(TmdbBrowseQuery(media = media, sort = TmdbBrowseSort.Trending), 1, region)
                        .items.take(TRENDING_PER_KIND).map { it.toTonightTitle() }
                }.onFailure { log.w { "Tonight: trending ${media.name} failed: ${it.message}" } }
                    .getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
    }

    /**
     * Titles from the Discover rows the user chose in Settings (Top picks, Because you watched, More like your
     * favourites). Discover keeps its rows for a while, so this is usually instant; a cold build can take half a
     * minute, so it waits a little and then takes whatever is there (the next gathering picks up the rest). A row
     * turned off in Discover's own settings is simply not there.
     */
    private suspend fun recommended(sources: Set<TonightDiscoverSource>): List<TonightTitle> {
        if (sources.isEmpty()) return emptyList()
        val byFamily = sources.associateBy { it.family.entryId }
        DiscoverRecommendationsRepository.refresh()
        val discover = DiscoverRecommendationsRepository.uiState
        val state = withTimeoutOrNull(DISCOVER_WAIT_MS) {
            discover.first { it.hasLoadedOnce && !it.isLoading && it.rows.none { row -> row.isPlaceholder && row.entryId in byFamily } }
        } ?: discover.value
        val titles = extractDiscoverTitles(state, sources)
        log.i { "Tonight: ${titles.size} from Discover (${sources.joinToString { it.name }})" }
        return titles
    }

    /** Fills in genres and length the sources left out, through the cached lightweight meta. */
    private suspend fun enrich(titles: List<TonightTitle>): List<TonightTitle> = coroutineScope {
        val gate = Semaphore(ENRICH_PARALLEL)
        var budget = ENRICH_MAX
        titles.map { title ->
            val needs = title.genres.isEmpty() || title.runtimeMinutes == null || title.year == null
            if (!needs || budget-- <= 0) {
                async { title }
            } else {
                async {
                    gate.withPermit {
                        val meta = runCatching { MetaDetailsRepository.fetchLightweightMeta(title.type, title.id) }.getOrNull()
                        if (meta == null) {
                            title
                        } else {
                            title.copy(
                                genres = title.genres.ifEmpty { meta.genres },
                                runtimeMinutes = title.runtimeMinutes ?: meta.runtime?.let(::parseRuntimeMinutes),
                                year = title.year ?: meta.releaseInfo.yearOrNull(),
                                rating = title.rating ?: meta.imdbRating?.toDoubleOrNull(),
                                poster = title.poster ?: meta.poster,
                                backdrop = title.backdrop ?: meta.background,
                                tmdbId = title.tmdbId ?: meta.tmdbId,
                            )
                        }
                    }
                }
            }
        }.awaitAll()
    }
}

private fun String?.yearOrNull(): Int? = this?.take(4)?.toIntOrNull()?.takeIf { it in 1880..2100 }

private fun kindOf(type: String) = if (type.equals("series", ignoreCase = true)) TonightKind.Show else TonightKind.Film

private fun TonightTitle.mergedWith(other: TonightTitle): TonightTitle = copy(
    poster = poster ?: other.poster,
    backdrop = backdrop ?: other.backdrop,
    year = year ?: other.year,
    genres = genres.ifEmpty { other.genres },
    runtimeMinutes = runtimeMinutes ?: other.runtimeMinutes,
    rating = rating ?: other.rating,
    origins = origins + other.origins,
    watched = watched || other.watched,
    started = started || other.started,
    nextEpisodeLabel = nextEpisodeLabel ?: other.nextEpisodeLabel,
    resumeMinutesLeft = resumeMinutesLeft ?: other.resumeMinutesLeft,
    addedAtEpochMs = addedAtEpochMs ?: other.addedAtEpochMs,
    tmdbId = tmdbId ?: other.tmdbId,
    recommendedBy = recommendedBy ?: other.recommendedBy,
)

private fun WatchProgressEntry.toTonightTitle(): TonightTitle {
    val kind = kindOf(parentMetaType)
    val durationMinutes = (durationMs / 60_000L).toInt().takeIf { it > 0 }
    val leftMinutes = ((durationMs - lastPositionMs) / 60_000L).toInt().takeIf { it > 0 }
    return TonightTitle(
        type = parentMetaType.lowercase(),
        id = parentMetaId,
        name = title,
        kind = kind,
        poster = poster,
        backdrop = background,
        // For a show the progress entry is one episode, which is the length that matters tonight.
        runtimeMinutes = durationMinutes,
        origins = setOf(TonightOrigin.ContinueWatching),
        started = true,
        nextEpisodeLabel = if (seasonNumber != null && episodeNumber != null) "S$seasonNumber E$episodeNumber" else null,
        resumeMinutesLeft = if (kind == TonightKind.Film && isResumable && lastPositionMs > 0L) leftMinutes else null,
    )
}

private fun LibraryItem.toTonightTitle(watched: Boolean): TonightTitle = TonightTitle(
    type = type.lowercase(),
    id = id,
    name = name,
    kind = kindOf(type),
    poster = poster,
    backdrop = banner,
    year = releaseInfo.yearOrNull(),
    genres = genres,
    runtimeMinutes = runtime?.let(::parseRuntimeMinutes),
    rating = imdbRating?.toDoubleOrNull(),
    origins = setOf(TonightOrigin.Library),
    watched = watched,
    addedAtEpochMs = savedAtEpochMs,
    tmdbId = tmdbId,
)

private fun MetaPreview.toTonightTitle(origin: TonightOrigin = TonightOrigin.Trending): TonightTitle = TonightTitle(
    type = type.lowercase(),
    id = id,
    name = name,
    kind = kindOf(type),
    poster = poster,
    backdrop = banner,
    year = releaseInfo.yearOrNull(),
    genres = genres,
    runtimeMinutes = runtime?.let(::parseRuntimeMinutes),
    rating = imdbRating?.toDoubleOrNull(),
    origins = setOf(origin),
    tmdbId = id.removePrefix("tmdb:").toIntOrNull(),
)
