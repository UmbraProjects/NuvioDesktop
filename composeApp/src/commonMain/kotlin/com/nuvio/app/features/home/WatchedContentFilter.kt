package com.nuvio.app.features.home

import com.nuvio.app.features.discover.buildWatchedParentKeys
import com.nuvio.app.features.discover.tmdbMediaTypeFor
import com.nuvio.app.features.discover.watchedParentKey
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.tmdb.ImdbToTmdbLookup
import com.nuvio.app.features.tmdb.TmdbBackgroundLane
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Hide watched content" — the title-level sibling of [filterReleasedItems].
 *
 * Applies to the browsing surfaces (Home rows and hero, catalog grids, the Discover browse row,
 * details "More Like This", collections, Library, Random Play). Deliberately not applied to search
 * results, Continue Watching or Next Up: those are where a title is looked up on purpose, and a
 * rewatch has to stay reachable somewhere. Discover's generated rows carry their own switch.
 *
 * "Watched" means the title, not the episode — see [buildWatchedParentKeys] for why one watched
 * episode is enough. The history is IMDb-keyed for every tracker import, so `tmdb:`-addressed rows
 * are answered through the persisted TMDB→IMDb cache and only once that mapping is known. The
 * filter itself never fetches; load paths can fill the cache first with [prepareForLoad].
 *
 * The verdict is made once, when a title is first published. Home republishes every few seconds,
 * and TMDB→IMDb mappings are learned in the meantime by whatever the user is looking at (custom
 * posters, hero metadata), so re-judging every title on every publish made posters vanish from
 * rows as they scrolled into view. A title that has already been shown is now hidden only by a
 * watch recorded after it was shown — see [ShownThisSession].
 */
internal class WatchedContentFilter private constructor(private val keys: Set<String>) {

    fun isWatched(type: String, id: String): Boolean = isWatchedIn(keys, type, id)

    fun filter(items: List<MetaPreview>): List<MetaPreview> {
        val kept = items.filterNot(::hides)
        return if (kept.size == items.size) items else kept
    }

    private fun hides(item: MetaPreview): Boolean {
        val title = "${item.type}:${item.id}"
        if (ShownThisSession.wasShown(title)) {
            return isWatchedIn(ShownThisSession.addedSinceBaseline(keys), item.type, item.id)
        }
        val watched = isWatchedIn(keys, item.type, item.id)
        if (!watched) ShownThisSession.markShown(title)
        return watched
    }

    /**
     * Titles that have passed the filter this session, and the watch history as it stood when
     * the first of them did. Only history added since that baseline — a finished episode, a
     * "mark watched", a tracker sync — can take a shown title back off screen.
     */
    private object ShownThisSession {
        private val titles = HashSet<String>()
        private var baseline: Set<String>? = null
        private var addedFor: Set<String>? = null
        private var added: Set<String> = emptySet()

        fun wasShown(title: String): Boolean = synchronized(this) { title in titles }

        fun markShown(title: String) {
            synchronized(this) { titles += title }
        }

        fun noteHistory(keys: Set<String>) {
            synchronized(this) { if (baseline == null) baseline = keys }
        }

        fun addedSinceBaseline(keys: Set<String>): Set<String> = synchronized(this) {
            if (addedFor !== keys) {
                val base = baseline.orEmpty()
                added = if (keys === base) emptySet() else keys - base
                addedFor = keys
            }
            added
        }

        /** The setting was switched off: switching it back on judges everything afresh. */
        fun reset() {
            synchronized(this) {
                titles.clear()
                baseline = null
                addedFor = null
                added = emptySet()
            }
        }
    }

