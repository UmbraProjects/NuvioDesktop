package com.nuvio.app.features.home

import co.touchlab.kermit.Logger
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.catalog.fetchCatalogPage
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Grows the Random Play pool until every enabled card can draw from
 * [RANDOM_PLAY_TARGET_CANDIDATES] titles, by the two means a loaded Home row leaves on the table.
 *
 * **Resolving what the row did not say.** A catalog response is thinner than the app's own screens:
 * most anime metas arrive with no genres at all and a bare start year, and the app only fills that
 * in later, per item, when the hero enriches or a details page opens. Judged raw, those titles fail
 * the genre filter outright and have their film/show form guessed. This fetches the same lightweight
 * metadata the hero uses — single-flight and cached in [MetaDetailsRepository], so items the hero
 * already resolved cost nothing — but only for the items whose bucket actually turns on it.
 *
 * **Paging.** Paginating rows expose their first page; non-paginating rows are cut to 18 items by
 * Home's preview limit even when the addon returned the lot. Both are widened here, cheapest first.
 *
 * Nothing here is rendered. The state is folded into the candidate pool by
 * [randomPlaySourceSections], exactly like [RandomPlayCollectionPool]'s sections.
 */
object RandomPlayCandidatePool {
    private val log = Logger.withTag("RandomPlayCandidatePool")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(RandomPlayPoolState.Empty)
    val state: StateFlow<RandomPlayPoolState> = _state.asStateFlow()

    private var activeJob: Job? = null
    private var loadedRequestKey: String? = null
    private var loadedFilterKey: String? = null
    private var loadedSectionKeys: Set<String> = emptySet()

    /** Per-title metadata, keyed by [MetaPreview.stableKey]. Facts about a title do not expire with
     *  the row it was seen in, so these survive a request-key change. */
    private val facts = linkedMapOf<String, RandomPlayMetaFacts>()

    /**
     * Items whose metadata lookup reached a terminal result, including a genuine null. Merely
     * starting a lookup is deliberately not recorded: a canceled caller leaves the shared
     * metadata fetch running, and the next pass must be free to collect its cached result.
     */
    private val enrichCompleted = linkedSetOf<String>()

    /** Extra pages fetched per section key, and where that section's next page starts. */
    private val extraPages = linkedMapOf<String, MutableList<MetaPreview>>()
    private val nextSkipByKey = linkedMapOf<String, Int>()
    private val exhaustedSections = linkedSetOf<String>()
    private val widenedSections = linkedSetOf<String>()

    /**
     * Requests spent on the current row set, and whether it has been worked as far as it goes.
     * Both bound the *repeat*: Home publishes state often, and a card that simply has no catalog
     * behind it — no anime row installed, say — must cost a fixed amount of work per row set
     * rather than another round of paging every time a row ticks.
     */
    private var requestsForKey = 0
    private var settledKey: String? = null

    /**
     * Tops the pool up for the currently enabled cards. Safe to call on every Home state change:
     * it is a no-op while a pass is in flight, and once every enabled card has reached the target
     * or every source has been exhausted.
     */
    fun ensureFilled(
        sourceSections: List<HomeCatalogSection>,
        settings: HomeCatalogSettingsUiState,
        watchedKeys: Set<String>,
    ) {
        if (!settings.randomPlayEnabled || settings.randomPlayCategories.isEmpty()) {
            clear()
            return
        }
        val sections = sourceSections.filterNot { it.key == RANDOM_PLAY_SECTION_KEY }
        if (sections.isEmpty()) return

        val filterKey = requestFilterKey(settings, watchedKeys)
        val sectionKeys = sections.mapTo(linkedSetOf(), HomeCatalogSection::key)
        val requestKey = requestKey(sections, filterKey)
        val rowSetChanged = requestKey != loadedRequestKey
        if (!rowSetChanged && (activeJob?.isActive == true || settledKey == requestKey)) return
        val appendOnlyRowUpdate = rowSetChanged && randomPlayRowSetChangeIsAppendOnly(
            previousFilterKey = loadedFilterKey,
            previousSectionKeys = loadedSectionKeys,
            nextFilterKey = filterKey,
            nextSectionKeys = sectionKeys,
        )
        loadedRequestKey = requestKey
        loadedFilterKey = filterKey
        loadedSectionKeys = sectionKeys
        if (rowSetChanged) settledKey = null

        enqueue(cancelPrevious = !appendOnlyRowUpdate) {
            if (loadedRequestKey != requestKey) return@enqueue
            // The rows or the filters changed: paging starts over against the new set, but the
            // resolved per-title facts stay valid and are deliberately kept.
            if (rowSetChanged && !appendOnlyRowUpdate) {
                resetRowSetState()
                publish()
            }
            runCatching { fill(sections, settings, watchedKeys, requestKey) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    log.w(error) { "Random Play pool pass failed" }
                }
        }
    }

