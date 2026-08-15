package com.nuvio.app.features.metadata

/**
 * The franchise id an anime-list entry is addressable by, or null when it has none.
 *
 * Single source of truth for the franchise-first rule: the SIMKL content-id chain and the migration
 * of ids persisted under the old kitsu-first policy must agree, or a migrated store stops matching
 * the ids the app now produces.
 *
 * TMDB movie and tv ids are separate namespaces, so a movie never borrows the entry's tv id (or the
 * reverse). TVDB is series-only for the same reason: an anime film maps onto season 0 of its parent
 * series' TVDB record, not onto a record of its own.
 */
internal fun AnimeIdMapping.franchiseContentId(contentType: String): String? {
    val isMovie = contentType.equals("movie", ignoreCase = true)
    return imdbIds.firstOrNull()?.takeIf { it.isNotBlank() }
        ?: (if (isMovie) tmdbMovieIds.firstOrNull() else tmdbTvId)?.let { "tmdb:$it" }
        ?: tvdbId?.takeIf { !isMovie }?.let { "tvdb:$it" }
}

/**
 * Rewrites a content id persisted under the old kitsu-first policy to its franchise equivalent, or
 * null when it is not a native anime id or the anime-list has no franchise id for it.
 *
 * Stores that hold a content id match it by string equality against ids the app computes now
 * (`MonitoredItem.contentId` against `LocalMediaItem.contentId`, `DownloadItem.parentMetaId`
 * against `MonitoredItem.contentId`). Those computations became franchise-first, so anything
 * written before that silently stops matching — a monitor keeps downloading nothing because its
 * episode list can no longer be fetched, and its finished downloads no longer link back to it.
 *
 * Returning null means "leave it alone": an entry with no franchise id at all is still addressed by
 * its native id, which is exactly what the live chain does.
 */
internal fun migrateLegacyAnimeContentId(
    contentId: String,
    contentType: String,
    preference: AnimeIdPreference = AnimeIdPreferenceRepository.current(),
): String? {
    // Under MAL or KITSU the user has asked for per-entry identity, so a stored native id is not
    // stale — it is what the live derivation produces. Migrating it would be the app fighting the
    // user's choice, and would break the very matches the migration exists to preserve.
    if (preference != AnimeIdPreference.IMDB) return null
    val entry = legacyAnimeEntryFor(contentId) ?: return null
    return entry.franchiseContentId(contentType)
}

/**
 * The video-id form of [migrateLegacyAnimeContentId], converting the episode coordinates too.
 *
 * A persisted `kitsu:49002:1:1` is entry-local — season 1 episode 1 *of that entry*. Rewriting only
 * the base would leave those coordinates against a franchise id, where they mean the first season's
 * first episode: the same wrong-episode bug the id flip produced in Continue Watching, frozen into
 * the download store. The entry's mapped season and cour offset convert them.
 */
internal fun migrateLegacyAnimeVideoId(
    videoId: String,
    contentType: String,
    preference: AnimeIdPreference = AnimeIdPreferenceRepository.current(),
): String? {
    if (preference != AnimeIdPreference.IMDB) return null
    val parts = videoId.split(':')
    if (parts.size != 2 && parts.size != 4) return null
    val base = "${parts[0]}:${parts[1]}"
    val entry = legacyAnimeEntryFor(base) ?: return null
    val franchiseBase = entry.franchiseContentId(contentType) ?: return null
    if (parts.size == 2) return franchiseBase

    val season = parts[2].toIntOrNull() ?: return null
    val episode = parts[3].toIntOrNull() ?: return null
    // Only season 1 is entry-local by convention; anything else was already franchise-numbered and
    // must be passed through untouched rather than shifted a second time.
    if (season != 1) return "$franchiseBase:$season:$episode"
    val mappedSeason = entry.tvdbSeason ?: entry.tmdbSeason ?: return "$franchiseBase:$season:$episode"
    val offset = when {
        entry.tvdbSeason != null -> entry.tvdbEpisodeOffset
        else -> entry.tmdbEpisodeOffset
    } ?: 0
    return "$franchiseBase:$mappedSeason:${episode + offset}"
}

private fun legacyAnimeEntryFor(contentId: String): AnimeIdMapping? {
    if (!contentId.hasAnimeNamespacePrefix()) return null
    val parts = contentId.split(':')
    val id = parts.getOrNull(1)?.toIntOrNull() ?: return null
    return when (parts[0].lowercase()) {
        "kitsu" -> AnimeIdMappingRepository.entryForNativeIds(kitsu = id)
        "mal", "myanimelist" -> AnimeIdMappingRepository.entryForNativeIds(mal = id)
        "al", "anilist" -> AnimeIdMappingRepository.entryForNativeIds(anilist = id)
        "anidb" -> AnimeIdMappingRepository.entryForNativeIds(anidb = id)
        else -> null
    }
}