    companion object {
        // Rebuilt only when the history list instance changes: a 6,000-row history is keyed in
        // milliseconds, but Home republishes on every settings tick and would pay it each time.
        @Volatile
        private var cached: Pair<List<WatchedItem>, Set<String>>? = null

        /** The active filter, or null when the setting is off so callers can short-circuit. */
        fun current(
            snapshot: HomeCatalogSettingsSnapshot = HomeCatalogSettingsRepository.snapshot(),
        ): WatchedContentFilter? {
            if (!snapshot.hideWatchedContent) {
                ShownThisSession.reset()
                return null
            }
            return forHistory(WatchedRepository.uiState.value.items)
        }

        /** Forgets every session verdict, as switching the setting off does. */
        internal fun resetSessionVerdicts() = ShownThisSession.reset()

        /**
         * Call on a load path before filtering a freshly fetched page, so the verdict made there —
         * the one that sticks — sees every mapping it can. Loads the persisted TMDB→IMDb cache
         * (the async hydration in [forHistory] may not have landed yet on the first page), and
         * with a [resolveBudgetMs] also looks up the page's still-unmapped `tmdb:` ids, giving up
         * on whatever has not answered in time rather than holding the page back.
         */
        suspend fun prepareForLoad(items: List<MetaPreview>, resolveBudgetMs: Long = 0) {
            if (!HomeCatalogSettingsRepository.snapshot().hideWatchedContent) return
            TmdbService.ensureExternalIdCacheLoaded()
            if (resolveBudgetMs <= 0) return
            // Once every watched title's TMDB id is known, a `tmdb:` row is judged from that index
            // and looking the row up adds nothing. Until then (first run, a fresh watch, no TMDB
            // key) the page is resolved exactly as before.
            if (WatchedTmdbIndex.covers(forHistory(WatchedRepository.uiState.value.items).keys)) return
            val unmapped = items.mapNotNull { item ->
                val tmdbId = item.id.takeIf { it.startsWith("tmdb:") }
                    ?.removePrefix("tmdb:")
                    ?.toIntOrNull()
                    ?: return@mapNotNull null
                val mediaType = tmdbMediaTypeFor(item.type)
                (tmdbId to mediaType).takeIf { TmdbService.peekTmdbToImdb(tmdbId, mediaType) == null }
            }.distinct()
            if (unmapped.isEmpty()) return
            withTimeoutOrNull(resolveBudgetMs) {
                coroutineScope {
                    unmapped.map { (tmdbId, mediaType) ->
                        async { runCatching { TmdbService.tmdbToImdb(tmdbId, mediaType) } }
                    }.awaitAll()
                }
            }
        }

        fun forHistory(items: List<WatchedItem>): WatchedContentFilter {
            TmdbService.hydrateExternalIdCacheAsync()
            cached?.let { (source, keys) ->
                if (source === items) {
                    ShownThisSession.noteHistory(keys)
                    return WatchedContentFilter(keys)
                }
            }
            val keys = buildWatchedParentKeys(items)
            cached = items to keys
            ShownThisSession.noteHistory(keys)
            WatchedTmdbIndex.learn(keys)
            return WatchedContentFilter(keys)
        }

        private fun isWatchedIn(keys: Set<String>, type: String, id: String): Boolean {
            if (keys.isEmpty()) return false
            if (watchedParentKey(type, id) in keys) return true
            if (!id.startsWith("tmdb:")) return false
            val tmdbId = id.removePrefix("tmdb:").toIntOrNull() ?: return false
            val mediaType = tmdbMediaTypeFor(type)
            TmdbService.peekTmdbToImdb(tmdbId, mediaType)?.let { imdbId ->
                if (watchedParentKey(type, imdbId) in keys) return true
            }
            return WatchedTmdbIndex.historyKeyFor(mediaType, tmdbId)?.let { it in keys } ?: false
        }
    }
}

/**
 * How long a user-opened page (a collection, a "See all" grid, a row's next page) may wait on
 * TMDB→IMDb lookups before it is shown with whatever could not be resolved still in it.
 */
internal const val WATCHED_FILTER_RESOLVE_BUDGET_MS = 2_500L

internal fun List<MetaPreview>.filterUnwatchedItems(filter: WatchedContentFilter?): List<MetaPreview> =
    filter?.filter(this) ?: this

internal fun HomeCatalogSection.filterUnwatchedItems(filter: WatchedContentFilter?): HomeCatalogSection {
    if (filter == null) return this
    val filteredItems = filter.filter(items)
    return if (filteredItems.size == items.size) this else copy(items = filteredItems)
}

/**
 * The two browsing filters together — "hide unreleased" and "hide watched" — so a surface that
 * applies one cannot forget the other. Resolved once per publish; [apply] is pure after that.
 */
internal class BrowsingFilters private constructor(
    private val todayIsoDate: String?,
    private val watched: WatchedContentFilter?,
) {
    val isActive: Boolean get() = todayIsoDate != null || watched != null

    fun apply(items: List<MetaPreview>): List<MetaPreview> =
        (if (todayIsoDate == null) items else items.filterReleasedItems(todayIsoDate))
            .filterUnwatchedItems(watched)

    fun apply(section: HomeCatalogSection): HomeCatalogSection {
        if (!isActive) return section
        val filteredItems = apply(section.items)
        return if (filteredItems.size == section.items.size) section else section.copy(items = filteredItems)
    }

    companion object {
        fun current(snapshot: HomeCatalogSettingsSnapshot = HomeCatalogSettingsRepository.snapshot()): BrowsingFilters =
            BrowsingFilters(
                todayIsoDate = if (snapshot.hideUnreleasedContent) CurrentDateProvider.todayIsoDate() else null,
                watched = WatchedContentFilter.current(snapshot),
            )
    }
}

