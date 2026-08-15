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
 * Coordinates are projected, not copied: a native id is entry-local on both halves — see
 * `SimklModels.episodeCoordinatesFor` — so the franchise alias has to be lifted onto the season the
 * entry maps to. Only where that lift is unambiguous, though; the rest are copied unchanged rather
 * than shifted on a guess, the failure mode `TrackingIdProjection.entryLocalEpisodeNumber`
 * documents.
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
        val (season, episode) = entry.franchiseCoordinatesFor(item.season, item.episode)
        for (franchiseId in entry.franchiseWatchedIds(item.type)) {
            keys += watchedItemKey(item.type, franchiseId, season, episode)
        }
    }
    return keys
}

/**
 * Lifts entry-local coordinates onto the franchise, where that can be told apart from coordinates
 * that are franchise already.
 *
 * A native id is addressed entry-locally by every live source — a Kitsu addon's details page and a
 * SIMKL pull alike present the entry as season 1 — so copying those coordinates onto a franchise id
 * claims the franchise's first season instead: Pokémon Best Wishes episode 1 would badge Pokémon
 * season 1 episode 1.
 *
 * The stored item does not record which space it used, and history written before SIMKL stopped
 * canonicalising is franchise-numbered under the same kind of id, so the discriminator is the mapped
 * season. Season 1 is the entry's own by definition, so a stored season of 1 is *a candidate* for
 * lifting — but only when the entry maps somewhere other than season 1, which is the case where
 * lifting is observable and where leaving it alone is visibly wrong. When the entry maps to season 1
 * the two spaces differ only by a cour offset, which is too small a difference to tell an old
 * already-offset coordinate from a current un-offset one; shifting on a guess would move an episode
 * that was never offset, so those are left untouched. The cost is that a second-cour entry's
 * franchise alias stays un-offset — visible only on the franchise id's own details page, which a
 * user on the MAL/KITSU preference does not open.
 */
private fun AnimeIdMapping.franchiseCoordinatesFor(season: Int?, episode: Int?): Pair<Int?, Int?> {
    if (season != 1 || episode == null) return season to episode
    val mappedSeason = tvdbSeason ?: tmdbSeason ?: return season to episode
    if (mappedSeason == 1) return season to episode
    val offset = (if (tvdbSeason != null) tvdbEpisodeOffset else tmdbEpisodeOffset) ?: 0
    return mappedSeason to (episode + offset)
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
