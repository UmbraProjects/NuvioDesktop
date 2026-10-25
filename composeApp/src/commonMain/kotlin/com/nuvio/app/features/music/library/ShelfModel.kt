package com.nuvio.app.features.music.library

import kotlin.math.abs

/**
 * Phase 4b: the Library shelf, as pure data. A shelf is one artist; a tile is one folded release shown
 * as its cover. Only releases that can be played are tiles: the rest are what the Discover layer will
 * show later. The artist's top albums come first, then every other playable release oldest to newest.
 */

internal data class ShelfTile(
    val release: LibraryRelease,
    /** 1..5 for a ranked or starred top album, else null. */
    val rank: Int?,
    val featured: Boolean,
    /** The person starred this album, so it leads the top five. */
    val pinned: Boolean,
)

internal data class ShelfRow(
    val artist: LibraryArtist,
    /** The quick-play albums: bigger tiles at the start of the rail. */
    val top: List<ShelfTile>,
    val rest: List<ShelfTile>,
    /** "Top 5", or "Latest 5" when nothing is ranked or starred. */
    val topLabel: String,
    /** Same count as the artist page: [countReleaseCards]. */
    val totalReleases: Int,
    /** Cards that can be played, demos included, as the room's "playable" figure counts them. */
    val playableReleases: Int,
) {
    val tiles: List<ShelfTile> get() = top + rest
}

private fun LibraryRelease.dateKey(): String = releaseDate ?: year ?: ""

internal fun buildShelfRow(artist: LibraryArtist): ShelfRow {
    val total = countReleaseCards(artist.releases)
    val playable = buildDiscography(artist.releases, playableOnly = true, filter = ReleaseFilter.All)
    val topAlbums = selectTopAlbums(artist)
    val topKeys = topAlbums.releases.map { it.key }.toSet()

    val top = topAlbums.releases.mapIndexed { index, release ->
        ShelfTile(
            release = release,
            rank = (index + 1).takeIf { index < topAlbums.rankedCount },
            featured = true,
            pinned = release.key in artist.pinnedTopKeys,
        )
    }
    val rest = playable.eras
        .flatMap { it.full + it.singles }
        .map { it.primary }
        .filter { it.key !in topKeys }
        .sortedBy { it.dateKey() }
        .map { ShelfTile(release = it, rank = null, featured = false, pinned = false) }

    val label = if (topAlbums.rankedCount > 0) "Top ${top.size}" else "Latest ${top.size}"
    return ShelfRow(artist, top, rest, label, totalReleases = total, playableReleases = playable.playableCount)
}

// region a year ago this week

internal data class YearAgoPick(val artist: LibraryArtist, val release: LibraryRelease)

private val cumulativeDays = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)

private fun ordinal(month: Int, day: Int): Int = cumulativeDays[month - 1] + day

/**
 * The release whose full date falls within [windowDays] of today's date one year earlier. A playable
 * release beats an unplayable one, then the nearest date, then a full release over a single. Releases
 * with only a year or month are ignored (no day to compare). A window crossing New Year is not handled.
 */
internal fun pickYearAgo(
    artists: List<LibraryArtist>,
    todayYear: Int,
    todayMonth: Int,
    todayDay: Int,
    windowDays: Int = 3,
): YearAgoPick? {
    val target = ordinal(todayMonth, todayDay)
    return artists.asSequence()
        .flatMap { a -> a.releases.asSequence().filterNot { it.isDemo() }.map { a to it } }
        .mapNotNull { (a, r) ->
            val p = r.releaseDate?.takeIf { it.length == 10 }?.split("-") ?: return@mapNotNull null
            val y = p[0].toIntOrNull() ?: return@mapNotNull null
            val m = p[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return@mapNotNull null
            val d = p[2].toIntOrNull()?.takeIf { it in 1..31 } ?: return@mapNotNull null
            if (y != todayYear - 1) return@mapNotNull null
            val diff = abs(ordinal(m, d) - target)
            if (diff > windowDays) null else Triple(YearAgoPick(a, r), diff, r)
        }
        .sortedWith(
            compareBy<Triple<YearAgoPick, Int, LibraryRelease>>(
                { !it.third.isPlayable },
                { it.second },
                { it.third.kind == ReleaseKind.Single },
            ),
        )
        .firstOrNull()?.first
}

// endregion
