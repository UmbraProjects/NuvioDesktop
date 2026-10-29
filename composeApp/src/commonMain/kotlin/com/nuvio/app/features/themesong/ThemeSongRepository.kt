package com.nuvio.app.features.themesong

import co.touchlab.kermit.Logger
import com.nuvio.app.features.library.LibraryClock
import com.nuvio.app.features.trailer.TrailerPlaybackResolver
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import com.nuvio.app.features.trailer.sourceOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private val log = Logger.withTag("ThemeSong")

private const val FOUND_TTL_MS = 3 * 60 * 60_000L // stream URLs last about six hours
private const val MISSING_TTL_MS = 15 * 60_000L
private const val RESOLVE_TIMEOUT_MS = 20_000L

/**
 * Finds a title's theme on YouTube and resolves it to a stream. In memory only: a miss is
 * remembered briefly so the details screen does not search again every time it is opened.
 */
object ThemeSongRepository {
    private data class Entry(val source: TrailerPlaybackSource?, val expiresAtMs: Long)

    private val mutex = Mutex()
    private val cache = HashMap<String, Entry>()

    /**
     * [type] is the catalogue type ("movie", "series", ...); [releaseInfo] may start with the year.
     * [metaId] lets the details screen and the trivia pre-show share one answer for a title, so the
     * song heard behind the details page is the one that carries on into the trivia cards.
     */
    suspend fun resolve(type: String, title: String, releaseInfo: String?, metaId: String? = null): TrailerPlaybackSource? {
        val name = title.trim()
        if (name.isEmpty()) return null
        val year = releaseInfo?.trim()?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
        val isSeries = !type.equals("movie", ignoreCase = true)
        val key = "${if (isSeries) "s" else "m"}:${name.lowercase()}:${year.orEmpty()}"
        val now = LibraryClock.nowEpochMs()
        val idKey = metaId?.trim()?.takeIf { it.isNotEmpty() }?.let { "id:$it" }
        mutex.withLock {
            (idKey?.let { cache[it] }?.takeIf { it.expiresAtMs > now } ?: cache[key]?.takeIf { it.expiresAtMs > now })
        }?.let { return it.source }

        val source = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { find(name, year, isSeries) }
        mutex.withLock {
            val entry = Entry(source, now + if (source == null) MISSING_TTL_MS else FOUND_TTL_MS)
            cache[key] = entry
            if (idKey != null) cache[idKey] = entry
        }
        return source
    }

    private suspend fun find(title: String, year: String?, isSeries: Boolean): TrailerPlaybackSource? {
        val query = themeSongSearchQuery(title, year, isSeries)
        val hits = try {
            TrailerPlaybackResolver.searchYouTube(query)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "search failed for \"$query\": ${error.message}" }
            emptyList()
        }
        val ranked = rankThemeSongHits(hits, title, year)
        log.i { "\"$query\": ${hits.size} hits, trying ${ranked.map { it.videoId }}" }
        var unreliable: TrailerPlaybackSource? = null
        for (hit in ranked) {
            val source = try {
                TrailerPlaybackResolver.resolveFromYouTubeUrl("https://www.youtube.com/watch?v=${hit.videoId}").sourceOrNull()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                null
            } ?: continue
            // A source from a restricted client stops serving after about a minute: fine as a last
            // resort, never preferred over one that will play to the end.
            val timed = source.copy(durationMs = hit.durationSeconds * 1000L)
            if (timed.reliable) return timed
            if (unreliable == null) unreliable = timed
        }
        return unreliable
    }
}
