package com.nuvio.app.features.discover

import co.touchlab.kermit.Logger
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.tmdb.TmdbHttp
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.tmdb.buildTmdbUrl
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.text.Normalizer
import java.time.LocalDate

/*
 * Discover's built-in TMDB browser, ported from the Nuvio Tracker site's browse.ts: TMDB's
 * /discover with the filters browsing needs — studio, network, language, certification, release
 * status, where a title streams — plus "Movies & shows" (each side asked what it can answer) and a
 * filterable Trending. The addon protocol has none of this; an addon catalog only offers its one
 * "genre" extra.
 */

@Serializable
enum class TmdbBrowseMedia { Movie, Tv, All }

@Serializable
enum class TmdbBrowseSort { Popular, Trending, TopRated, MostVoted, Newest, Oldest }

@Serializable
enum class TmdbBrowseStatus { Any, InCinemas, HomeRelease, Returning, Ended, Upcoming }

@Serializable
data class TmdbBrowseCompany(val id: Int, val name: String)

@Serializable
data class TmdbBrowseQuery(
    val media: TmdbBrowseMedia = TmdbBrowseMedia.Movie,
    val sort: TmdbBrowseSort = TmdbBrowseSort.Popular,
    val genres: List<Int> = emptyList(),
    val allGenres: Boolean = false,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val minRating: Double? = null,
    val minVotes: Int? = null,
    val runtimeMin: Int? = null,
    val runtimeMax: Int? = null,
    val language: String? = null,
    val status: TmdbBrowseStatus = TmdbBrowseStatus.Any,
    val certification: String? = null,
    val companies: List<TmdbBrowseCompany> = emptyList(),
    val networks: List<Int> = emptyList(),
    val providers: List<Int> = emptyList(),
    val releasedWithinDays: Int? = null,
    val withoutGenres: List<Int> = emptyList(),
) {
    /** Any filter beyond the sort and the media type — what "Clear filters" resets. */
    val hasFilters: Boolean
        get() = this != TmdbBrowseQuery(media = media, sort = sort)

    fun clearedFilters(): TmdbBrowseQuery = TmdbBrowseQuery(media = media, sort = sort)
}

data class TmdbBrowseGenre(val id: Int, val name: String)

data class TmdbBrowseProvider(val id: Int, val name: String, val logoUrl: String?)

data class TmdbBrowseNetwork(val id: Int, val name: String)

data class TmdbBrowseStudio(val id: Int, val name: String, val country: String?)

data class TmdbBrowseOptions(
    val genres: List<TmdbBrowseGenre> = emptyList(),
    val providers: List<TmdbBrowseProvider> = emptyList(),
    val networks: List<TmdbBrowseNetwork> = emptyList(),
    val region: String = "US",
)

data class TmdbBrowsePage(
    val items: List<MetaPreview>,
    val nextPage: Int?,
    val total: Int,
)

/** TV networks offered as quick picks (TMDB network ids, checked against TMDB in the tracker). */
val TmdbBrowseNetworks: List<TmdbBrowseNetwork> = listOf(
    213 to "Netflix", 49 to "HBO", 3186 to "HBO Max", 2552 to "Apple TV", 2739 to "Disney+",
    1024 to "Prime Video", 453 to "Hulu", 4330 to "Paramount+", 3353 to "Peacock", 174 to "AMC",
    88 to "FX", 67 to "Showtime", 318 to "STARZ", 2 to "ABC", 6 to "NBC", 16 to "CBS", 19 to "FOX",
    71 to "The CW", 80 to "Adult Swim", 1112 to "Crunchyroll", 4 to "BBC One", 26 to "Channel 4",
    9 to "ITV1",
).map { (id, name) -> TmdbBrowseNetwork(id, name) }

private const val DAY_MS = 86_400_000L

/**
 * The /discover parameters for one page of [query] — a straight port of the tracker's tmdbParams.
 * [today] is an ISO date; [media] is the concrete side being asked (never All).
 */
