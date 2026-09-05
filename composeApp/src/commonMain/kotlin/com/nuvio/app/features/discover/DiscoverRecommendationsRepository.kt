package com.nuvio.app.features.discover

import co.touchlab.kermit.Logger
import com.nuvio.app.features.home.HeroDiscoveryMetadataService
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HomeCatalogSettingsSnapshot
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.metadata.isAnimeNativeId
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationsClock
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tmdb.TmdbBackgroundLane
import com.nuvio.app.features.tmdb.TmdbSearchResult
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.tmdb.TmdbSettings
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.tmdb.customPosterTemplateNeedsImdbId
import com.nuvio.app.features.tmdb.customPosterTemplateNeedsTmdbId
import com.nuvio.app.features.tmdb.customPosterTemplateUsesNativeAnimeId
import com.nuvio.app.features.tmdb.resolveCustomPosterIds
import com.nuvio.app.features.tmdb.withCustomLibraryPoster
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.watchedItemKey
import com.nuvio.app.features.watchprogress.WatchProgressClock
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.discover_row_because_you_watched
import nuvio.composeapp.generated.resources.discover_row_custom_untitled
import nuvio.composeapp.generated.resources.discover_row_finish_what_you_started
import nuvio.composeapp.generated.resources.discover_row_hidden_gems
import nuvio.composeapp.generated.resources.discover_row_more_like_favourites
import nuvio.composeapp.generated.resources.discover_row_trending_in
import org.jetbrains.compose.resources.getString

/** One generated recommendation row. */
data class DiscoverRecommendationRow(
    val key: String,
    val title: String,
    val items: List<MetaPreview>,
    /**
     * The row-management entry this row belongs to — a [DiscoverRowFamily] id, or `custom:<id>`.
     *
     * Distinct from [key], which is per-row and must be unique for the lazy list. A family that
     * produces several rows stamps the same entry id on all of them, which is what lets one drag in
     * the settings list move the whole group.
     */
    val entryId: String,
    /**
     * A slot held open for a row still being built — plan §19.2.
     *
     * Carries no items and the family's real [entryId], so it sorts into the position its finished
     * row will occupy. Without these, every family that lands re-sorts the whole list and rows the
     * user is already looking at jump down the page: families are built in *cost* order and
     * rendered in *configured* order, and those two disagree.
     */
    val isPlaceholder: Boolean = false,
)

/** Key prefix for a placeholder row. The UI renders these as skeletons; see [DiscoverRecommendationRow.isPlaceholder]. */
const val DISCOVER_PLACEHOLDER_KEY_PREFIX = "discover-placeholder:"

data class DiscoverRecommendationsUiState(
    val rows: List<DiscoverRecommendationRow> = emptyList(),
    val isLoading: Boolean = false,
    val hasLoadedOnce: Boolean = false,
)

/**
 * Builds the Discover tab's generated rows.
 *
 * Four built-in generators, in their default order:
 *  1. **Finish what you started** — local watch progress only. No network, no TMDB key, no
 *     provider; it is the one row that works on a bare install.
 *  2. **Because you watched \<X>** — TMDB recommendations, one row per recent seed.
 *  3. **More like your favourites** — TMDB discover over the genres the history leans on.
 *  4. **Trending in \<genre>** — this week's trending list, intersected with those same genres.
 *
 * Plus any number of **custom rows** — saved TMDB `/discover` queries the user defined themselves
 * ([CustomDiscoverRow]). The rendered order of all of them is the user's, from the row-management
 * list; see [orderDiscoverRows].
 *
 * Everything here is lazy — nothing runs until the Discover tab is opened for the first time — and
 * results are cached for [CACHE_TTL_MS] so re-entering the tab is free. Changing any Discover
 * setting invalidates that cache, which is what makes the settings page feel live without the
 * screen having to know anything about it.
 */
object DiscoverRecommendationsRepository {
    private val log = Logger.withTag("DiscoverRecs")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow(DiscoverRecommendationsUiState())
    val uiState: StateFlow<DiscoverRecommendationsUiState> = _uiState.asStateFlow()

    private val refreshMutex = Mutex()
    private var activeJob: Job? = null
    private var lastBuiltAtEpochMs = 0L
    private var lastSettingsSignature: String? = null
    private var lastSeedSignature: String? = null
    private var hydrated = false
    private var activeProfileId: Int? = null

    private val cacheJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun refresh(force: Boolean = false) {
        scope.launch {
            refreshMutex.withLock {
                hydrateFromDisk()
                val now = WatchProgressClock.nowEpochMs()
                val settings = HomeCatalogSettingsRepository.snapshot()
                val fresh = now - lastBuiltAtEpochMs < CACHE_TTL_MS
                val settingsUnchanged = lastSettingsSignature == settingsSignature(settings)
                if (!force && fresh && settingsUnchanged && _uiState.value.rows.isNotEmpty()) {
                    // Row order is deliberately absent from the signature, because reordering does
                    // not change what any row contains. Re-sorting what is already in hand turns a
                    // drag in the settings list into zero TMDB requests.
                    applyRowOrder(settings)
                    return@withLock
                }
                activeJob?.cancel()
                // The whole build — seeds, the four families, the trending walk and the prune pass
                // that follows it — waits in TMDB's background lane. Nobody is watching a stopwatch
                // on a row that is not on screen yet, and sharing one queue with the hero meant a
                // cold open spent 15s of a 28s build waiting for permits behind enrichment traffic.
                activeJob = scope.launch(TmdbBackgroundLane) { build(now) }
            }
        }
    }

    /**
     * Drops everything built for the profile being switched away from.
     *
     * The rows derive from watch history, which is per-profile, so another profile's Discover tab
     * is not stale — it is somebody else's. (Discover was missing from the profile teardown
     * entirely before this, so a switch inside the cache window kept showing the previous profile's
     * rows until the TTL expired.)
     *
     * **Entering a profile at startup is not a switch, and this has to tell the difference.**
     * `ProfileRepository.selectProfile` is the same call in both cases, so a handler that tears
     * down unconditionally runs on every launch — which is exactly what it did in the first version
     * of this: it wiped the cache file and set [hydrated] before anything could read it, so the
     * persistent cache never once restored. The first call in a process therefore only records
     * which profile is active.
     *
     * The disk copy is deliberately **not** deleted here. [DiscoverRowCacheEntry.profileId] makes
     * reading it safe on its own, and keeping it means switching back to a profile whose rows are
     * still fresh costs nothing.
     */
    fun onProfileChanged(profileId: Int) {
        scope.launch {
            refreshMutex.withLock {
                val previous = activeProfileId
                activeProfileId = profileId
                if (previous == null || previous == profileId) return@withLock

                activeJob?.cancel()
                _uiState.value = DiscoverRecommendationsUiState()
                lastBuiltAtEpochMs = 0L
                lastSettingsSignature = null
                lastSeedSignature = null
                // Re-armed so the next visit re-reads the file under the new profile: it either
                // belongs to this profile and is usable, or it is rejected and rebuilt.
                hydrated = false
            }
        }
    }

