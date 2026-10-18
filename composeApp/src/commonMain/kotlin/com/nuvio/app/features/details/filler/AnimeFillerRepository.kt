package com.nuvio.app.features.details.filler

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.CoalescingCachePersister
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.metadata.AnimeIdMapping
import com.nuvio.app.features.metadata.AnimeIdMappingRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal expect object AnimeFillerCacheStorage {
    fun load(): String?
    fun save(payload: String)
}

/** What identifies a show for the filler lookup: its meta id plus whichever external ids it carries. */
data class AnimeFillerLookup(
    val metaId: String,
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val malId: String? = null,
)

/**
 * Filler / canon categories per episode, from AnimeFillerList (animefillerlist.com), keyed by video
 * id. Compass (Harbor) shows the same site's data as a badge; here it also drives "skip filler" in
 * next-episode autoplay.
 *
 * The show page is found by the anime-list's anime-planet slug, which AnimeFillerList uses for its
 * own URLs (naruto, one-piece, detective-conan, boruto-naruto-next-generations), so no title search
 * is needed and a title that only looks alike can never pick another show's page. Only TV entries
 * are tried: OVAs, films and specials have no pages there.
 *
 * Episodes are paired with the page by [matchAnimeFillerRows]; read it before changing anything
 * about which episode a category lands on.
 *
 * Pages are cached for 14 days (a missing page for one), on disk, since a long-runner's page is
 * half a megabyte and the site asks crawlers for 10 s between requests, which every fetch here
 * honours.
 */
object AnimeFillerRepository {
    private val log = Logger.withTag("AnimeFiller")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private const val BASE_URL = "https://www.animefillerlist.com/shows/"
    private const val PAGE_TTL_MS = 14L * 24L * 60L * 60L * 1000L
    private const val MISSING_PAGE_TTL_MS = 24L * 60L * 60L * 1000L
    private const val CRAWL_DELAY_MS = 10_000L
    private const val FETCH_TIMEOUT_MS = 20_000L
    private const val MAX_SLUGS = 3
    private const val MAX_CACHED_PAGES = 200
    private const val MAX_CACHED_RESULTS = 32

    @Serializable
    private data class StoredPage(
        val savedAtMs: Long,
        /** Null when the page does not exist. */
        val rows: List<AnimeFillerListRow>? = null,
    )

    private val pageMutex = Mutex()
    private var pages: MutableMap<String, StoredPage>? = null
    private val pageFetches = mutableMapOf<String, Deferred<List<AnimeFillerListRow>?>>()

    private val fetchGate = Mutex()
    private var lastFetchAtMs = 0L

    private val persister = CoalescingCachePersister(tag = "animeFiller") {
        val snapshot = pageMutex.withLock { pages?.toMap() } ?: return@CoalescingCachePersister
        AnimeFillerCacheStorage.save(json.encodeToString(snapshot))
    }

    private val _results = MutableStateFlow<Map<String, Map<String, AnimeEpisodeKind>>>(emptyMap())

    /** Results computed this session, keyed by [AnimeFillerLookup.metaId]. */
    val results: StateFlow<Map<String, Map<String, AnimeEpisodeKind>>> = _results.asStateFlow()

    fun peek(metaId: String): Map<String, AnimeEpisodeKind>? = _results.value[metaId]

    /**
     * Episode categories for [videos] (video id -> kind). Empty when the show is not anime, has no
     * AnimeFillerList page, or the page does not pair with this episode list.
     */
    suspend fun episodeKinds(lookup: AnimeFillerLookup, videos: List<MetaVideo>): Map<String, AnimeEpisodeKind> {
        if (videos.isEmpty()) return emptyMap()
        val slugs = withContext(Dispatchers.Default) { slugsFor(lookup) }
        if (slugs.isEmpty()) return emptyMap()

        val kinds = LinkedHashMap<String, AnimeEpisodeKind>()
        val conflicts = HashSet<String>()
        for ((index, slug) in slugs.withIndex()) {
            val rows = pageRows(slug) ?: continue
            val matches = withContext(Dispatchers.Default) { matchAnimeFillerRows(videos, rows) }
            matches.forEach { (videoId, match) ->
                val kind = match.row.kind ?: return@forEach
                val previous = kinds.put(videoId, kind)
                if (previous != null && previous != kind) conflicts += videoId
            }
            log.i {
                "slug=$slug meta=${lookup.metaId} videos=${videos.size} rows=${rows.size} matched=${matches.size} " +
                    "basis=${matches.values.groupingBy { it.basis }.eachCount()}"
            }
            // A sibling entry's page is only worth a request when this one ends before the show does.
            val more = index < slugs.lastIndex && hasEpisodesAfterPage(videos, rows)
            if (!more) break
        }
        // Two pages disagreeing about one episode: believe neither.
        conflicts.forEach(kinds::remove)
        _results.update { current ->
            val next = LinkedHashMap(current)
            next.remove(lookup.metaId)
            next[lookup.metaId] = kinds
            while (next.size > MAX_CACHED_RESULTS) next.remove(next.keys.first())
            next
        }
        return kinds
    }

    /** Writes any pending cache changes now. For the exit path. */
    suspend fun flushPendingWrites() {
        persister.flush()
    }

