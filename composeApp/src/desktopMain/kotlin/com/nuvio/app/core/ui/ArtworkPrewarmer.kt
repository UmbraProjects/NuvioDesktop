package com.nuvio.app.core.ui

import com.nuvio.app.features.tmdb.fullSizeTmdbBackdrop
import co.touchlab.kermit.Logger
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.nuvio.app.features.collection.Collection
import com.nuvio.app.features.collection.CollectionFolder
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.collection.FolderViewMode
import com.nuvio.app.features.collection.loadFolderPrewarmItems
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HomeHeroSnapshot
import com.nuvio.app.features.home.HomeRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.heroNeedsMetadataFetch
import com.nuvio.app.features.home.resolveFocusedHeroItem
import com.nuvio.app.features.home.searchLibraryHeroBackdropEnrichmentEnabled
import com.nuvio.app.features.home.withNormalizedHeroBackdrop
import com.nuvio.app.features.home.components.COLLECTION_ARTWORK_PREFETCH_LIMIT
import com.nuvio.app.features.home.components.HeroBackdropFramer
import com.nuvio.app.features.home.components.collectionFolderCardImageUrl
import com.nuvio.app.features.tmdb.HeroImageSource
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.home.components.continueWatchingDisplayArtworkChain
import com.nuvio.app.features.home.components.effectiveContinueWatchingStyle
import com.nuvio.app.features.home.components.landscapeCardTitleOverlay
import com.nuvio.app.features.home.components.posterCardArtwork
import com.nuvio.app.features.home.randomPlayCategoryOrNull
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.toMetaPreview
import com.nuvio.app.features.playlist.PlaylistContinueWatchingSnapshot
import com.nuvio.app.features.posterservice.CustomPosterKeys
import com.nuvio.app.features.posterservice.CustomPosterScreen
import com.nuvio.app.features.posterservice.CustomPosterSettingsRepository
import com.nuvio.app.features.posterservice.withCachedCustomPosters
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val prewarmLog = Logger.withTag("ArtworkPrewarm")

internal enum class PrewarmArtworkKind { Poster, Logo, Backdrop }

internal data class PrewarmTarget(val url: String, val kind: PrewarmArtworkKind)

/**
 * An item the hero shows when its card is focused (Adaptive, TV Mode, the Library tab). Which
 * backdrop and logo that is depends on the hero's metadata pass, so it is resolved when its turn
 * comes rather than up front: through the hero's own [resolveFocusedHeroItem], whose metadata is
 * cached, so the later focus is instant as well.
 */
internal data class PrewarmFocusedHero(val item: MetaPreview, val normalHomeMode: Boolean)

/**
 * A collection folder whose titles a pass warms (opt-in, [ImageCacheSettings.prewarmCollectionContents]).
 * Its catalogs are only fetched when its turn comes, like a focused hero's metadata, so a pass that
 * is restarted or cancelled early never pays for folders it did not reach.
 */
internal data class PrewarmCollectionFolder(
    val collectionId: String,
    val folder: CollectionFolder,
    val viewMode: FolderViewMode,
)

/**
 * The display settings that decide which image each surface shows. Read from the same
 * repositories the screens read, through the same derivations ([effectiveHomePosterCardStyle],
 * [effectiveContinueWatchingStyle]).
 */
internal data class PrewarmDisplay(
    val cardStyle: PosterCardStyleUiState = PosterCardStyleUiState(),
    val continueWatchingStyle: ContinueWatchingSectionStyle = ContinueWatchingSectionStyle.Card,
    val useEpisodeThumbnails: Boolean = true,
    /** Search/Library's "look up every focused result" mode; see searchLibraryHeroBackdropEnrichmentEnabled. */
    val searchLibraryBackdropEnrichment: Boolean = false,
    val tvdbForShows: Boolean = false,
    /** The hero frames backdrops automatically (Adaptive / Ambient with framing on). */
    val frameHeroBackdrops: Boolean = false,
)

private data class PrewarmInputs(
    val settings: ImageCacheSettings,
    val display: PrewarmDisplay,
    val hero: List<MetaPreview>,
    val sections: List<HomeCatalogSection>,
    val continueWatching: List<ContinueWatchingItem>,
    val focusRows: List<List<MetaPreview>> = emptyList(),
    val library: List<LibraryItem> = emptyList(),
    val collections: List<Collection> = emptyList(),
) {
    val isEmpty: Boolean
        get() = hero.isEmpty() && sections.isEmpty() && continueWatching.isEmpty() && library.isEmpty() &&
            collections.isEmpty()
}