    /**
     * Warms the in-memory cache from the last launch's build, once per process.
     *
     * Deliberately restores into the *existing* cache fields rather than answering "should we
     * rebuild?" itself: [refresh] already knows how to decide that from a freshness stamp and a
     * settings signature, and a second set of invalidation rules living here is how the two drift
     * apart. Hydration's only job is to make the disk copy indistinguishable from a build that
     * happened earlier in this process.
     *
     * A stale or foreign entry is dropped rather than shown: the settings signature is checked
     * because rows built under settings the user has since changed are wrong, not merely old, and
     * the TTL is checked because [refresh] would rebuild anyway — restoring rows it is about to
     * discard would only make them flash.
     */
    private suspend fun hydrateFromDisk() {
        if (hydrated) return
        hydrated = true

        // Read on IO: this is a blocking file read on the first visit to the tab, and it runs
        // inside the refresh lock, so leaving it on Dispatchers.Default parks a shared worker.
        val read = withContext(Dispatchers.IO) { runCatching { DiscoverRowCacheStorage.load() } }
        val raw = read.getOrElse { error ->
            // Distinguished from "no cache yet" on purpose: both used to return here in silence,
            // and a missing log line is then ambiguous between three different causes.
            log.d { "Could not read the Discover cache: ${error.message}" }
            return
        }
        if (raw == null) {
            log.d { "No Discover cache on disk; building" }
            return
        }
        val entry = runCatching { cacheJson.decodeFromString<DiscoverRowCacheEntry>(raw) }
            .getOrElse { error ->
                // A cache that cannot be read is a cache that should not stay on disk: leaving it
                // means paying the failed parse on every launch forever.
                log.d { "Discarding unreadable Discover cache: ${error.message}" }
                runCatching { DiscoverRowCacheStorage.save(null) }
                return
            }

        if (entry.version != DiscoverRowCacheEntry.VERSION) {
            log.d { "Discover cache is v${entry.version}, expected v${DiscoverRowCacheEntry.VERSION}; ignoring" }
            runCatching { DiscoverRowCacheStorage.save(null) }
            return
        }

        val age = WatchProgressClock.nowEpochMs() - entry.builtAtEpochMs
        // A negative age means the clock moved backwards since the write; treat it as stale rather
        // than as infinitely fresh.
        if (age !in 0 until CACHE_TTL_MS) {
            log.d { "Discover cache is ${age}ms old; rebuilding" }
            return
        }
        if (entry.settingsSignature != settingsSignature(HomeCatalogSettingsRepository.snapshot())) {
            log.d { "Discover cache was built under different settings; rebuilding" }
            return
        }
        val profileId = ProfileRepository.activeProfileId
        if (entry.profileId != profileId) {
            log.d { "Discover cache belongs to profile ${entry.profileId}, active is $profileId; rebuilding" }
            return
        }
        if (entry.rows.isEmpty()) return

        val rows = entry.rows.map { it.toRow() }
        _uiState.value = DiscoverRecommendationsUiState(rows = rows, isLoading = false, hasLoadedOnce = true)
        lastBuiltAtEpochMs = entry.builtAtEpochMs
        lastSettingsSignature = entry.settingsSignature
        lastSeedSignature = entry.seedSignature
        log.d { "Discover cache restored: ${rows.size} rows, ${age}ms old" }
    }