    /** Starts the same fill used by Home, then waits for the latest queued pass to become idle. */
    internal suspend fun ensureFilledAndAwait(
        sourceSections: List<HomeCatalogSection>,
        settings: HomeCatalogSettingsUiState,
        watchedKeys: Set<String>,
    ) {
        ensureFilled(sourceSections, settings, watchedKeys)
        awaitIdle()
    }

    /** Cancellable so callers can put their own small timeout around an on-demand fill. */
    internal suspend fun awaitIdle() {
        while (true) {
            val observed = activeJob ?: return
            observed.join()
            if (activeJob === observed) return
        }
    }

    fun clear() {
        loadedRequestKey = null
        loadedFilterKey = null
        loadedSectionKeys = emptySet()
        settledKey = null
        enqueue {
            resetRowSetState()
            _state.value = RandomPlayPoolState.Empty
        }
    }

    /** Facts are per-title, but a profile switch changes whose watched history and settings apply. */
    fun onProfileChanged() {
        loadedRequestKey = null
        loadedFilterKey = null
        loadedSectionKeys = emptySet()
        settledKey = null
        enqueue {
            facts.clear()
            enrichCompleted.clear()
            resetRowSetState()
            _state.value = RandomPlayPoolState.Empty
        }
    }

    /**
     * Everything that touches the pool's collections runs on one chain, newest first: the maps are
     * written from a background pass but reset from whichever thread changed a setting or switched
     * profile, and cancelling a pass does not stop it mid-write.
     */
    private fun enqueue(
        cancelPrevious: Boolean = true,
        block: suspend () -> Unit,
    ) {
        val previous = activeJob
        activeJob = scope.launch {
            if (cancelPrevious) {
                previous?.cancelAndJoin()
            } else {
                try {
                    previous?.join()
                } catch (cancellation: CancellationException) {
                    // If a later, incompatible request cancels this queued append-only pass,
                    // unwind the whole wait chain before letting that request mutate the maps.
                    withContext(NonCancellable) { previous?.cancelAndJoin() }
                    throw cancellation
                }
            }
            block()
        }
    }

    private fun resetRowSetState() {
        extraPages.clear()
        nextSkipByKey.clear()
        exhaustedSections.clear()
        widenedSections.clear()
        requestsForKey = 0
    }

    private suspend fun fill(
        sections: List<HomeCatalogSection>,
        settings: HomeCatalogSettingsUiState,
        watchedKeys: Set<String>,
        requestKey: String,
    ) {
        try {
            fillPasses(sections, settings, watchedKeys, requestKey)
        } finally {
            logClassification(sections.merged(), settings, watchedKeys)
        }
    }

    /**
     * What each card ended up holding and why. Addon payloads differ per user and cannot be
     * reproduced from a dev machine, so a misfiled title is diagnosed from this line.
     */
    private fun logClassification(
        sections: List<HomeCatalogSection>,
        settings: HomeCatalogSettingsUiState,
        watchedKeys: Set<String>,
    ) {
        log.i {
            val counts = settings.randomPlayCategories
                .sortedBy(RandomPlayCategory::ordinal)
                .joinToString { category ->
                    val size = randomPlayCandidates(
                        sourceSections = sections,
                        category = category,
                        allowedGenres = settings.randomPlayGenres,
                        minimumImdbRating = settings.randomPlayMinimumImdbRating,
                        watchedKeys = watchedKeys,
                    ).size
                    "${category.name}=$size"
                }
            val anime = listOf(RandomPlayCategory.AnimeMovie, RandomPlayCategory.AnimeSeries)
                .filter { it in settings.randomPlayCategories }
                .joinToString(separator = " | ") { category ->
                    "${category.name}: ${randomPlayClassificationTrace(sections, category)}"
                }
            "Random Play pool: $counts${if (anime.isBlank()) "" else " — $anime"}"
        }
    }