/**
 * The watch history's titles by TMDB id, so a `tmdb:` row can be judged without a lookup of its own.
 *
 * The history is IMDb-keyed, and a `tmdb:` row used to be matched by asking TMDB for that row's
 * IMDb id: one request per never-seen title, about 20 per TMDB source opened, and a large community
 * collection is thousands of titles. The history is the small side of that comparison — a few hundred
 * titles, growing one watch at a time — so it is the side mapped instead: once per title, in the
 * background, with the answers persisted by [TmdbService] like every other external-id mapping.
 *
 * [covers] is true only when every history title has a definite answer (a TMDB id, or TMDB saying
 * it has none). Until then — the first run, a title just watched, no TMDB key, TMDB unreachable —
 * the per-row path in [WatchedContentFilter.prepareForLoad] runs exactly as it did, so a gap here
 * can only cost requests, never let a watched title through.
 */
internal object WatchedTmdbIndex {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + TmdbBackgroundLane)
    private val lock = Any()

    /** `movie:603` / `tv:1399` → the history key it belongs to (`movie:tt0133093`, `series:tt0944947`). */
    private val historyKeyByTmdb = HashMap<String, String>()

    /** History keys with a definite answer, whichever it was. */
    private val settled = HashSet<String>()
    private val inFlight = HashSet<String>()

    /** Identity of the last history set [covers] confirmed, so the common case is one comparison. */
    @Volatile
    private var coveredKeys: Set<String>? = null

    fun historyKeyFor(tmdbMediaType: String, tmdbId: Int): String? =
        synchronized(lock) { historyKeyByTmdb["$tmdbMediaType:$tmdbId"] }

    fun covers(keys: Set<String>): Boolean {
        if (coveredKeys === keys) return true
        val covered = synchronized(lock) { settled.containsAll(keys) }
        if (covered) coveredKeys = keys
        return covered
    }

    /** Settles, in the background, whichever of [keys] are not settled yet. */
    fun learn(keys: Set<String>) {
        val pending = synchronized(lock) {
            keys.filter { it !in settled && inFlight.add(it) }
        }
        if (pending.isEmpty()) return
        scope.launch {
            TmdbService.ensureExternalIdCacheLoaded()
            val permits = Semaphore(LOOKUP_CONCURRENCY)
            pending.map { key ->
                launch {
                    val answer = try {
                        permits.withPermit { resolve(key) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        null
                    }
                    synchronized(lock) {
                        inFlight -= key
                        if (answer != null) {
                            settled += key
                            answer.tmdbKey?.let { historyKeyByTmdb[it] = key }
                        }
                    }
                }
            }.joinAll()
        }
    }

    /** null = no definite answer yet (asked again on the next history change or launch). */
    private suspend fun resolve(historyKey: String): Answer? {
        val type = historyKey.substringBefore(':')
        val id = historyKey.substringAfter(':')
        val mediaType = tmdbMediaTypeFor(type)
        return when {
            id.startsWith("tmdb:") -> {
                // Already TMDB-addressed: the row matches it directly in isWatchedIn.
                Answer(tmdbKey = id.removePrefix("tmdb:").toIntOrNull()?.let { "$mediaType:$it" })
            }
            id.startsWith("tt", ignoreCase = true) -> {
                TmdbService.peekImdbToTmdb(id, mediaType)?.let { return Answer("$mediaType:$it") }
                when (val lookup = TmdbService.lookupImdbToTmdb(id, mediaType)) {
                    is ImdbToTmdbLookup.Found -> Answer("$mediaType:${lookup.tmdbId}")
                    ImdbToTmdbLookup.NotFound -> Answer(tmdbKey = null)
                    ImdbToTmdbLookup.Unavailable -> null
                }
            }
            // kitsu:, mal:, tvdb: and the like: no `tmdb:` row was ever matched to these by the
            // per-row path either (it maps rows to IMDb ids), so there is nothing to wait for.
            else -> Answer(tmdbKey = null)
        }
    }

    private class Answer(val tmdbKey: String?)

    private const val LOOKUP_CONCURRENCY = 4
}