    /**
     * Writes the completed build to disk, off the build's own coroutine.
     *
     * Fire-and-forget on purpose: nothing waits on the cache, and a build that has already put its
     * rows on screen must not be held open by a file write. Only complete builds are written —
     * [publishInterim] does not persist, for the same reason it does not stamp the signatures.
     */
    private fun persistToDisk(rows: List<DiscoverRecommendationRow>, entry: DiscoverRowCacheEntry) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                // A build that produced nothing **clears** the cache rather than leaving the last
                // good one in place. Skipping the write instead would resurrect those rows on the
                // next launch and — signature and TTL both matching — skip the rebuild that would
                // have found them gone.
                DiscoverRowCacheStorage.save(if (rows.isEmpty()) null else cacheJson.encodeToString(entry))
            }.onFailure { error -> log.d { "Could not persist Discover rows: ${error.message}" } }
        }
    }

    private fun applyRowOrder(settings: HomeCatalogSettingsSnapshot) {
        val current = _uiState.value.rows
        val ordered = orderDiscoverRows(current, settings.discoverRowOrder) { it.entryId }
        if (ordered != current) _uiState.value = _uiState.value.copy(rows = ordered)
    }

    private suspend fun build(now: Long) {
        _uiState.value = _uiState.value.copy(isLoading = true)

        // Captured before the interim publish below replaces the rows with the local one. The
        // unchanged-seeds short-circuit further down asks "are the rows already in hand the ones
        // this build would produce?", and after an interim publish the rows in hand are a partial
        // answer — so the comparison has to run against what was there when the build started.
        val cachedRows = _uiState.value.rows
        val cachedSeedSignature = lastSeedSignature
        val cachedSettingsSignature = lastSettingsSignature

        val settings = HomeCatalogSettingsRepository.snapshot()
        val signature = settingsSignature(settings)
        val customDefinitions = settings.discoverCustomRows.filter { it.enabled }
        val wantsBecauseRows = settings.discoverBecauseYouWatchedRows > 0
        val wantsGenreRows = settings.discoverMoreLikeFavouritesEnabled ||
            settings.discoverHiddenGemsEnabled ||
            settings.discoverTrendingGenreRows > 0

        // Local first: no network, no TMDB key, so this is available immediately — and on an
        // install without a key it is the entire tab. It goes on screen below, before the
        // network-backed fan-out starts.
        val localRows = if (settings.discoverFinishWhatYouStartedEnabled) {
            listOfNotNull(buildFinishWhatYouStartedRow(settings, now))
        } else {
            emptyList()
        }

        // Imported lists cost nothing to produce — the items came off disk with the settings — so
        // they belong with the local row, ahead of anything network-backed.
        val importedRows = settings.discoverImportedRows.filter { it.enabled }.map { it.toRecommendationRow() }

        // AI rows are the same shape of free: the generation already happened, on an explicit
        // action, and its result came off disk with the settings. A row that has never been
        // generated has no items and is skipped rather than shown empty — it is unasked, not broken.
        //
        // The poster service is applied **here rather than baked in at generation time**. §8 makes
        // it an invariant that every row the repository builds runs the user's template itself —
        // an AI row's art comes from TMDB and has no addon behind it — and doing it at render means
        // changing the template takes effect immediately, instead of only after paying a provider
        // for a fresh generation of every row.
        val aiRowDefinitions = settings.discoverAiRows.filter { it.enabled && it.items.isNotEmpty() }
        val aiRows = if (aiRowDefinitions.isEmpty()) {
            emptyList()
        } else {
            val posterSettings = TmdbSettingsRepository.snapshot()
            val posterMdbListKey = MdbListSettingsRepository.snapshot().apiKey
            aiRowDefinitions.map { row ->
                val built = row.toRecommendationRow()
                val serviced = coroutineScope {
                    built.items
                        .map { item -> async { item.withPosterService(posterSettings, posterMdbListKey) } }
                        .awaitAll()
                }
                built.copy(items = serviced)
            }
        }

        val freeRows = localRows + importedRows + aiRows

        if (!wantsBecauseRows && !wantsGenreRows && customDefinitions.isEmpty()) {
            val onlyFreeRows = orderRows(freeRows, settings)
            publish(onlyFreeRows, now, signature, seedSignature = null)
            // Every built-in family switched off is a perfectly ordinary configuration, and an AI
            // row is exactly the kind that still wants §5.1's filters. Returning here without the
            // prune is how they would silently apply everywhere except the tab someone built by
            // hand.
            pruneGeneratedRows(
                rows = onlyFreeRows,
                generatedKeys = aiRows.mapTo(mutableSetOf()) { it.key },
                watchedParentKeys = buildWatchedParentKeys(WatchedRepository.uiState.value.items),
                hideWatched = settings.discoverHideWatched,
                hideUnreleased = settings.hideUnreleasedContent,
            )
            return
        }

        // One placeholder per row this build intends to produce, in the position it will occupy.
        // Counts come from settings rather than from the build, so they are known before any
        // request goes out — the whole point is that the shape of the tab is stable from the first
        // frame. A family that ends up producing fewer rows than configured drops its spares when
        // it emits, so an over-count costs a skeleton that disappears rather than a gap.
        var placeholders = buildList {
            customDefinitions.forEach { row ->
                add(placeholderRow(customDiscoverEntryId(row.id)))
            }
            if (wantsBecauseRows) {
                repeat(settings.discoverBecauseYouWatchedRows) {
                    add(placeholderRow(DiscoverRowFamily.Because.entryId))
                }
            }
            if (settings.discoverMoreLikeFavouritesEnabled) {
                add(placeholderRow(DiscoverRowFamily.Favourites.entryId))
            }
            if (settings.discoverHiddenGemsEnabled) {
                add(placeholderRow(DiscoverRowFamily.Gems.entryId))
            }
            repeat(settings.discoverTrendingGenreRows) {
                add(placeholderRow(DiscoverRowFamily.Trending.entryId))
            }
        }
        if (placeholders.isNotEmpty()) {
            publishInterim(orderRows(freeRows + placeholders, settings))
        }

        // Everything past this point is network-backed and measured in tens of seconds on a cold
        // build: seed resolution alone was 20s of sequential TMDB round trips in the log that
        // prompted this. The local row costs nothing and is already in hand, so put it on screen
        // now rather than holding it behind the fan-out — an empty Discover tab for half a minute
        // reads as broken, and this row is the whole tab on an install with no TMDB key.
        if (freeRows.isNotEmpty()) publishInterim(orderRows(freeRows, settings))

        // One seed set feeds both the per-title rows and the genre histogram. The histogram wants
        // more titles than the row budget allows, so ask for the larger of the two and slice.
        val history = WatchedRepository.uiState.value.items
        val seedBudget = maxOf(
            if (wantsBecauseRows) settings.discoverBecauseYouWatchedRows else 0,
            if (wantsGenreRows) DiscoverSeedService.MAX_PROFILE_SEEDS else 0,
        )
        // Timed because this step is the feature's floor and has no visible symptom of its own —
        // the tab just sits there. The number is what says whether the concurrent resolution in
        // [DiscoverSeedService.buildSeeds] is doing its job on a cold cache.
        val seedsStartedAt = WatchProgressClock.nowEpochMs()
        val seeds = if (seedBudget > 0) {
            DiscoverSeedService.buildSeeds(history, now = now, maxSeeds = seedBudget)
        } else {
            emptyList()
        }
        log.d { "Seeds resolved: ${seeds.size} in ${WatchProgressClock.nowEpochMs() - seedsStartedAt}ms" }
        // No seeds is fatal to every generated row but not to a custom one: a custom row is a query
        // the user wrote, so it needs no history behind it and must still render on a fresh install.
        if (seeds.isEmpty() && customDefinitions.isEmpty()) {
            log.d { "No usable seeds from ${history.size} watched rows" }
            publish(orderRows(freeRows, settings), now, signature, seedSignature = "")
            return
        }

        // Skip the whole fan-out when nothing that shapes the rows has changed: same seeds, same
        // settings, rows already in hand. Only the freshness stamp needs updating.
        val seedSignature = seeds.joinToString("|") { "${it.tmdbMediaType}:${it.tmdbId}" }
        if (seedSignature == cachedSeedSignature &&
            signature == cachedSettingsSignature &&
            cachedRows.isNotEmpty()
        ) {
            // Republished rather than left alone: the interim publish may have narrowed the tab to
            // the local row on the way here, so the cached set has to be put back explicitly.
            publish(cachedRows, now, signature, seedSignature)
            log.d { "Seeds and settings unchanged; kept ${cachedRows.size} cached rows" }
            return
        }

        // Keyed on the title, not the episode — see [buildWatchedParentKeys] for why the
        // per-episode keys cannot answer "have I watched this show".
        val watchedKeys = buildWatchedParentKeys(history)
        val hideWatched = settings.discoverHideWatched
        val filters = RowFilters(
            watchedParentKeys = watchedKeys,
            hideWatched = hideWatched,
            excludedGenreIds = DiscoverSeedService.resolveExcludedGenreIds(settings.discoverExcludedGenres),
        )

        // Cross-row dedupe, so an item claimed by one row does not reappear three rows down. The
        // local row is exempt: it is the user's own unfinished viewing, and dropping a title from it
        // because a recommendation row happened to also suggest it would be the wrong way round.
        //
        // **Claiming order is build order, not render order.** Custom rows claim first because they
        // are the only rows the user asked for by name — losing a title from an explicit query to a
        // generated suggestion is the wrong way round for the same reason the local row is exempt.
        // Among the generated rows the order is unchanged, and re-ranking them in the settings list
        // deliberately does not re-run the fan-out (see [refresh]).
        val claimed = mutableSetOf<String>()
        val generatedRows = mutableListOf<DiscoverRecommendationRow>()

        // The four order-independent families are built **concurrently**. They used to run one
        // after another, and with seed resolution down to ~0.3s that serial chain is now the whole
        // of the cold-build cost — 14.2s of a 14.5s build in the log that prompted this.
        //
        // Two things are deliberately *not* parallelised with them:
        //  - **The trending walk**, which stays last. Its top-up decision reads the claim set the
        //    other families fill, and its internal dedupe is sequential on purpose (below).
        //  - **Claiming order.** The families are awaited in priority order no matter which
        //    finishes first, so custom still claims before "because", exactly as §8 of the handoff
        //    requires. Concurrency changes when the requests go out, never who wins a contested
        //    title.
        //
        // Each family is published as its turn comes up, so rows only ever *append* — a title can
        // never vanish from a row already on screen because a higher-priority family claimed it
        // afterwards.
        coroutineScope {
            val customDeferred = async {
                if (customDefinitions.isNotEmpty()) buildCustomRows(customDefinitions, filters) else emptyList()
            }
            val becauseDeferred = async {
                if (wantsBecauseRows && seeds.isNotEmpty()) {
                    seeds.take(settings.discoverBecauseYouWatchedRows)
                        .map { seed -> async { buildBecauseRow(seed, filters) } }
                        .awaitAll()
                        .filterNotNull()
                } else {
                    emptyList()
                }
            }
            // Shared by favourites, gems and trending; resolved once, awaited three times.
            val affinitiesDeferred = async {
                if (wantsGenreRows && seeds.isNotEmpty()) {
                    DiscoverSeedService.resolveGenreAffinities(
                        seeds = seeds,
                        now = now,
                        excludedGenres = settings.discoverExcludedGenres,
                    )
                } else {
                    emptyList()
                }
            }
            val favouritesDeferred = async {
                if (!settings.discoverMoreLikeFavouritesEnabled) return@async null
                affinitiesDeferred.await().takeIf { it.isNotEmpty() }
                    ?.let { buildMoreLikeFavouritesRow(it, filters) }
            }
            val gemsDeferred = async {
                if (!settings.discoverHiddenGemsEnabled) return@async null
                affinitiesDeferred.await().takeIf { it.isNotEmpty() }
                    ?.let { buildHiddenGemsRow(it, filters, now) }
            }

            fun emit(
                family: String,
                releases: (String) -> Boolean,
                rows: List<DiscoverRecommendationRow>,
            ) {
                val kept = dedupeAcrossRows(rows, claimed)
                log.d { "$family: ${kept.size} rows at +${WatchProgressClock.nowEpochMs() - now}ms" }
                generatedRows += kept
                // Dropped even when the family produced nothing: a row that turned out to be empty
                // has to take its slot with it, or the skeleton sits there for the rest of the
                // build promising something that is never coming.
                placeholders = placeholders.filterNot { releases(it.entryId) }
                publishInterim(orderRows(freeRows + generatedRows + placeholders, settings))
            }

            // Every custom row carries its own entry id, so this releases the whole namespace.
            emit("Custom rows", { customDiscoverRowId(it) != null }, customDeferred.await())
            emit("Because you watched", { it == DiscoverRowFamily.Because.entryId }, becauseDeferred.await())
            emit("More like your favourites", { it == DiscoverRowFamily.Favourites.entryId }, listOfNotNull(favouritesDeferred.await()))
            emit("Hidden gems", { it == DiscoverRowFamily.Gems.entryId }, listOfNotNull(gemsDeferred.await()))

            val affinities = affinitiesDeferred.await()
            if (affinities.isNotEmpty() && settings.discoverTrendingGenreRows > 0) {
                // Dedupe is applied *inside* the trending walk rather than after it. The trending
                // list is one shared pool of ~40 titles, so each genre row eats into what the next
                // one has left; deduping afterwards starved the later rows below the minimum and
                // silently dropped them, which is why asking for four genres produced three.
                val trending = buildTrendingGenreRows(
                    affinities = affinities,
                    rowCount = settings.discoverTrendingGenreRows,
                    filters = filters,
                    claimed = claimed,
                )
                log.d { "Trending rows: ${trending.size} rows at +${WatchProgressClock.nowEpochMs() - now}ms" }
                generatedRows += trending
            }
        }

        val rows = orderRows(freeRows + generatedRows, settings)
        publish(rows, now, signature, seedSignature)
        log.d { "Build complete: ${rows.size} rows in ${WatchProgressClock.nowEpochMs() - now}ms" }

        // Per-title filtering runs after the rows are on screen and prunes them as answers arrive,
        // rather than holding everything back behind a fan-out of N requests.
        pruneGeneratedRows(
            rows = rows,
            // AI rows join the generated set here even though they were published with the free
            // ones: §5.1's filters were written for exactly the mistakes a model makes, and the
            // prune pass is the only place either filter can be afforded. See its doc for the two
            // row kinds that stay out.
            generatedKeys = (generatedRows + aiRows).mapTo(mutableSetOf()) { it.key },
            watchedParentKeys = watchedKeys,
            hideWatched = hideWatched,
            hideUnreleased = settings.hideUnreleasedContent,
        )
    }

    /**
     * Puts rows on screen mid-build, while the rest of the fan-out is still running.
     *
     * Deliberately not a [publish]: `isLoading` stays true, `hasLoadedOnce` is left alone, and the
     * freshness stamps are **invalidated** rather than set. A partial row set that carried a valid
     * stamp would let the next [refresh] inside the cache window short-circuit onto it and keep the
     * tab permanently thin — the failure mode is silent, so the stamps are cleared instead.
     */
    private fun publishInterim(rows: List<DiscoverRecommendationRow>) {
        _uiState.value = _uiState.value.copy(rows = rows, isLoading = true)
        lastBuiltAtEpochMs = 0L
        lastSettingsSignature = null
        lastSeedSignature = null
    }

    private fun publish(
        rows: List<DiscoverRecommendationRow>,
        now: Long,
        settingsSignature: String,
        seedSignature: String?,
    ) {
        _uiState.value = DiscoverRecommendationsUiState(
            rows = rows,
            isLoading = false,
            hasLoadedOnce = true,
        )
        lastBuiltAtEpochMs = now
        lastSettingsSignature = settingsSignature
        lastSeedSignature = seedSignature
        persistToDisk(
            rows = rows,
            entry = DiscoverRowCacheEntry(
                builtAtEpochMs = now,
                settingsSignature = settingsSignature,
                seedSignature = seedSignature,
                profileId = ProfileRepository.activeProfileId,
                rows = rows.map { it.toCachedRow() },
            ),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Generators
    // ---------------------------------------------------------------------------------------

    /**
     * Titles the user stopped partway through and never returned to. Selection rules — and why
     * this is not just Continue Watching again — live in [selectFinishWhatYouStarted].
     *
     * Deliberately exempt from the hide-watched filter: a part-watched title *is* partly watched,
     * so filtering on that would empty the row by definition.
     */
    private suspend fun buildFinishWhatYouStartedRow(
        settings: HomeCatalogSettingsSnapshot,
        now: Long,
    ): DiscoverRecommendationRow? {
        // Two different sources on purpose. Candidates come from **local** playback progress,
        // because that is what records "you stopped thirty minutes into episode four" and it exists
        // whichever service drives Continue Watching. The exclusion set comes from `uiState`,
        // because that is what Continue Watching is actually showing right now.
        //
        // Reading `uiState` for both is what made this row impossible: with a remote source
        // selected it contains that provider's rows *instead of* the local ones, so every candidate
        // was also, by definition, a Continue Watching row — and the row could never be non-empty.
        val localEntries = WatchProgressRepository.localPlaybackEntries()
        val continueWatchingKeys = WatchProgressRepository.uiState.value.continueWatchingEntries
            .mapTo(mutableSetOf()) { finishGroupKey(it.parentMetaType, it.parentMetaId) }
        val picked = selectFinishWhatYouStarted(
            entries = localEntries,
            continueWatchingParentKeys = continueWatchingKeys,
            now = now,
            minIdleMs = settings.discoverFinishIdleDays * MILLIS_PER_DAY,
        )
        if (picked.isEmpty()) {
            log.d {
                "Finish what you started: nothing idle past ${settings.discoverFinishIdleDays}d " +
                    "(${localEntries.size} local entries, ${continueWatchingKeys.size} shown in Continue Watching)"
            }
            return null
        }
        log.d { "Finish what you started: ${picked.size} titles" }
        val tmdbSettings = TmdbSettingsRepository.snapshot()
        val mdbListApiKey = MdbListSettingsRepository.snapshot().apiKey
        val items = coroutineScope {
            picked.map { entry ->
                async { entry.toMetaPreview().withProgressPosterService(tmdbSettings, mdbListApiKey) }
            }.awaitAll()
        }
        return DiscoverRecommendationRow(
            key = FINISH_ROW_KEY,
            title = getString(Res.string.discover_row_finish_what_you_started),
            items = items,
            entryId = DiscoverRowFamily.Finish.entryId,
        )
    }

    /**
     * Runs a locally-sourced item through the user's poster service.
     *
     * Separate from [withPosterService] because the two start from different places. A TMDB-generated
     * item knows its TMDB id and nothing else; this one knows whatever id its addon used, which is
     * usually IMDb and sometimes neither. [resolveCustomPosterIds] fills in only what the template
     * actually names, so a template wanting just `{id}` costs no lookups at all.
     *
     * Anime addressed by a native id is left alone unless the template asks for those ids: kitsu/MAL
     * ids belong to the franchise, so a poster service hands back season one's art for every season.
     */
    private suspend fun MetaPreview.withProgressPosterService(
        settings: TmdbSettings,
        mdbListApiKey: String?,
    ): MetaPreview {
        if (!settings.libraryPosterEnabled) return this
        if (id.isAnimeNativeId() && !settings.customPosterTemplateUsesNativeAnimeId()) return this
        val resolved = runCatching {
            resolveCustomPosterIds(
                settings = settings,
                imdbId = id.takeIf { it.startsWith("tt") },
                tmdbId = id.removePrefix("tmdb:").toIntOrNull(),
                type = tmdbMediaTypeFor(type),
            )
        }.getOrNull() ?: return this
        // A template naming {tmdb_id} that gets a blank one is not merely a poorer request — it is a
        // rejected one. PostersPlus answers 400 for `tmdb_id=&imdb_id=tt…` while serving both-blank
        // happily, so an unresolved id here would replace working addon art with a broken image.
        // Keeping what we have is the better failure.
        if (resolved.tmdbId == null && settings.customPosterTemplateNeedsTmdbId()) {
            log.d { "Poster service skipped for $id: template needs a TMDB id and none resolved" }
            return this
        }
        return withCustomLibraryPoster(
            settings = settings,
            imdbId = resolved.imdbId,
            tmdbId = resolved.tmdbId,
            mdbListApiKey = mdbListApiKey,
        )
    }

    private suspend fun buildBecauseRow(
        seed: DiscoverSeed,
        filters: RowFilters,
    ): DiscoverRecommendationRow? {
        val results = fetchPaged { page -> TmdbService.fetchRecommendations(seed.tmdbId, seed.tmdbMediaType, page) }
        if (results.isEmpty()) return null
        val items = results.toRowItems(filters)
        if (items.isEmpty()) return null
        return DiscoverRecommendationRow(
            key = "discover:because:${seed.tmdbMediaType}:${seed.tmdbId}",
            title = getString(Res.string.discover_row_because_you_watched, seed.title),
            items = items,
            entryId = DiscoverRowFamily.Because.entryId,
        )
    }

    /**
     * TMDB discover over the two genres the history leans on hardest, per media type.
     *
     * The two ids are ANDed, which is the point — "like your favourites" means the intersection,
     * not another popularity list. That can come back thin for an unusual pairing, so a side that
     * returns too little is retried on its top genre alone rather than left empty.
     *
     * Movie and tv are queried separately because TMDB's genre ids mean different things in each
     * namespace ([GenreAffinity]), then interleaved so a mixed history gets a mixed row.
     */
    private suspend fun buildMoreLikeFavouritesRow(
        affinities: List<GenreAffinity>,
        filters: RowFilters,
    ): DiscoverRecommendationRow? {
        if (affinities.isEmpty()) return null
        val movieGenres = affinities.mapNotNull { it.idFor("movie") }.take(FAVOURITE_GENRE_DEPTH)
        val tvGenres = affinities.mapNotNull { it.idFor("tv") }.take(FAVOURITE_GENRE_DEPTH)
        if (movieGenres.isEmpty() && tvGenres.isEmpty()) return null

        val (movies, shows) = coroutineScope {
            val movieCall = async { discoverForGenres("movie", movieGenres, MIN_VOTES_MOVIE) }
            val tvCall = async { discoverForGenres("tv", tvGenres, MIN_VOTES_TV) }
            movieCall.await() to tvCall.await()
        }
        val interleaved = interleave(movies, shows)
        val items = interleaved.toRowItems(filters)
        if (items.size < MIN_ROW_ITEMS) {
            log.d { "More like your favourites: only ${items.size} items, skipping" }
            return null
        }
        return DiscoverRecommendationRow(
            key = FAVOURITES_ROW_KEY,
            title = getString(Res.string.discover_row_more_like_favourites),
            items = items,
            entryId = DiscoverRowFamily.Favourites.entryId,
        )
    }

    /**
     * Well rated, not widely seen — the deterministic version of the AI prompt of the same name.
     *
     * The parameters are the whole feature and every one of them was chosen by running the query,
     * not by reasoning about it:
     *
     * - **Sorted by popularity, not rating.** `vote_average.desc` maximises rating across a pool of
     *   a thousand titles, so it returns the extreme tail: a comedy rated 9.9 by 143 people, and a
     *   great deal of regional content that is obscure to this user for reasons unrelated to
     *   quality. Popularity-within-a-band surfaces things a person might plausibly have heard of.
     * - **A vote-count band, not a floor.** The ceiling is what makes a gem hidden. Without it —
     *   and even at a ceiling of 1,500 — the row fills with Ted Lasso, Frasier and Bob's Burgers,
     *   which are many things but not undiscovered. TV sits lower than film because TV records
     *   collect fewer votes overall.
     * - **A release-date window, not just a ceiling.** Nothing has fewer votes than a film that does
     *   not exist yet, so "few votes" selects hard for unreleased titles — without the ceiling the
     *   row was mostly 2026 releases. The floor is the other half: a 1954 film with few TMDB votes
     *   is canonical rather than undiscovered, and without it the row filled with mid-century
     *   titles nobody was looking to be recommended.
     * - **Format genres are excluded.** Talk shows, news, reality, soaps and daily kids' programming
     *   have low vote counts *structurally* — TMDB voters do not rate them the way they rate drama —
     *   so a vote-count band selects for them regardless of how well known they are. This is what
     *   put Sesame Street, Conan and The Graham Norton Show in a row about hidden gems. They are
     *   excluded as formats, not as tastes; the genre being queried is never excluded, so a profile
     *   whose top genre *is* Documentary still gets a row.
     */
    private suspend fun buildHiddenGemsRow(
        affinities: List<GenreAffinity>,
        filters: RowFilters,
        now: Long,
    ): DiscoverRecommendationRow? {
        if (affinities.isEmpty()) return null
        val settledBefore = EpisodeReleaseNotificationsClock.isoDateFromEpochMs(now - GEM_MIN_AGE_MS)
        val settledAfter = EpisodeReleaseNotificationsClock.isoDateFromEpochMs(now - GEM_MAX_AGE_MS)
        val movieGenre = affinities.firstNotNullOfOrNull { it.idFor("movie") }
        val tvGenre = affinities.firstNotNullOfOrNull { it.idFor("tv") }
        if (movieGenre == null && tvGenre == null) return null

        val (movies, shows) = coroutineScope {
            val movieCall = async {
                if (movieGenre == null) {
                    emptyList()
                } else {
                    fetchPaged(target = INTERLEAVED_SIDE_TARGET) { page ->
                        TmdbService.fetchDiscover(
                            mediaType = "movie",
                            genreIds = listOf(movieGenre),
                            withoutGenreIds = MOVIE_FORMAT_GENRE_IDS - movieGenre,
                            minVoteCount = GEM_VOTES_MOVIE.first,
                            maxVoteCount = GEM_VOTES_MOVIE.last,
                            minVoteAverage = GEM_MIN_RATING,
                            releasedBefore = settledBefore,
                            releasedAfter = settledAfter,
                            page = page,
                        )
                    }
                }
            }
            val tvCall = async {
                if (tvGenre == null) {
                    emptyList()
                } else {
                    fetchPaged(target = INTERLEAVED_SIDE_TARGET) { page ->
                        TmdbService.fetchDiscover(
                            mediaType = "tv",
                            genreIds = listOf(tvGenre),
                            withoutGenreIds = TV_FORMAT_GENRE_IDS - tvGenre,
                            minVoteCount = GEM_VOTES_TV.first,
                            maxVoteCount = GEM_VOTES_TV.last,
                            minVoteAverage = GEM_MIN_RATING,
                            releasedBefore = settledBefore,
                            releasedAfter = settledAfter,
                            page = page,
                        )
                    }
                }
            }
            movieCall.await() to tvCall.await()
        }

        val items = interleave(movies, shows).toRowItems(filters)
        if (items.size < MIN_ROW_ITEMS) {
            log.d { "Hidden gems: only ${items.size} items, skipping" }
            return null
        }
        return DiscoverRecommendationRow(
            key = HIDDEN_GEMS_ROW_KEY,
            title = getString(Res.string.discover_row_hidden_gems),
            items = items,
            entryId = DiscoverRowFamily.Gems.entryId,
        )
    }

    /**
     * The user's own rows. Built in parallel — each is one or two TMDB requests — and every failure
     * is local to its row, so one badly-specified query cannot take the others down with it.
     */
    private suspend fun buildCustomRows(
        definitions: List<CustomDiscoverRow>,
        filters: RowFilters,
    ): List<DiscoverRecommendationRow> = coroutineScope {
        definitions.map { definition -> async { buildCustomRow(definition, filters) } }
            .awaitAll()
            .filterNotNull()
    }

    /**
     * One saved query, resolved.
     *
     * Genre names are resolved to ids **per namespace** rather than once: TMDB's movie and tv genre
     * vocabularies are different, so the same name is a different id, or no id at all, on each side
     * (see [CustomDiscoverRow]).
     */
    private suspend fun buildCustomRow(
        definition: CustomDiscoverRow,
        filters: RowFilters,
    ): DiscoverRecommendationRow? {
        val genreIdsByType = DiscoverSeedService.resolveGenreIdsByType(definition.genres)
        val mediaTypes = definition.mediaType.tmdbMediaTypes
        val perType = coroutineScope {
            mediaTypes.map { mediaType ->
                async {
                    val genreIds = genreIdsByType[mediaType].orEmpty().toList()
                    // A row whose genres exist only in the other namespace must contribute nothing
                    // here rather than everything: dropping the filter would turn "Anime films"
                    // into an unfiltered popularity list, which looks like the row is broken in a
                    // way the user cannot diagnose.
                    // Same rule for both: a filter this namespace cannot answer means the namespace
                    // contributes nothing, never that the filter is dropped. Widening a query the
                    // user narrowed is the one wrong answer here.
                    if (definition.genres.isNotEmpty() && genreIds.isEmpty()) {
                        emptyList()
                    } else if (!definition.status.appliesTo(mediaType)) {
                        emptyList()
                    } else if (mediaType == "tv" && definition.certification.isNotBlank()) {
                        emptyList()
                    } else if (
                        mediaType == "tv" && (definition.cast.isNotEmpty() || definition.crew.isNotEmpty())
                    ) {
                        // TMDB's tv endpoint ignores with_cast/with_crew rather than rejecting them,
                        // so sending them would return an unfiltered list of shows next to a
                        // correctly filtered list of films — the worst of both.
                        emptyList()
                    } else {
                        fetchPaged(
                            // A single-namespace row takes the whole budget; a Both row splits it.
                            target = if (mediaTypes.size == 1) MAX_ITEMS_PER_ROW else INTERLEAVED_SIDE_TARGET,
                        ) { page ->
                            TmdbService.fetchDiscover(
                                mediaType = mediaType,
                                genreIds = genreIds,
                                genreMatchAll = definition.matchAllGenres,
                                sortBy = definition.sort.tmdbSortBy(mediaType),
                                minVoteCount = definition.effectiveMinVotes(),
                                minVoteAverage = definition.minRating.takeIf { it > 0 }?.toDouble(),
                                releasedAfter = definition.fromYear.takeIf { it > 0 }?.let { "$it-01-01" },
                                releasedBefore = definition.toYear.takeIf { it > 0 }?.let { "$it-12-31" },
                                originalLanguage = definition.language.takeIf { it.isNotBlank() },
                                movieReleaseTypes = definition.status.movieReleaseTypes,
                                tvStatuses = definition.status.tvStatuses,
                                certification = definition.certification.takeIf { it.isNotBlank() },
                                certificationCountry = CUSTOM_DISCOVER_CERTIFICATION_COUNTRY
                                    .takeIf { definition.certification.isNotBlank() },
                                minRuntime = definition.minRuntime.takeIf { it > 0 },
                                maxRuntime = definition.maxRuntime.takeIf { it > 0 },
                                companyIds = definition.companies.orJoinedIds(),
                                castIds = definition.cast.orJoinedIds(),
                                crewIds = definition.crew.orJoinedIds(),
                                page = page,
                            )
                        }
                    }
                }
            }.awaitAll()
        }

        val items = interleave(perType.getOrElse(0) { emptyList() }, perType.getOrElse(1) { emptyList() })
            .toRowItems(filters)
        // A lower floor than the generated rows get. A thin generated row is the app guessing badly
        // and is better deleted; a thin custom row is the honest answer to a narrow question the
        // user asked deliberately, and deleting it looks like the query silently failed.
        if (items.size < MIN_CUSTOM_ROW_ITEMS) {
            log.d { "Custom row ${definition.id}: only ${items.size} items, skipping" }
            return null
        }
        return DiscoverRecommendationRow(
            key = "$CUSTOM_ROW_KEY_PREFIX${definition.id}",
            title = definition.displayTitle(),
            items = items,
            entryId = definition.entryId,
        )
    }

    /** Ids as TMDB's OR form, or null when there is nothing to send. See [CustomDiscoverRow]. */
    /**
     * An untitled row still needs a heading, and its genres describe it better than a placeholder
     * would. Falls back to the localised generic only when there is nothing to describe.
     */
    private suspend fun CustomDiscoverRow.displayTitle(): String = title.ifBlank {
        genres.sorted().joinToString(" • ").ifBlank { getString(Res.string.discover_row_custom_untitled) }
    }

    private suspend fun discoverForGenres(
        mediaType: String,
        genreIds: List<Int>,
        minVoteCount: Int,
    ): List<TmdbSearchResult> {
        if (genreIds.isEmpty()) return emptyList()
        val narrow = fetchPaged(target = INTERLEAVED_SIDE_TARGET) { page ->
            TmdbService.fetchDiscover(
                mediaType = mediaType,
                genreIds = genreIds,
                minVoteCount = minVoteCount,
                page = page,
            )
        }
        if (narrow.size >= MIN_ROW_ITEMS || genreIds.size == 1) return narrow
        return fetchPaged(target = INTERLEAVED_SIDE_TARGET) { page ->
            TmdbService.fetchDiscover(
                mediaType = mediaType,
                genreIds = genreIds.take(1),
                minVoteCount = minVoteCount,
                page = page,
            )
        }
    }

    /**
     * Collects TMDB pages until the row is full enough or the list runs out.
     *
     * TMDB pages at 20 results and rows hold [MAX_ITEMS_PER_ROW], so one page can no longer fill
     * one row. Pages are fetched **in sequence, not in parallel**, and the loop stops as soon as it
     * has a full row's worth: most queries are satisfied by two, and firing all
     * [MAX_PAGES_PER_ROW] at once would triple this feature's request count to discard most of the
     * answers. A short page means the result set is exhausted — there is no page after it.
     *
     * The cap is a per-row budget, not a target. Every generated row pays it on a cold build.
     */
    private suspend fun fetchPaged(
        target: Int = MAX_ITEMS_PER_ROW,
        maxPages: Int = MAX_PAGES_PER_ROW,
        fetchPage: suspend (Int) -> List<TmdbSearchResult>,
    ): List<TmdbSearchResult> {
        val collected = mutableListOf<TmdbSearchResult>()
        for (page in 1..maxPages) {
            val results = fetchPage(page)
            collected += results
            if (results.size < TMDB_PAGE_SIZE || collected.size >= target) break
        }
        return collected.distinctBy { "${it.isTv}:${it.id}" }
    }

    /**
     * This week's trending titles, split by the genres the user actually watches.
     *
     * Built from the trending list [TmdbService.fetchTrending] already caches for the hero badge,
     * so the common case costs nothing. A genre that the trending list barely covers is topped up
     * from discover, and one that still comes back thin is skipped in favour of the next genre
     * down — a four-item row is worse than no row.
     */
    private suspend fun buildTrendingGenreRows(
        affinities: List<GenreAffinity>,
        rowCount: Int,
        filters: RowFilters,
        claimed: MutableSet<String>,
    ): List<DiscoverRecommendationRow> {
        if (rowCount <= 0 || affinities.isEmpty()) return emptyList()
        // One call, not one per type: each asks the service to warm both lists, so two of them in
        // parallel would fetch all four pages twice on a cold cache.
        val trending = TmdbService.fetchTrending("all")

        val rows = mutableListOf<DiscoverRecommendationRow>()
        for (affinity in affinities) {
            if (rows.size >= rowCount) break
            val matches = trending.filter { result ->
                val genreId = affinity.idFor(if (result.isTv) "tv" else "movie") ?: return@filter false
                genreId in result.genreIds
            }
            // Counted against what earlier rows already took, so a genre whose trending share has
            // been eaten is topped up now rather than discovered to be short after the fact.
            val unclaimed = matches.count { "${if (it.isTv) "series" else "movie"}:tmdb:${it.id}" !in claimed }
            val topUp = if (unclaimed < MIN_ROW_ITEMS) fillGenreFromDiscover(affinity) else emptyList()
            val items = (matches + topUp).toRowItems(filters)
                .filter { "${it.type}:${it.id}" !in claimed }
                .take(MAX_ITEMS_PER_ROW)
            if (items.size < MIN_ROW_ITEMS) {
                log.d { "Trending in ${affinity.name}: only ${items.size} items after filtering, skipping" }
                continue
            }
            items.forEach { claimed += "${it.type}:${it.id}" }
            rows += DiscoverRecommendationRow(
                key = "$TRENDING_ROW_KEY_PREFIX${affinity.name.lowercase().replace(' ', '-')}",
                title = getString(Res.string.discover_row_trending_in, affinity.name),
                items = items,
                entryId = DiscoverRowFamily.Trending.entryId,
            )
        }
        if (rows.size < rowCount) {
            log.d { "Trending rows: asked for $rowCount, ${affinities.size} genres yielded ${rows.size}" }
        }
        return rows
    }

    private suspend fun fillGenreFromDiscover(affinity: GenreAffinity): List<TmdbSearchResult> =
        coroutineScope {
            affinity.idsByMediaType.map { (mediaType, genreId) ->
                async {
                    fetchPaged(target = INTERLEAVED_SIDE_TARGET) { page ->
                        TmdbService.fetchDiscover(
                            mediaType = mediaType,
                            genreIds = listOf(genreId),
                            minVoteCount = if (mediaType == "tv") MIN_VOTES_TV else MIN_VOTES_MOVIE,
                            page = page,
                        )
                    }
                }
            }.awaitAll().flatten()
        }

    // ---------------------------------------------------------------------------------------
    // Shared row plumbing
    // ---------------------------------------------------------------------------------------

    /** The filters every generated row applies, resolved once per build. */
    private data class RowFilters(
        val watchedParentKeys: Set<String>,
        val hideWatched: Boolean,
        /** Media type → TMDB genre ids the user has excluded. */
        val excludedGenreIds: Map<String, Set<Int>>,
    )

    /**
     * TMDB results → row items: de-duplicated, filtered, capped, and run through the user's poster
     * service.
     *
     * Excluded genres are applied here rather than in the prune pass because list responses already
     * carry `genre_ids` — the answer is free, so a title the user never wants to see never reaches
     * the screen at all.
     *
     * The poster step matters because these rows have no addon behind them — nothing else would
     * apply the template on their behalf — and it runs after the cap so an IMDb lookup is never
     * paid for a title that got filtered out.
     */
    private suspend fun List<TmdbSearchResult>.toRowItems(filters: RowFilters): List<MetaPreview> {
        val settings = TmdbSettingsRepository.snapshot()
        val mdbListApiKey = MdbListSettingsRepository.snapshot().apiKey
        val shortlist = distinctBy { "${it.isTv}:${it.id}" }
            .filterNot { result ->
                val excluded = filters.excludedGenreIds[if (result.isTv) "tv" else "movie"].orEmpty()
                excluded.isNotEmpty() && result.genreIds.any { it in excluded }
            }
            .map { it.toMetaPreview() }
            .filter { item ->
                // Only catches TMDB-keyed history; the `tt…` majority is resolved after publish.
                !filters.hideWatched ||
                    watchedParentKey(item.type, item.id) !in filters.watchedParentKeys
            }
            .take(MAX_ITEMS_PER_ROW)
        if (shortlist.isEmpty()) return emptyList()
        return coroutineScope {
            shortlist.map { item -> async { item.withPosterService(settings, mdbListApiKey) } }.awaitAll()
        }
    }

    /**
     * Drops items an earlier row already claimed, updating [claimed] as it goes.
     *
     * A row thinned by dedupe is kept unless it is nearly empty: it passed the size test when it was
     * built, and losing a few titles to a neighbouring row is not a reason to delete it. Only the
     * initial construction uses the full [MIN_ROW_ITEMS] floor.
     */
    private fun dedupeAcrossRows(
        rows: List<DiscoverRecommendationRow>,
        claimed: MutableSet<String>,
    ): List<DiscoverRecommendationRow> = rows.mapNotNull { row ->
        val kept = row.items.filter { claimed.add("${it.type}:${it.id}") }
        if (kept.size < MIN_ROW_ITEMS_AFTER_FILTER) {
            log.d { "Dropped row ${row.key}: ${kept.size} items left after dedupe" }
            null
        } else {
            row.copy(items = kept)
        }
    }

    /** Alternates the two lists so a mixed history yields a mixed row rather than two blocks. */
    private fun interleave(
        first: List<TmdbSearchResult>,
        second: List<TmdbSearchResult>,
    ): List<TmdbSearchResult> {
        val out = mutableListOf<TmdbSearchResult>()
        for (index in 0 until maxOf(first.size, second.size)) {
            first.getOrNull(index)?.let(out::add)
            second.getOrNull(index)?.let(out::add)
        }
        return out
    }

    /**
     * The two per-title filters that cannot be answered from the list responses, applied in one
     * pass after the rows are already visible.
     *
     * **Hide-watched needs this pass, not just the cheap key check.** Generated rows are addressed
     * as `tmdb:<id>`, while watch history is keyed on whatever id the user's addons write —
     * overwhelmingly `tt…`. The in-row filter therefore only ever catches history that happens to
     * be TMDB-keyed; matching the rest means resolving each item's IMDb id, which is one cached,
     * single-flighted request per title and far too much to do before first paint.
     *
     * **[generatedKeys] carries the AI rows too, and that is the point of §5.1.** A model asked for
     * recommendations will confidently name something the user finished last month and something
     * that is not out yet, and the prompt's "do not suggest these" line is a first defence that is
     * never worth trusting. AI rows publish with the free rows, before this pass exists, so they
     * are filtered here rather than at build time like the TMDB-generated ones — the row appears
     * immediately and thins as answers arrive, which is what §5.1 asked for.
     *
     * Two kinds are deliberately left out. The local "finish what you started" row is part-watched
     * by definition, so hide-watched would empty it. An imported list is a list the user chose and
     * froze; silently deleting entries from it is not filtering, it is editing.
     */
    private suspend fun pruneGeneratedRows(
        rows: List<DiscoverRecommendationRow>,
        generatedKeys: Set<String>,
        watchedParentKeys: Set<String>,
        hideWatched: Boolean,
        hideUnreleased: Boolean,
    ) {
        if (!hideWatched && !hideUnreleased) return
        val candidates = rows.filter { it.key in generatedKeys }.flatMap { it.items }
            .distinctBy { "${it.type}:${it.id}" }
        if (candidates.isEmpty()) return

        val drop = mutableSetOf<String>()
        // Chunked rather than one flat fan-out: this is up to a hundred titles, each worth a TMDB
        // request, and firing them all at once is exactly the shape that draws a rate limit — the
        // /find endpoint already has a comment about that. Nothing is waiting on this pass, so
        // trading latency for politeness costs nothing visible.
        for (chunk in candidates.chunked(PRUNE_CONCURRENCY)) {
            coroutineScope {
                chunk.map { item ->
                    async {
                        val key = "${item.type}:${item.id}"
                        // The cheap key check first — the TMDB-generated rows already applied it
                        // at build time, but the AI rows reach this pass unfiltered, and paying a
                        // /find call to re-learn what the history states outright is waste.
                        val watched = hideWatched && (
                            watchedParentKey(item.type, item.id) in watchedParentKeys ||
                                item.isWatchedByExternalId(watchedParentKeys)
                            )
                        val unreleased = !watched && hideUnreleased && item.isUnreleased()
                        key to (watched || unreleased)
                    }
                }.awaitAll()
            }.forEach { (key, remove) -> if (remove) drop += key }
        }

        if (drop.isEmpty()) return
        val pruned = rows.mapNotNull { row ->
            if (row.key !in generatedKeys) return@mapNotNull row
            val kept = row.items.filterNot { "${it.type}:${it.id}" in drop }
            if (kept.size < MIN_ROW_ITEMS_AFTER_FILTER) {
                log.d { "Dropped row ${row.key}: ${kept.size} items left after pruning" }
                null
            } else {
                row.copy(items = kept)
            }
        }

        // Only publish if this build is still the current one.
        if (_uiState.value.rows.map { it.key } == rows.map { it.key }) {
            log.d { "Pruned ${drop.size} items from Discover rows" }
            _uiState.value = _uiState.value.copy(rows = pruned)
            // Re-persisted, because [publish] wrote the *unpruned* set on its way past — this pass
            // had not finished yet. Leaving that file alone means the next launch inside the TTL
            // hydrates the unfiltered rows and takes the unchanged-signature short-circuit straight
            // past this pass, so both filters quietly stop applying until the cache expires. The
            // stamps are the ones publish just set, so the file stays consistent with them.
            persistToDisk(
                rows = pruned,
                entry = DiscoverRowCacheEntry(
                    builtAtEpochMs = lastBuiltAtEpochMs,
                    settingsSignature = lastSettingsSignature.orEmpty(),
                    seedSignature = lastSeedSignature,
                    profileId = ProfileRepository.activeProfileId,
                    rows = pruned.map { it.toCachedRow() },
                ),
            )
        }
    }

    /** Resolves this TMDB-addressed item's IMDb id and asks the watch history about that instead. */
    private suspend fun MetaPreview.isWatchedByExternalId(watchedParentKeys: Set<String>): Boolean {
        val tmdbId = id.removePrefix("tmdb:").toIntOrNull() ?: return false
        val imdbId = runCatching {
            TmdbService.tmdbToImdb(tmdbId, tmdbMediaTypeFor(type))
        }.getOrNull() ?: return false
        return watchedParentKey(type, imdbId) in watchedParentKeys
    }

    /**
     * Whether the title is Cinema or Production — not out yet, so not watchable. Reuses the hero
     * badge's status resolution (and therefore its cache and single-flight), which is the only
     * place in the app that knows how to answer this.
     */
    private suspend fun MetaPreview.isUnreleased(): Boolean = runCatching {
        HeroDiscoveryMetadataService.fetch(
            type = type,
            id = id,
            priority = listOf(RELEASE_STATUS_SLOT),
            releaseStatusUnavailableOnly = true,
        ).any { fact -> fact.category == RELEASE_STATUS_SLOT }
    }.getOrDefault(false)

    /**
     * Rewrites [MetaPreview.poster] through the configured poster template, keeping the plain TMDB
     * image as [MetaPreview.posterFallback] — the service does not have art for everything, and a
     * missing custom poster must not leave a blank card.
     */
    private suspend fun MetaPreview.withPosterService(
        settings: TmdbSettings,
        mdbListApiKey: String?,
    ): MetaPreview {
        if (!settings.libraryPosterEnabled) return this
        val tmdbId = id.removePrefix("tmdb:").toIntOrNull() ?: return this
        // Only pay for the /find call when the template actually names {imdb_id}; TmdbService
        // caches and single-flights it, so the cost is one request per title ever.
        val imdbId = if (settings.customPosterTemplateNeedsImdbId()) {
            runCatching { TmdbService.tmdbToImdb(tmdbId, tmdbMediaTypeFor(type)) }.getOrNull()
        } else {
            null
        }
        return withCustomLibraryPoster(
            settings = settings,
            imdbId = imdbId,
            tmdbId = tmdbId,
            mdbListApiKey = mdbListApiKey,
        )
    }

    private fun TmdbSearchResult.toMetaPreview(): MetaPreview {
        val type = if (isTv) "series" else "movie"
        return MetaPreview(
            // TMDB ids are namespaced by type, and the details screen understands the tmdb: form.
            id = "tmdb:$id",
            type = type,
            name = displayTitle,
            poster = TmdbService.tmdbImageUrl(posterPath),
            // w1280, not w780: this banner is what seeds the hero when a Discover row is the one
            // on screen, and a full-width hero at 780px is visibly soft. It matches what Home's
            // addon-sourced rows carry (Cinemeta's "background/medium" is ~1280 wide), so Discover
            // is no longer the odd one out. Not `original` — the same field renders landscape cards,
            // and a 4MB backdrop per card is a real cost for art shown at ~300px. When TMDB image
            // mode is on, the hero enrichment pass still upgrades the on-screen item to original.
            banner = TmdbService.tmdbImageUrl(backdropPath, size = "w1280"),
            description = overview,
            releaseInfo = year?.toString(),
            popularity = popularity,
            // Free — it rode along with the list response. Home's TMDB-backed rows already fill
            // this field the same way, and the corner rating badge reads it without knowing or
            // caring which surface built the row; leaving it null was Discover being the odd one
            // out, not a decision.
            imdbRating = tmdbVoteAverageLabel(voteAverage),
        )
    }

    /**
     * A part-watched title as a poster card. Art comes from the progress entry itself, which the
     * addon already supplied — so this row needs no metadata lookup of any kind.
     */
    private fun WatchProgressEntry.toMetaPreview(): MetaPreview = MetaPreview(
        id = parentMetaId,
        type = parentMetaType,
        name = title,
        poster = poster,
        banner = background,
        logo = logo,
        description = pauseDescription,
    )

    /**
     * Everything that changes what the rows contain. Any edit invalidates the cache on the next
     * visit to the tab, which is why the settings page needs no wiring of its own.
     */
    /** An empty slot for a row still being built. Same entry id, so it sorts where the real one will. */
    private fun placeholderRow(entryId: String): DiscoverRecommendationRow =
        DiscoverRecommendationRow(
            // Unique per slot: two placeholders for the same family would otherwise collide in the
            // lazy list, which is the duplicate-key crash ensureUniqueKeys exists to prevent.
            key = "$DISCOVER_PLACEHOLDER_KEY_PREFIX$entryId:${placeholderCounter++}",
            title = "",
            items = emptyList(),
            entryId = entryId,
            isPlaceholder = true,
        )

    private var placeholderCounter = 0

    private fun orderRows(
        rows: List<DiscoverRecommendationRow>,
        settings: HomeCatalogSettingsSnapshot,
    ): List<DiscoverRecommendationRow> =
        orderDiscoverRows(rows, settings.discoverRowOrder) { it.entryId }

    private fun settingsSignature(settings: HomeCatalogSettingsSnapshot): String = listOf(
        settings.discoverBecauseYouWatchedRows,
        settings.discoverHideWatched,
        settings.discoverFinishWhatYouStartedEnabled,
        settings.discoverFinishIdleDays,
        settings.discoverMoreLikeFavouritesEnabled,
        settings.discoverHiddenGemsEnabled,
        settings.discoverTrendingGenreRows,
        settings.discoverExcludedGenres.sorted().joinToString(","),
        settings.hideUnreleasedContent,
        // The definitions, not the order: editing a query changes what its row contains, moving it
        // does not. [refresh] re-sorts for the latter without rebuilding anything.
        settings.discoverCustomRows.joinToString(";"),
        // Imported lists are keyed by id and item count rather than by their contents: the contents
        // never change once imported — that is what "imported" means — so hashing them on every
        // build would be work in service of an answer that cannot differ.
        settings.discoverImportedRows.joinToString(";") { "${it.id}:${it.items.size}" },
    ).joinToString("|")

    const val FINISH_ROW_KEY = "discover:finish"
    const val FAVOURITES_ROW_KEY = "discover:favourites"
    const val HIDDEN_GEMS_ROW_KEY = "discover:gems"
    const val TRENDING_ROW_KEY_PREFIX = "discover:trending:"
    const val CUSTOM_ROW_KEY_PREFIX = "discover:custom:"

    /** Vote floor applied to a rating-sorted custom row that set none. See [effectiveMinVotes]. */

    private const val RELEASE_STATUS_SLOT = "release_status"

    /**
     * How long a generated row is allowed to be.
     *
     * Was 20 — one TMDB page — which is short enough that a row ends while the user is still
     * flicking through it, against every other shelf in the app where the addon keeps feeding.
     * Fifty is roughly where a horizontal shelf stops feeling finite at normal poster sizes.
     *
     * This is a ceiling, not a quota: filtering runs after the fetch, so a row that starts with 60
     * candidates and loses half to hide-watched is simply shorter, and that is fine.
     */
    private const val MAX_ITEMS_PER_ROW = 50

    /** TMDB's fixed page size. A short page means the result set ended, not that it was filtered. */
    private const val TMDB_PAGE_SIZE = 20

    /**
     * Pages any one query will walk. Three covers [MAX_ITEMS_PER_ROW] with headroom for the
     * filtering that follows; the cap exists because a badly-specified custom row could otherwise
     * page through a genre until TMDB ran out.
     */
    private const val MAX_PAGES_PER_ROW = 3

    /**
     * The target for one side of a row that interleaves films and shows.
     *
     * Deliberately more than half of [MAX_ITEMS_PER_ROW]: the two sides are rarely equally
     * productive after filtering, and a strict half means a row goes short whenever one namespace
     * has less to offer. Two pages per side, so an interleaved row costs four requests rather than
     * six.
     */
    private const val INTERLEAVED_SIDE_TARGET = 34

    /** A newly built row below this reads as broken rather than short, so it is not built at all. */
    private const val MIN_ROW_ITEMS = 5

    /** The same floor for a user-defined row, which is allowed to be narrow. See [buildCustomRow]. */
    private const val MIN_CUSTOM_ROW_ITEMS = 3

    /**
     * The floor for a row that has *already* been built and then thinned by dedupe or filtering.
     * Lower on purpose: the row earned its place at [MIN_ROW_ITEMS], and deleting it because two of
     * its titles turned out to be watched loses more than the short row costs.
     */
    private const val MIN_ROW_ITEMS_AFTER_FILTER = 2

    /** Titles resolved in parallel by the post-publish prune pass. See [pruneGeneratedRows]. */
    private const val PRUNE_CONCURRENCY = 8

    /** How many top genres "More like your favourites" intersects. */
    private const val FAVOURITE_GENRE_DEPTH = 2

    // Vote floors for discover queries. Without them `popularity.desc` surfaces a lot of
    // barely-rated noise; TV is lower because TV records collect far fewer votes than films do.
    private const val MIN_VOTES_MOVIE = 300
    private const val MIN_VOTES_TV = 100

    // Hidden gems. Bands, not floors — the ceiling is what makes a gem hidden. Measured against a
    // live account; see buildHiddenGemsRow for what each one is holding back.
    private val GEM_VOTES_MOVIE = 200..800
    private val GEM_VOTES_TV = 150..600
    private const val GEM_MIN_RATING = 7.0

    /** How settled a title must be before "few votes" means overlooked rather than brand new. */
    private const val GEM_MIN_AGE_MS = 365L * 24 * 60 * 60 * 1000

    /** And how old before "few votes" means canonical rather than overlooked. */
    private const val GEM_MAX_AGE_MS = 30L * 365 * 24 * 60 * 60 * 1000

    // Formats whose TMDB vote counts say nothing about how well known they are. Subtracted from,
    // never added to, the genre being queried — see buildHiddenGemsRow.
    private val TV_FORMAT_GENRE_IDS = listOf(
        10762, // Kids
        10763, // News
        10764, // Reality
        10766, // Soap
        10767, // Talk
    )
    private val MOVIE_FORMAT_GENRE_IDS = listOf(
        10751, // Family
        99, // Documentary
        10770, // TV Movie
    )

    /** Recommendations move slowly; an hour is plenty and keeps re-entering the tab free. */
    private const val CACHE_TTL_MS = 60L * 60 * 1000
}

/**
 * TMDB's vote average as the poster's corner rating badge reads it, or null when there is no score.
 *
 * Shared with [DiscoverAiGenerator] so a TMDB-generated row and an AI row label the same title the
 * same way. Rounded to a tenth, matching how the addon-backed rows arrive and how
 * `TmdbMetadataService` formats its own — the badge renders whatever is in `imdbRating` and cannot
 * tell the sources apart, so they have to agree before it gets there.
 *
 * Zero is TMDB's "nobody has rated this", not a score of nought.
 */
internal fun tmdbVoteAverageLabel(voteAverage: Double): String? {
    if (voteAverage <= 0.0) return null
    return (kotlin.math.round(voteAverage * 10.0) / 10.0).toString()
}