internal fun tmdbBrowseParams(
    query: TmdbBrowseQuery,
    media: TmdbBrowseMedia,
    page: Int,
    region: String,
    today: LocalDate,
): Map<String, String> {
    val tv = media == TmdbBrowseMedia.Tv
    val date = if (tv) "first_air_date" else "primary_release_date"
    val todayIso = today.toString()
    val p = linkedMapOf("include_adult" to "false", "page" to page.toString())
    p["sort_by"] = when (query.sort) {
        TmdbBrowseSort.Popular, TmdbBrowseSort.Trending -> "popularity.desc"
        TmdbBrowseSort.TopRated -> "vote_average.desc"
        TmdbBrowseSort.MostVoted -> "vote_count.desc"
        TmdbBrowseSort.Newest -> "$date.desc"
        TmdbBrowseSort.Oldest -> "$date.asc"
    }
    if (query.genres.isNotEmpty()) p["with_genres"] = query.genres.joinToString(if (query.allGenres) "," else "|")
    if (query.withoutGenres.isNotEmpty()) p["without_genres"] = query.withoutGenres.joinToString(",")
    query.yearFrom?.let { p["$date.gte"] = "$it-01-01" }
    query.yearTo?.let { p["$date.lte"] = "$it-12-31" }
    query.minRating?.let { p["vote_average.gte"] = it.formatNumber() }
    val votes = query.minVotes ?: if (query.sort == TmdbBrowseSort.TopRated) RATING_SORT_MIN_VOTES else null
    votes?.let { p["vote_count.gte"] = it.toString() }
    query.runtimeMin?.let { p["with_runtime.gte"] = it.toString() }
    query.runtimeMax?.let { p["with_runtime.lte"] = it.toString() }
    query.language?.let { p["with_original_language"] = it }
    if (query.companies.isNotEmpty()) p["with_companies"] = query.companies.joinToString("|") { it.id.toString() }
    if (tv && query.networks.isNotEmpty()) p["with_networks"] = query.networks.joinToString("|")
    if (query.providers.isNotEmpty()) {
        p["with_watch_providers"] = query.providers.joinToString("|")
        p["watch_region"] = region
        p["with_watch_monetization_types"] = "flatrate|free|ads"
    }
    if (!tv && query.certification != null) {
        p["certification_country"] = "US"
        p["certification"] = query.certification
    }
    query.releasedWithinDays?.let { days ->
        p["$date.gte"] = today.minusDays(days.toLong()).toString()
        p["$date.lte"] = todayIso
    }
    // "Newest" means out already: otherwise announced titles years off lead it.
    if (query.sort == TmdbBrowseSort.Newest && p["$date.lte"] == null) p["$date.lte"] = todayIso
    when (query.status) {
        TmdbBrowseStatus.InCinemas -> if (!tv) {
            p["with_release_type"] = "2|3"
            p["region"] = region
        }
        TmdbBrowseStatus.HomeRelease -> if (!tv) p["with_release_type"] = "4|5|6"
        TmdbBrowseStatus.Returning -> if (tv) p["with_status"] = "0"
        TmdbBrowseStatus.Ended -> if (tv) p["with_status"] = "3|4"
        TmdbBrowseStatus.Upcoming -> {
            p["$date.gte"] = today.plusDays(1).toString()
            p.remove("$date.lte")
            if (query.sort == TmdbBrowseSort.Popular) p["sort_by"] = "popularity.desc"
        }
        TmdbBrowseStatus.Any -> Unit
    }
    return p
}

private fun Double.formatNumber(): String =
    if (this == kotlin.math.floor(this)) toLong().toString() else toString()

/** Interleaves two lists, a[0], b[0], a[1], b[1]…, running on with the longer one. */
internal fun <T> interleave(a: List<T>, b: List<T>): List<T> = buildList {
    for (i in 0 until maxOf(a.size, b.size)) {
        a.getOrNull(i)?.let(::add)
        b.getOrNull(i)?.let(::add)
    }
}

/** Lower-cased words with accents stripped, for matching a typed name. */
internal fun browseWords(text: String?): List<String> =
    Normalizer.normalize(text.orEmpty(), Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotEmpty() }

