package com.nuvio.app.features.streams

import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.metadata.ResolvedEpisodeIdentity
import com.nuvio.app.features.metadata.hasNativeAnimePrefix
import com.nuvio.app.features.metadata.nativeAnimeBase
import com.nuvio.app.features.plugins.pluginContentId
import com.nuvio.app.features.tmdb.TmdbSeasonEpisodeCount
import com.nuvio.app.features.tmdb.TmdbService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where a native anime episode (`kitsu:12:150`) sits in TMDB's seasons.
 *
 * Native anime ids number episodes absolutely inside one anime entry. That is the right request for
 * anime-aware providers, but plugin scrapers take a TMDB id plus TMDB season/episode by contract,
 * and were handed the raw kitsu id (every scraper 404'd on `api.themoviedb.org/3/tv/kitsu:12:150`).
 * Addons need TVDB numbering instead; see [AnimeFranchiseEpisodeLocator].
 */
internal data class TmdbEpisodeLocation(
    val tmdbId: Int,
    val season: Int,
    /** 1-based position inside [season]. */
    val seasonIndex: Int,
    /**
     * TMDB's own `episode_number`, which TMDB's episode endpoints (and plugins built on them) take.
     * Equal to [seasonIndex] for most shows; One Piece numbers absolutely (S6 = 144-195).
     */
    val episodeNumber: Int,
)

internal data class PluginEpisodeTarget(
    val contentId: String,
    val season: Int?,
    val episode: Int?,
)

/** 1-based (season, position) of the [absolute]-th main episode, or null past the last season. */
internal fun List<TmdbSeasonEpisodeCount>.locateAbsoluteEpisode(absolute: Int): Pair<Int, Int>? {
    if (absolute <= 0) return null
    var remaining = absolute
    for (entry in sortedBy { it.season }) {
        if (remaining <= entry.episodeCount) return entry.season to remaining
        remaining -= entry.episodeCount
    }
    return null
}

/** Main episodes before [season], or null when TMDB has no such season. */
internal fun List<TmdbSeasonEpisodeCount>.episodesBefore(season: Int): Int? {
    if (none { it.season == season }) return null
    return filter { it.season < season }.sumOf { it.episodeCount }
}

/**
 * The absolute franchise episode for an entry-local [entryEpisode], given the entry's anime-list
 * placement. Null when the placement cannot be trusted (specials, or a TVDB-only placement past
 * season 1, whose TMDB start we do not know).
 */
internal fun animeEntryAbsoluteEpisode(
    counts: List<TmdbSeasonEpisodeCount>,
    entryEpisode: Int,
    tmdbSeason: Int?,
    tmdbEpisodeOffset: Int?,
    tvdbSeason: Int?,
    tvdbEpisodeOffset: Int?,
): Int? {
    if (entryEpisode <= 0) return null
    if (tmdbSeason != null) {
        if (tmdbSeason <= 0) return null
        val before = counts.episodesBefore(tmdbSeason) ?: return null
        return before + (tmdbEpisodeOffset ?: 0) + entryEpisode
    }
    // No TMDB placement: only an entry that starts the show (One Piece has no season at all) can
    // be counted from the first episode.
    if (tvdbSeason != null && tvdbSeason != 1) return null
    if ((tvdbEpisodeOffset ?: 0) != 0) return null
    return entryEpisode
}

internal object AnimeTmdbEpisodeLocator {
    /** A native anime episode request with a known TMDB show: the only shape this rewrites. */
    fun appliesTo(identity: ResolvedEpisodeIdentity): Boolean =
        identity.streamSeason == null &&
            identity.streamEpisode != null &&
            identity.videoId.hasNativeAnimePrefix() &&
            identity.ids.tmdb != null &&
            identity.ids.preferredTmdbMediaType != "movie"

    suspend fun locate(identity: ResolvedEpisodeIdentity): TmdbEpisodeLocation? {
        if (!appliesTo(identity)) return null
        val ids = identity.ids
        val tmdbId = ids.tmdb ?: return null
        val entryEpisode = identity.streamEpisode ?: return null
        val counts = TmdbService.fetchSeasonEpisodeCounts(tmdbId) ?: return null
        val absolute = animeEntryAbsoluteEpisode(
            counts = counts,
            entryEpisode = entryEpisode,
            tmdbSeason = ids.tmdbSeason,
            tmdbEpisodeOffset = ids.tmdbEpisodeOffset,
            tvdbSeason = ids.tvdbSeason,
            tvdbEpisodeOffset = ids.tvdbEpisodeOffset,
        ) ?: return null
        val (season, index) = counts.locateAbsoluteEpisode(absolute) ?: return null
        val episodeNumber = TmdbService.fetchSeasonEpisodeNumbers(tmdbId, season)
            ?.getOrNull(index - 1)
            ?: index
        return TmdbEpisodeLocation(
            tmdbId = tmdbId,
            season = season,
            seasonIndex = index,
            episodeNumber = episodeNumber,
        )
    }
}

/**
 * What plugin scrapers receive. Native anime episodes are translated to TMDB; everything else keeps
 * the existing id handling.
 */
internal fun ResolvedEpisodeIdentity.pluginEpisodeTarget(location: TmdbEpisodeLocation?): PluginEpisodeTarget {
    if (location != null) {
        return PluginEpisodeTarget(
            contentId = location.tmdbId.toString(),
            season = location.season,
            episode = location.episodeNumber,
        )
    }
    val tmdbId = ids.tmdb
    if (AnimeTmdbEpisodeLocator.appliesTo(this) && tmdbId != null && season != null && episode != null) {
        // TMDB unreachable: the anime-list placement is still a TMDB id, which beats a kitsu id
        // every scraper rejects.
        return PluginEpisodeTarget(contentId = tmdbId.toString(), season = season, episode = episode)
    }
    return PluginEpisodeTarget(
        contentId = pluginContentId(videoId = videoId, season = streamSeason, episode = streamEpisode),
        season = streamSeason,
        episode = streamEpisode,
    )
}

/** Something the player can open; AIOStreams' scrape-summary rows are external links only. */
internal fun List<StreamItem>.hasPlayableStream(): Boolean =
    any { it.url != null || it.infoHash != null || it.clientResolve != null }

/**
 * Native anime episodes re-addressed in the numbering stream addons and release names use.
 *
 * Releases (and the indexers AIOStreams searches) follow TVDB's seasons, and so does the `tt…:S:E`
 * numbering that Stremio meta addons serve for an IMDb id. TMDB's seasons are a different split
 * (One Piece 150 is TMDB S6 but TVDB S9E7; `S06E07` releases are episode 75), so TMDB coordinates
 * must never reach an addon. Plain counting through the IMDb id's episode list is not safe either:
 * TVDB keeps some episodes out of its seasons (One Piece 590, a crossover special), which shifts
 * everything after it by one.
 *
 * Instead each episode is found by air date in the IMDb id's own episode list, from the same meta
 * addons the app already uses. The result is the id the user would get by opening the show from an
 * IMDb catalog. A weekly show airs one episode a day, so the date names it; days with several
 * episodes are paired in order, and anything unclear is left unmatched rather than guessed.
 */
internal object AnimeFranchiseEpisodeLocator {
    private const val MAX_CACHED_FRANCHISES = 16

    /** Episode lists of franchise (IMDb id) metas, for the session; small LRU. */
    private val franchiseVideos = object : LinkedHashMap<String, List<MetaVideo>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MetaVideo>>?): Boolean =
            size > MAX_CACHED_FRANCHISES
    }
    private val cacheMutex = Mutex()

    fun appliesTo(identity: ResolvedEpisodeIdentity): Boolean =
        identity.streamSeason == null &&
            identity.streamEpisode != null &&
            identity.videoId.hasNativeAnimePrefix() &&
            // A franchise-remapped request already names the right sibling entry.
            !identity.ids.franchiseNumbering &&
            identity.ids.imdb?.startsWith("tt") == true

    /** The `tt…:S:E` id for this native episode, or null when it cannot be matched with confidence. */
    suspend fun franchiseVideoId(identity: ResolvedEpisodeIdentity, type: String, parentMetaId: String?): String? {
        if (!appliesTo(identity)) return null
        val imdb = identity.ids.imdb ?: return null
        val entryBase = identity.videoId.nativeAnimeBase()
        val entryMetaId = parentMetaId?.takeIf { it.equals(entryBase, ignoreCase = true) } ?: entryBase
        val entryVideos = (
            MetaDetailsRepository.peekAny(type, entryMetaId)
                ?: MetaDetailsRepository.fetchForSchedule(type, entryMetaId)
            )?.videos.orEmpty()
        // Only the exact episode: matching by number alone could pick another season's episode in a
        // franchise-style list.
        val entryEpisode = entryVideos.firstOrNull { it.id.equals(identity.videoId, ignoreCase = true) }
            ?: return null
        val franchise = franchiseVideosFor(type, imdb) ?: return null
        return matchFranchiseEpisode(entryEpisode, entryVideos, franchise)?.id
    }

    private suspend fun franchiseVideosFor(type: String, imdb: String): List<MetaVideo>? {
        cacheMutex.withLock { franchiseVideos[imdb] }?.let { return it }
        val videos = MetaDetailsRepository.fetchForSchedule(type, imdb)?.videos
            ?.filter { it.id.startsWith("$imdb:") && (it.season ?: 0) > 0 && it.episode != null }
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        cacheMutex.withLock { franchiseVideos[imdb] = videos }
        return videos
    }
}

