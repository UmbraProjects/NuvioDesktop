package com.nuvio.app.features.iptv

import com.nuvio.app.core.deeplink.buildMetaDeepLinkUrl
import com.nuvio.app.core.deeplink.handleAppUrl
import com.nuvio.app.features.remote.RemoteEpisodePlay
import com.nuvio.app.features.remote.RemotePlayMode
import com.nuvio.app.features.remote.RemotePlayerHub
import com.nuvio.app.features.tmdb.TmdbService
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * fork:live-tv-on-demand - the TMDB look-up (remembered for the session, misses too) and the hand-off to the app's
 * own player: the same path as a title's page, from the start, with the source the viewer's add-ons pick.
 */
object IptvOnDemand {
    private val none = IptvOnDemandTarget("", "", "", null, null, null, null)
    private const val MAX_SEASONS = 30
    private val found = ConcurrentHashMap<String, IptvOnDemandTarget>()

    private fun key(q: IptvOnDemandQuery) = "${if (q.film) "m" else "s"}:${IptvDecide.normalise(q.title)}:${q.year ?: ""}:${q.season ?: ""}:${q.episode ?: ""}"

    /** What is already known for [entry], without a request. */
    fun peek(entry: IptvEpgEntry): IptvOnDemandTarget? =
        IptvOnDemandRules.query(entry)?.let { found[key(it)] }?.takeIf { it !== none }

    /** Looks [entry] up on TMDB; null when nothing matches closely enough. */
    suspend fun find(entry: IptvEpgEntry): IptvOnDemandTarget? {
        val q = IptvOnDemandRules.query(entry) ?: return null
        val k = key(q)
        found[k]?.let { return it.takeIf { t -> t !== none } }
        val target = try {
            withContext(Dispatchers.Default) { lookUp(q) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            return null // not remembered: the next look may have a connection
        }
        found[k] = target ?: none
        return target
    }

    private suspend fun lookUp(q: IptvOnDemandQuery): IptvOnDemandTarget? {
        val wanted = IptvDecide.normalise(q.title)
        val results = TmdbService.searchTitles(q.title, mediaType = if (q.film) "movie" else "tv", year = if (q.film) q.year else null)
        // The same name only: a guide title near another programme's name is usually not it.
        val best = results.firstOrNull { IptvDecide.normalise(if (q.film) it.title.orEmpty() else it.name.orEmpty()) == wanted }
            ?: if (q.film && q.year != null) results.firstOrNull() else null
        best ?: return null
        val imdb = TmdbService.tmdbToImdb(best.id, if (q.film) "movie" else "tv") ?: return null
        // No numbers in the guide: find the episode by the name its description starts with.
        var season = q.season
        var episode = q.episode
        if (!q.film && season == null && !q.desc.isNullOrBlank()) {
            val seasons = TmdbService.fetchSeasonEpisodeCounts(best.id).orEmpty().take(MAX_SEASONS).mapNotNull { count ->
                TmdbService.fetchSeasonEpisodeNames(best.id, count.season)?.let { count.season to it }
            }
            IptvOnDemandRules.episodeByName(q.desc, seasons)?.let { (s, e) ->
                season = s
                episode = e
            }
        }
        return IptvOnDemandTarget(
            type = if (q.film) "movie" else "series",
            imdbId = imdb,
            name = (if (q.film) best.title else best.name) ?: q.title,
            season = season,
            episode = episode,
            poster = best.posterPath?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w342$it" },
            backdrop = best.backdropPath?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w780$it" },
        )
    }

    /** Plays it from the start through the add-ons, or opens the series' page to pick the episode. */
    fun play(target: IptvOnDemandTarget) {
        // The viewer chose a programme, not a release: the add-ons' source is picked without asking.
        com.nuvio.app.features.streams.StreamAutoPlayOverride.arm(target.videoId)
        if (!target.playable) {
            handleAppUrl(buildMetaDeepLinkUrl(target.type, target.imdbId))
            return
        }
        RemotePlayerHub.playEpisode(
            RemoteEpisodePlay(
                type = target.type,
                videoId = target.videoId,
                parentMetaId = target.imdbId,
                parentMetaType = target.type,
                title = target.name,
                logo = null,
                poster = target.poster,
                background = target.backdrop,
                seasonNumber = target.season,
                episodeNumber = target.episode,
                episodeTitle = null,
                episodeThumbnail = null,
                pauseDescription = null,
                resumePositionMs = null,
                mode = RemotePlayMode.FromStart,
            ),
        )
    }
}