/** Every typed word is a word of the name, or (three letters on) the start of one. */
internal fun browseNameRelevant(query: String, name: String?): Boolean {
    val wanted = browseWords(query)
    if (wanted.isEmpty()) return true
    val have = browseWords(name)
    return wanted.all { w -> have.any { h -> h == w || (w.length >= 3 && h.startsWith(w)) } }
}

/** The region for "Streaming on" and "In cinemas": the system locale's country, else US. */
fun tmdbBrowseRegion(): String =
    java.util.Locale.getDefault().country.uppercase().takeIf { Regex("^[A-Z]{2}$").matches(it) } ?: "US"

object TmdbBrowse {
    private val log = Logger.withTag("TmdbBrowse")
    private val json = Json { ignoreUnknownKeys = true }

    val isAvailable: Boolean
        get() = TmdbSettingsRepository.snapshot().apiKey.isNotBlank()

    // ---- Vocabularies, cached a day ----

    private val vocabMutex = Mutex()
    private val vocab = mutableMapOf<String, Pair<Long, Any>>()

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cachedVocab(key: String, load: suspend () -> T?): T? {
        val now = System.currentTimeMillis()
        vocabMutex.withLock { vocab[key] }?.let { (at, value) -> if (now - at < DAY_MS) return value as T }
        val value = load() ?: return null
        vocabMutex.withLock { vocab[key] = now to value }
        return value
    }

    suspend fun genres(media: TmdbBrowseMedia): List<TmdbBrowseGenre> {
        if (media == TmdbBrowseMedia.All) {
            // Both lists, merged by name.
            val byName = linkedMapOf<String, TmdbBrowseGenre>()
            (genres(TmdbBrowseMedia.Movie) + genres(TmdbBrowseMedia.Tv)).forEach { byName.putIfAbsent(it.name, it) }
            return byName.values.sortedBy { it.name }
        }
        return TmdbService.fetchGenreNames(media.apiName).map { (id, name) -> TmdbBrowseGenre(id, name) }
    }

    /**
     * Subscription services in the region, TMDB's order; rental stores and add-on channels
     * ("… Amazon Channel") left out.
     */
    suspend fun providers(media: TmdbBrowseMedia, region: String): List<TmdbBrowseProvider> {
        val side = if (media == TmdbBrowseMedia.Tv) TmdbBrowseMedia.Tv else TmdbBrowseMedia.Movie
        return cachedVocab("providers:${side.apiName}:$region") {
            val body = get<TmdbWatchProvidersResponse>("watch/providers/${side.apiName}", mapOf("watch_region" to region))
                ?: return@cachedVocab null
            body.results
                .filterNot { NotAService.containsMatchIn(it.providerName) }
                .sortedBy { it.displayPriorities[region] ?: 999 }
                .take(16)
                .map { TmdbBrowseProvider(it.providerId, it.providerName, TmdbService.tmdbImageUrl(it.logoPath, "w92")) }
        }.orEmpty()
    }

    suspend fun options(media: TmdbBrowseMedia, region: String): TmdbBrowseOptions = coroutineScope {
        val genres = async { runCatching { genres(media) }.getOrDefault(emptyList()) }
        val providers = async { runCatching { providers(media, region) }.getOrDefault(emptyList()) }
        TmdbBrowseOptions(
            genres = genres.await(),
            providers = providers.await(),
            networks = if (media == TmdbBrowseMedia.Movie) emptyList() else TmdbBrowseNetworks,
            region = region,
        )
    }

    // ---- Studios by name, ranked by catalogue size ----

    private val companySizes = mutableMapOf<Int, Pair<Long, Int>>()

    private suspend fun companySize(id: Int): Int {
        val now = System.currentTimeMillis()
        vocabMutex.withLock { companySizes[id] }?.let { (at, n) -> if (now - at < 7 * DAY_MS) return n }
        val (movie, tv) = coroutineScope {
            listOf("movie", "tv").map { media ->
                async { get<TmdbBrowseResponse>("discover/$media", mapOf("with_companies" to id.toString(), "include_adult" to "false")) }
            }.awaitAll()
        }
        val n = (movie?.totalResults ?: 0) + (tv?.totalResults ?: 0)
        if (movie != null && tv != null) vocabMutex.withLock {
            if (companySizes.size > 5000) companySizes.clear()
            companySizes[id] = now to n
        }
        return n
    }