/**
 * Downloads the artwork for Home, Continue Watching and the Library into the disk cache before
 * anyone scrolls to it (opt-in, Settings → Advanced → Cache).
 *
 * Nearly all the waiting on Home is first sight: a poster-service render costs ~2.5 s, while the
 * same image read back from disk costs a few milliseconds. So once Home has settled, this walks
 * the hero, Continue Watching, every row from the top, Home's collection cards and then the Library,
 * and fetches the image each one would show with the current settings, for the chosen kinds, when it
 * is not on disk yet. Optionally it then goes inside each collection folder as well.
 * Bytes only, never decoded — the size a card will ask for is unknown here, and a bitmap decoded at
 * the wrong size is a memory entry nothing reads (the same reasoning as the collection row
 * prefetch).
 *
 * Deliberately gentle: [Concurrency] fetches at a time so it cannot crowd out the cards the user
 * is looking at (an identical visible request joins the prewarm's download instead of starting
 * another, see CoalescingRequestStrategy), nothing at all while the player is open, and anything
 * already on disk is skipped without a request — including a stale copy, whose revalidation is
 * left to the card that eventually shows it. A change to Home or to a display setting restarts the
 * pass after [IdleDelayMs], and the restart skips everything the last pass fetched. What failed —
 * an image or a hero's metadata lookup — is left alone for [FailureRetryMs], and a lookup is waited
 * on for at most [LookupTimeoutMs].
 */
