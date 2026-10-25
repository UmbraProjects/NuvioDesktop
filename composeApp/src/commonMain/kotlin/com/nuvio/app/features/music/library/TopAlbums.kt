package com.nuvio.app.features.music.library

/**
 * Phase 4d: an artist's top albums, for the shelf's quick play. Pure data, no Compose.
 *
 * Ranking comes from Deezer's top tracks: every track scores for the album it is on. Only albums and
 * EPs that can be played are ranked (a greatest-hits compilation would win on hits alone, and a live
 * album or a single is not what "top albums" means). Albums the person starred come first.
 */

internal const val TOP_ALBUM_COUNT = 5

private fun ReleaseGroup.canBeTopAlbum(): Boolean = isPlayable && (kind == ReleaseKind.Album || kind == ReleaseKind.EP)

private fun LibraryRelease.dateOrder(): String = releaseDate ?: year ?: ""

/** Cards that can be played and are albums or EPs, demos left out. */
private fun topCandidates(releases: List<LibraryRelease>): List<ReleaseGroup> =
    buildDiscography(releases, playableOnly = true, filter = ReleaseFilter.All)
        .eras.flatMap { it.full }
        .filter { it.canBeTopAlbum() }

/**
 * Keys of the top albums among [releases], best first, from Deezer's [topTracks]. A card stands for
 * every version folded into it, so a track on the deluxe edition scores for the standard one too.
 * Empty when no top track lands on a playable album.
 */
internal fun rankTopAlbums(
    releases: List<LibraryRelease>,
    topTracks: List<DeezerTopTrack>,
    limit: Int = TOP_ALBUM_COUNT,
): List<String> {
    if (topTracks.isEmpty()) return emptyList()
    val groups = topCandidates(releases)
    val byDeezerId = HashMap<Long, ReleaseGroup>()
    for (g in groups) for (v in g.versions) v.deezerId?.let { byDeezerId[it] = g }

    // Deezer's popularity number when every track has one, else the position in its list.
    val useRank = topTracks.all { it.rank > 0 }
    val score = HashMap<String, Double>()
    topTracks.forEachIndexed { index, track ->
        val group = byDeezerId[track.albumId] ?: return@forEachIndexed
        val weight = if (useRank) track.rank.toDouble() else (topTracks.size - index).toDouble()
        score[group.primary.key] = (score[group.primary.key] ?: 0.0) + weight
    }
    return groups
        .filter { it.primary.key in score }
        .sortedWith(compareByDescending<ReleaseGroup> { score.getValue(it.primary.key) }.thenBy { it.primary.dateOrder() })
        .take(limit)
        .map { it.primary.key }
}

/**
 * The albums the shelf plays for "Top 5". [rankedCount] of them (the first ones) are really ranked or
 * starred; the rest only fill the five from the newest playable albums, so an artist Deezer knows
 * nothing about still gets a quick play. With [rankedCount] 0 the label should say "Latest", not "Top".
 */
internal data class TopAlbums(val releases: List<LibraryRelease>, val rankedCount: Int)

internal fun selectTopAlbums(artist: LibraryArtist, limit: Int = TOP_ALBUM_COUNT): TopAlbums {
    val groups = topCandidates(artist.releases)
    fun find(key: String): ReleaseGroup? = groups.firstOrNull { g -> g.versions.any { it.key == key } }

    val chosen = LinkedHashMap<String, ReleaseGroup>()
    fun take(group: ReleaseGroup?) {
        if (group != null && chosen.size < limit) chosen.putIfAbsent(group.primary.key, group)
    }
    artist.pinnedTopKeys.forEach { take(find(it)) }
    artist.topAlbumKeys.forEach { take(find(it)) }
    val ranked = chosen.size
    groups.sortedByDescending { it.primary.dateOrder() }.forEach { take(it) }
    return TopAlbums(chosen.values.map { it.primary }, ranked)
}

/** [pinned] with [key] added, or removed when it is there already. The newest [limit] pins are kept. */
internal fun togglePinned(pinned: List<String>, key: String, limit: Int = TOP_ALBUM_COUNT): List<String> =
    if (key in pinned) pinned - key else (pinned + key).takeLast(limit)
