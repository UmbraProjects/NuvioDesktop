package com.nuvio.app.features.details

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One IMDb "interest": a sub-genre tag ("Folk Horror") together with the genre IMDb files it
 * under ("Horror"). Unlike TMDB keywords, this is a curated taxonomy with the genre link built in.
 */
@Serializable
data class ImdbInterest(
    val name: String,
    val category: String,
)

/**
 * IMDb's sub-genre taxonomy for a title — the "Psychological Drama · Folk Horror · Dark Comedy"
 * line an IMDb title page shows under its genres.
 *
 * TMDB's keywords are folksonomy and read as themes (`grief`, `cult`, `isolation`); the
 * sub-genres the genre hover was built for mostly never appear there. IMDb's interests are the
 * missing layer, and every one names its parent genre, so no curated vocabulary is needed to
 * attach them. Fetched through the same GraphQL edge the episode ratings already use.
 *
 * Results are held per IMDb id for the session, empty results included, and concurrent callers
 * for one id share a request: the Home hero asks for its whole strip at once and the details page
 * asks again for the item that was opened.
 */
internal object ImdbInterestsService {

    suspend fun fetch(imdbId: String): List<ImdbInterest> {
        val id = imdbId.trim()
        if (!id.startsWith("tt", ignoreCase = true)) return emptyList()
        cache[id]?.let { return it }
        val shared = mutex.withLock {
            cache[id]?.let { return it }
            inFlight[id] ?: scope.async {
                try {
                    // Kept between launches with the hero's other facts (see HeroBundleCache).
                    val serializer = kotlinx.serialization.builtins.ListSerializer(ImdbInterest.serializer())
                    val stored = com.nuvio.app.features.home.HeroBundleCache.get("interests", id, serializer)
                    val result = stored ?: request(id)?.also { fresh ->
                        com.nuvio.app.features.home.HeroBundleCache.put(
                            "interests", id, fresh, serializer, isEmpty = fresh.isEmpty(), isSeries = false,
                        )
                    }.orEmpty()
                    mutex.withLock { cache[id] = result }
                    result
                } finally {
                    mutex.withLock { inFlight.remove(id) }
                }
            }.also { inFlight[id] = it }
        }
        return shared.await()
    }

    /** null when the request failed (not kept on disk, so the next launch asks again). */
    private suspend fun request(imdbId: String): List<ImdbInterest>? = runCatching {
        val response = httpRequestRaw(
            method = "POST",
            url = IMDB_GRAPHQL_URL,
            headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/json",
                // See ImdbGraphQlApi: IMDb's edge 403s a request with no client name.
                "x-imdb-client-name" to IMDB_GRAPHQL_CLIENT_NAME,
            ),
            body = "{\"query\":${json.encodeToString(query(imdbId))}}",
            callTimeoutMs = CALL_TIMEOUT_MS,
        )
        if (response.status !in 200..299 || response.body.isBlank()) {
            log.w { "IMDb interests request failed for $imdbId (${response.status})" }
            return@runCatching null
        }
        parseImdbInterests(response.body)
    }.getOrElse { error ->
        if (error is kotlinx.coroutines.CancellationException) throw error
        log.w(error) { "IMDb interests request failed for $imdbId" }
        null
    }

    private fun query(imdbId: String): String = """
        query {
          title(id: "$imdbId") {
            interests(first: $PAGE_SIZE) {
              edges { node { primaryText { text } category { text } } }
            }
          }
        }
    """.trimIndent()

    private val cache = mutableMapOf<String, List<ImdbInterest>>()
    private val inFlight = mutableMapOf<String, Deferred<List<ImdbInterest>>>()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val log = Logger.withTag("ImdbInterests")
    private val json = Json { ignoreUnknownKeys = true }

    private const val IMDB_GRAPHQL_URL = "https://api.graphql.imdb.com/"
    private const val IMDB_GRAPHQL_CLIENT_NAME = "nuvio-desktop"

    /** IMDb tags a title with a handful of interests; the most seen is around a dozen. */
    private const val PAGE_SIZE = 50
    private const val CALL_TIMEOUT_MS = 6_000L
}

/**
 * Reads the interests connection out of an IMDb GraphQL title response. IMDb lists the title's
 * plain genres among its interests too ("Horror" filed under "Horror"); those say nothing a genre
 * row does not already, and are dropped here so every result is a refinement of its category.
 */
internal fun parseImdbInterests(body: String): List<ImdbInterest> =
    Json { ignoreUnknownKeys = true }
        .decodeFromString<ImdbInterestsResponseDto>(body)
        .data?.title?.interests?.edges.orEmpty()
        .mapNotNull { edge ->
            val name = edge.node?.primaryText?.text?.trim()?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val category = edge.node.category?.text?.trim()?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            if (name.equals(category, ignoreCase = true)) return@mapNotNull null
            ImdbInterest(name = name, category = category)
        }
        .distinctBy { it.name.lowercase() }

@Serializable
private data class ImdbInterestsResponseDto(val data: ImdbInterestsDataDto? = null)

@Serializable
private data class ImdbInterestsDataDto(val title: ImdbInterestsTitleDto? = null)

@Serializable
private data class ImdbInterestsTitleDto(val interests: ImdbInterestsConnectionDto? = null)

@Serializable
private data class ImdbInterestsConnectionDto(val edges: List<ImdbInterestsEdgeDto> = emptyList())

@Serializable
private data class ImdbInterestsEdgeDto(val node: ImdbInterestsNodeDto? = null)

@Serializable
private data class ImdbInterestsNodeDto(
    val primaryText: ImdbInterestsTextDto? = null,
    val category: ImdbInterestsTextDto? = null,
)

@Serializable
private data class ImdbInterestsTextDto(val text: String? = null)
