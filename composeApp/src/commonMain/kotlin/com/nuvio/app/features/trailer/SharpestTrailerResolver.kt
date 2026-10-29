package com.nuvio.app.features.trailer

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * fork:trailer-quality
 *
 * Plays the sharpest upload of a trailer rather than just the listed one. The listed trailer is
 * resolved first and accepted as it is when it reaches [GOOD_ENOUGH_TRAILER_HEIGHT], which keeps
 * the common case to a single resolve. Only a soft one (or none) triggers the search: the next
 * listed trailers and the best YouTube search results are resolved together and the tallest wins,
 * ties keeping the listed order. Everything past the first resolve is best effort and bounded, so
 * it can only improve on, never lose, the trailer already in hand.
 *
 * Lives in its own file so [HeroTrailerMetadataService] only needs a one-line call into it.
 */
internal object SharpestTrailerResolver {
    /** The hunt for a sharper upload is bounded; the first upload plays if it overruns. */
    private const val IMPROVE_TIMEOUT_MS = 20_000L

    private const val MAX_PARALLEL_RESOLVES = 2
    private const val UNRELIABLE_RETRIES = 2
    private const val UNRELIABLE_RETRY_DELAY_MS = 800L
    private val resolveGate = Semaphore(MAX_PARALLEL_RESOLVES)

    private val log = Logger.withTag("SharpestTrailer")

    suspend fun resolve(title: String?, releaseInfo: String?, listedKeys: List<String>): TrailerPlaybackSource? {
        val first = listedKeys.firstOrNull()?.let { resolveKey(it) }
        if (first != null && first.reliable && !first.portrait && first.heightPx >= GOOD_ENOUGH_TRAILER_HEIGHT) return first
        if (title.isNullOrBlank() && listedKeys.size < 2) return first

        val tried = listedKeys.take(1).toMutableSet()
        val firstOption = first?.let { TrailerOption(listedKeys.first(), it, order = 0) }
        val better = withTimeoutOrNull(IMPROVE_TIMEOUT_MS) {
            coroutineScope {
                val listed = listedKeys.drop(1).take(MAX_LISTED_ALTERNATIVES)
                tried += listed
                val listedJobs = listed.mapIndexed { index, key ->
                    async { resolveKey(key)?.let { TrailerOption(key, it, order = index + 1) } }
                }
                val searchJob = async {
                    if (title.isNullOrBlank()) return@async emptyList<TrailerOption>()
                    val year = releaseInfo?.trim()?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
                    val hits = filterTrailerSearchHits(
                        hits = runCatching { TrailerPlaybackResolver.searchYouTube(trailerSearchQuery(title, year)) }
                            .getOrDefault(emptyList()),
                        title = title,
                        year = year,
                        excludeKeys = tried,
                    )
                    log.i { "search alternatives for \"$title\": ${hits.map { it.videoId }}" }
                    hits.mapIndexed { index, hit ->
                        async { resolveKey(hit.videoId)?.let { TrailerOption(hit.videoId, it, order = 100 + index) } }
                    }.awaitAll().filterNotNull()
                }
                listedJobs.awaitAll().filterNotNull() + searchJob.await()
            }
        }.orEmpty()

        val all = listOfNotNull(firstOption) + better
        val best = pickBestTrailer(all)
        log.i {
            "sharpest of ${all.size}: " + all.joinToString { "${it.key}=${it.source.heightPx}p${if (it.source.portrait) " portrait" else ""}" } +
                " -> ${best?.key}"
        }
        return best?.source ?: first
    }

    /**
     * Resolves one upload. When the unrestricted client did not answer, the result is a source
     * whose URLs 403, so it is looked up again after a pause (the usual cause is throttling while
     * several uploads are resolved at once) and the unreliable one only kept if that fails too.
     * At most [MAX_PARALLEL_RESOLVES] lookups run at once, so a search for a sharper upload does
     * not itself cause the throttling.
     */
    private suspend fun resolveKey(key: String): TrailerPlaybackSource? {
        suspend fun once(): TrailerPlaybackSource? = resolveGate.withPermit {
            runCatching { TrailerPlaybackResolver.resolveFromYouTubeUrl(youtubePlaybackUrlFor(key)).sourceOrNull() }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
        }
        var source = once()
        var attempt = 0
        while (source != null && !source.reliable && attempt < UNRELIABLE_RETRIES) {
            attempt++
            log.i { "$key came back through a restricted client, retrying ($attempt)" }
            delay(UNRELIABLE_RETRY_DELAY_MS * attempt)
            source = once() ?: source
        }
        return source
    }

    private fun youtubePlaybackUrlFor(key: String): String =
        key.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: "https://www.youtube.com/watch?v=$key"
}
