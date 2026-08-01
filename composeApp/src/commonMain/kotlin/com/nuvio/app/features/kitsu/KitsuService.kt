package com.nuvio.app.features.kitsu

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Minimal Kitsu (kitsu.io) title search, used to resolve *anime* local-library items to a native
 * `kitsu:` id. TMDB search is weak for anime (romaji vs. English titles, cour splits, seasons
 * folded into one show); Kitsu indexes the same titles the app's anime stream/scrobble pipeline
 * already speaks, so a Kitsu match drops straight into the existing `kitsu:` handling.
 *
 * Read-only, unauthenticated, and platform-agnostic (built on the shared [httpRequestRaw]).
 */
object KitsuService {
    private val log = Logger.withTag("KitsuService")
    private val json = Json { ignoreUnknownKeys = true }

    private const val BASE_URL = "https://kitsu.io/api/edge"
    // Kitsu is a JSON:API service; it 415s without the vendor Accept type.
    private val headers = mapOf(
        "Accept" to "application/vnd.api+json",
        "Content-Type" to "application/vnd.api+json",
    )

    /**
     * Text search for anime titles. [preferMovie] biases (but does not hard-filter) the ranking
     * toward feature films vs. series, matching the folder the user filed the title under.
     */
    suspend fun searchTitles(query: String, preferMovie: Boolean): List<KitsuSearchResult> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        val url = buildUrl(
            "$BASE_URL/anime",
            mapOf(
                "filter[text]" to trimmed,
                "page[limit]" to "10",
                // Only the fields we actually read, to keep the payload small.
                "fields[anime]" to "canonicalTitle,titles,subtype,startDate,posterImage,synopsis",
            ),
        )

        val response = runCatching {
            httpRequestRaw(method = "GET", url = url, headers = headers, body = "")
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.d { "Kitsu search failed for \"$trimmed\": ${error.message}" }
        }.getOrNull() ?: return emptyList()

        if (response.status !in 200..299) return emptyList()

        val parsed = runCatching { json.decodeFromString<KitsuListResponse>(response.body) }
            .getOrNull() ?: return emptyList()

        return parsed.data.mapNotNull { it.toResult() }
            .sortedByDescending { subtypeRankFor(it.subtype, preferMovie) }
    }

    /** Poster URL for a Kitsu id (used when a match was made by id, without a search payload). */
    suspend fun fetchPosterUrl(kitsuId: Int): String? {
        val url = buildUrl("$BASE_URL/anime/$kitsuId", mapOf("fields[anime]" to "posterImage"))
        val response = runCatching {
            httpRequestRaw(method = "GET", url = url, headers = headers, body = "")
        }.getOrNull() ?: return null
        if (response.status !in 200..299) return null
        return runCatching { json.decodeFromString<KitsuSingleResponse>(response.body) }
            .getOrNull()?.data?.toResult()?.poster
    }

    /**
     * Authoritative episode count for one Kitsu entry.
     *
     * Detail addons are allowed to enrich a native anime entry with franchise metadata, so their
     * video list is not a safe source for operations that must stay inside the selected Kitsu
     * entry (such as season-pack auto-match). This lightweight request asks Kitsu for the one field
     * that owns that boundary.
     */
    suspend fun fetchEpisodeCount(kitsuId: Int): Int? {
        val url = buildUrl("$BASE_URL/anime/$kitsuId", mapOf("fields[anime]" to "episodeCount"))
        val response = runCatching {
            httpRequestRaw(method = "GET", url = url, headers = headers, body = "")
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.d { "Kitsu episode-count lookup failed for $kitsuId: ${error.message}" }
        }.getOrNull() ?: return null
        if (response.status !in 200..299) return null

        return runCatching { json.decodeFromString<KitsuSingleResponse>(response.body) }
            .getOrNull()
            ?.data
            ?.takeIf { it.id?.toIntOrNull() == kitsuId }
            ?.attributes
            ?.episodeCount
            ?.takeIf { it > 0 }
    }

    private fun subtypeRankFor(subtype: String?, preferMovie: Boolean): Int {
        val isMovie = subtype?.equals("movie", ignoreCase = true) == true
        return if (isMovie == preferMovie) 1 else 0
    }

    private fun buildUrl(base: String, params: Map<String, String>): String {
        if (params.isEmpty()) return base
        val query = params.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
        return "$base?$query"
    }

    // Kitsu filter values routinely contain spaces and non-ASCII (Japanese) title text; percent
    // encoding keeps the query string valid without pulling in a platform URL builder.
    private fun encode(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val c = byte.toInt() and 0xFF
            when {
                c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
                    c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code ->
                    append(c.toChar())
                else -> append('%').append(c.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
}

data class KitsuSearchResult(
    val id: Int,
    val title: String,
    // Every title variant Kitsu returned (canonical/romaji, English, en-jp). Auto-matching scores
    // the local folder name against the best of these — a folder may use any of them.
    val matchTitles: List<String>,
    val year: Int?,
    val subtype: String?,
    val poster: String?,
    val synopsis: String?,
) {
    val isMovie: Boolean get() = subtype?.equals("movie", ignoreCase = true) == true
}

@Serializable
private data class KitsuListResponse(val data: List<KitsuAnime> = emptyList())

@Serializable
private data class KitsuSingleResponse(val data: KitsuAnime? = null)

@Serializable
private data class KitsuAnime(
    val id: String? = null,
    val attributes: KitsuAttributes = KitsuAttributes(),
) {
    fun toResult(): KitsuSearchResult? {
        val numericId = id?.toIntOrNull() ?: return null
        val variants = listOfNotNull(
            attributes.titles?.en,
            attributes.canonicalTitle,
            attributes.titles?.enJp,
        ).filter { it.isNotBlank() }.distinct()
        val title = variants.firstOrNull() ?: return null
        return KitsuSearchResult(
            id = numericId,
            title = title,
            matchTitles = variants,
            year = attributes.startDate?.take(4)?.toIntOrNull(),
            subtype = attributes.subtype,
            poster = attributes.posterImage?.let { it.small ?: it.original },
            synopsis = attributes.synopsis,
        )
    }
}

@Serializable
private data class KitsuAttributes(
    val canonicalTitle: String? = null,
    val titles: KitsuTitles? = null,
    val subtype: String? = null,
    val startDate: String? = null,
    val episodeCount: Int? = null,
    val synopsis: String? = null,
    val posterImage: KitsuPosterImage? = null,
)

@Serializable
private data class KitsuTitles(
    val en: String? = null,
    @SerialName("en_jp") val enJp: String? = null,
)

@Serializable
private data class KitsuPosterImage(
    val small: String? = null,
    val original: String? = null,
)
