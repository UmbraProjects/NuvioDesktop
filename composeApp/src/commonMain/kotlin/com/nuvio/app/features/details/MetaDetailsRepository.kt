package com.nuvio.app.features.details

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.filterReleasedItems
import com.nuvio.app.features.mdblist.MdbListMetadataService
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.metadata.AnimeArtworkService
import com.nuvio.app.features.metadata.animeMovieTmdbFallbackId
import com.nuvio.app.features.metadata.hasAnimeNamespacePrefix
import com.nuvio.app.features.tmdb.TmdbMetadataService
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.tmdb.HeroImageSource
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.tvdb.TvdbImageService
import com.nuvio.app.features.tvdb.TvdbSettingsRepository
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TraktConnectionMode
import com.nuvio.app.features.trakt.TraktRelatedRepository
import com.nuvio.app.features.trakt.TraktSettingsRepository
import com.nuvio.app.features.trakt.shouldUseTraktMoreLikeThis
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

object MetaDetailsRepository {
    private data class CachedMetaEntry(
        val baseMeta: MetaDetails,
        val metaScreenMeta: MetaDetails? = null,
        val metaScreenSettingsFingerprint: String? = null,
    )

    private val log = Logger.withTag("MetaDetailsRepo")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _uiState = MutableStateFlow(MetaDetailsUiState())
    val uiState: StateFlow<MetaDetailsUiState> = _uiState.asStateFlow()
    private var activeRequestKey: String? = null
    // Bounded so a long session of browsing detail pages can't grow this map without limit.
    // Confined to the Main dispatcher (see `scope`), so a plain insertion-order LinkedHashMap that
    // drops its eldest entry past the cap is safe.
    //
    // Deliberately still insertion-ordered rather than access-ordered: accessOrder = true makes a
    // read a structural modification, and this map is read from other dispatchers (see
    // fetchLightweightMetaInternal), so LRU ordering would turn a benign stale read into a
    // concurrent mutation.
    //
    // 80 was already tight - one measured session touched 107 distinct full-fetch keys, so it was
    // evicting entries it would need again - and hero summaries now share it as a third key class.
    // Entries are metadata records, so a few hundred is still cheap to hold.
    private val cachedMetaByRequestKey = object : LinkedHashMap<String, CachedMetaEntry>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedMetaEntry>): Boolean =
            size > 300
    }

    fun load(type: String, id: String) {
        log.d { "load() called — type=$type id=$id" }
        val requestKey = "$type:$id"

        // Unmatched local-library items have no addon meta; serve a synthesized one so the details
        // page still opens and plays the local file(s). (Matched items use their real tt/tmdb meta.)
        if (id.startsWith("local:")) {
            val synthetic = com.nuvio.app.features.locallibrary.LocalLibraryRepository.syntheticMetaFor(id)
            if (synthetic != null) {
                _uiState.value = MetaDetailsUiState(meta = synthetic)
                activeRequestKey = requestKey
                return
            }
        }

        val currentState = _uiState.value
        val mdbListSettings = MdbListSettingsRepository.snapshot()
        val metaScreenSettingsFingerprint = buildMetaScreenSettingsFingerprint(mdbListSettings)

        cachedMetaByRequestKey[requestKey]?.let { cachedEntry ->
            cachedEntry.metaScreenMeta
                ?.takeIf { cachedEntry.metaScreenSettingsFingerprint == metaScreenSettingsFingerprint }
                ?.let { cachedMeta ->
                    _uiState.value = MetaDetailsUiState(meta = cachedMeta.withUnreleasedFilter())
                    activeRequestKey = requestKey
                    return
                }

            val cachedBaseMeta = cachedEntry.baseMeta
            if (!shouldEnrichForMetaScreen(cachedBaseMeta, id, mdbListSettings)) {
                _uiState.value = MetaDetailsUiState(meta = cachedBaseMeta.withUnreleasedFilter())
                activeRequestKey = requestKey
                return
            }

            if (currentState.isLoading && activeRequestKey == requestKey) {
                log.d { "Meta screen enrichment already in flight — type=$type id=$id" }
                return
            }

            activeRequestKey = requestKey
            _uiState.value = MetaDetailsUiState(
                isLoading = true,
            )

            scope.launch {
                val enrichedMeta = withContext(Dispatchers.Default) {
                    enrichForMetaScreen(
                        requestKey = requestKey,
                        meta = cachedBaseMeta,
                        fallbackItemId = id,
                        fallbackItemType = type,
                        settings = mdbListSettings,
                        settingsFingerprint = metaScreenSettingsFingerprint,
                    )
                }
                _uiState.value = MetaDetailsUiState(meta = enrichedMeta.withUnreleasedFilter())
                activeRequestKey = requestKey
            }
            return
        }

        if (currentState.meta?.type == type && currentState.meta.id == id && !currentState.isLoading) {
            log.d { "Skipping reload for cached meta — type=$type id=$id" }
            activeRequestKey = requestKey
            return
        }

        if (currentState.isLoading && activeRequestKey == requestKey) {
            log.d { "Request already in flight — type=$type id=$id" }
            return
        }

        activeRequestKey = requestKey
        _uiState.value = MetaDetailsUiState(isLoading = true)

        scope.launch {
            val metaLookupId = resolveMetaLookupId(itemId = id, itemType = type)
            val manifests = findMetaManifests(type = type, id = metaLookupId)

            if (manifests.isEmpty()) {
                val tmdbMeta = tryFetchTmdbFallbackMeta(type = type, id = id)
                if (tmdbMeta != null) {
                    publishLoadedMeta(
                        requestKey = requestKey,
                        meta = tmdbMeta,
                        fallbackItemId = id,
                        fallbackItemType = type,
                        mdbListSettings = mdbListSettings,
                        metaScreenSettingsFingerprint = metaScreenSettingsFingerprint,
                    )
                    return@launch
                }

                log.w { "No addon provides meta for type=$type id=$id" }
                _uiState.value = MetaDetailsUiState(
                    errorMessage = getString(Res.string.details_no_addon_meta),
                )
                activeRequestKey = null
                return@launch
            }

            var supplementalMeta: MetaDetails? = null
            for (manifest in manifests) {
                val result = withContext(Dispatchers.Default) {
                    tryFetchMeta(
                        manifest,
                        type,
                        metaLookupId,
                        includeMdbList = false,
                        origin = "supplemental:$type:$metaLookupId",
                    )
                }
                if (result != null) {
                    if (type.isSeriesMetaType() && result.videos.isEmpty()) {
                        supplementalMeta = supplementalMeta?.mergeSupplementalMeta(result) ?: result
                        continue
                    }
                    publishLoadedMeta(
                        requestKey = requestKey,
                        meta = result.mergeSupplementalMeta(supplementalMeta),
                        fallbackItemId = metaLookupId,
                        fallbackItemType = type,
                        mdbListSettings = mdbListSettings,
                        metaScreenSettingsFingerprint = metaScreenSettingsFingerprint,
                    )
                    return@launch
                }
            }

            val tmdbMeta = tryFetchTmdbFallbackMeta(type = type, id = id)
            if (tmdbMeta != null) {
                publishLoadedMeta(
                    requestKey = requestKey,
                    meta = tmdbMeta.mergeSupplementalMeta(supplementalMeta),
                    fallbackItemId = id,
                    fallbackItemType = type,
                    mdbListSettings = mdbListSettings,
                    metaScreenSettingsFingerprint = metaScreenSettingsFingerprint,
                )
                return@launch
            }

            _uiState.value = MetaDetailsUiState(
                errorMessage = getString(Res.string.details_load_failed_all_addons),
            )
            activeRequestKey = null
        }
    }

    fun peek(type: String, id: String): MetaDetails? {
        val requestKey = "$type:$id"
        val currentMeta = _uiState.value.meta?.takeIf { it.type == type && it.id == id }
        if (currentMeta != null) return currentMeta

        val metaScreenSettingsFingerprint = buildMetaScreenSettingsFingerprint(MdbListSettingsRepository.snapshot())
        val cachedEntry = cachedMetaByRequestKey[requestKey] ?: return null
        return cachedEntry.metaScreenMeta
            ?.takeIf { cachedEntry.metaScreenSettingsFingerprint == metaScreenSettingsFingerprint }
            ?: cachedEntry.baseMeta
    }

    /**
     * [peek] widened to every cache this repository keeps, for callers that only want *a* record of
     * the title rather than the one the details screen is showing.
     *
     * [peek] reads exactly the key `load()` writes (`"$type:$id"`), which means it answers null for
     * a title that has only ever been through `fetch()` or `fetchLightweightMeta()` — they key on
     * the *resolved* lookup id plus their own suffix, so their entries are invisible to it. That is
     * correct for the details screen, which wants the enriched record or nothing, and wrong for
     * everything that just wants a field: a title fetched for the home hero, an episode list or a
     * library row is sitting right there and gets reported as unknown.
     *
     * The lookup id cannot be resolved here (it suspends), so this only tries the un-aliased keys.
     * A `tt`-id title fetched under a `tmdb:` alias still misses; that costs a null, never a wrong
     * answer.
     */
    fun peekAny(type: String, id: String): MetaDetails? {
        peek(type, id)?.let { return it }
        val fetchKeys = listOf("$type:$id:enrich=true", "$type:$id:enrich=false", "$type:$id:hero")
        fetchKeys.firstNotNullOfOrNull { key -> cachedMetaByRequestKey[key]?.baseMeta }
            ?.let { return it }
        return listOf("$type:$id:addon", "$type:$id:tmdb")
            .firstNotNullOfOrNull { key -> lightweightMetaCache[key] }
    }

    /** Episode titles keyed by the displayed franchise coordinates. Stream addons sometimes
     * return a file whose numeric tag claims the requested episode while its title identifies a
     * different episode; this lets the picker reject that contradiction without show-specific data. */
    fun episodeTitlesByCoordinate(type: String, id: String): Map<Pair<Int, Int>, String> =
        peek(type, id)?.videos.orEmpty()
            .mapNotNull { video ->
                val season = video.season ?: return@mapNotNull null
                val episode = video.episode ?: return@mapNotNull null
                val title = video.title.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                (season to episode) to title
            }
            .toMap()

    /** Series name for [episodeTitlesByCoordinate]'s companion check: an episode title that merely
     * repeats it identifies no particular episode and must not be matched against stream text. */
    fun seriesTitleFor(type: String, id: String): String? =
        peek(type, id)?.name?.trim()?.takeIf { it.isNotBlank() }

    fun clear() {
        activeRequestKey = null
        cachedMetaByRequestKey.clear()
        _uiState.value = MetaDetailsUiState()
    }

    /**
     * In-flight full fetches, so concurrent callers on one key share a request — plan §21.4.
     *
     * The lightweight path has had this for a while; this one did not, and the log showed the
     * consequence plainly: pairs of identical `full:` fetches issued in the same instant, on top of
     * the sequential repeats the timeout bug caused.
     */
    private val inFlightFullMeta = mutableMapOf<String, kotlinx.coroutines.Deferred<MetaDetails?>>()
    private val fullMetaMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun fetch(type: String, id: String, enrichTmdb: Boolean = true, forceRefresh: Boolean = false): MetaDetails? {
        // Keyed on the **resolved** lookup id, not the id the caller happened to hold — plan §23.3.
        // `tt11561116` and `tmdb:860508` are the same title and produce the identical addon URL, so
        // keying on the requested id made each alias pay for its own fetch. Resolving first is
        // effectively free now: it goes through `TmdbService.tmdbToImdb`, which is disk-cached.
        val metaLookupId = resolveMetaLookupId(itemId = id, itemType = type)
        val requestKey = "$type:$metaLookupId:enrich=$enrichTmdb"
        if (!forceRefresh) {
            cachedMetaByRequestKey[requestKey]?.let { return it.baseMeta }
            val shared = fullMetaMutex.withLock {
                inFlightFullMeta[requestKey] ?: scope.async {
                    try {
                        fetchUncached(type, id, enrichTmdb, requestKey, metaLookupId)
                    } finally {
                        fullMetaMutex.withLock { inFlightFullMeta.remove(requestKey) }
                    }
                }.also { inFlightFullMeta[requestKey] = it }
            }
            return shared.await()
        }
        return fetchUncached(type, id, enrichTmdb, requestKey, metaLookupId)
    }

    private suspend fun fetchUncached(
        type: String,
        id: String,
        enrichTmdb: Boolean,
        requestKey: String,
        metaLookupId: String,
    ): MetaDetails? = runMetaFetch(
        type = type,
        id = id,
        metaLookupId = metaLookupId,
        requestKey = requestKey,
        enrichTmdb = enrichTmdb,
        // The details screen is the one consumer that genuinely wants all of this.
        trailerScope = TmdbMetadataService.TrailerScope.AllSeasons,
        includeEpisodes = true,
        origin = "full",
    )

    /**
     * The manifest walk that [fetchUncached] and [fetchHeroSummary] share, minus the caching.
     *
     * They differ only in how much decoration they ask TMDB for and which key the answer lands
     * under, so everything else - manifest order, the supplemental merge for a series whose addon
     * returned no video list, the TMDB fallback - lives here once.
     */
    private suspend fun runMetaFetch(
        type: String,
        id: String,
        metaLookupId: String,
        requestKey: String,
        enrichTmdb: Boolean,
        trailerScope: TmdbMetadataService.TrailerScope,
        includeEpisodes: Boolean,
        origin: String,
    ): MetaDetails? {
        // forceRefresh bypasses the LRU so a background sweep (library auto-download) sees newly
        // aired episodes instead of a stale cached video list. The non-forced read happens in
        // fetch(), before the single-flight, and so does the id resolution - its result arrives as
        // [metaLookupId] rather than being computed twice.
        val manifests = findMetaManifests(type = type, id = metaLookupId)

        var supplementalMeta: MetaDetails? = null
        for (manifest in manifests) {
            val result = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                tryFetchMeta(
                    manifest,
                    type,
                    metaLookupId,
                    includeMdbList = false,
                    enrichTmdb = enrichTmdb,
                    trailerScope = trailerScope,
                    includeEpisodes = includeEpisodes,
                    origin = "$origin:$requestKey",
                )
            }
            if (result != null) {
                if (type.isSeriesMetaType() && result.videos.isEmpty()) {
                    supplementalMeta = supplementalMeta?.mergeSupplementalMeta(result) ?: result
                    continue
                }
                val merged = result.mergeSupplementalMeta(supplementalMeta)
                cachedMetaByRequestKey[requestKey] = CachedMetaEntry(baseMeta = merged)
                return merged
            }
        }

        return tryFetchTmdbFallbackMeta(type = type, id = id)?.also { result ->
            cachedMetaByRequestKey[requestKey] = CachedMetaEntry(
                baseMeta = result.mergeSupplementalMeta(supplementalMeta),
            )
        }?.mergeSupplementalMeta(supplementalMeta)
    }

    /**
     * What the hero needs, and nothing the hero cannot show.
     *
     * The hero's cast, discovery badges, production credits and trailer all used to fall through to
     * [fetch], whose defaults are the details screen's: every episode of every season decorated, and
     * trailers fetched per season. Measured over one session that was **529 of 1,028** queued TMDB
     * requests - 51% of all TMDB traffic - spent on `tv/{id}/season/...` for heroes that display no
     * episode at all. Those requests share the Interactive lane with hero artwork, so they also put
     * 574 s of cumulative queue wait in front of the images the user is waiting to see.
     *
     * Two things make dropping them safe rather than merely cheaper:
     *
     * - `includeEpisodes` only controls *decoration* - TMDB titles, stills and season posters
     *   applied over the addon's own video list. `applyEnrichment` leaves `videos` untouched when
     *   that map is empty, so the season and episode counts the discovery badges compute from
     *   `meta.videos` are identical either way.
     * - `TrailerScope.AllSeasons` fetched a trailer list per season, and
     *   `HeroTrailerMetadataService` then discarded every one above season 1 as a spoiler risk.
     *   Asking for season 1 specifically is not a compromise here; it is the only season whose
     *   trailers the hero was ever going to play.
     *
     * Cached under its own key so it can never stand in for the details screen's record: [peek]
     * reads only the key `load()` writes and [fetch] reads only its own, so opening a title still
     * performs the full fetch with its episodes.
     */
    suspend fun fetchHeroSummary(type: String, id: String): MetaDetails? {
        // A record already in hand beats a summary and costs nothing - but only a *complete* one.
        // Deliberately not peekAny(): that also answers from the lightweight artwork cache, whose
        // records can be a name, a backdrop and a logo with no cast at all. Handing one of those to
        // HeroCastMetadataService would have it cache "this title has no cast" for a day.
        peek(type, id)?.let { return it }

        val metaLookupId = resolveMetaLookupId(itemId = id, itemType = type)
        cachedMetaByRequestKey["$type:$metaLookupId:enrich=true"]?.let { return it.baseMeta }

        val requestKey = "$type:$metaLookupId:hero"
        cachedMetaByRequestKey[requestKey]?.let { return it.baseMeta }

        val shared = fullMetaMutex.withLock {
            inFlightFullMeta[requestKey] ?: scope.async {
                try {
                    runMetaFetch(
                        type = type,
                        id = id,
                        metaLookupId = metaLookupId,
                        requestKey = requestKey,
                        enrichTmdb = true,
                        trailerScope = TmdbMetadataService.TrailerScope.SingleSeason(HERO_TRAILER_SEASON),
                        includeEpisodes = false,
                        origin = "hero",
                    )
                } finally {
                    fullMetaMutex.withLock { inFlightFullMeta.remove(requestKey) }
                }
            }.also { inFlightFullMeta[requestKey] = it }
        }
        return shared.await()
    }

    // Separate lightweight cache: survives LaunchedEffect restarts without polluting the
    // main detail-page cache (which the full fetch() path writes to).
    private val lightweightMetaCache = mutableMapOf<String, MetaDetails>()

    // Guards lightweightMetaCache and inFlightLightweightMeta. Unlike cachedMetaByRequestKey,
    // this path is reached from arbitrary dispatchers (hero enrichment, catalog rows, detail
    // prefetch), so it can't rely on Main confinement.
    private val lightweightMetaMutex = Mutex()
    private val inFlightLightweightMeta = mutableMapOf<String, Deferred<MetaDetails?>>()

    // Lightweight fetch for hero enrichment — returns the first non-null addon result
    // without requiring a video list for series. Uses its own cache so LaunchedEffect
    // restarts (triggered by library reloads) return instantly on the second pass.
    //
    // When preferTmdbImages = true (TMDB-for-everything mode): collects the first addon
    // result for text metadata, then ALWAYS also runs the TMDB fallback to get a proper
    // TMDB backdrop/logo. Merges the two so text comes from the addon and images from TMDB.
    // This is simpler and more reliable than a separate TMDB image service.
    suspend fun fetchLightweightMeta(type: String, id: String, preferTmdbImages: Boolean = false): MetaDetails? {
        val meta = fetchLightweightMetaInternal(type = type, id = id, preferTmdbImages = preferTmdbImages)
        // Native anime ids are per-season entries, but TMDB/TVDB art is franchise-wide — every
        // season of a kitsu catalog would show the same backdrop. Swap in the entry's own
        // AniList/Kitsu banner when one exists (service-cached, so repeat calls are free).
        if (meta == null || !id.hasAnimeNamespacePrefix()) return meta
        val seasonBackdrop = AnimeArtworkService.seasonBackdrop(id) ?: return meta
        return meta.copy(background = seasonBackdrop)
    }

    private suspend fun fetchLightweightMetaInternal(type: String, id: String, preferTmdbImages: Boolean): MetaDetails? {
        // Resolved id, same reasoning as fetch() — see plan §23.3. Two aliases of one title were
        // each paying for their own lightweight fetch of the identical addon URL.
        val lookupId = resolveMetaLookupId(itemId = id, itemType = type)
        val requestKey = "$type:$lookupId:${if (preferTmdbImages) "tmdb" else "addon"}"
        // When preferTmdbImages is false: use the main detail-page cache (cachedMetaByRequestKey).
        // When preferTmdbImages is true: skip the main cache — it contains AIOMetadata responses
        // which have TMDB images baked in and would bypass TVDB completely. The lightweight
        // cache (lightweightMetaCache, keyed with ":tmdb") serves as our cache instead.
        if (!preferTmdbImages) {
            // Keyed as `load()` writes it — `"$type:$id"`, the id the *caller* used. load() is not
            // a suspend function so it cannot resolve before building its key, and reading this map
            // under the resolved id would miss every entry load() put there.
            cachedMetaByRequestKey["$type:$id"]?.let { return it.baseMeta }
        }

        // Single-flight. The cache is only populated once a fetch finishes, so on startup the
        // hero, home enrichment and every row showing the same title all missed it at the same
        // instant and each launched its own fetch — one show was fetched 7 times in a single
        // startup. Callers racing on the same key now share one request.
        //
        // The shared job runs in `scope` rather than in the caller's coroutine: a LaunchedEffect
        // being torn down (hero rotation, navigating away) no longer discards a fetch the other
        // callers are awaiting, and the result still reaches the cache for whoever asks next.
        val request = lightweightMetaMutex.withLock {
            lightweightMetaCache[requestKey]?.let { return it }
            inFlightLightweightMeta.getOrPut(requestKey) {
                scope.async {
                    try {
                        runLightweightMetaFetch(
                            type = type,
                            id = id,
                            preferTmdbImages = preferTmdbImages,
                            requestKey = requestKey,
                        )
                    } finally {
                        // A failed fetch must not leave its Deferred behind for later callers to
                        // await forever, so the removal has to survive cancellation too.
                        withContext(NonCancellable) {
                            lightweightMetaMutex.withLock { inFlightLightweightMeta.remove(requestKey) }
                        }
                    }
                }
            }
        }
        return request.await()
    }

    private suspend fun runLightweightMetaFetch(
        type: String,
        id: String,
        preferTmdbImages: Boolean,
        requestKey: String,
    ): MetaDetails? {
        val heroImageSource = TmdbSettingsRepository.snapshot().heroImageSource
        val isTvType = type.equals("series", ignoreCase = true) || type.equals("anime", ignoreCase = true)
        val tvdbApiKeyPresent = TvdbSettingsRepository.snapshot().hasApiKey
        val tvdbActiveForType = heroImageSource == HeroImageSource.TmdbMoviesTvdbShows &&
            isTvType && tvdbApiKeyPresent
        log.d { "fetchLightweightMeta preferTmdb=$preferTmdbImages type=$type id=$id heroSrc=$heroImageSource isTv=$isTvType tvdbKey=$tvdbApiKeyPresent tvdbActive=$tvdbActiveForType" }

        // Composite CW IDs like "upnext_tt4384086_trakt1989742" embed the real IMDB ID.
        // External services (TVDB, TMDB) can't resolve the full composite string — extract
        // the embedded tt-prefixed segment for external lookups.
        val externalId = id.split("_").firstOrNull { it.startsWith("tt", ignoreCase = true) } ?: id

        val metaLookupId = resolveMetaLookupId(itemId = id, itemType = type)
        val manifests = findMetaManifests(type = type, id = metaLookupId)

        // Warm up the TVDB token in parallel with the addon meta call so it's cached
        // by the time we might need it — eliminates the token-acquisition round-trip.
        if (tvdbActiveForType) scope.launch { TvdbImageService.warmToken() }

        var addonResult: MetaDetails? = null
        for (manifest in manifests) {
            val result = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                // Home enriches a screenful of titles at once; the details page enriches the one
                // the user opened. Only the second can afford a request per season.
                tryFetchMeta(
                    manifest,
                    type,
                    metaLookupId,
                    includeMdbList = false,
                    trailerScope = TmdbMetadataService.TrailerScope.SingleSeason(
                        preferredTrailerSeason(id),
                    ),
                    // Nothing on this path reads the episode decoration — traced in plan §25 — and
                    // it costs one TMDB request per season of the show. It was the single largest
                    // block of TMDB traffic left: 474 season requests across 69 shows in one
                    // browsing session, none of them redundant, none of them used.
                    includeEpisodes = false,
                    origin = "lightweight:$requestKey",
                )
            }
            if (result != null) {
                if (!preferTmdbImages) {
                    // Standard path: first result wins.
                    cacheLightweightMeta(requestKey, result)
                    return result
                }
                if (addonResult == null) addonResult = result
                // In TVDB-for-TV mode: always break to TVDB regardless of what the addon provides.
                // Without this guard, AIOMetadata's TMDB-sourced image.tmdb.org URLs cause an
                // early return that skips TVDB completely.
                //
                // The TMDB-art check short-circuits a redundant round-trip on the assumption that
                // TMDB art implies TMDB text. That holds for an addon answering an imdb id, but not
                // for a native anime id: the addon can identify the entry from an anime source and
                // borrow only TMDB's images, returning art with no plot, genres, year or runtime.
                // Short-circuiting there leaves the hero showing the title and nothing else, so
                // require the text to actually be present before trusting the result as complete.
                if (!tvdbActiveForType &&
                    result.hasUsableHeroText() &&
                    result.background?.contains("image.tmdb.org") == true
                ) {
                    cacheLightweightMeta(requestKey, result)
                    return result
                }
                break  // Collected text metadata; proceed to image source resolution.
            }
        }
        val imdbTmdbIdentityTrusted = addonResult?.imdbTmdbIdentityTrusted != false

        val tvdbResult: com.nuvio.app.features.details.MetaDetails? =
            if (tvdbActiveForType) {
                val addonBg = addonResult?.background
                val addonLogo = addonResult?.logo
                val addonHasTvdbBackdrop = addonBg?.contains("artworks.thetvdb.com") == true || addonBg?.contains("metahub.space") == true
                val addonHasTvdbLogo = addonLogo?.contains("artworks.thetvdb.com") == true || addonLogo?.contains("metahub.space") == true

                if (addonHasTvdbBackdrop) {
                    // Addon (e.g. AIOMetadata) already ran the TVDB selection and embedded the
                    // result in its meta response — reuse it directly, zero extra HTTP calls.
                    // This matches AIOMetadata's speed: one meta call returns everything.
                    log.d { "TVDB: reusing addon backdrop/logo for $id (no extra call needed)" }
                    com.nuvio.app.features.details.MetaDetails(
                        id = id, type = type, name = addonResult?.name.orEmpty(),
                        background = addonBg,
                        logo = if (addonHasTvdbLogo) addonLogo else null,
                    )
                } else {
                    // Addon doesn't provide a TVDB backdrop (e.g. Cinemeta gives metahub URLs,
                    // or the show has no art) — call TVDB artworks for proper lang=null selection.
                    val knownTvdbId = addonResult?.tvdbId?.trim()?.takeIf(String::isNotBlank)
                    val images = runCatching {
                        if (knownTvdbId != null) TvdbImageService.fetchWithKnownTvdbId(knownTvdbId)
                        else if (imdbTmdbIdentityTrusted) TvdbImageService.fetch(type, externalId)
                        else null
                    }.getOrNull()
                    images?.let { imgs ->
                        com.nuvio.app.features.details.MetaDetails(
                            id = id, type = type, name = addonResult?.name.orEmpty(),
                            background = imgs.backdrop,
                            logo = imgs.logo,
                        )
                    }
                }
            } else null

        // Determine whether TMDB needs to be called.
        // Movies (all image modes): always TMDB.
        // TV in TmdbOnly: always TMDB.
        // TV in TmdbMoviesTvdbShows: TMDB only when TVDB is missing backdrop OR logo,
        //   so the full chain TVDB → TMDB → metahub is honoured for each field independently.
        val shouldUseTmdb = heroImageSource != HeroImageSource.Addon &&
            !(heroImageSource == HeroImageSource.TmdbMoviesTvdbShows && isTvType)
        val tvdbMissingAnyImage = tvdbResult?.background == null || tvdbResult?.logo == null
        val needsTmdb = imdbTmdbIdentityTrusted && (shouldUseTmdb || tvdbMissingAnyImage)
        val resolvedTmdbNumericId = if (needsTmdb) {
            TmdbService.ensureTmdbId(externalId, type)
            // ensureTmdbId only parses tt/tvdb/bare-numeric ids — it reads `kitsu:395` as "kitsu"
            // and gives up, which skips the entire TMDB branch below and leaves a native anime
            // movie with whatever sparse text the addon returned. The anime-list mapping supplies
            // the movie id it cannot derive.
                ?: id.takeIf { type.equals("movie", ignoreCase = true) }
                    ?.animeMovieTmdbFallbackId()
                    ?.substringAfter(':')
        } else null
        val tmdbFallbackId = if (resolvedTmdbNumericId != null) "tmdb:$resolvedTmdbNumericId" else id
        val rawTmdbResult = if (needsTmdb && resolvedTmdbNumericId != null) {
            tryFetchTmdbFallbackMeta(type = type, id = tmdbFallbackId)
        } else null
        // resolvedTmdbNumericId can come from a bare-numeric addon id trusted without
        // verification (TmdbService.ensureTmdbId's "all digits" branch) — if that number
        // collides with an unrelated TMDB entry, discard its background/logo/text rather than
        // stitching a wrong title's art onto the addon's own correct metadata. No addon result
        // to compare against means there's nothing to protect, so trust it as before.
        val tmdbResult = rawTmdbResult?.takeUnless {
            addonResult != null &&
                TmdbMetadataService.looksLikeDifferentTitle(addonResult.name, addonResult.releaseInfo, it.name, it.releaseInfo)
        }

        // Metahub fallback check: query metahub ourselves before relying on TMDB.
        // When there's no TMDB key, resolve the IMDB id straight from TVDB's own /extended
        // endpoint instead — keeps this fallback working for catalogs that hand back native
        // tvdb:-prefixed ids (e.g. movies, which TvdbImageService never images itself).
        val tvdbNativeId = addonResult?.tvdbId?.trim()?.takeIf(String::isNotBlank)
            ?: externalId.takeIf { it.startsWith("tvdb:", ignoreCase = true) }?.substringAfter(':')?.trim()
        val imdbId = if (!imdbTmdbIdentityTrusted) {
            null
        } else if (externalId.startsWith("tt")) {
            externalId
        } else if (resolvedTmdbNumericId != null) {
            TmdbService.tmdbToImdb(tmdbId = resolvedTmdbNumericId.toInt(), mediaType = type)
        } else if (tvdbNativeId != null && tvdbApiKeyPresent) {
            TvdbImageService.resolveImdbId(type = type, tvdbId = tvdbNativeId)
        } else null
        var explicitMetahubLogo: String? = null
        var explicitMetahubBackground: String? = null
        if (imdbId != null && tvdbMissingAnyImage) {
            coroutineScope {
                val logoDeferred = if (tvdbResult?.logo == null) async { MetahubService.getValidLogoUrl(imdbId) } else null
                val bgDeferred = if (tvdbResult?.background == null) async { MetahubService.getValidBackgroundUrl(imdbId) } else null
                explicitMetahubLogo = logoDeferred?.await()
                explicitMetahubBackground = bgDeferred?.await()
            }
        }

        // Merge independently per field so TMDB fills in when TVDB has no backdrop/logo.
        // e.g. a series not yet on TVDB gets TVDB logo (if present) + TMDB backdrop.
        // Priority order: TVDB -> Explicit Metahub -> Addon -> TMDB
        val textSource = tmdbResult ?: tvdbResult  // TMDB has richer text metadata
        val merged = when {
            addonResult != null -> addonResult.copy(
                background = tvdbResult?.background ?: explicitMetahubBackground ?: addonResult.background ?: tmdbResult?.background,
                logo = tvdbResult?.logo ?: explicitMetahubLogo ?: addonResult.logo ?: tmdbResult?.logo,
                genres = addonResult.genres.ifEmpty { textSource?.genres ?: emptyList() },
                description = addonResult.description ?: textSource?.description,
                releaseInfo = addonResult.releaseInfo ?: textSource?.releaseInfo,
                runtime = addonResult.runtime ?: textSource?.runtime,
                // Same reasoning as the fields above: the hero prints a rating when it has one, and
                // an addon that supplied none left a gap TMDB can fill.
                imdbRating = addonResult.imdbRating?.takeIf { it.isNotBlank() } ?: textSource?.imdbRating,
            )
            else -> (tvdbResult ?: tmdbResult)?.let { img ->
                img.copy(
                    // The TVDB branch above is an artwork carrier: it names itself from the addon
                    // result, so with no addon result its name is empty. Callers treat this as the
                    // title of the thing, so take the one source here that has a real name rather
                    // than answering with a nameless title.
                    name = img.name.takeIf { it.isNotBlank() } ?: tmdbResult?.name.orEmpty(),
                    background = tvdbResult?.background ?: explicitMetahubBackground ?: tmdbResult?.background,
                    logo = tvdbResult?.logo ?: explicitMetahubLogo ?: tmdbResult?.logo
                )
            }
        }
        merged?.let { cacheLightweightMeta(requestKey, it) }
        return merged
    }

    private suspend fun cacheLightweightMeta(requestKey: String, meta: MetaDetails) {
        lightweightMetaMutex.withLock { lightweightMetaCache[requestKey] = meta }
    }

    /**
     * Whether this result carries the text the hero actually prints beneath the title — the
     * synopsis, the genre line and the year. A result with none of them renders as a bare title
     * plus its type, so it is not a complete answer no matter how good its artwork is.
     */
    private fun MetaDetails.hasUsableHeroText(): Boolean =
        !description.isNullOrBlank() ||
            genres.any { it.isNotBlank() } ||
            !releaseInfo.isNullOrBlank()

    /**
     * Budget for one addon meta fetch **including its enrichment** — plan §21.4.
     *
     * Must exceed what it wraps. `tryFetchMeta` downloads the payload, then spends up to
     * [TMDB_ENRICH_TIMEOUT_MS] on TMDB and up to [MDBLIST_ENRICH_TIMEOUT_MS] on MDBList, each of
     * which already degrades gracefully on its own timeout. At 5s this outer budget was shorter
     * than the inner two combined, so under TMDB permit pressure it cancelled the whole thing —
     * **357 payloads in one session were downloaded and then silently thrown away** (a rethrown
     * `CancellationException` logs nothing), nothing was cached, and the caller refetched the same
     * URL up to eighteen times.
     *
     * Sized to let both inner timeouts expire and degrade rather than take the payload with them.
     */
    /**
     * The only season whose trailers the hero will play - `HeroTrailerMetadataService` filters to
     * series-level or season 1, treating later-season trailers as a spoiler risk.
     */
    private const val HERO_TRAILER_SEASON = 1

    private const val FETCH_TIMEOUT_MS = 12_000L
    private const val TMDB_ENRICH_TIMEOUT_MS = 5_000L
    private const val MDBLIST_ENRICH_TIMEOUT_MS = 5_000L
    private const val LOGO_FALLBACK_TIMEOUT_MS = 5_000L

    /**
     * The season whose trailers are worth fetching for a Home-side enrichment.
     *
     * Highest season the user has actually watched, from local history; null when they have not
     * started it, which the trailer scope reads as "the first". Local data only — this must not add
     * a request to a path whose whole purpose is to avoid them.
     */
    private fun preferredTrailerSeason(id: String): Int? =
        WatchedRepository.uiState.value.items
            .asSequence()
            .filter { it.id == id }
            .mapNotNull { it.season }
            .filter { it > 0 }
            .maxOrNull()

    private suspend fun tryFetchMeta(
        manifest: AddonManifest,
        type: String,
        id: String,
        includeMdbList: Boolean,
        enrichTmdb: Boolean = true,
        trailerScope: TmdbMetadataService.TrailerScope = TmdbMetadataService.TrailerScope.AllSeasons,
        includeEpisodes: Boolean = true,
        /**
         * Which cache namespace asked for this — diagnostic only, plan §21.3.
         *
         * The same addon meta URL was measured being fetched 7-9 times in one session despite the
         * single-flight below working as designed. Every line this logs is a cache *miss* by
         * definition, so counting distinct origins against one repeated URL says whether the
         * duplicates are separate namespaces legitimately each paying once (three of them exist:
         * lightweight, full, supplemental) or one namespace failing to cache at all.
         */
        origin: String = "unknown",
    ): MetaDetails? {
        val url = buildAddonResourceUrl(
            manifestUrl = manifest.transportUrl,
            resource = "meta",
            type = type,
            id = id,
        )

        return try {
            TmdbSettingsRepository.ensureLoaded()
            log.d { "Fetching meta from: $url [origin=$origin]" }
            val payload = httpGetText(url)
            log.d { "Raw payload length=${payload.length}, first 500 chars: ${payload.take(500)}" }
            val result = MetaDetailsParser.parse(payload)
            val tmdbEnriched = if (enrichTmdb) {
                withTimeoutOrNull(TMDB_ENRICH_TIMEOUT_MS) {
                    TmdbMetadataService.enrichMeta(
                        meta = result,
                        fallbackItemId = id,
                        settings = TmdbSettingsRepository.snapshot(),
                        trailerScope = trailerScope,
                        includeEpisodes = includeEpisodes,
                    )
                } ?: result
            } else {
                result
            }
            val enriched = if (includeMdbList) {
                MdbListSettingsRepository.ensureLoaded()
                withTimeoutOrNull(MDBLIST_ENRICH_TIMEOUT_MS) {
                    MdbListMetadataService.enrichMeta(
                        meta = tmdbEnriched,
                        fallbackItemId = id,
                        settings = MdbListSettingsRepository.snapshot(),
                    )
                } ?: tmdbEnriched
            } else {
                tmdbEnriched
            }
            log.d { "Parsed meta: type=${enriched.type}, name=${enriched.name}, videos=${enriched.videos.size}" }
            if (enriched.videos.isNotEmpty()) {
                val first = enriched.videos.first()
                log.d { "First video: id=${first.id} title=${first.title} s=${first.season} e=${first.episode} embeddedStreams=${first.streams.size}" }
            }
            enriched
        } catch (e: Throwable) {
            if (e is CancellationException) {
                // Logged before rethrowing: cancellation after the payload arrived means a request
                // was paid for and discarded, and the silence is what made that invisible for so
                // long. Still rethrown — cancellation must propagate.
                log.d { "Meta fetch cancelled after payload for $url [origin=$origin]" }
                throw e
            }
            log.e(e) { "Failed to fetch/parse meta from $url (manifest=${manifest.transportUrl})" }
            null
        }
    }

    private fun findMetaManifests(type: String, id: String): List<AddonManifest> =
        AddonRepository.uiState.value.addons
            .enabledAddons()
            .mapNotNull { it.manifest }
            .filter { manifest ->
                manifest.resources.any { resource ->
                    resource.name == "meta" &&
                        resource.types.contains(type) &&
                        (resource.idPrefixes.isEmpty() || resource.idPrefixes.any { id.startsWith(it) })
                }
            }

    private suspend fun resolveMetaLookupId(itemId: String, itemType: String): String {
        val tmdbId = itemId
            .takeIf { it.startsWith("tmdb:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.substringBefore(':')
            ?.toIntOrNull()
            ?: return itemId

        return withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            TmdbService.tmdbToImdb(tmdbId = tmdbId, mediaType = itemType)
        }
            ?.takeIf { it.isNotBlank() }
            ?: itemId
    }

    private suspend fun tryFetchTmdbFallbackMeta(type: String, id: String): MetaDetails? {
        // TmdbMetadataService only understands `tmdb:` ids. A native anime movie id
        // (`kitsu:`/`mal:`/`simkl:`, which is what SIMKL Continue Watching produces) can be
        // translated through the anime-list mapping, so a setup whose anime metadata comes from
        // TMDB still resolves it instead of showing a title with no artwork or text.
        val lookupId = when {
            id.startsWith("tmdb:", ignoreCase = true) -> id
            type.equals("movie", ignoreCase = true) -> id.animeMovieTmdbFallbackId() ?: return null
            else -> return null
        }
        val meta = withTimeoutOrNull(TMDB_ENRICH_TIMEOUT_MS) {
            TmdbMetadataService.fetchStandaloneMeta(
                type = type,
                id = lookupId,
                settings = TmdbSettingsRepository.snapshot(),
            )
        } ?: return null
        // The caller asked about the native id and everything downstream is keyed on it; only the
        // lookup borrowed the TMDB id.
        return if (lookupId == id) meta else meta.copy(id = id)
    }

    private suspend fun publishLoadedMeta(
        requestKey: String,
        meta: MetaDetails,
        fallbackItemId: String,
        fallbackItemType: String,
        mdbListSettings: com.nuvio.app.features.mdblist.MdbListSettings,
        metaScreenSettingsFingerprint: String,
    ) {
        val cachedEntry = CachedMetaEntry(baseMeta = meta)
        cachedMetaByRequestKey[requestKey] = cachedEntry

        if (!shouldEnrichForMetaScreen(meta, fallbackItemId, mdbListSettings)) {
            _uiState.value = MetaDetailsUiState(meta = meta.withUnreleasedFilter())
            activeRequestKey = requestKey
            return
        }

        _uiState.value = MetaDetailsUiState(
            isLoading = true,
        )
        val enrichedMeta = withContext(Dispatchers.Default) {
            enrichForMetaScreen(
                requestKey = requestKey,
                meta = meta,
                fallbackItemId = fallbackItemId,
                fallbackItemType = fallbackItemType,
                settings = mdbListSettings,
                settingsFingerprint = metaScreenSettingsFingerprint,
            )
        }
        cachedMetaByRequestKey[requestKey] = cachedEntry.copy(
            metaScreenMeta = enrichedMeta,
            metaScreenSettingsFingerprint = metaScreenSettingsFingerprint,
        )
        _uiState.value = MetaDetailsUiState(meta = enrichedMeta.withUnreleasedFilter())
        activeRequestKey = requestKey
    }

    private suspend fun enrichForMetaScreen(
        requestKey: String,
        meta: MetaDetails,
        fallbackItemId: String,
        fallbackItemType: String,
        settings: com.nuvio.app.features.mdblist.MdbListSettings,
        settingsFingerprint: String,
    ): MetaDetails {
        val mdbListEnrichedMeta = withTimeoutOrNull(MDBLIST_ENRICH_TIMEOUT_MS) {
            MdbListMetadataService.enrichMeta(
                meta = meta,
                fallbackItemId = fallbackItemId,
                settings = settings,
            )
        } ?: meta
        val moreLikeThisEnrichedMeta = applyMoreLikeThisSource(
            meta = mdbListEnrichedMeta,
            fallbackItemId = fallbackItemId,
            fallbackItemType = fallbackItemType,
        )

        // Apply TVDB and explicit Metahub fallbacks directly so that the Details screen
        // prioritizes them over TMDB images in exactly the same way fetchLightweightMeta does.
        val externalId = fallbackItemId.split("_").firstOrNull { it.startsWith("tt", ignoreCase = true) } ?: fallbackItemId
        val imdbId = if (meta.imdbTmdbIdentityTrusted && externalId.startsWith("tt")) externalId else null

        val heroImageSource = TmdbSettingsRepository.snapshot().heroImageSource
        val isTvType = fallbackItemType.equals("series", ignoreCase = true) || fallbackItemType.equals("anime", ignoreCase = true)
        val tvdbApiKeyPresent = TvdbSettingsRepository.snapshot().hasApiKey
        val tvdbActiveForType = heroImageSource == HeroImageSource.TmdbMoviesTvdbShows && isTvType && tvdbApiKeyPresent

        val tvdbResult = if (tvdbActiveForType) {
            coroutineScope {
                val knownTvdbId = meta.tvdbId?.trim()?.takeIf(String::isNotBlank)
                val images = runCatching {
                    if (knownTvdbId != null) TvdbImageService.fetchWithKnownTvdbId(knownTvdbId)
                    else if (meta.imdbTmdbIdentityTrusted) TvdbImageService.fetch(fallbackItemType, externalId)
                    else null
                }.getOrNull()
                images?.let { imgs ->
                    MetaDetails(
                        id = meta.id, type = meta.type, name = meta.name,
                        background = imgs.backdrop,
                        logo = imgs.logo,
                    )
                }
            }
        } else null

        val tvdbMissingAnyImage = tvdbResult?.background == null || tvdbResult?.logo == null

        var explicitMetahubLogo: String? = null
        var explicitMetahubBackground: String? = null
        if (imdbId != null && tvdbMissingAnyImage) {
            coroutineScope {
                val logoDeferred = if (tvdbResult?.logo == null) async { MetahubService.getValidLogoUrl(imdbId) } else null
                val bgDeferred = if (tvdbResult?.background == null) async { MetahubService.getValidBackgroundUrl(imdbId) } else null
                explicitMetahubLogo = logoDeferred?.await()
                explicitMetahubBackground = bgDeferred?.await()
            }
        }

        val resolvedLogo = (tvdbResult?.logo ?: explicitMetahubLogo ?: moreLikeThisEnrichedMeta.logo)
            ?.takeIf { it.isNotBlank() }

        // Every source above can come up empty for reasons unrelated to the title actually lacking
        // artwork: TMDB enrichment dropped by its 5s timeout or by the artwork toggle, or an addon
        // that carried the logo but no episode list — mergeSupplementalMeta keeps only its trailers
        // and links, so its images are discarded. The home hero resolves logos through
        // fetchLightweightMeta and hits none of those, which is why a details page can show the
        // title while the hero for the same item shows a logo. Reuse what that path already found
        // rather than declaring the item logo-less. Its result is cached per id (and the home hero
        // normally warmed it with these exact arguments), so this is usually a map read.
        val fallbackLogo = if (resolvedLogo == null) {
            runCatching {
                withTimeoutOrNull(LOGO_FALLBACK_TIMEOUT_MS) {
                    fetchLightweightMeta(
                        type = fallbackItemType,
                        id = fallbackItemId,
                        preferTmdbImages = true,
                    )
                }?.logo?.takeIf { it.isNotBlank() }
            }.getOrNull()
        } else {
            null
        }

        val finalLogo = resolvedLogo ?: fallbackLogo
        log.d {
            val source = when {
                tvdbResult?.logo != null -> "tvdb"
                explicitMetahubLogo != null -> "metahub"
                resolvedLogo != null -> "addon/tmdb"
                fallbackLogo != null -> "lightweight-fallback"
                else -> "none"
            }
            "Detail logo for $fallbackItemId: source=$source url=${finalLogo?.substringBefore('?')?.take(500)}"
        }

        val enrichedMeta = moreLikeThisEnrichedMeta.copy(
            background = meta.background ?: tvdbResult?.background ?: explicitMetahubBackground ?: moreLikeThisEnrichedMeta.background,
            logo = finalLogo,
        )

        cachedMetaByRequestKey[requestKey] = cachedMetaByRequestKey[requestKey]
            ?.copy(
                metaScreenMeta = enrichedMeta,
                metaScreenSettingsFingerprint = settingsFingerprint,
            )
            ?: CachedMetaEntry(
                baseMeta = meta,
                metaScreenMeta = enrichedMeta,
                metaScreenSettingsFingerprint = settingsFingerprint,
            )

        return enrichedMeta
    }

    private suspend fun applyMoreLikeThisSource(
        meta: MetaDetails,
        fallbackItemId: String,
        fallbackItemType: String,
    ): MetaDetails {
        if (!meta.imdbTmdbIdentityTrusted) {
            return meta.copy(moreLikeThis = emptyList(), moreLikeThisSource = null)
        }
        TraktSettingsRepository.ensureLoaded()
        TraktAuthRepository.ensureLoaded()
        TmdbSettingsRepository.ensureLoaded()

        val traktSettings = TraktSettingsRepository.uiState.value
        val isTraktAuthenticated = TraktAuthRepository.uiState.value.mode == TraktConnectionMode.CONNECTED
        val shouldUseTrakt = shouldUseTraktMoreLikeThis(
            isAuthenticated = isTraktAuthenticated,
            source = traktSettings.moreLikeThisSource,
        ) && supportsMoreLikeThis(meta, fallbackItemType)

        if (shouldUseTrakt) {
            val items = runCatching {
                TraktRelatedRepository.getRelated(
                    meta = meta,
                    fallbackItemId = fallbackItemId,
                    fallbackItemType = fallbackItemType,
                )
            }.onFailure { error ->
                log.w { "Failed to load Trakt related titles for ${meta.id}: ${error.message}" }
            }.getOrDefault(emptyList())

            return meta.copy(
                moreLikeThis = items,
                moreLikeThisSource = MoreLikeThisSource.TRAKT.takeIf { items.isNotEmpty() },
            )
        }

        val tmdbSettings = TmdbSettingsRepository.snapshot()
        if (!tmdbSettings.enabled || !tmdbSettings.useMoreLikeThis) {
            return meta.copy(moreLikeThis = emptyList(), moreLikeThisSource = null)
        }

        return meta.copy(
            moreLikeThisSource = MoreLikeThisSource.TMDB.takeIf { meta.moreLikeThis.isNotEmpty() },
        )
    }

    private fun shouldFetchMdbListOnMetaScreen(
        meta: MetaDetails,
        fallbackItemId: String,
        settings: com.nuvio.app.features.mdblist.MdbListSettings,
    ): Boolean = MdbListMetadataService.shouldFetchForMeta(
        meta = meta,
        fallbackItemId = fallbackItemId,
        settings = settings,
    )

    private fun shouldEnrichForMetaScreen(
        meta: MetaDetails,
        fallbackItemId: String,
        settings: com.nuvio.app.features.mdblist.MdbListSettings,
    ): Boolean {
        if (!meta.imdbTmdbIdentityTrusted && !meta.tvdbId.isNullOrBlank()) return true
        if (shouldFetchMdbListOnMetaScreen(meta, fallbackItemId, settings)) return true
        return shouldApplyMoreLikeThisSource(meta)
    }

    private fun shouldApplyMoreLikeThisSource(meta: MetaDetails): Boolean {
        TraktSettingsRepository.ensureLoaded()
        TraktAuthRepository.ensureLoaded()
        TmdbSettingsRepository.ensureLoaded()

        val traktSettings = TraktSettingsRepository.uiState.value
        val isTraktAuthenticated = TraktAuthRepository.uiState.value.mode == TraktConnectionMode.CONNECTED
        val tmdbSettings = TmdbSettingsRepository.snapshot()
        return shouldUseTraktMoreLikeThis(
            isAuthenticated = isTraktAuthenticated,
            source = traktSettings.moreLikeThisSource,
        ) || !tmdbSettings.enabled || !tmdbSettings.useMoreLikeThis || meta.moreLikeThisSource == null && meta.moreLikeThis.isNotEmpty()
    }

    private fun buildMetaScreenSettingsFingerprint(
        settings: com.nuvio.app.features.mdblist.MdbListSettings,
    ): String {
        TraktSettingsRepository.ensureLoaded()
        TraktAuthRepository.ensureLoaded()
        TmdbSettingsRepository.ensureLoaded()
        val providers = settings.enabledProvidersInPriorityOrder().joinToString(",")
        val traktSettings = TraktSettingsRepository.uiState.value
        val traktAuthMode = TraktAuthRepository.uiState.value.mode
        val tmdbSettings = TmdbSettingsRepository.snapshot()
        return buildString {
            append("${settings.enabled}:${settings.apiKey.trim()}:$providers")
            append("|more_like=${traktSettings.moreLikeThisSource}:$traktAuthMode")
            append("|tmdb=${tmdbSettings.enabled}:${tmdbSettings.useMoreLikeThis}:${tmdbSettings.hasApiKey}:${tmdbSettings.language}")
        }
    }

    private fun supportsMoreLikeThis(meta: MetaDetails, fallbackItemType: String): Boolean =
        normalizeMoreLikeThisType(meta.type) != null || normalizeMoreLikeThisType(fallbackItemType) != null

    private fun normalizeMoreLikeThisType(value: String?): String? =
        when (value?.trim()?.lowercase()) {
            "movie", "film" -> "movie"
            "series", "show", "tv", "tvshow" -> "series"
            else -> null
        }

    private fun String.isSeriesMetaType(): Boolean =
        trim().lowercase() in setOf("series", "show", "tv", "tvshow")

    private fun MetaDetails.mergeSupplementalMeta(supplemental: MetaDetails?): MetaDetails {
        if (supplemental == null) return this
        val mergedTrailers = (trailers + supplemental.trailers)
            .distinctBy { trailer -> trailer.key.ifBlank { trailer.id } }
        return copy(
            trailers = mergedTrailers,
            links = (links + supplemental.links).distinctBy { link -> link.url },
        )
    }

    private fun MetaDetails.withUnreleasedFilter(): MetaDetails {
        if (!HomeCatalogSettingsRepository.snapshot().hideUnreleasedContent) return this
        val todayIsoDate = CurrentDateProvider.todayIsoDate()
        val releasedMoreLikeThis = moreLikeThis.filterReleasedItems(todayIsoDate)
        return copy(
            moreLikeThis = releasedMoreLikeThis,
            moreLikeThisSource = moreLikeThisSource.takeIf { releasedMoreLikeThis.isNotEmpty() },
            collectionItems = collectionItems.filterReleasedItems(todayIsoDate),
        )
    }

   
    fun findEmbeddedStreams(videoId: String): List<com.nuvio.app.features.streams.StreamItem> {
        val meta = _uiState.value.meta ?: return emptyList()
        val addonStreams = findAddonEmbeddedStreams(meta, videoId)
            .filterNot { it.streamType == "local" }
        if (addonStreams.isNotEmpty()) return addonStreams
        return emptyList()
    }

    /** Local files are a supplemental stream group unless the detail page explicitly prefers them. */
    fun findLocalStreams(videoId: String): List<com.nuvio.app.features.streams.StreamItem> {
        val meta = _uiState.value.meta
        if (meta != null) {
            val embeddedLocalStreams = findAddonEmbeddedStreams(meta, videoId)
                .filter { it.streamType == "local" }
            if (embeddedLocalStreams.isNotEmpty()) return embeddedLocalStreams
        }
        // Matched local-library items have real (addon) meta with no embedded streams; overlay the
        // local file(s) here so the whole streams → player → scrobble pipeline can serve them.
        // The lookup is video-id-keyed, so it works even when the loaded meta is stale or absent
        // (continue watching from Home, player-internal episode switches).
        return com.nuvio.app.features.locallibrary.LocalLibraryRepository.localStreamsFor(meta?.id, videoId)
    }

    private fun findAddonEmbeddedStreams(
        meta: MetaDetails,
        videoId: String,
    ): List<com.nuvio.app.features.streams.StreamItem> {
        val videosWithStreams = meta.videos.filter { it.streams.isNotEmpty() }
        if (videosWithStreams.isEmpty()) return emptyList()

        val directMatch = videosWithStreams.firstOrNull { it.id == videoId }
        if (directMatch != null) return directMatch.streams

        val parts = videoId.split(":")
        if (parts.size >= 3) {
            val season = parts[parts.size - 2].toIntOrNull()
            val episode = parts[parts.size - 1].toIntOrNull()
            if (season != null && episode != null) {
                val episodeMatch = videosWithStreams.firstOrNull { it.season == season && it.episode == episode }
                if (episodeMatch != null) return episodeMatch.streams
            }
        }

        val prefixMatch = videosWithStreams.firstOrNull { it.id.startsWith("$videoId:") }
        if (prefixMatch != null) return prefixMatch.streams

        if (videoId == meta.id && videosWithStreams.size == 1) {
            return videosWithStreams.first().streams
        }

        if (videoId == meta.id && videosWithStreams.isNotEmpty()) {
            return videosWithStreams.flatMap { it.streams }
        }

        return emptyList()
    }
}