    private suspend fun fillPasses(
        sections: List<HomeCatalogSection>,
        settings: HomeCatalogSettingsUiState,
        watchedKeys: Set<String>,
        requestKey: String,
    ) {
        repeat(RANDOM_PLAY_POOL_MAX_PASSES) { pass ->
            if (loadedRequestKey != requestKey) return
            val merged = sections.merged()
            val short = randomPlayShortCategories(merged, settings, watchedKeys)
            if (requestsForKey >= RANDOM_PLAY_POOL_MAX_REQUESTS) {
                settledKey = requestKey
                log.i { "Random Play pool: request budget spent, still short for $short" }
                return
            }

            // Resolving beats paging: it is capped, it is often already cached, and a page of a
            // catalog whose metas carry no genres would arrive just as unusable as the last one.
            // It also runs before the shortfall check, because verifying a suspect anime film is
            // owed whether or not the card is full.
            val resolved = resolvePass(merged, settings, short, requestKey)
            if (loadedRequestKey != requestKey) return
            requestsForKey += resolved
            if (resolved > 0) {
                publish()
                return@repeat
            }
            if (short.isEmpty()) {
                log.d { "Random Play pool: every enabled card has $RANDOM_PLAY_TARGET_CANDIDATES candidates" }
                return
            }

            val paged = pagePass(sections, requestKey)
            requestsForKey += paged
            if (paged == 0) {
                settledKey = requestKey
                log.i {
                    "Random Play pool: nothing left to fetch after pass ${pass + 1}, still short for $short"
                }
                return
            }
            publish()
        }
    }

    /**
     * Asks the metadata layer about the items whose bucket depends on something the row omitted:
     * a title with no genres while the allow-list is narrowed, an anime meta whose form is still a
     * guess, or a missing rating while a minimum is set. Everything else is left alone — this is a
     * request per title, and the pool has no business resolving a row it can already classify.
     */
    private suspend fun resolvePass(
        sections: List<HomeCatalogSection>,
        settings: HomeCatalogSettingsUiState,
        short: Set<RandomPlayCategory>,
        requestKey: String,
    ): Int {
        val allowEveryGenre = randomPlayAllowsEveryGenre(settings.randomPlayGenres)
        val pending = linkedMapOf<String, Pair<MetaPreview, RandomPlayRow>>()
        for (section in sections) {
            val row = section.randomPlayRow()
            for (item in section.items) {
                if (pending.size >= RANDOM_PLAY_POOL_RESOLVE_BATCH) break
                val key = item.stableKey()
                if (key in enrichCompleted || key in facts) continue
                val category = item.randomPlayCategoryIn(row) ?: continue
                // An unverified anime film is wrong until proven otherwise, so it is asked about
                // regardless of how full its card is; everything else only earns a request when it
                // could help a card that is short.
                val needsVerifying = RandomPlayCategory.AnimeMovie in settings.randomPlayCategories &&
                    item.randomPlayAnimeMovieNeedsVerifying(row)
                if (!needsVerifying) {
                    val formIsGuessed = item.randomPlayFormIsGuessed(row)
                    // A guessed anime form can move between the two anime cards, so either one
                    // being short is reason enough to look.
                    val couldHelpShortCard = category in short || (
                        formIsGuessed && short.any {
                            it == RandomPlayCategory.AnimeMovie || it == RandomPlayCategory.AnimeSeries
                        }
                        )
                    if (!couldHelpShortCard) continue
                    val needsGenres = !allowEveryGenre && item.randomPlayGenresUnknown(row)
                    val needsRating = settings.randomPlayMinimumImdbRating > 0f &&
                        item.imdbRating.isNullOrBlank()
                    if (!needsGenres && !needsRating && !formIsGuessed) continue
                }
                pending[key] = item to row
            }
            if (pending.size >= RANDOM_PLAY_POOL_RESOLVE_BATCH) break
        }
        if (pending.isEmpty()) return 0

        var completedCount = 0
        var resolvedCount = 0
        for (batch in pending.entries.chunked(RANDOM_PLAY_POOL_RESOLVE_CONCURRENCY)) {
            if (loadedRequestKey != requestKey) break
            coroutineScope {
                val requests = batch.map { (key, candidate) ->
                    val (item, row) = candidate
                    async {
                        val meta = resolveMeta(item, row)
                        key to meta?.let {
                            RandomPlayMetaFacts(
                                genres = it.genres,
                                releaseInfo = it.releaseInfo,
                                imdbRating = it.imdbRating,
                                resolvedType = it.type,
                                carriesAnimeCatalogueId = !it.malId.isNullOrBlank(),
                                episodeCount = it.videos.size,
                            )
                        }
                    }
                }
                // Commit each outcome as it completes. If a later request in this batch is
                // canceled, already-finished title facts remain valid and only the unfinished
                // keys stay retryable on the next pass.
                for (request in requests) {
                    val (key, learned) = request.await()
                    enrichCompleted += key
                    if (learned != null) {
                        facts[key] = learned
                        resolvedCount++
                    }
                    completedCount++
                }
            }
        }
        log.d { "Random Play pool: resolved $resolvedCount / $completedCount completed under-described titles" }
        return completedCount
    }

