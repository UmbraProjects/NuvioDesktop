package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val SIMKL_RELATED_BASE_URL = "https://api.simkl.com"
private const val RELATED_LIMIT = 20
private const val RELATED_CACHE_TTL_MS = 10 * 60_000L

/** The id prefixes SIMKL's `/search/id` accepts, as they appear in our content ids. */
private val SIMKL_LOOKUP_SOURCES = setOf("imdb", "tmdb", "tvdb", "mal", "anidb", "anilist", "kitsu", "simkl")

@Serializable
private data class SimklRelatedSearchHit(
    val type: String? = null,
    val ids: SimklMediaIds? = null,
)

@Serializable
private data class SimklRelatedItem(
    val title: String? = null,
    @SerialName("en_title") val enTitle: String? = null,
    val year: Int? = null,
    val poster: String? = null,
    val fanart: String? = null,
    val type: String? = null,
    @SerialName("anime_type") val animeType: String? = null,
    val ids: SimklMediaIds? = null,
)

@Serializable
private data class SimklRelatedDetail(
    val similar: List<SimklRelatedItem>? = null,
    @SerialName("users_recommendations") val usersRecommendations: List<SimklRelatedItem>? = null,
)

/**
 * More Like This from SIMKL (upstream af1298eb4 and 1b2f7a99b, skoruppa): the title's community
 * recommendations, then its similar titles, up to [RELATED_LIMIT]. Public catalog reads, so they
 * go through [simklRequest] unauthenticated and share its edge caching and back-off.
 *
 * Ids are mapped the way the rest of the SIMKL integration maps them: franchise-first for anime
 * (so addons can resolve them), imdb first otherwise.
 */
object SimklRelatedRepository {
    private val log = Logger.withTag("SimklRelated")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val lock = Mutex()
    private val cache = mutableMapOf<String, Pair<Long, List<MetaPreview>>>()

    suspend fun getRelated(
        meta: MetaDetails,
        fallbackItemId: String? = null,
        fallbackItemType: String? = null,
    ): List<MetaPreview> {
        val (source, id) = lookupParam(meta.id)
            ?: meta.imdbId?.takeIf(String::isNotBlank)?.let { "imdb" to it }
            ?: lookupParam(fallbackItemId)
            ?: return emptyList()
        val typeHint = meta.type.ifBlank { fallbackItemType.orEmpty() }
        val cacheKey = "$source:$id:$typeHint"
        lock.withLock {
            cache[cacheKey]?.let { (at, items) ->
                if (System.currentTimeMillis() - at in 0..RELATED_CACHE_TTL_MS) return items
            }
        }
        val (endpoint, simklId) = resolve(source, id, typeHint) ?: return emptyList()
        val detail = get<SimklRelatedDetail>("/$endpoint/$simklId?extended=full") ?: return emptyList()
        val raw = buildList {
            detail.usersRecommendations.orEmpty().forEach(::add)
            detail.similar.orEmpty().forEach { item ->
                if (none { it.ids?.simkl == item.ids?.simkl }) add(item)
            }
        }.take(RELATED_LIMIT)
        val items = raw.mapNotNull(::toPreview).distinctBy { "${it.type}:${it.id}" }
        lock.withLock { cache[cacheKey] = System.currentTimeMillis() to items }
        return items
    }

    fun clearCache() {
        cache.clear()
    }

    /** SIMKL's endpoint ("movies", "tv" or "anime") and id for a content id. */
    private suspend fun resolve(source: String, id: String, typeHint: String): Pair<String, Int>? {
        val hits = get<List<SimklRelatedSearchHit>>("/search/id?$source=${simklUrlEncode(id)}").orEmpty()
        val wanted = when (typeHint.trim().lowercase()) {
            "movie", "film" -> setOf("movie")
            "series", "tv", "show" -> setOf("show", "tv")
            "anime" -> setOf("anime")
            else -> emptySet()
        }
        val hit = hits.firstOrNull { it.type in wanted } ?: hits.firstOrNull() ?: return null
        val simklId = hit.ids?.simkl ?: return null
        val endpoint = when (hit.type) {
            "movie" -> "movies"
            "show", "tv" -> "tv"
            else -> "anime"
        }
        return endpoint to simklId
    }

    private suspend inline fun <reified T> get(path: String): T? = try {
        val response = simklRequest(
            method = "GET",
            url = SimklAuthRepository.appendParams("$SIMKL_RELATED_BASE_URL$path"),
            authenticated = false,
        )
        if (response.status in 200..299) {
            json.decodeFromString<T>(response.body)
        } else {
            log.w { "SIMKL related request for ${path.substringBefore('?')} returned ${response.status}" }
            null
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w { "SIMKL related request failed for ${path.substringBefore('?')}: ${error.message}" }
        null
    }

    private fun toPreview(item: SimklRelatedItem): MetaPreview? {
        val title = item.enTitle?.takeIf(String::isNotBlank) ?: item.title?.takeIf(String::isNotBlank) ?: return null
        val ids = item.ids ?: return null
        val isMovie = item.type.equals("movie", ignoreCase = true) || item.animeType.equals("movie", ignoreCase = true)
        val isAnime = item.type.equals("anime", ignoreCase = true)
        val contentId = when {
            isAnime && isMovie -> ids.toBestAnimeMovieContentId()
            isAnime -> ids.toBestAnimeContentId()
            else -> ids.toBestContentId()
        } ?: return null
        val poster = item.poster?.trim()?.trim('/')?.takeIf(String::isNotBlank)
        val fanart = item.fanart?.trim()?.trim('/')?.takeIf(String::isNotBlank)
        return MetaPreview(
            id = contentId,
            type = if (isMovie) "movie" else "series",
            name = title,
            poster = poster?.simklPosterUrl(),
            // Wide cards (landscape More Like This) need a banner: the fanart when there is one.
            banner = fanart?.let { "https://simkl.net/fanart/${it}_medium.jpg" },
            posterShape = PosterShape.Poster,
            releaseInfo = item.year?.toString(),
        )
    }

    private fun lookupParam(contentId: String?): Pair<String, String>? {
        val raw = contentId?.trim()?.takeIf(String::isNotBlank) ?: return null
        if (raw.startsWith("tt")) return "imdb" to raw.substringBefore(':')
        val colon = raw.indexOf(':')
        if (colon <= 0) return null
        val prefix = raw.substring(0, colon).lowercase()
        val value = raw.substring(colon + 1).substringBefore(':').takeIf(String::isNotBlank) ?: return null
        return if (prefix in SIMKL_LOOKUP_SOURCES) prefix to value else null
    }
}
