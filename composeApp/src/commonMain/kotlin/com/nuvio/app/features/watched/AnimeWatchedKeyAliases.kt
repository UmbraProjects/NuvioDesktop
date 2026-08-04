package com.nuvio.app.features.watched

import com.nuvio.app.features.metadata.AnimeIdMapping
import com.nuvio.app.features.metadata.AnimeIdMappingRepository
import com.nuvio.app.features.metadata.hasAnimeNamespacePrefix

/**
 * Watched keys aliased from a native anime id onto the franchise ids the same entry is known by.
 *
 * Anime content ids are franchise-first now, but history recorded before that change — and any
 * a kitsu-aware addon still records — is keyed on `kitsu:`/`mal:`/`anilist:`/`anidb:`. Without an
 * alias those episodes silently stop counting as watched the moment the id policy changes. Mirrors
 * upstream's `animeAlternateWatchedKeys`: **keys only, never items**, so Continue Watching does not
 * grow a duplicate row for the same episode under a second id.
 *
 * Coordinates are copied unchanged. Anime watched items already carry franchise season/episode —
 * `SimklWatchedRepository` runs `toCanonicalAnimeEpisode` before storing, so only the id half was
 * ever entry-local. Re-deriving them here would shift episodes that were never offset, which is
 * the failure mode `TrackingIdProjection.entryLocalEpisodeNumber` documents.
 *
 * Only native → franchise is aliased. The reverse is genuinely ambiguous — one franchise id covers
 * every season's entry, which is why `AnimeIdMappingRepository.lookup` returns nothing rather than
 * guess which entry a bare `tt…` means.
 */
internal fun animeAlternateWatchedKeys(items: Collection<WatchedItem>): Set<String> {
    if (items.isEmpty()) return emptySet()
    val keys = linkedSetOf<String>()
    for (item in items) {
        val entry = item.id.animeEntryForNativeId() ?: continue
        for (franchiseId in entry.franchiseWatchedIds(item.type)) {
            keys += watchedItemKey(item.type, franchiseId, item.season, item.episode)
        }
    }
    return keys
}

private fun String.animeEntryForNativeId(): AnimeIdMapping? {
    if (!hasAnimeNamespacePrefix()) return null
    val parts = split(':')
    val id = parts.getOrNull(1)?.toIntOrNull() ?: return null
    return when (parts[0].lowercase()) {
        "kitsu" -> AnimeIdMappingRepository.entryForNativeIds(kitsu = id)
        "mal", "myanimelist" -> AnimeIdMappingRepository.entryForNativeIds(mal = id)
        "al", "anilist" -> AnimeIdMappingRepository.entryForNativeIds(anilist = id)
        "anidb" -> AnimeIdMappingRepository.entryForNativeIds(anidb = id)
        else -> null
    }
}

/**
 * Every franchise id this entry could be looked up under, not just the best one — the alias exists
 * to match whatever id the caller happens to hold. Namespace rules match the content-id chain: a
 * movie never takes the entry's TMDB *tv* id, and a TVDB record belongs to the parent series.
 */
private fun AnimeIdMapping.franchiseWatchedIds(contentType: String): List<String> {
    val isMovie = contentType.equals("movie", ignoreCase = true)
    return buildList {
        imdbIds.filter { it.isNotBlank() }.forEach(::add)
        if (isMovie) {
            tmdbMovieIds.forEach { add("tmdb:$it") }
        } else {
            tmdbTvId?.let { add("tmdb:$it") }
            tvdbId?.let { add("tvdb:$it") }
        }
    }
}