/**
 * [entryEpisode]'s counterpart in [franchiseVideos] (main seasons of the IMDb id), by air date.
 *
 * Candidates are franchise episodes aired within a day of it (time zones). When the day holds as
 * many entry episodes as candidates, they pair in order (double-episode days). Otherwise only a
 * clear title match is accepted; translations differ between sources, so titles are a tie-break,
 * not the key.
 */
internal fun matchFranchiseEpisode(
    entryEpisode: MetaVideo,
    entryVideos: List<MetaVideo>,
    franchiseVideos: List<MetaVideo>,
): MetaVideo? {
    val day = entryEpisode.released.epochDay() ?: return null
    val sameDay = entryVideos
        .filter { it.episode != null && it.released.epochDay() == day }
        .sortedBy { it.episode }
    val candidates = franchiseVideos
        .filter { video ->
            val candidateDay = video.released.epochDay() ?: return@filter false
            kotlin.math.abs(candidateDay - day) <= 1
        }
        .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
    if (candidates.isEmpty()) return null
    if (candidates.size == sameDay.size) {
        val index = sameDay.indexOfFirst { it.id == entryEpisode.id }
        return candidates.getOrNull(index)
    }
    val scored = candidates
        .map { it to titleSimilarity(entryEpisode.title, it.title) }
        .sortedByDescending { it.second }
    val (best, score) = scored.first()
    val runnerUp = scored.getOrNull(1)?.second ?: 0.0
    return best.takeIf { score >= 0.6 && score - runnerUp >= 0.15 }
}

