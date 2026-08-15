package com.nuvio.app.features.simkl

import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.watched.WatchedItem
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * Full SIMKL watched history, including per-episode timestamps.
 *
 * A failed fetch throws rather than degrading to the last good snapshot. The one consumer merges
 * this additively into the local watched store, so handing it a stale snapshot re-adds every tick
 * the user has removed since — an unmark that undoes itself at the next sync. Skipping the merge
 * loses nothing: the local store is already the authority between pulls.
 */
internal object SimklWatchedRepository {
    private const val BASE_URL = "https://api.simkl.com"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun watchedItems(): List<WatchedItem> {
        val headers = SimklAuthRepository.authorizedHeaders() ?: return emptyList()
        // `extended=full` supplies seasons/episodes and `episode_watched_at=yes` distinguishes
        // watched episodes from the unwatched episode rows included in that extended response.
        val url = SimklAuthRepository.appendParams(
            "$BASE_URL/sync/all-items/all?extended=full&episode_watched_at=yes",
        )
        val response = httpRequestRaw(method = "GET", url = url, headers = headers, body = "")
        if (response.status !in 200..299) {
            error("SIMKL watched-history fetch failed: HTTP ${response.status}")
        }
        return runCatching {
            json.decodeFromString<SimklAllItemsResponse>(response.body)
        }.getOrElse { failure ->
            if (failure is CancellationException) throw failure
            error("SIMKL watched-history payload could not be parsed: ${failure.message}")
        }.toWatchedItems()
    }
}

internal fun SimklAllItemsResponse.toWatchedItems(): List<WatchedItem> = buildList {
    movies.forEach { entry ->
        if (entry.isRewatch) return@forEach
        val movie = entry.movie ?: return@forEach
        val watchedAt = entry.lastWatchedAt ?: return@forEach
        val id = if (movie.ids.isKnownAnime()) {
            movie.ids.toBestAnimeMovieContentId()
        } else {
            movie.ids.toBestContentId()
        } ?: return@forEach
        add(
            WatchedItem(
                id = id,
                type = "movie",
                name = movie.title ?: id,
                poster = movie.poster?.takeIf(String::isNotBlank)?.simklPosterUrl(),
                releaseInfo = movie.year?.toString(),
                markedAtEpochMs = parseSimklTimestamp(watchedAt) ?: 0L,
            ),
        )
    }

    fun addEpisodes(entry: SimklAllItemsEntry, anime: Boolean) {
        if (entry.isRewatch) return
        val show = (if (anime) entry.anime else entry.show) ?: return
        val id = (if (anime) show.ids.toBestAnimeContentId() else show.ids.toBestContentId())
            ?: return
        entry.seasons.forEach { season ->
            val rawSeason = season.number ?: return@forEach
            season.episodes.forEach { episode ->
                val watchedAt = episode.watchedAt ?: return@forEach
                val rawEpisode = episode.number ?: return@forEach
                val (seasonNumber, episodeNumber) = show.ids.episodeCoordinatesFor(
                    contentId = id,
                    isAnime = anime,
                    entrySeason = rawSeason,
                    entryEpisode = rawEpisode,
                    // SIMKL's own franchise (TVDB) coordinates — see SimklEpisodeTvdbMapping.
                    franchiseSeason = episode.tvdb?.season,
                    franchiseEpisode = episode.tvdb?.episode,
                )
                add(
                    WatchedItem(
                        id = id,
                        type = "series",
                        name = show.title ?: id,
                        poster = show.poster?.takeIf(String::isNotBlank)?.simklPosterUrl(),
                        releaseInfo = show.year?.toString(),
                        season = seasonNumber,
                        episode = episodeNumber,
                        markedAtEpochMs = parseSimklTimestamp(watchedAt) ?: 0L,
                    ),
                )
            }
        }
    }

    shows.forEach { addEpisodes(it, anime = false) }
    anime.forEach { addEpisodes(it, anime = true) }
}
