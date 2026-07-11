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
import com.nuvio.app.features.metadata.hasAnimeNamespacePrefix
import com.nuvio.app.features.tmdb.TmdbMetadataService
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    // Local files are a playback preference of the catalog entry used to reach this detail page,
    // not a global replacement for streams from search, home, or related-content pages.
    private var activeLocalStreamsAllowed = false
    // Bounded so a long session of browsing detail pages can't grow this map without limit.
    // Confined to the Main dispatcher (see `scope`), so a plain insertion-order LinkedHashMap that
    // drops its eldest entry past the cap is safe — no synchronization needed. 80 entries is far
    // more detail pages than a user revisits in a session while staying cheap to hold.
    private val cachedMetaByRequestKey = object : LinkedHashMap<String, CachedMetaEntry>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedMetaEntry>): Boolean =
            size > 80
    }

    fun load(type: String, id: String, preferLocalStreams: Boolean = false) {
        log.d { "load() called — type=$type id=$id" }
        val requestKey = "$type:$id"
        activeLocalStreamsAllowed = preferLocalStreams

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
                    tryFetchMeta(manifest, type, metaLookupId, includeMdbList = false)
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

    fun clear() {
        activeRequestKey = null
        cachedMetaByRequestKey.clear()
        _uiState.value = MetaDetailsUiState()
    }

    suspend fun fetch(type: String, id: String, enrichTmdb: Boolean = true): MetaDetails? {
        val requestKey = "$type:$id:enrich=$enrichTmdb"
        cachedMetaByRequestKey[requestKey]?.let { return it.baseMeta }

        val metaLookupId = resolveMetaLookupId(itemId = id, itemType = type)
        val manifests = findMetaManifests(type = type, id = metaLookupId)

        var supplementalMeta: MetaDetails? = null
        for (manifest in manifests) {
            val result = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                tryFetchMeta(manifest, type, metaLookupId, includeMdbList = false, enrichTmdb = enrichTmdb)
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

    // Separate lightweight cache: survives LaunchedEffect restarts without polluting the
    // main detail-page cache (which the full fetch() path writes to).
    private val lightweightMetaCache = mutableMapOf<String, MetaDetails>()

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
        val requestKey = "$type:$id:${if (preferTmdbImages) "tmdb" else "addon"}"
        // When preferTmdbImages is false: use the main detail-page cache (cachedMetaByRequestKey).
        // When preferTmdbImages is true: skip the main cache — it contains AIOMetadata responses
        // which have TMDB images baked in and would bypass TVDB completely. The lightweight
        // cache (lightweightMetaCache, keyed with ":tmdb") serves as our cache instead.
        if (!preferTmdbImages) {
            cachedMetaByRequestKey["$type:$id"]?.let { return it.baseMeta }
        }
        lightweightMetaCache[requestKey]?.let { return it }

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
                tryFetchMeta(manifest, type, metaLookupId, includeMdbList = false)
            }
            if (result != null) {
                if (!preferTmdbImages) {
                    // Standard path: first result wins.
                    lightweightMetaCache[requestKey] = result
                    return result
                }
                if (addonResult == null) addonResult = result
                // In TVDB-for-TV mode: always break to TVDB regardless of what the addon provides.
                // Without this guard, AIOMetadata's TMDB-sourced image.tmdb.org URLs cause an
                // early return that skips TVDB completely.
                if (!tvdbActiveForType && result.background?.contains("image.tmdb.org") == true) {
                    lightweightMetaCache[requestKey] = result
                    return result
                }
                break  // Collected text metadata; proceed to image source resolution.
            }
        }

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
                        else TvdbImageService.fetch(type, externalId)
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
        val needsTmdb = shouldUseTmdb || tvdbMissingAnyImage
        val resolvedTmdbNumericId = if (needsTmdb) {
            TmdbService.ensureTmdbId(externalId, type)
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
        val imdbId = if (externalId.startsWith("tt")) {
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
            )
            else -> (tvdbResult ?: tmdbResult)?.let { img ->
                img.copy(
                    background = tvdbResult?.background ?: explicitMetahubBackground ?: tmdbResult?.background,
                    logo = tvdbResult?.logo ?: explicitMetahubLogo ?: tmdbResult?.logo
                )
            }
        }
        merged?.let { lightweightMetaCache[requestKey] = it }
        return merged
    }

    private const val FETCH_TIMEOUT_MS = 5_000L
    private const val TMDB_ENRICH_TIMEOUT_MS = 5_000L
    private const val MDBLIST_ENRICH_TIMEOUT_MS = 5_000L

    private suspend fun tryFetchMeta(
        manifest: AddonManifest,
        type: String,
        id: String,
        includeMdbList: Boolean,
        enrichTmdb: Boolean = true,
    ): MetaDetails? {
        val url = buildAddonResourceUrl(
            manifestUrl = manifest.transportUrl,
            resource = "meta",
            type = type,
            id = id,
        )

        return try {
            TmdbSettingsRepository.ensureLoaded()
            log.d { "Fetching meta from: $url" }
            val payload = httpGetText(url)
            log.d { "Raw payload length=${payload.length}, first 500 chars: ${payload.take(500)}" }
            val result = MetaDetailsParser.parse(payload)
            val tmdbEnriched = if (enrichTmdb) {
                withTimeoutOrNull(TMDB_ENRICH_TIMEOUT_MS) {
                    TmdbMetadataService.enrichMeta(
                        meta = result,
                        fallbackItemId = id,
                        settings = TmdbSettingsRepository.snapshot(),
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
            if (e is CancellationException) throw e
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

    private suspend fun tryFetchTmdbFallbackMeta(type: String, id: String): MetaDetails? =
        withTimeoutOrNull(TMDB_ENRICH_TIMEOUT_MS) {
            TmdbMetadataService.fetchStandaloneMeta(
                type = type,
                id = id,
                settings = TmdbSettingsRepository.snapshot(),
            )
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
        val imdbId = if (externalId.startsWith("tt")) externalId else null

        val heroImageSource = TmdbSettingsRepository.snapshot().heroImageSource
        val isTvType = fallbackItemType.equals("series", ignoreCase = true) || fallbackItemType.equals("anime", ignoreCase = true)
        val tvdbApiKeyPresent = TvdbSettingsRepository.snapshot().hasApiKey
        val tvdbActiveForType = heroImageSource == HeroImageSource.TmdbMoviesTvdbShows && isTvType && tvdbApiKeyPresent

        val tvdbResult = if (tvdbActiveForType) {
            coroutineScope {
                val knownTvdbId = meta.tvdbId?.trim()?.takeIf(String::isNotBlank)
                val images = runCatching {
                    if (knownTvdbId != null) TvdbImageService.fetchWithKnownTvdbId(knownTvdbId)
                    else TvdbImageService.fetch(fallbackItemType, externalId)
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

        val enrichedMeta = moreLikeThisEnrichedMeta.copy(
            background = meta.background ?: explicitMetahubBackground ?: tvdbResult?.background ?: moreLikeThisEnrichedMeta.background,
            logo = tvdbResult?.logo ?: explicitMetahubLogo ?: moreLikeThisEnrichedMeta.logo,
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

   
    fun prefersLocalStreams(): Boolean = activeLocalStreamsAllowed

    fun findEmbeddedStreams(videoId: String): List<com.nuvio.app.features.streams.StreamItem> {
        val meta = _uiState.value.meta ?: return emptyList()
        val addonStreams = findAddonEmbeddedStreams(meta, videoId)
            .filterNot { it.streamType == "local" }
        if (addonStreams.isNotEmpty()) return addonStreams
        return emptyList()
    }

    /** Local files are a supplemental stream group unless the detail page explicitly prefers them. */
    fun findLocalStreams(videoId: String): List<com.nuvio.app.features.streams.StreamItem> {
        val meta = _uiState.value.meta ?: return emptyList()
        val embeddedLocalStreams = findAddonEmbeddedStreams(meta, videoId)
            .filter { it.streamType == "local" }
        if (embeddedLocalStreams.isNotEmpty()) return embeddedLocalStreams
        // Matched local-library items have real (addon) meta with no embedded streams; overlay the
        // local file(s) here so the whole streams → player → scrobble pipeline can serve them.
        return com.nuvio.app.features.locallibrary.LocalLibraryRepository.localStreamsFor(meta.id, videoId)
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
