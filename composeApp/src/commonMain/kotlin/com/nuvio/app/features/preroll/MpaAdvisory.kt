package com.nuvio.app.features.preroll

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The reasons the MPA gave for a film's rating ("for strong bloody violence, disturbing behavior and
 * language"), which the details record does not carry.
 *
 * Read from the US certificate IMDb lists for the title, looked up by IMDb id so there is no title
 * search to get wrong. Same GraphQL edge the interests and episode ratings already use. Every step is
 * best effort: a slow or changed service, no US certificate or one with no reason all end in null, and
 * the card simply has no reasons line.
 */
internal object MpaAdvisory {
    private val log = Logger.withTag("MpaAdvisory")
    private val json = Json { ignoreUnknownKeys = true }

    private const val URL = "https://api.graphql.imdb.com/"
    private const val CLIENT_NAME = "nuvio-desktop"
    private const val CALL_TIMEOUT_MS = 6_000L

    private val lock = Any()
    private val cache = HashMap<String, String?>()

    /** The reasons ("for strong violence and language"), or null when none could be found. */
    suspend fun reasonFor(imdbId: String?): String? {
        val id = imdbId?.trim()?.takeIf { Regex("tt\\d+").matches(it) } ?: return null
        synchronized(lock) { if (cache.containsKey(id)) return cache[id] }
        val body = withTimeoutOrNull(CALL_TIMEOUT_MS + 1_000) {
            runCatching {
                val response = httpRequestRaw(
                    method = "POST",
                    url = URL,
                    headers = mapOf(
                        "Accept" to "application/json",
                        "Content-Type" to "application/json",
                        // IMDb's edge 403s a request with no client name.
                        "x-imdb-client-name" to CLIENT_NAME,
                    ),
                    body = "{\"query\":${json.encodeToString(query(id))}}",
                    callTimeoutMs = CALL_TIMEOUT_MS,
                )
                if (response.status in 200..299) response.body else {
                    log.w { "certificates request failed for $id (${response.status})" }
                    null
                }
            }.onFailure { log.w { "certificates request failed for $id: ${it.message}" } }.getOrNull()
        }
        if (body == null) return null
        val reason = parseMpaReason(body)
        log.i { "$id: ${reason ?: "no US rating reason"}" }
        synchronized(lock) { cache[id] = reason }
        return reason
    }

    private fun query(imdbId: String): String = """
        query {
          title(id: "$imdbId") {
            certificates(first: 100) {
              edges { node { rating ratingReason country { id } } }
            }
          }
        }
    """.trimIndent()
}

private val MPA_RATINGS = setOf("G", "PG", "PG-13", "R", "NC-17")

/**
 * The reasons out of an IMDb certificates response: the US certificate that is an MPA rating and
 * carries a reason, formatted by [formatMpaReason]. Null when there is none.
 */
internal fun parseMpaReason(body: String): String? {
    val nodes = runCatching {
        Json { ignoreUnknownKeys = true }.decodeFromString<MpaCertificatesResponseDto>(body)
            .data?.title?.certificates?.edges.orEmpty().mapNotNull { it.node }
    }.getOrNull() ?: return null
    return nodes
        .filter { it.country?.id.equals("US", ignoreCase = true) }
        .filter { (it.rating ?: "").trim().uppercase() in MPA_RATINGS }
        .mapNotNull { it.ratingReason?.trim()?.takeIf(String::isNotEmpty) }
        .firstOrNull()
        ?.let(::formatMpaReason)
}

/**
 * The reason as the MPA's own cards print it: lower case after "for", no "Rated R" lead-in.
 * "Rated R for strong violence and language" becomes "for strong violence and language".
 */
internal fun formatMpaReason(raw: String): String? {
    val text = raw.trim().replace(Regex("\\s+"), " ")
        .replace(Regex("^(rated\\s+(g|pg-13|pg|r|nc-17)\\b[\\s:,-]*)", RegexOption.IGNORE_CASE), "")
        .trim()
        .trimEnd('.')
    if (text.isEmpty()) return null
    return if (text.startsWith("for ", ignoreCase = true)) "for " + text.drop(4) else "for $text"
}

@Serializable
private data class MpaCertificatesResponseDto(val data: MpaDataDto? = null)

@Serializable
private data class MpaDataDto(val title: MpaTitleDto? = null)

@Serializable
private data class MpaTitleDto(val certificates: MpaConnectionDto? = null)

@Serializable
private data class MpaConnectionDto(val edges: List<MpaEdgeDto> = emptyList())

@Serializable
private data class MpaEdgeDto(val node: MpaNodeDto? = null)

@Serializable
private data class MpaNodeDto(
    val rating: String? = null,
    val ratingReason: String? = null,
    val country: MpaIdDto? = null,
)

@Serializable
private data class MpaIdDto(val id: String? = null)
