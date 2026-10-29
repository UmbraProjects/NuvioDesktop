package com.nuvio.app.features.music.library

/**
 * Library views, phase C6: Play on the Constellation, as pure data (no Compose, so it is unit-tested).
 *
 * Play on a star plays that artist's top albums in order ([starPlayReleases], the shelf's Top 5). Play cluster plays the
 * top albums of every artist in the genre group, mixed ([clusterPlayReleases], as Play era does across an era). Both go
 * through [playTopAlbums]; when nothing in them can be played the map says so.
 */

/** Most albums Play cluster opens at once: [playTopAlbums] fetches every album together, so a huge group is capped. */
internal const val PLAY_CLUSTER_MAX_ALBUMS = 30

internal const val PLAY_OPENING = "Opening…"
internal const val PLAY_NOTHING = "Nothing here is on YouTube Music yet."
internal const val PLAY_FAILED = "Couldn't open that."

/**
 * The albums Play on a star opens: the artist's top albums ([selectTopAlbums]). An artist with none (only singles or
 * compilations playable) brings its newest playable releases instead, so a star with anything playable is never
 * "nothing". Empty when nothing the artist has can be played.
 */
internal fun starPlayReleases(artist: LibraryArtist): List<LibraryRelease> =
    selectTopAlbums(artist).releases.ifEmpty {
        artist.releases
            .filter { it.isPlayable }
            .sortedByDescending { it.releaseDate ?: it.year ?: "" }
            .take(TOP_ALBUM_COUNT)
    }

/**
 * The albums Play cluster opens: each artist's [starPlayReleases], with artists taking turns (best album first) so a cap
 * at [max] keeps every artist's best rather than the first artists' everything. Artists go in name order, so the same
 * group gives the same albums. Empty when nothing in the group can be played; the caller shuffles.
 */
internal fun clusterPlayReleases(artists: List<LibraryArtist>, max: Int = PLAY_CLUSTER_MAX_ALBUMS): List<LibraryRelease> {
    val perArtist = artists.sortedWith(compareBy({ it.name.lowercase() }, { it.id })).map(::starPlayReleases)
    val rounds = perArtist.maxOfOrNull { it.size } ?: 0
    return (0 until rounds).flatMap { i -> perArtist.mapNotNull { it.getOrNull(i) } }.take(max)
}