    /**
     * The item's own type first, then the row's if that found nothing. An addon that types a show
     * `movie` may well advertise only the row's type (`anime`) for the id prefix it answers, and
     * the whole point of the lookup is that the item's type is not to be trusted.
     */
    private suspend fun resolveMeta(item: MetaPreview, row: RandomPlayRow): MetaDetails? {
        val byItemType = fetchMetaOrNull {
            MetaDetailsRepository.fetchLightweightMeta(
                type = item.metadataType,
                id = item.metadataId,
            )
        }
        if (byItemType != null) return byItemType
        val rowType = row.contentType?.takeIf { !it.equals(item.metadataType, ignoreCase = true) }
            ?: return null
        return fetchMetaOrNull {
            MetaDetailsRepository.fetchLightweightMeta(type = rowType, id = item.metadataId)
        }
    }

    private suspend inline fun fetchMetaOrNull(fetch: suspend () -> MetaDetails?): MetaDetails? = try {
        fetch()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        null
    }

    /**
     * One page of catalog per pass, cheapest first: a non-paginating row is re-read once without
     * Home's preview cap (one request for the whole response the addon already sent), and a
     * paginating row moves on to its next skip offset.
     */
    private suspend fun pagePass(
        sections: List<HomeCatalogSection>,
        requestKey: String,
    ): Int {
        val widenable = sections.filter { section ->
            val target = section.target as? CatalogTarget.Addon
            target != null && !target.supportsPagination && section.key !in widenedSections &&
                section.availableItemCount > section.items.size
        }
        val pageable = sections.filter { section ->
            val target = section.target as? CatalogTarget.Addon
            target != null && target.supportsPagination && section.key !in exhaustedSections
        }
        val batch = (widenable + pageable).take(RANDOM_PLAY_POOL_PAGE_BATCH)
        if (batch.isEmpty()) return 0

        val results = coroutineScope {
            batch.map { section ->
                async {
                    val target = section.target as? CatalogTarget.Addon ?: return@async null
                    val widening = !target.supportsPagination
                    val skip = if (widening) null else nextSkip(section)
                    try {
                        val page = fetchCatalogPage(
                            manifestUrl = target.manifestUrl,
                            type = target.contentType,
                            catalogId = target.catalogId,
                            genre = target.genre,
                            skip = skip,
                        )
                        Triple(section, page.items, page.nextSkip.takeIf { !widening })
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        null
                    }
                }
            }.awaitAll()
        }
        if (loadedRequestKey != requestKey) return 0

        var fetched = 0
        results.filterNotNull().forEach { (section, items, nextSkip) ->
            fetched++
            val widening = (section.target as? CatalogTarget.Addon)?.supportsPagination == false
            if (widening) widenedSections += section.key
            // Only what the row does not already hold; randomPlayCandidates dedupes too, but an
            // unbounded extra list would grow by a full page on every widening pass.
            val known = section.items.mapTo(linkedSetOf(), MetaPreview::stableKey)
            val bucket = extraPages.getOrPut(section.key) { mutableListOf() }
            known += bucket.map(MetaPreview::stableKey)
            val added = items.filter { known.add(it.stableKey()) }
            bucket += added
            if (nextSkip == null || added.isEmpty()) {
                exhaustedSections += section.key
            } else {
                nextSkipByKey[section.key] = nextSkip
            }
        }
        log.d { "Random Play pool: fetched $fetched extra page(s), pool now ${extraPages.values.sumOf { it.size }} extra items" }
        return fetched
    }

