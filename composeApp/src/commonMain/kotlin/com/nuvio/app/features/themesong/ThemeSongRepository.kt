package com.nuvio.app.features.themesong

import co.touchlab.kermit.Logger
import com.nuvio.app.features.library.LibraryClock
import com.nuvio.app.features.trailer.TrailerPlaybackResolver
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import com.nuvio.app.features.trailer.YouTubeSearchHit
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
    suspend fun resolve(
        type: String,
        title: String,
        releaseInfo: String?,
        metaId: String? = null,
        isAnime: Boolean = false,
    ): TrailerPlaybackSource? {
        val name = title.trim()
        if (name.isEmpty()) return null
        val year = releaseInfo?.trim()?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
        val isSeries = !type.equals("movie", ignoreCase = true)
        val key = "${if (isSeries) "s" else "m"}${if (isAnime) "a" else ""}:${name.lowercase()}:${year.orEmpty()}"
        val now = LibraryClock.nowEpochMs()
        val idKey = metaId?.trim()?.takeIf { it.isNotEmpty() }?.let { "id:$it" }
        mutex.withLock {
            (idKey?.let { cache[it] }?.takeIf { it.expiresAtMs > now } ?: cache[key]?.takeIf { it.expiresAtMs > now })
        }?.let { return it.source }

        val source = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { find(name, year, isSeries, isAnime) }
        mutex.withLock {
            val entry = Entry(source, now + if (source == null) MISSING_TTL_MS else FOUND_TTL_MS)
            cache[key] = entry
            if (idKey != null) cache[idKey] = entry
        }
        return source
    }

    /**
     * The way to the theme that suits the title:
     * - a film's best-known music is its soundtrack album's most popular track on Deezer, looked up
     *   on YouTube as that exact recording ([ThemeSongCatalog]); a search for "<film> main theme"
     *   turns up fan uploads of whichever cue is called "theme";
     * - a show's is its opening as aired, so it searches "<show> intro" ([themeIntroSearchQuery]),
     *   then "<show> theme song". Never its soundtrack album: that is licensed songs or, for South
     *   Park, a video game's score;
     * - anime searches for its opening, then its album.
     */
    private suspend fun find(title: String, year: String?, isSeries: Boolean, isAnime: Boolean): TrailerPlaybackSource? =
        when {
            isAnime -> fromThemeSearch(title, year, isSeries, isAnime) ?: fromSoundtrack(title, year, isSeries)
            isSeries -> fromThemeSearch(title, year, isSeries, isAnime, intro = true)
                ?: fromThemeSearch(title, year, isSeries, isAnime)
            else -> fromSoundtrack(title, year, isSeries) ?: fromThemeSearch(title, year, isSeries, isAnime)
        }

    private suspend fun fromThemeSearch(
        title: String,
        year: String?,
        isSeries: Boolean,
        isAnime: Boolean,
        intro: Boolean = false,
    ): TrailerPlaybackSource? {
        val query = if (intro) themeIntroSearchQuery(title) else themeSongSearchQuery(title, year, isSeries, isAnime)
        val hits = search(query)
        val ranked = rankThemeSongHits(hits, title, year, intro = intro)
        log.i { "\"$query\": ${hits.size} hits, trying ${ranked.map { it.videoId }}" }
        return firstPlayable(ranked)
    }

    private suspend fun fromSoundtrack(title: String, year: String?, isSeries: Boolean): TrailerPlaybackSource? {
        for (song in ThemeSongCatalog.popularSongs(title, year, isSeries, limit = 2)) {
            val query = songSearchQuery(song)
            val hits = search(query)
            val ranked = rankSongHits(hits, song)
            log.i { "\"$query\" (soundtrack of \"$title\"): ${hits.size} hits, trying ${ranked.map { it.videoId }}" }
            firstPlayable(ranked)?.let { return it }
        }
        return null
    }

    private suspend fun search(query: String): List<YouTubeSearchHit> =
        try {
            TrailerPlaybackResolver.searchYouTube(query)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "search failed for \"$query\": ${error.message}" }
            emptyList()
        }

    private suspend fun firstPlayable(ranked: List<YouTubeSearchHit>): TrailerPlaybackSource? {
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
