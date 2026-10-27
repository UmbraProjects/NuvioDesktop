package com.nuvio.app.features.music.library

import com.nuvio.app.features.music.MusicAlbum
import com.nuvio.app.features.music.MusicAlbumSummary
import com.nuvio.app.features.music.MusicArtistSummary
import com.nuvio.app.features.music.MusicCatalog
import com.nuvio.app.features.music.MusicPlayback

/**
 * Ladder phase 6: playing and saving a rung.
 *
 * A rung is a MusicBrainz artist; playing needs a YouTube Music one. [rungArtist] finds it by exact name (a namesake
 * is never played), [playRung] picks the album to start with and hands it to the player, and saving goes through
 * [MusicLibraryRepository.addFoundVia] so the artist is stored with how it was found ([foundViaLabel]).
 *
 * Which album: the artist's top album when they are already in the Library with ranked albums (the existing top-album
 * path, [selectTopAlbums]); otherwise the first full album their YouTube Music page lists, else the first release of
 * any kind. A rung with nothing playable is reported, not skipped, so the screen can flag it.
 */

internal sealed interface RungPlay {
    data class Playing(val albumTitle: String, val artistId: String) : RungPlay

    /** No YouTube Music artist by that name, or none of their releases would open. */
    data object NotPlayable : RungPlay
}

/** What a saved artist records as how it was found, for example "ladder: Into shoegaze". */
internal fun foundViaLabel(ladderTitle: String): String = "ladder: $ladderTitle"

/** The search hit with exactly the rung's name (most specific first hit), or null. */
internal fun pickRungArtist(name: String, hits: List<MusicArtistSummary>): MusicArtistSummary? =
    hits.firstOrNull { sameArtistName(it.name, name) }

/** The release to start with: the first full album in the page's own order, else the first release of any kind. */
internal fun pickRungAlbum(releases: List<MusicAlbumSummary>): MusicAlbumSummary? =
    releases.firstOrNull { it.kind.equals("Album", ignoreCase = true) } ?: releases.firstOrNull()

/** The YouTube Music artist behind a rung, or null when there is none by that name. */
internal suspend fun rungArtist(catalog: MusicCatalog, name: String): MusicArtistSummary? =
    pickRungArtist(name, runCatching { catalog.searchArtists(name) }.getOrDefault(emptyList()))

/** The YouTube Music artist behind a rung and the album to start with, already opened and with something playable on it. */
internal data class ResolvedRung(val artist: MusicArtistSummary, val album: MusicAlbum)

/**
 * Finds the rung's YouTube Music artist and the album to start with: the top album when the artist is in the Library
 * ([libraryArtists], [topAlbumId]), else the first full album on their page, else the first release of any kind. Null
 * when there is no such artist, no release, or no track on it that would open. Shared by [playRung] and the audition.
 */
internal suspend fun resolveRungAlbum(
    catalog: MusicCatalog,
    name: String,
    libraryArtists: List<LibraryArtist>,
    topAlbumId: (LibraryArtist) -> String? = { a -> selectTopAlbums(a).releases.firstNotNullOfOrNull { it.ytBrowseId } },
): ResolvedRung? {
    val artist = rungArtist(catalog, name) ?: return null
    val saved = libraryArtists.firstOrNull { it.id == artist.browseId }
    val topId = saved?.let(topAlbumId)
    val albumId = topId ?: run {
        val releases = runCatching { catalog.artistReleases(artist.browseId) }.getOrDefault(emptyList())
            .ifEmpty { runCatching { catalog.artist(artist.browseId) }.getOrNull()?.albums.orEmpty() }
        pickRungAlbum(releases)?.browseId
    } ?: return null
    val album = runCatching { catalog.album(albumId) }.getOrNull()
    if (album == null || album.tracks.none { it.isAvailable }) return null
    return ResolvedRung(artist, album)
}

/**
 * Starts the rung's artist. [play] receives the album to play (the live one hands it to the music player).
 * [libraryArtists] decides whether the top-album path applies; [topAlbumId] names that artist's top playable album.
 */
internal suspend fun playRung(
    catalog: MusicCatalog,
    name: String,
    libraryArtists: List<LibraryArtist>,
    topAlbumId: (LibraryArtist) -> String? = { a -> selectTopAlbums(a).releases.firstNotNullOfOrNull { it.ytBrowseId } },
    play: (MusicAlbum) -> Unit = { MusicPlayback.player.playAlbums(listOf(it)) },
): RungPlay {
    val resolved = resolveRungAlbum(catalog, name, libraryArtists, topAlbumId) ?: return RungPlay.NotPlayable
    play(resolved.album)
    return RungPlay.Playing(resolved.album.title, resolved.artist.browseId)
}

/** Saves the rung's artist to the Library as found through [via]. Returns the channel id, or null when there is no such artist. */
internal suspend fun saveRung(catalog: MusicCatalog, name: String, via: String): String? {
    val artist = rungArtist(catalog, name) ?: return null
    MusicLibraryRepository.addFoundVia(artist.browseId, via, catalog)
    return artist.browseId
}