    private fun nextSkip(section: HomeCatalogSection): Int =
        nextSkipByKey[section.key]
            ?: section.nextSkip
            ?: (section.items.size + extraPages[section.key].orEmpty().size)

    /** The sections as the candidate builder currently sees them: rows + extra pages + facts. */
    private fun List<HomeCatalogSection>.merged(): List<HomeCatalogSection> {
        val snapshot = currentState()
        return map { section ->
            section.copy(
                items = (section.items + snapshot.extraPages[section.key].orEmpty())
                    .map { it.withRandomPlayFacts(snapshot.facts) },
            )
        }
    }

    private fun currentState() = RandomPlayPoolState(
        extraPages = extraPages.mapValues { (_, items) -> items.toList() },
        facts = facts.toMap(),
    )

    private fun publish() {
        _state.value = currentState()
    }

    /**
     * Covers the rows themselves and every filter that decides what counts as a candidate — a
     * narrowed genre list or a raised minimum rating changes which items are worth resolving, and
     * a changed card set changes which shortfalls the pool is chasing.
     */
    private fun requestKey(
        sections: List<HomeCatalogSection>,
        filterKey: String,
    ): String = buildString {
        sections.forEach { section ->
            append(section.key)
            append(';')
        }
        append('|')
        append(filterKey)
    }

    private fun requestFilterKey(
        settings: HomeCatalogSettingsUiState,
        watchedKeys: Set<String>,
    ): String = buildString {
        append("categories=")
        append(settings.randomPlayCategories.sortedBy(RandomPlayCategory::ordinal).joinToString())
        append("|genres=")
        append(settings.randomPlayGenres.sorted().joinToString())
        append("|minRating=")
        append(settings.randomPlayMinimumImdbRating)
        append("|watched=")
        watchedKeys.sorted().forEach { key ->
            append(key)
            append(';')
        }
    }
}

/** True only when the same filters now see all previous rows plus newly appended rows. */
internal fun randomPlayRowSetChangeIsAppendOnly(
    previousFilterKey: String?,
    previousSectionKeys: Set<String>,
    nextFilterKey: String,
    nextSectionKeys: Set<String>,
): Boolean = previousFilterKey != null &&
    previousFilterKey == nextFilterKey &&
    nextSectionKeys.containsAll(previousSectionKeys)

/** Passes per call. Each pass either resolves a batch of titles or fetches a batch of pages. */
private const val RANDOM_PLAY_POOL_MAX_PASSES = 6
/** Hard ceiling on requests for one call, so a user with no anime catalog stops rather than digs. */
private const val RANDOM_PLAY_POOL_MAX_REQUESTS = 96
private const val RANDOM_PLAY_POOL_RESOLVE_BATCH = 24
private const val RANDOM_PLAY_POOL_RESOLVE_CONCURRENCY = 6
private const val RANDOM_PLAY_POOL_PAGE_BATCH = 4
