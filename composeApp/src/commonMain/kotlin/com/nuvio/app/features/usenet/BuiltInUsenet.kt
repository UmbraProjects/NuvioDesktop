package com.nuvio.app.features.usenet

import co.touchlab.kermit.Logger
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.tmdb.TmdbService
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Built-in Usenet search (NuvioTV's BuiltInUsenetService, ported in its core): the user's
 * Newznab indexers are searched for the title, the releases filtered and sorted by the Usenet
 * settings, and listed as one source group whose rows play through the Usenet engine with the
 * user's own NNTP providers.
 *
 * Kept from the TV: ID-first searches with a title fallback, the season search for packs,
 * indexer priorities with optional fallback, duplicate hiding, and the filters and sorts.
 * Not ported (yet): the "available"/"unavailable" badges from releases tried before, season-pack
 * per-episode sizes, the progressive result display and the usage statistics.
 */
object BuiltInUsenet {
    const val GROUP_ID = "usenet:built-in"
    const val GROUP_NAME = "Built-in Usenet"

    private val log = Logger.withTag("BuiltInUsenet")
    val client = NewznabClient { UsenetSettingsRepository.engine.userAgent }

    /** On, with a provider and an indexer, and the title is one the search can look up. */
    fun canSearch(type: String, videoId: String): Boolean =
        UsenetSettingsRepository.sources.ready && builtInUsenetCanSearch(type, videoId)

    fun loadingGroup(): AddonStreamGroup =
        AddonStreamGroup(addonName = GROUP_NAME, addonId = GROUP_ID, streams = emptyList(), isLoading = true)

    /** Searches and returns the finished group; an empty group with an error when nothing could be asked. */
    suspend fun group(type: String, videoId: String, season: Int?, episode: Int?): AddonStreamGroup = try {
        val streams = search(type, videoId, season, episode)
        AddonStreamGroup(addonName = GROUP_NAME, addonId = GROUP_ID, streams = streams, isLoading = false)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AddonStreamGroup(
            addonName = GROUP_NAME,
            addonId = GROUP_ID,
            streams = emptyList(),
            isLoading = false,
            error = e.message ?: "Usenet search failed",
        )
    }

    suspend fun search(type: String, videoId: String, season: Int?, episode: Int?): List<StreamItem> {
        val config = UsenetSettingsRepository.sources
        if (!config.ready) return emptyList()
        val series = !type.equals("movie", ignoreCase = true)
        val (idSeason, idEpisode) = episodeNumbers(videoId)
        val request = metadata(
            UsenetSearchRequest(
                season = season ?: idSeason,
                episode = episode ?: idEpisode,
                series = series,
            ),
            videoId = videoId,
            type = type,
        )
        val indexers = config.indexers.filter { it.enabled }
        val byPriority = indexers.groupBy { it.priority }.toSortedMap()
        val releases = mutableListOf<UsenetRelease>()
        var failures = 0
        coroutineScope {
            if (config.indexerFallback) {
                // Lower priorities only when the higher ones found nothing.
                for ((_, group) in byPriority) {
                    val found = group.map { indexer -> async { searchOne(indexer, request, config) } }.awaitAll()
                    failures += found.count { it == null }
                    releases += found.filterNotNull().flatten()
                    if (releases.isNotEmpty()) break
                }
            } else {
                val found = indexers.map { indexer -> async { searchOne(indexer, request, config) } }.awaitAll()
                failures += found.count { it == null }
                releases += found.filterNotNull().flatten()
            }
        }
        if (releases.isEmpty() && failures == indexers.size && indexers.isNotEmpty()) {
            error("No indexer answered")
        }
        val candidates = if (config.hideDuplicates) {
            NewznabProtocol.withoutDuplicates(releases, config, series = series)
        } else {
            releases
        }
        return NewznabProtocol.arrange(candidates, config, series = series).map {
            it.toStream(config, series, UsenetStreamFormat.Subject(request.title, request.year, series))
        }
    }

    private suspend fun searchOne(
        indexer: UsenetIndexer,
        request: UsenetSearchRequest,
        config: UsenetSourceConfiguration,
    ): List<UsenetRelease>? = try {
        val caps = client.capabilities(indexer)
        client.search(indexer, request, caps, config.indexerResults)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Never the message of an HTTP failure: it can name the URL, which carries the key.
        log.i { "${indexer.name}: search failed (${e.javaClass.simpleName})" }
        null
    }

    /** Fills in the ids, title and year the indexers are asked with. */
    private suspend fun metadata(request: UsenetSearchRequest, videoId: String, type: String): UsenetSearchRequest {
        val parentId = parentContentId(videoId)
        val imdb = parentId.takeIf { it.startsWith("tt") }
        val tmdb = runCatching { TmdbService.ensureTmdbId(parentId, type) }.getOrNull()
        val resolvedImdb = imdb ?: tmdb?.toIntOrNull()?.let { runCatching { TmdbService.tmdbToImdb(it, type) }.getOrNull() }
        val meta = MetaDetailsRepository.peek(type, parentId)
            ?: runCatching { MetaDetailsRepository.fetch(type, parentId) }.getOrNull()
        return request.copy(
            imdbId = resolvedImdb,
            tmdbId = tmdb,
            title = meta?.name,
            year = meta?.releaseInfo?.let { YEAR.find(it)?.value?.toIntOrNull() },
        )
    }

    /** The title's id from an episode id: tt123:1:2 is tt123, tmdb:10:1:2 is tmdb:10. */
    private fun parentContentId(videoId: String): String {
        val parts = videoId.split(':')
        return when {
            videoId.startsWith("tmdb:") || videoId.startsWith("tvdb:") -> parts.take(2).joinToString(":")
            else -> parts.first()
        }
    }

    private fun UsenetRelease.toStream(
        config: UsenetSourceConfiguration,
        series: Boolean,
        subject: UsenetStreamFormat.Subject,
    ): StreamItem {
        val raw = StreamItem(
            name = "Usenet",
            title = title,
            addonName = GROUP_NAME,
            addonId = GROUP_ID,
            streamType = "usenet",
            behaviorHints = StreamBehaviorHints(
                bingeGroup = bingeGroup(title, series),
                videoSize = size.takeIf { it > 0 },
                filename = title,
            ),
            nzbUrl = nzbUrl,
        )
        return UsenetStreamFormat.format(raw, subject, indexerName, publishedAt, config.showReleaseNames)
    }

    /** One upload's episodes share a binge group, so the next episode plays from the same upload. */
    fun bingeGroup(title: String?, series: Boolean = true): String? {
        val name = title?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        if (!series) return "$GROUP_NAME|$name"
        val upload = NewznabProtocol.episodeTemplate(name)?.first
            ?: name.takeIf { NewznabProtocol.packSeasons(it).isNotEmpty() } ?: return null
        return "$GROUP_NAME|$upload"
    }

    private val YEAR = Regex("(19|20)\\d{2}")
}