internal object ArtworkPrewarmer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            // Nothing to warm before Home has loaded, and startup has better uses for the time.
            delay(StartupDelayMs)
            ImageCacheSettingsRepository.ensureLoaded()
            PosterCardStyleRepository.ensureLoaded()
            ContinueWatchingPreferencesRepository.ensureLoaded()
            val display = combine(
                PosterCardStyleRepository.uiState,
                HomeCatalogSettingsRepository.uiState.map {
                    it.tvModeEnabled to (it.adaptiveHeroEnabled && !it.tvModeEnabled && it.adaptiveHeroAutoFraming)
                }.distinctUntilChanged(),
                ContinueWatchingPreferencesRepository.uiState,
                TmdbSettingsRepository.uiState,
            ) { cardStyle, (tvMode, frameHeroBackdrops), cwPreferences, tmdb ->
                val homeCardStyle = effectiveHomePosterCardStyle(cardStyle, tvModeEnabled = tvMode)
                PrewarmDisplay(
                    cardStyle = homeCardStyle,
                    continueWatchingStyle = effectiveContinueWatchingStyle(
                        requestedStyle = cwPreferences.style,
                        tvModeEnabled = tvMode,
                        catalogLandscapeModeEnabled = homeCardStyle.catalogLandscapeModeEnabled,
                    ),
                    useEpisodeThumbnails = cwPreferences.useEpisodeThumbnails,
                    searchLibraryBackdropEnrichment = searchLibraryHeroBackdropEnrichmentEnabled(
                        tmdbSettings = tmdb,
                        // The raw setting, as HomeScreen reads it for this decision.
                        catalogLandscapeModeEnabled = cardStyle.catalogLandscapeModeEnabled,
                    ),
                    tvdbForShows = tmdb.heroImageSource == HeroImageSource.TmdbMoviesTvdbShows,
                    frameHeroBackdrops = frameHeroBackdrops,
                )
            }.distinctUntilChanged()
            combine(
                ImageCacheSettingsRepository.settings,
                display,
                // The hero as Home settled on it, after enrichment may have swapped its backdrop
                // or filled its logo — the catalog row alone cannot say which one it will show.
                HomeHeroSnapshot.items.map { it.orEmpty() },
                HomeRepository.uiState.map { it.sections }.distinctUntilChanged(),
                // What Home's Continue Watching and Next Up rows draw, published by Home itself
                // (custom posters already applied), so this needs no copy of their assembly.
                PlaylistContinueWatchingSnapshot.rows.map { (cw, nextUp) -> cw + nextUp }.distinctUntilChanged(),
            ) { settings, displayNow, hero, sections, continueWatching ->
                PrewarmInputs(settings, displayNow, hero, sections, continueWatching, emptyList())
            }
                .combine(
                    LibraryRepository.uiState
                        .map { state -> state.sections.flatMap { it.items }.ifEmpty { state.items } }
                        .distinctUntilChanged(),
                ) { inputs, library -> inputs.copy(library = library) }
                .combine(HomeHeroSnapshot.focusRows) { inputs, focusRows -> inputs.copy(focusRows = focusRows) }
                .combine(homeCollections()) { inputs, collections -> inputs.copy(collections = collections) }
                .collectLatest { inputs ->
                    val settings = inputs.settings
                    if (!settings.prewarmsAnything || inputs.isEmpty) return@collectLatest
                    delay(IdleDelayMs)
                    val folders = prewarmCollectionFolders(inputs.collections, settings)
                    val (targets, focused) = withContext(Dispatchers.Main) {
                        // Library posters resolve through the poster-service settings, which are
                        // written on Main; read them there too.
                        val library = inputs.library.take(PrewarmLibraryLimit).map { it.toMetaPreview() }
                        prewarmTargets(
                            heroItems = inputs.hero,
                            sections = inputs.sections,
                            settings = settings,
                            display = inputs.display,
                            continueWatching = inputs.continueWatching,
                            library = library,
                            collections = inputs.collections,
                        ) to prewarmFocusedHeroes(
                            focusRows = inputs.focusRows,
                            library = library,
                            settings = settings,
                        )
                    }
                    // The rotating hero's own backdrops, which automatic framing will crop.
                    val heroBackdrops = inputs.hero.mapNotNull { heroArtwork(it).firstOrNull()?.url }.toSet()
                    warm(targets, focused, folders, settings, inputs.display, heroBackdrops)
                }
        }
    }

    /**
     * The collections Home shows, in Home's order: enabled in the Home layout and with at least one
     * folder, as HomeScreen filters them.
     */
    private fun homeCollections() = combine(
        HomeCatalogSettingsRepository.uiState.map { state ->
            state.items.filter { it.enabled && it.isCollection }.map { it.key }
        }.distinctUntilChanged(),
        CollectionRepository.collections,
    ) { keys, collections ->
        val byKey = collections.filter { it.folders.isNotEmpty() }.associateBy { "collection_${it.id}" }
        keys.mapNotNull(byKey::get)
    }.distinctUntilChanged()

    private suspend fun warm(
        targets: List<PrewarmTarget>,
        focused: List<PrewarmFocusedHero>,
        folders: List<PrewarmCollectionFolder>,
        settings: ImageCacheSettings,
        display: PrewarmDisplay,
        heroBackdrops: Set<String> = emptySet(),
    ) {
        val jobCount = targets.size + focused.size + folders.size
        if (jobCount == 0) return
        val loader = SingletonImageLoader.get(PlatformContext.INSTANCE)
        val diskCache = loader.diskCache ?: return
        val startedAt = System.currentTimeMillis()
        val next = AtomicInteger(0)
        val checked = AtomicInteger(0)
        val downloaded = AtomicInteger(0)
        val alreadyCached = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val resolvedHeroes = AtomicInteger(0)
        val lookupsHeldOff = AtomicInteger(0)
        val failuresHeldOff = AtomicInteger(0)
        val folderTitles = AtomicInteger(0)
        val framed = AtomicInteger(0)
        // What is on disk for what Home and the Library show now, whether this pass fetched it or
        // an earlier one did — the running total the settings show, not just this pass's work.
        val ready = PrewarmArtworkKind.entries.associateWith { AtomicInteger(0) }
        val framedReady = AtomicInteger(0)
        val framingChecked = ConcurrentHashMap.newKeySet<String>()
        // Automatic framing is analysed here too, so a hero backdrop is framed from its first
        // frame rather than on first sight. Only for backdrops this pass is allowed to fetch, and
        // only while the hero would actually frame them.
        val frameHeroBackdrops = settings.prewarmBackdrops && display.frameHeroBackdrops
        // A few examples for the log; the full list would be noise.
        val failedExamples = ConcurrentHashMap.newKeySet<String>()
        val failedLookupKeys = ConcurrentHashMap.newKeySet<String>()
        // URLs already taken by an earlier job, so a focused hero whose backdrop a card or the
        // rotating hero already warmed costs nothing.
        val seen = ConcurrentHashMap.newKeySet<String>().apply { targets.forEach { add(it.url) } }
        fun publish(running: Boolean) = ImageCacheSettingsRepository.publishPrewarmStatus(
            ImageCachePrewarmStatus(
                running = running,
                checked = checked.get(),
                downloaded = downloaded.get(),
                alreadyCached = alreadyCached.get(),
                failed = failed.get(),
                total = jobCount,
                postersReady = ready.getValue(PrewarmArtworkKind.Poster).get(),
                logosReady = ready.getValue(PrewarmArtworkKind.Logo).get(),
                backdropsReady = ready.getValue(PrewarmArtworkKind.Backdrop).get(),
                framingActive = frameHeroBackdrops,
                backdropsFramed = framedReady.get(),
            ),
        )

        suspend fun fetch(url: String, kind: PrewarmArtworkKind) {
            val onDisk = runCatching { diskCache.openSnapshot(url)?.use { true } }.getOrNull() == true
            if (onDisk) {
                alreadyCached.incrementAndGet()
                ready.getValue(kind).incrementAndGet()
                return
            }
            // Failures are never written to disk (ImageDiskCacheStrategy), so without this every
            // restarted pass would ask for the same missing images again.
            if (recentlyFailed(failedUrls, url)) {
                failuresHeldOff.incrementAndGet()
                return
            }
            val error: Throwable? = try {
                when (
                    val result = loader.execute(
                        ImageRequest.Builder(PlatformContext.INSTANCE)
                            .data(url)
                            .nuvioArtworkRequestSize()
                            .build(),
                    )
                ) {
                    is SuccessResult -> null
                    is ErrorResult -> result.throwable
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                error
            }
            if (error == null) {
                failedUrls.remove(url)
                downloaded.incrementAndGet()
                ready.getValue(kind).incrementAndGet()
            } else {
                failedUrls[url] = System.currentTimeMillis()
                failed.incrementAndGet()
                if (failedExamples.size < LoggedFailureExamples) {
                    failedExamples += "${ImageFetchTimingListener.describeData(url)} " +
                        "(${error.javaClass.simpleName}: ${error.message?.take(80)})"
                }
            }
        }

        /**
         * Analyses a hero backdrop for automatic framing unless that is already on disk. Decoded
         * at a modest size with no memory-cache entry: the analysis reduces it to 640px anyway,
         * and a bitmap left in memory here is one nothing on screen would read.
         */
        suspend fun frame(url: String) {
            if (!frameHeroBackdrops || !framingChecked.add(url) || recentlyFailed(failedUrls, url)) return
            if (HeroBackdropFramer.profile(url, image = null) != null) {
                framedReady.incrementAndGet()
                return
            }
            val image = try {
                (
                    loader.execute(
                        ImageRequest.Builder(PlatformContext.INSTANCE)
                            .data(url)
                            .size(FramingDecodeSize, FramingDecodeSize)
                            .precision(coil3.size.Precision.INEXACT)
                            .memoryCachePolicy(CachePolicy.DISABLED)
                            .build(),
                    ) as? SuccessResult
                    )?.image
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                null
            } ?: return
            if (HeroBackdropFramer.profile(url, image) != null) {
                framed.incrementAndGet()
                framedReady.incrementAndGet()
            }
        }

        publish(running = true)
        try {
            coroutineScope {
                repeat(Concurrency) {
                    launch {
                        while (true) {
                            val index = next.getAndIncrement()
                            if (index >= jobCount) break
                            while (DesktopArtworkCaches.isPlaybackActive) delay(PlaybackPollMs)
                            when {
                                index < targets.size -> {
                                    val url = targets[index].url
                                    fetch(url, targets[index].kind)
                                    if (url in heroBackdrops) frame(url)
                                }
                                index < targets.size + focused.size -> {
                                    val job = focused[index - targets.size]
                                    val shown = focusedHeroArtwork(
                                        job = job,
                                        display = display,
                                        onLookup = { resolvedHeroes.incrementAndGet() },
                                        onHeldOff = { lookupsHeldOff.incrementAndGet() },
                                        onFailed = { failedLookupKeys += it },
                                    )
                                    shown.filter { it.kind.isEnabledIn(settings) && seen.add(it.url) }
                                        .forEach { fetch(it.url, it.kind) }
                                    // Framed even when a card already fetched the same image.
                                    shown.firstOrNull { it.kind != PrewarmArtworkKind.Logo }?.let { frame(it.url) }
                                }
                                else -> {
                                    val job = folders[index - targets.size - focused.size]
                                    val items = folderItems(job)
                                    folderTitles.addAndGet(items.size)
                                    prewarmFolderTargets(items, job.viewMode, display, settings)
                                        .filter { seen.add(it.url) }
                                        .forEach { fetch(it.url, it.kind) }
                                }
                            }
                            if (checked.incrementAndGet() % StatusEvery == 0) publish(running = true)
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            publish(running = false)
            // Otherwise a pass that Home restarted leaves no trace at all.
            prewarmLog.i {
                "pass cancelled after ${System.currentTimeMillis() - startedAt}ms at " +
                    "${checked.get()}/$jobCount: downloaded=${downloaded.get()} failed=${failed.get()}"
            }
            throw error
        }
        publish(running = false)
        prewarmLog.i {
            "pass done in ${System.currentTimeMillis() - startedAt}ms: ${targets.size} targets " +
                "(${targets.count { it.kind == PrewarmArtworkKind.Poster }} posters, " +
                "${targets.count { it.kind == PrewarmArtworkKind.Logo }} logos, " +
                "${targets.count { it.kind == PrewarmArtworkKind.Backdrop }} backdrops) + " +
                "${focused.size} focused heroes (${resolvedHeroes.get()} looked up, " +
                "${lookupsHeldOff.get()} held off after a recent failure) + " +
                "${folders.size} collection folders (${folderTitles.get()} titles), " +
                "downloaded=${downloaded.get()} cached=${alreadyCached.get()} failed=${failed.get()} " +
                "heldOff=${failuresHeldOff.get()} framed=${framed.get()}"
        }
        if (failedExamples.isNotEmpty()) {
            prewarmLog.i { "failed images (first $LoggedFailureExamples): ${failedExamples.joinToString(" | ")}" }
        }
        if (failedLookupKeys.isNotEmpty()) {
            prewarmLog.i {
                "hero lookups with no answer, held off for ${FailureRetryMs / 60_000} min: " +
                    failedLookupKeys.take(LoggedFailureExamples).joinToString() +
                    if (failedLookupKeys.size > LoggedFailureExamples) " (+${failedLookupKeys.size - LoggedFailureExamples} more)" else ""
            }
        }
    }

    private fun recentlyFailed(failures: ConcurrentHashMap<String, Long>, key: String): Boolean {
        val failedAt = failures[key] ?: return false
        if (System.currentTimeMillis() - failedAt < FailureRetryMs) return true
        failures.remove(key, failedAt)
        return false
    }

    /**
     * What the hero shows for [job]'s item when its card is focused: the item as the hero reads it
     * ([withNormalizedHeroBackdrop]), then the hero's own metadata pass when it would run one.
     */
    private suspend fun focusedHeroArtwork(
        job: PrewarmFocusedHero,
        display: PrewarmDisplay,
        onLookup: () -> Unit,
        onHeldOff: () -> Unit,
        onFailed: (String) -> Unit,
    ): List<PrewarmTarget> {
        val raw = job.item.withNormalizedHeroBackdrop()
        if (!raw.heroNeedsMetadataFetch(job.normalHomeMode, display.searchLibraryBackdropEnrichment)) {
            return heroArtwork(raw)
        }
        // A title whose metadata did not come back is not asked again for a while: three titles
        // whose addon timed out held all three workers for a minute in every pass. Nothing is
        // warmed for it meanwhile, since what its hero will show is unknown — the failed pass
        // already warmed whatever it fell back to.
        val key = "${raw.metadataType}:${raw.metadataId}"
        if (recentlyFailed(failedLookups, key)) {
            onHeldOff()
            return emptyList()
        }
        onLookup()
        var metaMissing = false
        // Only the wait is cut short: the fetch itself is shared and runs in MetaDetailsRepository's
        // scope, so a slow answer still reaches its cache for when the card is focused.
        val shown = withTimeoutOrNull(LookupTimeoutMs) {
            resolveFocusedHeroItem(
                raw = raw,
                normalHomeMode = job.normalHomeMode,
                tvdbForShows = display.tvdbForShows,
                onMetaMissing = { metaMissing = true },
            ).first
        }
        if (shown == null || metaMissing) {
            failedLookups[key] = System.currentTimeMillis()
            onFailed(if (shown == null) "$key (timed out)" else key)
        } else {
            failedLookups.remove(key)
        }
        return shown?.let(::heroArtwork).orEmpty()
    }

    private val failedLookups = ConcurrentHashMap<String, Long>()
    private val failedUrls = ConcurrentHashMap<String, Long>()

    /**
     * A folder's titles as its screen shows them: its catalogs' first pages, through the
     * Collections poster-service overlay. The catalogs are cached for [FolderItemsTtlMs], because a
     * pass restarts whenever Home changes and would otherwise fetch every folder again each time.
     * Behind that, the list from an earlier launch ([PrewarmFolderListStore]), so a launch does not
     * refetch every warmed folder either.
     */
    private suspend fun folderItems(job: PrewarmCollectionFolder): List<MetaPreview> {
        val key = "${job.collectionId}/${job.folder.id}/${job.folder.resolvedSources.hashCode()}"
        val now = System.currentTimeMillis()
        val raw = folderItemsCache[key]?.takeIf { now - it.first < FolderItemsTtlMs }?.second
            ?: PrewarmFolderListStore.read(key)?.also { stored -> folderItemsCache[key] = stored }?.second
            // An empty answer is usually a failed fetch, so it is retried next pass rather than kept.
            ?: loadFolderPrewarmItems(job.folder, PrewarmPerRowLimit, PrewarmFolderSourceLimit)
                .also { items ->
                    if (items.isNotEmpty()) {
                        folderItemsCache[key] = now to items
                        PrewarmFolderListStore.write(key, now, items)
                    }
                }
        // The poster-service settings are written on Main; read them there too.
        return withContext(Dispatchers.Main) {
            raw.withCachedCustomPosters(
                CustomPosterSettingsRepository.snapshot(CustomPosterScreen.Collections),
                CustomPosterKeys.snapshot(),
            )
        }
    }

    private val folderItemsCache = ConcurrentHashMap<String, Pair<Long, List<MetaPreview>>>()

    private const val FolderItemsTtlMs = 30 * 60_000L

    /**
     * Sources warmed per folder: the rows a folder opens on. A community folder can hold 50 sources
     * (one TMDB or Trakt request each); the ones past the first screen are reached by scrolling, by
     * which time their art loads as it would have anyway.
     */
    private const val PrewarmFolderSourceLimit = 8
    private const val StartupDelayMs = 15_000L

    /** Long enough for Home's rows to finish arriving, so one pass covers them all. */
    private const val IdleDelayMs = 8_000L
    private const val Concurrency = 3

    /** Decode bound for framing analysis: comfortably above the 640px the analysis works at. */
    private const val FramingDecodeSize = 1280
    private const val PlaybackPollMs = 5_000L
    private const val StatusEvery = 25

    /** How long a failed image or hero lookup is left alone before a pass tries it again. */
    private const val FailureRetryMs = 30 * 60_000L

    /** Longer than a healthy lookup takes; the addon's own socket timeout is a minute. */
    private const val LookupTimeoutMs = 15_000L
    private const val LoggedFailureExamples = 5
}

/**
 * What a prewarm pass fetches: the one image each surface would show with [display], in the order
 * the user meets them — the hero, Continue Watching (and Next Up), each Home row from the top,
 * Home's collection cards, then the Library. Fallbacks are left alone; a card only asks for one
 * when its first choice fails.
 *
 * Each surface is asked through the function that surface itself uses, so the two cannot drift:
 *  - **Hero** — the settled hero item's backdrop (the poster when it has none) and its logo.
 *  - **Row and Library cards** — [cardArtwork].
 *  - **Collection cards** — each folder's card image and its cover, as the row's own prefetch
 *    (PrefetchCollectionArtwork) takes them.
 *  - **Continue Watching** — the head of [continueWatchingDisplayArtworkChain] for the row's style.
 *    These cards draw no logo.
 *
 * Kinds follow what the image is: the poster, a poster-service landscape poster and Continue
 * Watching's custom art are posters; a backdrop, an episode still or a thumbnail is a backdrop.
 */
internal fun prewarmTargets(
    heroItems: List<MetaPreview>,
    sections: List<HomeCatalogSection>,
    settings: ImageCacheSettings,
    display: PrewarmDisplay = PrewarmDisplay(),
    continueWatching: List<ContinueWatchingItem> = emptyList(),
    library: List<MetaPreview> = emptyList(),
    collections: List<Collection> = emptyList(),
    perRowLimit: Int = PrewarmPerRowLimit,
    maxTargets: Int = PrewarmMaxTargets,
): List<PrewarmTarget> {
    if (!settings.prewarmsAnything) return emptyList()
    val seen = HashSet<String>()
    val targets = ArrayList<PrewarmTarget>()
    fun add(url: String?, kind: PrewarmArtworkKind) {
        if (targets.size >= maxTargets || !kind.isEnabledIn(settings)) return
        val value = url.httpUrlOrNull() ?: return
        if (seen.add(value)) targets += PrewarmTarget(value, kind)
    }

    heroItems.forEach { item -> heroArtwork(item).forEach { add(it.url, it.kind) } }

    continueWatching.forEach { item ->
        val shown = item.continueWatchingDisplayArtworkChain(
            style = display.continueWatchingStyle,
            useEpisodeThumbnails = display.useEpisodeThumbnails,
        ).firstOrNull()?.trim() ?: return@forEach
        val isPosterArt = shown == item.customPoster?.trim() || shown == item.poster?.trim() ||
            shown == item.customLandscape?.trim()
        add(shown, if (isPosterArt) PrewarmArtworkKind.Poster else PrewarmArtworkKind.Backdrop)
    }

    fun addCard(item: MetaPreview) = cardArtwork(item, display).forEach { add(it.url, it.kind) }
    sections.forEach { section -> section.items.take(perRowLimit).forEach(::addCard) }
    // Curated art, warmed as Poster like other card art. The cover goes too when the card leads
    // with a focus GIF: it is what the card shows while the GIF's frames decode.
    collections.forEach { collection ->
        collection.folders.take(COLLECTION_ARTWORK_PREFETCH_LIMIT).forEach { folder ->
            add(collectionFolderCardImageUrl(folder, animateGifs = true), PrewarmArtworkKind.Poster)
            add(folder.coverImageUrl, PrewarmArtworkKind.Poster)
        }
    }
    library.forEach(::addCard)
    return targets
}

/**
 * A row or Library card's image ([posterCardArtwork]) and, on a landscape card, the logo
 * [landscapeCardTitleOverlay] draws over it. Portrait cards draw no logo.
 */
internal fun cardArtwork(item: MetaPreview, display: PrewarmDisplay): List<PrewarmTarget> {
    // Random Play cards draw a collage of their own, not the item's art.
    if (item.randomPlayCategoryOrNull() != null) return emptyList()
    val landscape = display.cardStyle.catalogLandscapeModeEnabled
    val artwork = posterCardArtwork(
        isLandscapeMode = landscape,
        posterUrl = item.poster,
        posterFallbackUrl = item.posterFallback,
        backdropUrl = item.banner,
        landscapePosterUrl = item.landscapePoster,
    )
    val overlay = landscapeCardTitleOverlay(
        isLandscapeMode = landscape,
        useTextTitle = display.cardStyle.landscapeTextTitlesEnabled,
        hideLabels = display.cardStyle.hideLabelsEnabled,
        title = item.name,
        logoUrl = item.logo,
        artIncludesTitle = landscape && !item.landscapePoster.isNullOrBlank(),
    )
    return listOfNotNull(
        artwork.imageUrl.httpUrlOrNull()?.let { shown ->
            val kind = if (shown == item.banner?.trim()) PrewarmArtworkKind.Backdrop else PrewarmArtworkKind.Poster
            PrewarmTarget(shown, kind)
        },
        overlay.logoUrl.httpUrlOrNull()?.let { PrewarmTarget(it, PrewarmArtworkKind.Logo) },
    )
}

/**
 * The folders whose titles a pass warms, in Home's order, when
 * [ImageCacheSettings.prewarmCollectionContents] is on. Capped per collection like the cards.
 */
internal fun prewarmCollectionFolders(
    collections: List<Collection>,
    settings: ImageCacheSettings,
    perCollectionLimit: Int = COLLECTION_ARTWORK_PREFETCH_LIMIT,
    totalLimit: Int = PREWARM_COLLECTION_FOLDER_LIMIT,
): List<PrewarmCollectionFolder> {
    if (!settings.prewarmsAnything || !settings.prewarmCollectionContents) return emptyList()
    return collections.flatMap { collection ->
        collection.folders.take(perCollectionLimit).map { folder ->
            PrewarmCollectionFolder(collection.id, folder, collection.folderViewMode)
        }
    }.take(totalLimit)
}

/**
 * Folders warmed per pass across every collection, in Home's order. Per-collection capping alone
 * let a 21-collection community pack queue 344 folders (40,000 titles, ~3,200 source requests).
 */
internal const val PREWARM_COLLECTION_FOLDER_LIMIT = 60

/**
 * What a folder screen draws for [items]: the grid shows every title as a plain poster whatever the
 * card style, while the row layouts draw Home's cards ([cardArtwork]).
 */
internal fun prewarmFolderTargets(
    items: List<MetaPreview>,
    viewMode: FolderViewMode,
    display: PrewarmDisplay,
    settings: ImageCacheSettings,
): List<PrewarmTarget> {
    val shown = if (viewMode == FolderViewMode.TABBED_GRID) {
        items.mapNotNull { item -> item.poster.httpUrlOrNull()?.let { PrewarmTarget(it, PrewarmArtworkKind.Poster) } }
    } else {
        items.flatMap { cardArtwork(it, display) }
    }
    return shown.filter { it.kind.isEnabledIn(settings) }.distinctBy { it.url }
}

/**
 * The items whose focused-hero art a pass warms, in the order the user meets them: Home's rows
 * when the hero follows focus there (Adaptive, TV Mode — HomeScreen publishes them only then),
 * then the Library, whose hero always follows focus. Nothing when neither backdrops nor logos are
 * wanted, since that is all a focused hero adds over the card.
 */
internal fun prewarmFocusedHeroes(
    focusRows: List<List<MetaPreview>>,
    library: List<MetaPreview>,
    settings: ImageCacheSettings,
    perRowLimit: Int = PrewarmPerRowLimit,
    maxItems: Int = PrewarmMaxTargets,
): List<PrewarmFocusedHero> {
    if (!settings.prewarmsAnything || !(settings.prewarmBackdrops || settings.prewarmLogos)) return emptyList()
    val seen = HashSet<String>()
    val home = focusRows.asSequence()
        .flatMap { row -> row.asSequence().take(perRowLimit) }
        .filter { seen.add("home:${it.type}:${it.id}") }
        .map { PrewarmFocusedHero(it, normalHomeMode = true) }
    val libraryHeroes = library.asSequence()
        .filter { seen.add("library:${it.type}:${it.id}") }
        .map { PrewarmFocusedHero(it, normalHomeMode = false) }
    return (home + libraryHeroes).take(maxItems).toList()
}

/** The hero's backdrop (its poster when it has none) and its logo, as HomeHeroSection draws them. */
internal fun heroArtwork(item: MetaPreview): List<PrewarmTarget> {
    // The size HeroBackdropImage asks for, or the warm lands on a URL the hero never requests.
    val backdrop = fullSizeTmdbBackdrop(item.banner.httpUrlOrNull())
    return listOfNotNull(
        (backdrop ?: item.poster.httpUrlOrNull())?.let {
            PrewarmTarget(it, if (backdrop != null) PrewarmArtworkKind.Backdrop else PrewarmArtworkKind.Poster)
        },
        item.logo.httpUrlOrNull()?.let { PrewarmTarget(it, PrewarmArtworkKind.Logo) },
    )
}

private fun String?.httpUrlOrNull(): String? =
    this?.trim()?.takeIf { it.isNotEmpty() && it.startsWith("http", ignoreCase = true) }

internal fun PrewarmArtworkKind.isEnabledIn(settings: ImageCacheSettings): Boolean = when (this) {
    PrewarmArtworkKind.Poster -> settings.prewarmPosters
    PrewarmArtworkKind.Logo -> settings.prewarmLogos
    PrewarmArtworkKind.Backdrop -> settings.prewarmBackdrops
}

/** Further than anyone scrolls a row in one sitting, and what one page of most catalogs holds. */
internal const val PrewarmPerRowLimit = 40

/** Library titles taken per pass, after everything on Home; most libraries are smaller. */
internal const val PrewarmLibraryLimit = 500

/** A ceiling so a Home with dozens of rows still finishes a pass in minutes rather than hours. */
internal const val PrewarmMaxTargets = 3_000
