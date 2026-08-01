package com.nuvio.app.features.tracking

import com.nuvio.app.features.metadata.ResolvedMediaIds
import com.nuvio.app.features.metadata.canonicalEpisodeNumber
import com.nuvio.app.features.metadata.canonicalSeasonNumber

/**
 * How a tracking provider addresses episodes of an anime.
 *
 * Every provider belongs to exactly one family, and the choice is a property of the provider's API,
 * not of the content:
 *
 * - [ENTRY_LOCAL] — season is always 1 and the episode is absolute *within one anime-list entry*.
 *   SAO season 3 is its own entry starting again at episode 1. SIMKL works this way, as do the
 *   Kitsu / MAL / AniList / AniDB namespaces.
 * - [FRANCHISE] — one show with many seasons, each episode numbered within its season, exactly as
 *   TVDB and TMDB record it. Trakt, MDBList and Yamtrack all work this way.
 *
 * Non-anime content is identical in both families; the distinction only bites for anime, where the
 * two numbering systems disagree and a wrong answer still returns HTTP 200.
 */
internal enum class TrackingCoordinateFamily {
    ENTRY_LOCAL,
    FRANCHISE,
}

/**
 * Episode coordinates for one scrobble, plus whether the per-entry native ids may accompany them.
 *
 * Providers own their own id containers, so this carries the *decision* rather than the ids: see
 * [TrackingScrobbleCoordinates.retainsNativeAnimeIds].
 */
internal data class TrackingScrobbleCoordinates(
    val season: Int?,
    val episode: Int?,
    val isAnime: Boolean,
    /**
     * False when the per-entry native ids (simkl/mal/kitsu/anilist/anidb) must be dropped because
     * they contradict the season/episode being sent. Always true for [TrackingCoordinateFamily
     * .FRANCHISE], which never pins a request to a single anime-list entry.
     */
    val retainsNativeAnimeIds: Boolean,
)

/**
 * The single place where an anime coordinate system is chosen.
 *
 * Pure arithmetic over the mapping already resolved by `MediaIdResolver` — no id container and no
 * network. Whether a provider can address the item at all is a separate question it answers from
 * its own accepted id set (see [hasFranchiseScrobbleId]), because some providers only learn their
 * usable ids after an enrichment round-trip.
 */
internal fun ResolvedMediaIds.projectScrobbleCoordinates(
    family: TrackingCoordinateFamily,
    sourceSeason: Int?,
    sourceEpisode: Int?,
    isAnime: Boolean,
): TrackingScrobbleCoordinates = when (family) {
    TrackingCoordinateFamily.FRANCHISE -> TrackingScrobbleCoordinates(
        // Entry-local sources report season 1; canonicalSeasonNumber lifts that onto the mapped
        // TVDB/TMDB season and canonicalEpisodeNumber adds the split-cour offset. Both are no-ops
        // for content that is already franchise-numbered.
        season = sourceSeason?.let { canonicalSeasonNumber(it) ?: it },
        episode = sourceEpisode?.let { canonicalEpisodeNumber(it) ?: it },
        isAnime = isAnime,
        // A franchise request is never pinned to one entry, so per-entry ids can ride along
        // harmlessly — Trakt has always sent them.
        retainsNativeAnimeIds = true,
    )

    TrackingCoordinateFamily.ENTRY_LOCAL -> TrackingScrobbleCoordinates(
        season = sourceSeason?.let { entryLocalSeasonNumber(it, isAnime) },
        episode = sourceEpisode?.let { entryLocalEpisodeNumber(sourceSeason, it, isAnime) },
        isAnime = isAnime,
        retainsNativeAnimeIds = retainsEntryLocalNativeIds(isAnime),
    )
}

/**
 * True when at least one franchise-level id is present.
 *
 * The [TrackingCoordinateFamily.FRANCHISE] providers accept only imdb/tmdb/tvdb/trakt ids, so an
 * entry that resolved to nothing but a Kitsu or MAL id cannot be addressed by them at all. That is
 * now the permanent state of anime that started airing after the anime-list snapshot's cut-off, so
 * it is an ordinary case rather than an edge case — callers must skip, never guess, because a
 * plausible-looking guess is accepted silently and marks the wrong episode watched.
 */
internal val ResolvedMediaIds.hasFranchiseScrobbleId: Boolean
    get() = trakt != null || !imdb.isNullOrBlank() || tmdb != null || tvdb != null

/**
 * Franchise season → entry-local season.
 *
 * An entry's own season is 1 by definition. When the caller addressed the entry by its mapped
 * TVDB/TMDB season, that collapses to 1; any other season number is passed through, because it did
 * not come from this entry's mapping.
 */
private fun ResolvedMediaIds.entryLocalSeasonNumber(sourceSeason: Int, isAnime: Boolean): Int {
    if (!isAnime || simkl == null) return sourceSeason
    val mappedSeason = tmdbSeason ?: tvdbSeason ?: return sourceSeason
    return if (sourceSeason == mappedSeason) 1 else sourceSeason
}

/**
 * Franchise episode → entry-local episode, by removing the split-cour offset that placed this
 * entry inside its TVDB/TMDB season.
 *
 * The offset is removed **only when the season was actually collapsed** — that is, when the caller
 * addressed this entry by its mapped TVDB/TMDB season. Any other season number means the
 * coordinates are not this entry's to shift, and shifting them anyway corrupts them.
 *
 * The shipped code omitted this check while [entryLocalSeasonNumber] applied its equivalent, so the
 * two disagreed in two real cases: an entry-local source (a Kitsu meta reporting S1E3 of a
 * second-cour entry) had the offset subtracted from an episode it was never added to, and an
 * ambiguous franchise season had its franchise episode number shifted while its franchise season
 * was passed through untouched. Both sent a wrong-but-plausible episode, which SIMKL accepts.
 */
private fun ResolvedMediaIds.entryLocalEpisodeNumber(
    sourceSeason: Int?,
    sourceEpisode: Int,
    isAnime: Boolean,
): Int {
    if (!isAnime || simkl == null) return sourceEpisode
    val mappedSeason = tmdbSeason ?: tvdbSeason ?: return sourceEpisode
    if (sourceSeason == null || sourceSeason != mappedSeason) return sourceEpisode
    val offset = tmdbEpisodeOffset ?: tvdbEpisodeOffset ?: 0
    return (sourceEpisode - offset).coerceAtLeast(1)
}

/**
 * Whether the per-entry native ids still describe the episode being sent.
 *
 * A franchise-numbered request can fail to land on one unambiguous anime-list entry — long-running
 * franchises such as Pokémon span several TVDB seasons inside a single entry. The native ids
 * retained from the opened or adjacent entry are then actively harmful: pairing that entry's id
 * with a franchise season produces an episode that cannot exist. Dropping them leaves the
 * franchise ids, and lets the provider's own seasonal-anime mapping translate the TVDB/TMDB
 * coordinates instead.
 */
private fun ResolvedMediaIds.retainsEntryLocalNativeIds(isAnime: Boolean): Boolean =
    !(isAnime && franchiseNumbering && nativeMappingCoversFranchiseEpisode == false)
