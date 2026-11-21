package com.nuvio.app.features.usenet

import co.touchlab.kermit.Logger
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.kitsu.KitsuService
import com.nuvio.app.features.metadata.MediaIdResolver
import com.nuvio.app.features.metadata.ResolvedMediaIds
import com.nuvio.app.features.metadata.explicitFranchiseCoords
import com.nuvio.app.features.metadata.isAnimeNativeId
import com.nuvio.app.features.metadata.nativeAnimeBase
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
        val subject = UsenetStreamFormat.Subject(request.title, request.year, series)
        val engine = UsenetSettingsRepository.engine
        return NewznabProtocol.arrange(candidates, config, series = series)
            .filterNot { UsenetCompressedReleases.isCompressed(it.nzbUrl) && engine.skipsCompressed(it.size.takeIf { size -> size > 0 }) }
            .map { it.toStream(config, series, subject) }
    }

    private suspend fun searchOne(
        indexer: UsenetIndexer,
        request: UsenetSearchRequest,
        config: UsenetSourceConfiguration,
    ): List<UsenetRelease>? {
        val started = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - started) / 1_000_000
        return try {
            val caps = client.capabilities(indexer)
            client.search(indexer, request, caps, config.indexerResults).also { found ->
                val ms = elapsedMs()
                UsenetStats.search(indexer.id, ms, found.size)
                UsenetStats.found(found)
                log.i { "${indexer.name}: ${found.size} releases in $ms ms" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IndexerCooldownException) {
            // Paused for its limit: not asked, so neither a search nor a failure.
            log.i { "${indexer.name}: skipped (limit pause)" }
            null
        } catch (e: Exception) {
            val ms = elapsedMs()
            UsenetStats.search(indexer.id, ms, null)
            // Never the message of an HTTP failure: it can name the URL, which carries the key.
            log.i { "${indexer.name}: search failed after $ms ms (${e.javaClass.simpleName})" }
            null
        }
    }

    /** Fills in the ids, title and year the indexers are asked with, and for anime its numbering. */
    private suspend fun metadata(request: UsenetSearchRequest, videoId: String, type: String): UsenetSearchRequest {
        val parentId = parentContentId(videoId)
        val native = parentId.isAnimeNativeId()
        // Anime entry ids (kitsu:, mal:, ...) are mapped to the show's IMDb, TVDB and TMDB ids and
        // its season, the same way the stream addons are asked.
        val ids = quietly {
            MediaIdResolver.resolve(
                contentType = type,
                parentMetaId = parentId,
                videoId = videoId,
                sourceSeasonNumber = if (native && request.series) request.season ?: 1 else request.season,
                sourceEpisodeNumber = request.episode,
                isAnimeHint = type.equals("anime", ignoreCase = true),
            )
        }
        val meta = MetaDetailsRepository.peek(type, parentId)
            ?: quietly { MetaDetailsRepository.fetch(type, parentId) }
        val tmdb = ids?.tmdb?.toString()
            ?: meta?.tmdbId?.toString()
            ?: quietly { TmdbService.ensureTmdbId(parentId, type) }
        val imdb = ids?.imdb
            ?: meta?.imdbId?.takeIf { it.startsWith("tt") }
            ?: parentId.takeIf { it.startsWith("tt") }
            ?: tmdb?.toIntOrNull()?.let { quietly { TmdbService.tmdbToImdb(it, type) } }
        val tvdb = ids?.tvdb?.toString() ?: meta?.tvdbId?.trim()?.takeIf { it.toIntOrNull() != null }
        val names = tmdb?.toIntOrNull()?.let { quietly { TmdbService.fetchOtherTitles(it, if (request.series) "series" else "movie") } }
        // An anime entry's meta names the entry ("... 2nd Season"); TMDB names the show.
        val title = if (native) names?.title ?: meta?.name else meta?.name ?: names?.title
        val base = request.copy(
            imdbId = imdb,
            tmdbId = tmdb,
            tvdbId = tvdb,
            title = title,
            originalTitle = names?.originalTitle?.takeIf { !it.equals(title, ignoreCase = true) },
            otherTitles = names?.alternativeTitles,
            year = meta?.releaseInfo?.let { YEAR.find(it)?.value?.toIntOrNull() },
        )
        if (!request.series || ids?.isAnime != true) return base
        return animeNumbering(base, ids, native = native && videoId.explicitFranchiseCoords() == null,
            entryName = meta?.name.takeIf { native })
    }

    /**
     * Places an anime episode in each numbering its releases use: the TVDB season and episode
     * (scene releases, ID searches), the episode within the entry and the one counted from the
     * show's start (fansub releases). [native] requests number within the entry, others by season.
     */
    private suspend fun animeNumbering(
        request: UsenetSearchRequest,
        ids: ResolvedMediaIds,
        native: Boolean,
        entryName: String?,
    ): UsenetSearchRequest {
        val episode = request.episode ?: return request
        val tmdb = request.tmdbId?.toIntOrNull()
        val counts = tmdb?.let { quietly { TmdbService.fetchSeasonEpisodeCounts(it) } }
        val entryEpisode: Int?
        val seasonEpisode: Pair<Int, Int>?
        val tmdbEpisode: Pair<Int, Int>?
        if (native) {
            entryEpisode = episode
            tmdbEpisode = ids.tmdbSeason?.let { it to episode + (ids.tmdbEpisodeOffset ?: 0) }
            seasonEpisode = ids.tvdbSeason?.let { it to episode + (ids.tvdbEpisodeOffset ?: 0) }
                ?: tmdbEpisode
                // No season known: the entry is taken to be the whole show, placed in TMDB's seasons.
                ?: counts?.let { seasonOf(episode, it) }
        } else {
            val season = request.season ?: return request
            seasonEpisode = season to episode
            tmdbEpisode = null
            entryEpisode = when (season) {
                ids.tvdbSeason -> episode - (ids.tvdbEpisodeOffset ?: 0)
                ids.tmdbSeason -> episode - (ids.tmdbEpisodeOffset ?: 0)
                else -> null
            }?.takeIf { it >= 1 }
        }
        val startsShow = animeEntryStartsShow(ids.tvdbSeason, ids.tmdbSeason, ids.tvdbEpisodeOffset, ids.tmdbEpisodeOffset)
        val absolute = animeAbsoluteEpisode(native, startsShow, entryEpisode, tmdbEpisode ?: seasonEpisode, counts)
        val showNames = listOfNotNull(request.title, request.originalTitle) + request.otherTitles.orEmpty()
        val kitsuNames = ids.kitsu?.let { quietly { KitsuService.fetchTitles(it) } }.orEmpty()
        // An entry named like the show cannot tell its own count from the show's, unless they agree.
        val entryTitles = (listOfNotNull(entryName) + kitsuNames).distinctBy { it.lowercase(Locale.ROOT) }
            .filter { name -> entryEpisode == absolute || showNames.none { it.equals(name, ignoreCase = true) } }
        val others = listOfNotNull(
            tmdbEpisode,
            absolute?.let { 1 to it },
        ).filter { it != seasonEpisode }.distinct()
        return request.copy(
            season = seasonEpisode?.first,
            episode = seasonEpisode?.second,
            anime = UsenetAnimeNumbering(
                entryEpisode = entryEpisode,
                absoluteEpisode = absolute,
                entryTitles = entryTitles,
                otherCoordinates = others,
            ),
        )
    }

    /** A lookup the search can do without: its failure leaves the field unknown. */
    private suspend fun <T> quietly(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** The title's id from an episode id: tt123:1:2 is tt123, tmdb:10:1:2 is tmdb:10, kitsu:7:5 is kitsu:7. */
    private fun parentContentId(videoId: String): String {
        val parts = videoId.split(':')
        return when {
            videoId.startsWith("tmdb:") || videoId.startsWith("tvdb:") -> parts.take(2).joinToString(":")
            videoId.isAnimeNativeId() -> videoId.nativeAnimeBase()
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
            usenetAlternates = alternates,
            usenetPassword = password,
        )
        return UsenetStreamFormat.format(
            raw,
            subject,
            indexerName,
            publishedAt,
            config.showReleaseNames,
            kept = UsenetSettingsRepository.engine.cacheNzb && UsenetKeptNzbs.isKept(nzbUrl),
        )
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