    /** AnimeFillerList slugs to try for [lookup], most specific first. */
    internal fun slugsFor(lookup: AnimeFillerLookup): List<String> {
        val entries = ArrayList<AnimeIdMapping>()
        nativeEntry(lookup.metaId)?.let(entries::add)
        lookup.malId?.trim()?.toIntOrNull()?.let { mal ->
            AnimeIdMappingRepository.entryForNativeIds(mal = mal)?.let(entries::add)
        }
        val imdb = lookup.imdbId?.trim()?.substringBefore(':')?.takeIf { it.startsWith("tt") }
            ?: lookup.metaId.substringBefore(':').takeIf { it.startsWith("tt") }
        imdb?.let { entries += AnimeIdMappingRepository.entriesForImdb(it) }
        val tmdb = lookup.tmdbId
            ?: lookup.metaId.takeIf { it.startsWith("tmdb:", ignoreCase = true) }
                ?.split(':')?.getOrNull(1)?.toIntOrNull()
        tmdb?.let { entries += AnimeIdMappingRepository.entriesForTmdb(it, isTv = true) }
        return entries
            .filter { it.type.equals("TV", ignoreCase = true) }
            .mapNotNull { it.animePlanetId?.trim()?.lowercase()?.takeIf(::isSafeSlug) }
            .distinct()
            .take(MAX_SLUGS)
    }

    private fun nativeEntry(metaId: String): AnimeIdMapping? {
        val parts = metaId.trim().split(':')
        val value = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return when (parts[0].lowercase()) {
            "kitsu" -> AnimeIdMappingRepository.entryForNativeIds(kitsu = value)
            "mal", "myanimelist" -> AnimeIdMappingRepository.entryForNativeIds(mal = value)
            "al", "anilist" -> AnimeIdMappingRepository.entryForNativeIds(anilist = value)
            "anidb" -> AnimeIdMappingRepository.entryForNativeIds(anidb = value)
            else -> null
        }
    }

    private fun isSafeSlug(slug: String): Boolean =
        slug.isNotEmpty() && slug.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }

    private suspend fun pageRows(slug: String): List<AnimeFillerListRow>? {
        val now = System.currentTimeMillis()
        val deferred = pageMutex.withLock {
            ensureLoaded()[slug]?.let { stored ->
                val ttl = if (stored.rows == null) MISSING_PAGE_TTL_MS else PAGE_TTL_MS
                if (now - stored.savedAtMs <= ttl) return stored.rows
            }
            pageFetches[slug] ?: scope.async { fetchAndStore(slug) }.also { pageFetches[slug] = it }
        }
        return deferred.await()
    }

    private suspend fun fetchAndStore(slug: String): List<AnimeFillerListRow>? {
        try {
            val fetched = fetchPage(slug)
            if (fetched != null) {
                val rows = fetched.getOrNull()
                pageMutex.withLock {
                    val map = ensureLoaded()
                    map.remove(slug)
                    map[slug] = StoredPage(savedAtMs = System.currentTimeMillis(), rows = rows)
                    while (map.size > MAX_CACHED_PAGES) {
                        map.entries.minByOrNull { it.value.savedAtMs }?.let { map.remove(it.key) } ?: break
                    }
                }
                persister.schedule()
                return rows
            }
            return null
        } finally {
            pageMutex.withLock { pageFetches.remove(slug) }
        }
    }

    /**
     * The page's rows; `Result.failure` when the page does not exist (cached as missing), null when
     * the request itself failed (not cached, tried again next time).
     */
    private suspend fun fetchPage(slug: String): Result<List<AnimeFillerListRow>>? =
        fetchGate.withLock {
            val wait = lastFetchAtMs + CRAWL_DELAY_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            try {
                val response = httpRequestRaw(
                    method = "GET",
                    url = BASE_URL + slug,
                    headers = mapOf("Accept" to "text/html"),
                    body = "",
                    allowLargeResponse = true,
                    callTimeoutMs = FETCH_TIMEOUT_MS,
                )
                when {
                    response.status == 404 -> Result.failure(NoSuchElementException(slug))
                    response.status !in 200..299 -> {
                        log.w { "slug=$slug HTTP ${response.status}" }
                        null
                    }
                    else -> {
                        val rows = withContext(Dispatchers.Default) { parseAnimeFillerListPage(response.body) }
                        // A page that exists but has no table is treated like a missing one.
                        if (rows.isEmpty()) Result.failure(NoSuchElementException(slug)) else Result.success(rows)
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                log.w { "slug=$slug fetch failed: ${error.message}" }
                null
            } finally {
                lastFetchAtMs = System.currentTimeMillis()
            }
        }

    private fun ensureLoaded(): MutableMap<String, StoredPage> {
        pages?.let { return it }
        val loaded = runCatching {
            AnimeFillerCacheStorage.load()
                ?.let { json.decodeFromString<Map<String, StoredPage>>(it) }
                .orEmpty()
        }.onFailure { error ->
            log.w { "Discarding unreadable filler cache: ${error.message}" }
        }.getOrDefault(emptyMap())
        return LinkedHashMap(loaded).also { pages = it }
    }
}

/** Whether skip-filler should pass over [videoId]: only episodes AnimeFillerList calls pure filler. */
fun Map<String, AnimeEpisodeKind>.isSkippableFiller(videoId: String?): Boolean =
    videoId != null && this[videoId] == AnimeEpisodeKind.Filler