/** Days since 1970-01-01 for an ISO date or date-time (`2003-04-13T11:00:00Z`), or null. */
internal fun String?.epochDay(): Long? {
    val text = this?.trim()?.take(10) ?: return null
    if (text.length != 10 || text[4] != '-' || text[7] != '-') return null
    val year = text.substring(0, 4).toIntOrNull() ?: return null
    val month = text.substring(5, 7).toIntOrNull()?.takeIf { it in 1..12 } ?: return null
    val dayOfMonth = text.substring(8, 10).toIntOrNull()?.takeIf { it in 1..31 } ?: return null
    // Howard Hinnant's days-from-civil.
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + dayOfMonth - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097L + doe - 719468L
}

/** Dice coefficient over letter/digit bigrams, case-insensitive: 1.0 for the same title. */
internal fun titleSimilarity(a: String?, b: String?): Double {
    fun bigrams(value: String?): List<String> {
        val normalized = value.orEmpty().lowercase().filter { it.isLetterOrDigit() }
        return (0 until (normalized.length - 1).coerceAtLeast(0)).map { normalized.substring(it, it + 2) }
    }
    val left = bigrams(a)
    val right = bigrams(b)
    if (left.isEmpty() || right.isEmpty()) return 0.0
    val counts = right.groupingBy { it }.eachCount().toMutableMap()
    var shared = 0
    left.forEach { gram ->
        val remaining = counts[gram] ?: 0
        if (remaining > 0) {
            shared++
            counts[gram] = remaining - 1
        }
    }
    return 2.0 * shared / (left.size + right.size)
}

/**
 * The stream request for [franchiseVideoId] at [manifest], or null when the addon does not take
 * IMDb ids for this type.
 */
internal fun animeFranchiseFallbackUrl(franchiseVideoId: String, manifest: AddonManifest, type: String): String? {
    val supportsImdb = manifest.resources.any { resource ->
        resource.name == "stream" &&
            resource.types.contains(type) &&
            (resource.idPrefixes.isEmpty() || resource.idPrefixes.any { franchiseVideoId.startsWith(it) })
    }
    if (!supportsImdb) return null
    return buildAddonResourceUrl(
        manifestUrl = manifest.transportUrl,
        resource = "stream",
        type = type,
        id = franchiseVideoId,
    )
}

/**
 * Retries a native anime episode under its IMDb-id coordinates when the native request produced
 * nothing playable. Returns the replacement streams, or null to keep the original answer.
 */
internal suspend fun animeFranchiseFallbackStreams(
    franchiseVideoId: String?,
    manifest: AddonManifest,
    type: String,
    seriesTitle: String?,
    fetch: suspend (url: String) -> String,
    parse: (payload: String) -> List<StreamItem>,
): List<StreamItem>? {
    franchiseVideoId ?: return null
    val parts = franchiseVideoId.split(':')
    val season = parts.getOrNull(parts.lastIndex - 1)?.toIntOrNull() ?: return null
    val episode = parts.lastOrNull()?.toIntOrNull() ?: return null
    val url = animeFranchiseFallbackUrl(franchiseVideoId, manifest, type) ?: return null
    val streams = parse(fetch(url)).filterForRequestedEpisode(
        season = season,
        episode = episode,
        // The entry meta's titles are keyed by its own numbering (1, 150), not these coordinates.
        episodeTitlesByCoordinate = emptyMap(),
        seriesTitle = seriesTitle,
    )
    return streams.takeIf { it.hasPlayableStream() }
}
