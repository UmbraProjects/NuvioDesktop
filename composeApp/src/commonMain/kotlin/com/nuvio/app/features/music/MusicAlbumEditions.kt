package com.nuvio.app.features.music

import com.nuvio.app.features.music.library.MusicLibraryRepository
import kotlinx.coroutines.CancellationException

/** How many "Other versions" are looked at before giving up; an album rarely has more than two or three. */
private const val MAX_EDITIONS_CHECKED = 4

/**
 * fork:album-editions — YouTube Music lists an artist's album once, and its other editions (a deluxe
 * or expanded one) only under that album's "Other versions". The library links a release to the
 * album the artist page lists, which can be the short edition, while the metadata sources describe
 * the long one ("Your library lists 11 tracks; YouTube Music has 7").
 *
 * Given [album] and the track count the library expects ([listed]), returns the edition of the album
 * that has that many tracks, fetched with [fetch], or [album] itself when it already matches, when
 * the page lists no other versions, or when none of them matches.
 */
internal suspend fun matchingEdition(
    album: MusicAlbum,
    listed: Int?,
    fetch: suspend (browseId: String) -> MusicAlbum?,
): MusicAlbum {
    if (listed == null || listed <= 0 || album.tracks.size == listed) return album
    for (id in album.otherVersionIds.take(MAX_EDITIONS_CHECKED)) {
        val other = try {
            fetch(id)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            null
        } ?: continue
        if (other.tracks.size == listed) return other
    }
    return album
}

/**
 * [album], swapped for the edition the library expects when that is a different one; the library's
 * link for the release is moved to it, so its Play button and every later open use it directly.
 */
internal suspend fun libraryEdition(catalog: MusicCatalog, album: MusicAlbum): MusicAlbum {
    val listed = MusicLibraryRepository.state.value.artistOfAlbum(album.browseId)
        ?.releases?.firstOrNull { it.ytBrowseId == album.browseId }?.trackCount
    val edition = matchingEdition(album, listed) { catalog.album(it) }
    if (edition.browseId != album.browseId) MusicLibraryRepository.relinkRelease(album.browseId, edition.browseId)
    return edition
}
