package com.nuvio.app.features.player.skip

import com.nuvio.app.features.metadata.MediaIdResolver
import com.nuvio.app.features.metadata.ResolvedMediaIds
import com.nuvio.app.features.metadata.nativeAnimeEpisode
import kotlinx.coroutines.CancellationException

/**
 * The id namespaces an anime-list entry can be addressed by, and the `source` value ARM knows each
 * one as. Several carry more than one prefix because the addons in the wild are not consistent
 * about which spelling they emit.
 */
internal enum class AnimeIdNamespace(val armSource: String, val prefixes: List<String>) {
    KITSU("kitsu", listOf("kitsu")),
    MAL("myanimelist", listOf("mal", "myanimelist")),
    ANILIST("anilist", listOf("anilist", "al")),
    ANIDB("anidb", listOf("anidb")),
}

/**
 * Which provider chain a playback addresses, worked out from its content id.
 *
 * Skip providers are keyed on two different identity systems and the id decides which one applies:
 * SkipDB and IntroDB take an IMDb id with season/episode coordinates, while AniSkip and Anime-Skip
 * take an anime-list entry with an entry-local absolute episode. Sending an id to the wrong chain
 * does not fail loudly — every provider simply answers "nothing here" — so the choice is made once,
 * here, where it can be tested.
 */
internal sealed interface SkipLookupTarget {
    /** An IMDb-addressed episode: SkipDB and IntroDB, then the anime chain via ARM. */
    data class Episode(val imdbId: String, val season: Int, val episode: Int) : SkipLookupTarget

    /** An IMDb-addressed film. Only SkipDB holds anything for one. */
    data class Movie(val imdbId: String) : SkipLookupTarget

    /** An anime-list entry plus its own absolute episode number. */
    data class Anime(
        val namespace: AnimeIdNamespace,
        val id: String,
        val episode: Int,
    ) : SkipLookupTarget

    /** An anime-list entry with no episode coordinate at all, i.e. a film. */
    data class AnimeMovie(val namespace: AnimeIdNamespace, val id: String) : SkipLookupTarget
}

/**
 * Routes a playback to its provider chain, or null when the id belongs to no namespace any
 * provider is keyed on (a local file, a `tmdb:` or `tvdb:` id, an addon's own scheme).
 *
 * [season] and [episode] are the coordinates the player is showing, which for a native anime id
 * are the *franchise* numbering and therefore the wrong ones to look up by — the episode baked
 * into the id is entry-local and is what AniSkip and Anime-Skip expect. See
 * `nuviodesktop-anime-id-preference`: a split-cour entry numbers its episodes from 1 while the
 * franchise keeps counting, so the two disagree for exactly the shows that need skipping most.
 */
internal fun skipLookupTargetFor(
    videoId: String?,
    season: Int?,
    episode: Int?,
): SkipLookupTarget? {
    val vid = videoId?.trim().orEmpty()
    if (vid.isEmpty()) return null

    val parts = vid.split(':')
    val prefix = parts.firstOrNull()?.lowercase().orEmpty()

    AnimeIdNamespace.entries.firstOrNull { prefix in it.prefixes }?.let { namespace ->
        val id = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val animeEpisode = vid.nativeAnimeEpisode()
        return when {
            animeEpisode != null -> SkipLookupTarget.Anime(namespace, id, animeEpisode)
            // No episode segment and none from the player either: a film, not an episode whose
            // number went missing. Falling back to the player's episode here would be guessing
            // with franchise numbering, which is the mismatch this routing exists to avoid.
            episode == null -> SkipLookupTarget.AnimeMovie(namespace, id)
            else -> SkipLookupTarget.Anime(namespace, id, episode)
        }
    }

    val imdbId = parts.firstOrNull()?.takeIf { it.startsWith("tt") } ?: return null
    return if (season != null && episode != null) {
        SkipLookupTarget.Episode(imdbId, season, episode)
    } else {
        SkipLookupTarget.Movie(imdbId)
    }
}

/**
 * Routes a playback whose id no provider is keyed on directly, by resolving it first.
 *
 * The prefix form above only recognises `tt` and the anime namespaces, which is every id a
 * Cinemeta-style or Kitsu-style catalogue hands out but not what the rest emit: the TMDB addon and
 * AIOMetadata address content as `tmdb:`, others as `tvdb:` or `simkl:`, and an addon may use a
 * scheme of its own. Left at the prefix step those all fall out as "no skip at all", which reads
 * to the user as the feature being broken rather than the episode being uncovered.
 */
internal suspend fun resolveSkipLookupTarget(
    videoId: String?,
    parentMetaId: String?,
    contentType: String?,
    season: Int?,
    episode: Int?,
): SkipLookupTarget? {
    // The video id, then the parent's. Both are free, and between them they cover every catalogue
    // that already speaks a namespace the providers know — a `tt` parent with an addon-specific
    // video id resolves here rather than going to the network.
    skipLookupTargetFor(videoId, season, episode)?.let { return it }
    skipLookupTargetFor(parentMetaId, season, episode)?.let { return it }

    val parent = parentMetaId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    // The scrobblers run this same resolution for this same playback, and it is cached and
    // single-flighted across callers, so reaching it here is usually free rather than a round trip.
    val ids = runCatching {
        MediaIdResolver.resolve(
            contentType = contentType?.trim()?.takeIf { it.isNotEmpty() } ?: "series",
            parentMetaId = parent,
            videoId = videoId,
            sourceSeasonNumber = season,
            sourceEpisodeNumber = episode,
        )
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        return null
    }
    return skipLookupTargetFromResolvedIds(ids, season, episode)
}

/**
 * Picks the chain for a playback the app has already resolved ids for.
 *
 * [season] and [episode] here came from the metadata provider, so they are *franchise*
 * coordinates — the numbering IMDb uses. That is why an IMDb id wins even for anime: the IMDb
 * chain maps a franchise season onto an anime-list entry itself (ARM lists a title's entries in
 * season order), which is exactly the translation these coordinates need. Handing them straight to
 * the anime chain instead would be the split-cour mismatch in `nuviodesktop-anime-id-preference`,
 * only in the opposite direction.
 */
internal fun skipLookupTargetFromResolvedIds(
    ids: ResolvedMediaIds,
    season: Int?,
    episode: Int?,
): SkipLookupTarget? {
    ids.imdb?.trim()?.takeIf { it.startsWith("tt") }?.let { imdbId ->
        return if (season != null && episode != null) {
            SkipLookupTarget.Episode(imdbId, season, episode)
        } else {
            SkipLookupTarget.Movie(imdbId)
        }
    }

    // No IMDb id at all, so the title exists only on the anime lists. An entry ARM cannot tie to
    // an IMDb title is in practice a standalone one, which reports season 1 and numbers its
    // episodes from 1 — making the player's episode the entry-local number the anime chain wants.
    val anime = listOfNotNull(
        ids.kitsu?.let { AnimeIdNamespace.KITSU to it.toString() },
        ids.mal?.let { AnimeIdNamespace.MAL to it.toString() },
        ids.anilist?.let { AnimeIdNamespace.ANILIST to it.toString() },
        ids.anidb?.let { AnimeIdNamespace.ANIDB to it.toString() },
    ).firstOrNull() ?: return null

    return if (episode != null) {
        SkipLookupTarget.Anime(anime.first, anime.second, episode)
    } else {
        SkipLookupTarget.AnimeMovie(anime.first, anime.second)
    }
}