    suspend fun searchStudios(query: String): List<TmdbBrowseStudio> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()
        val encoded = URLEncoder.encode(trimmed, Charsets.UTF_8).replace("+", "%20")
        val res = get<TmdbCompanyPageResponse>("search/company", mapOf("query" to encoded, "page" to "1"))
            ?: return emptyList()
        val candidates = res.results.filter { browseNameRelevant(trimmed, it.name) }.take(20)
        val sizes = coroutineScope { candidates.map { async { companySize(it.id) } }.awaitAll() }
        return candidates.zip(sizes)
            .filter { (_, n) -> n > 0 }
            .sortedByDescending { (_, n) -> n }
            .take(6)
            .map { (c, _) -> TmdbBrowseStudio(c.id, c.name, c.originCountry?.takeIf(String::isNotBlank)) }
    }

    // ---- The browser ----

    suspend fun browse(query: TmdbBrowseQuery, page: Int, region: String, pages: Int = 2): TmdbBrowsePage {
        if (query.media == TmdbBrowseMedia.All) return browseBoth(query, page, region)
        if (query.sort == TmdbBrowseSort.Trending) return trendingBrowse(query, region)
        val media = query.media
        val today = LocalDate.parse(CurrentDateProvider.todayIsoDate())
        val results = coroutineScope {
            (0 until pages).map { i ->
                async { get<TmdbBrowseResponse>("discover/${media.apiName}", tmdbBrowseParams(query, media, page + i, region, today)) }
            }.awaitAll()
        }
        val seen = HashSet<Int>()
        val items = results.flatMap { it?.results.orEmpty() }
            .filter { it.id > 0 && seen.add(it.id) }
            .map { it.toMetaPreview(media) }
        val totalPages = results.firstOrNull()?.totalPages ?: 0
        val last = page + pages - 1
        return TmdbBrowsePage(
            items = items,
            nextPage = if (last < minOf(totalPages, 500)) last + 1 else null,
            total = results.firstOrNull()?.totalResults ?: 0,
        )
    }

    /**
     * Movies and shows together. Each side gets what it can answer, as the tracker does: genres go
     * by name to that side's own ids (films have "Action", TV "Action & Adventure"), and a filter
     * only one side has — a network, a TV status, a film certification or cinema release — leaves
     * the other side out rather than quietly ignoring it. Queried side by side and interleaved.
     */
    private suspend fun browseBoth(query: TmdbBrowseQuery, page: Int, region: String): TmdbBrowsePage {
        val (mg, tg) = coroutineScope {
            val m = async { runCatching { TmdbService.fetchGenreNames("movie") }.getOrDefault(emptyMap()) }
            val t = async { runCatching { TmdbService.fetchGenreNames("tv") }.getOrDefault(emptyMap()) }
            m.await() to t.await()
        }
        fun nameOf(id: Int): String? = mg[id] ?: tg[id]
        fun side(media: TmdbBrowseMedia): TmdbBrowseQuery? {
            val list = if (media == TmdbBrowseMedia.Movie) mg else tg
            fun map(ids: List<Int>): List<Int> = ids.mapNotNull(::nameOf).mapNotNull { n ->
                list.entries.firstOrNull { it.value == n }?.key
            }
            val wanted = map(query.genres)
            if (query.genres.isNotEmpty() && (if (query.allGenres) wanted.size < query.genres.size else wanted.isEmpty())) return null
            if (media == TmdbBrowseMedia.Movie &&
                (query.networks.isNotEmpty() || query.status == TmdbBrowseStatus.Returning || query.status == TmdbBrowseStatus.Ended)
            ) return null
            if (media == TmdbBrowseMedia.Tv &&
                (query.certification != null || query.status == TmdbBrowseStatus.InCinemas || query.status == TmdbBrowseStatus.HomeRelease)
            ) return null
            return query.copy(media = media, genres = wanted, withoutGenres = map(query.withoutGenres))
        }
        val empty = TmdbBrowsePage(emptyList(), null, 0)
        val (m, t) = coroutineScope {
            listOf(side(TmdbBrowseMedia.Movie), side(TmdbBrowseMedia.Tv)).map { q ->
                async { if (q == null) empty else browse(q, page, region, pages = 1) }
            }.awaitAll()
        }
        return TmdbBrowsePage(
            items = interleave(m.items, t.items),
            nextPage = if (m.nextPage != null || t.nextPage != null) maxOf(m.nextPage ?: 0, t.nextPage ?: 0) else null,
            total = m.total + t.total,
        )
    }

    // ---- Trending, filtered ----
    // TMDB's discover can't sort by trend, so "Trending" is TMDB's weekly trending list (its first
    // 300), in its order. What the list carries — genres, language, dates, rating, votes — is
    // filtered on directly; what it doesn't (streaming service, network, studio, certification,
    // length, status) is applied by keeping only the trending titles TMDB's discover returns for
    // those filters (its 200 most popular, which is where trending titles sit).

    private const val TRENDING_PAGES = 15
    private const val MATCH_PAGES = 10
    private val listMutex = Mutex()
    private val listCache = mutableMapOf<String, Pair<Long, List<TmdbBrowseItem>>>()

    private suspend fun cachedList(key: String, ttlMs: Long, load: suspend () -> List<TmdbBrowseItem>?): List<TmdbBrowseItem> {
        val now = System.currentTimeMillis()
        listMutex.withLock { listCache[key] }?.let { (at, value) -> if (now - at < ttlMs) return value }
        val value = load() ?: return emptyList()
        listMutex.withLock {
            if (listCache.size > 500) listCache.clear()
            listCache[key] = now to value
        }
        return value
    }

    private suspend fun trendingPool(media: TmdbBrowseMedia): List<TmdbBrowseItem> =
        cachedList("trending:${media.apiName}", 3 * 3_600_000L) {
            val pages = coroutineScope {
                (1..TRENDING_PAGES).map { page ->
                    async { get<TmdbBrowseResponse>("trending/${media.apiName}/week", mapOf("page" to page.toString())) }
                }.awaitAll()
            }
            if (pages.all { it == null }) return@cachedList null
            val seen = HashSet<Int>()
            pages.flatMap { it?.results.orEmpty() }.filter { !it.adult && it.id > 0 && seen.add(it.id) }
        }

    private suspend fun trendingBrowse(query: TmdbBrowseQuery, region: String): TmdbBrowsePage {
        val media = query.media
        val tv = media == TmdbBrowseMedia.Tv
        val today = LocalDate.parse(CurrentDateProvider.todayIsoDate())
        val todayIso = today.toString()
        fun dateOf(r: TmdbBrowseItem): String = (if (tv) r.firstAirDate else r.releaseDate).orEmpty()
        var rows = trendingPool(media).filter { r ->
            val g = r.genreIds
            if (query.genres.isNotEmpty() &&
                !(if (query.allGenres) query.genres.all { it in g } else query.genres.any { it in g })
            ) return@filter false
            if (query.withoutGenres.any { it in g }) return@filter false
            if (query.language != null && r.originalLanguage != query.language) return@filter false
            val d = dateOf(r)
            val year = d.take(4).toIntOrNull()
            if (query.yearFrom != null && (year == null || year < query.yearFrom)) return@filter false
            if (query.yearTo != null && (year == null || year > query.yearTo)) return@filter false
            if (query.releasedWithinDays != null &&
                (d.isEmpty() || d < today.minusDays(query.releasedWithinDays.toLong()).toString() || d > todayIso)
            ) return@filter false
            if (query.status == TmdbBrowseStatus.Upcoming && d <= todayIso) return@filter false
            if (query.minRating != null && r.voteAverage < query.minRating) return@filter false
            if (query.minVotes != null && r.voteCount < query.minVotes) return@filter false
            true
        }

        val remote = query.providers.isNotEmpty() || query.companies.isNotEmpty() ||
            (tv && query.networks.isNotEmpty()) || (!tv && query.certification != null) ||
            query.runtimeMin != null || query.runtimeMax != null ||
            query.status in setOf(TmdbBrowseStatus.InCinemas, TmdbBrowseStatus.HomeRelease, TmdbBrowseStatus.Returning, TmdbBrowseStatus.Ended)
        if (remote) {
            val q = query.copy(
                sort = TmdbBrowseSort.Popular, genres = emptyList(), withoutGenres = emptyList(), language = null,
                yearFrom = null, yearTo = null, minRating = null, minVotes = null, releasedWithinDays = null,
            )
            val params = tmdbBrowseParams(q, media, 1, region, today)
            val matches = cachedList("match:${media.apiName}:$params", 3_600_000L) {
                coroutineScope {
                    (1..MATCH_PAGES).map { page ->
                        async { get<TmdbBrowseResponse>("discover/${media.apiName}", params + ("page" to page.toString())) }
                    }.awaitAll()
                }.flatMap { it?.results.orEmpty() }
            }
            val allowed = matches.mapTo(HashSet()) { it.id }
            rows = rows.filter { it.id in allowed }
        }
        return TmdbBrowsePage(items = rows.map { it.toMetaPreview(media) }, nextPage = null, total = rows.size)
    }

    // ---- HTTP ----

    private suspend inline fun <reified T> get(endpoint: String, query: Map<String, String>): T? {
        val apiKey = TmdbSettingsRepository.snapshot().apiKey.trim().takeIf(String::isNotBlank) ?: return null
        val url = buildTmdbUrl(endpoint = endpoint, apiKey = apiKey, query = query)
        return runCatching { json.decodeFromString<T>(TmdbHttp.getText(url)) }
            .onFailure { error -> log.w { "TMDB browse request failed for $endpoint: ${error.message}" } }
            .getOrNull()
    }
}

