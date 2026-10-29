package com.nuvio.app.features.preroll

import co.touchlab.kermit.Logger
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.tmdb.TmdbService
import kotlinx.coroutines.withTimeoutOrNull

/** The pictures the pre-show is painted over. Empty [backdrops] is fine: the theme still draws. */
data class TriviaArtwork(
    val backdrops: List<String> = emptyList(),
    val logo: String? = null,
)

private const val MAX_BACKDROPS = 10
private const val ARTWORK_FETCH_TIMEOUT_MS = 8_000L

/**
 * Merges artwork sources into one ordered, de-duplicated list.
 *
 * Order is the point: the addon's own background leads (it is what the details page shows, so it
 * is the one the viewer already associates with the film), then TMDB's extra textless fanart, then
 * a metahub background as a last resort for titles TMDB does not know.
 */
internal fun mergeBackdrops(
    primary: String?,
    extras: List<String>,
    fallback: String?,
    limit: Int = MAX_BACKDROPS,
): List<String> =
    (listOfNotNull(primary) + extras + listOfNotNull(fallback))
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .take(limit)

/**
 * Pulls fanart for a title's pre-show. Reads only; costs nothing on the AI key and a single TMDB
 * request, and is cached for the session since the gate may open for the same film repeatedly.
 */
object TriviaArtworkRepository {
    private val log = Logger.withTag("Trivia")
    private val lock = Any()
    private val cache = mutableMapOf<String, TriviaArtwork>()

    suspend fun forTitle(type: String, metaId: String): TriviaArtwork {
        val key = "$type:$metaId"
        synchronized(lock) { cache[key] }?.let { return it }
        val meta = MetaDetailsRepository.peekAny(type, metaId)
            ?: withTimeoutOrNull(ARTWORK_FETCH_TIMEOUT_MS) {
                runCatching { MetaDetailsRepository.fetch(type, metaId) }.getOrNull()
            }
            ?: return TriviaArtwork()
        val artwork = runCatching { build(meta) }
            .onFailure { log.w { "trivia artwork for ${meta.name} failed: ${it.message}" } }
            .getOrDefault(TriviaArtwork(backdrops = mergeBackdrops(meta.background, emptyList(), null), logo = meta.logo))
        // Only remembered when it found something; an empty answer may be a transient network miss.
        if (artwork.backdrops.isNotEmpty()) synchronized(lock) { cache[key] = artwork }
        log.i { "trivia artwork ${meta.name}: ${artwork.backdrops.size} backdrops" }
        return artwork
    }

    private suspend fun build(meta: MetaDetails): TriviaArtwork {
        val tmdbId = meta.tmdbId
            ?: withTimeoutOrNull(ARTWORK_FETCH_TIMEOUT_MS) {
                TmdbService.ensureTmdbId(meta.id, meta.type)?.toIntOrNull()
            }
        val extras = tmdbId
            ?.let { withTimeoutOrNull(ARTWORK_FETCH_TIMEOUT_MS) { TmdbService.fetchBackdropUrls(it, meta.type) } }
            .orEmpty()
        val imdb = meta.imdbId ?: meta.id.takeIf { it.startsWith("tt") }
        val fallback = imdb?.let { "https://images.metahub.space/background/large/$it/img" }
        return TriviaArtwork(
            backdrops = mergeBackdrops(meta.background, extras, fallback),
            logo = meta.logo?.takeIf(String::isNotBlank),
        )
    }
}