internal val TmdbBrowseMedia.apiName: String
    get() = when (this) {
        TmdbBrowseMedia.Movie -> "movie"
        TmdbBrowseMedia.Tv -> "tv"
        TmdbBrowseMedia.All -> "all"
    }

private val NotAService = Regex(
    "channel|store|google play|justwatch|amazon video|youtube|fandango|vudu|microsoft|spectrum|directv|philo|fubo|sling",
    RegexOption.IGNORE_CASE,
)

/** Same card as Discover's generated rows build from a TMDB list result. */
private fun TmdbBrowseItem.toMetaPreview(media: TmdbBrowseMedia): MetaPreview {
    val date = (releaseDate ?: firstAirDate).orEmpty()
    return MetaPreview(
        id = "tmdb:$id",
        type = if (media == TmdbBrowseMedia.Tv) "series" else "movie",
        name = (title ?: name).orEmpty(),
        poster = TmdbService.tmdbImageUrl(posterPath),
        banner = TmdbService.tmdbImageUrl(backdropPath, size = "w1280"),
        description = overview,
        releaseInfo = date.take(4).takeIf { it.length == 4 },
        popularity = popularity,
        imdbRating = tmdbVoteAverageLabel(voteAverage),
    )
}

@Serializable
private data class TmdbBrowseResponse(
    val results: List<TmdbBrowseItem> = emptyList(),
    @SerialName("total_pages") val totalPages: Int = 0,
    @SerialName("total_results") val totalResults: Int = 0,
)

@Serializable
internal data class TmdbBrowseItem(
    val id: Int = 0,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val popularity: Double = 0.0,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("vote_count") val voteCount: Int = 0,
    @SerialName("original_language") val originalLanguage: String? = null,
    val adult: Boolean = false,
)

@Serializable
private data class TmdbWatchProvidersResponse(val results: List<TmdbWatchProvider> = emptyList())

@Serializable
private data class TmdbWatchProvider(
    @SerialName("provider_id") val providerId: Int = 0,
    @SerialName("provider_name") val providerName: String = "",
    @SerialName("logo_path") val logoPath: String? = null,
    @SerialName("display_priorities") val displayPriorities: Map<String, Int> = emptyMap(),
)

@Serializable
private data class TmdbCompanyPageResponse(val results: List<TmdbCompanyHit> = emptyList())

@Serializable
private data class TmdbCompanyHit(
    val id: Int = 0,
    val name: String = "",
    @SerialName("origin_country") val originCountry: String? = null,
)
