package com.nuvio.app.features.music

import co.touchlab.kermit.Logger
import com.nuvio.app.features.music.library.LibraryRelease
import com.nuvio.app.features.music.library.MusicLibraryRepository
import com.nuvio.app.features.music.library.baseTitleKey
import kotlinx.coroutines.CancellationException

private val log = Logger.withTag("MusicEditions")

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
): MusicAlbum = matchingEdition(album, listOfNotNull(listed), fetch)

/**
 * [matchingEdition] for several track counts the library might expect ([wanted]): the first of the
 * album's other versions with any of them. A count the album already has is not looked for.
 */
internal suspend fun matchingEdition(
    album: MusicAlbum,
    wanted: List<Int>,
    fetch: suspend (browseId: String) -> MusicAlbum?,
): MusicAlbum {
    val targets = wanted.filter { it > 0 && it != album.tracks.size }.toSet()
    if (targets.isEmpty()) return album
    for (id in album.otherVersionIds.take(MAX_EDITIONS_CHECKED)) {
        val other = try {
            fetch(id)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            null
        } ?: continue
        if (other.tracks.size in targets) return other
    }
    return album
}

/**
 * fork:album-editions - the fuller edition is not always under "Other versions": YouTube Music can list it as an album of its
 * own (Ren's "Vincent's Tale", 11 tracks, beside the 7-track one). Looks through [candidates] (a search for the album's title
 * and artist) for another album of the same artist and title with one of the [wanted] track counts, fetched with [fetch];
 * [album] itself when none is.
 */
internal suspend fun searchedEdition(
    album: MusicAlbum,
    wanted: List<Int>,
    candidates: List<MusicAlbumSummary>,
    fetch: suspend (browseId: String) -> MusicAlbum?,
): MusicAlbum {
    val targets = wanted.filter { it > 0 && it != album.tracks.size }.toSet()
    if (targets.isEmpty()) return album
    val title = baseTitleKey(album.title)
    val artist = album.artists.firstOrNull()?.name?.trim()?.lowercase()
    val tried = candidates
        .filter { it.browseId != album.browseId && it.browseId !in album.otherVersionIds }
        .filter { baseTitleKey(it.title) == title }
        .filter { c -> artist == null || c.artists.any { it.name.trim().lowercase() == artist } }
        .distinctBy { it.browseId }
        .take(MAX_EDITIONS_CHECKED)
    for (candidate in tried) {
        val other = try {
            fetch(candidate.browseId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            null
        } ?: continue
        if (other.tracks.size in targets) return other
    }
    return album
}

/**
 * The track counts the library expects for the release linked to the YouTube Music album [ytBrowseId]: its
 * own, and those of its sibling versions that are not on YouTube Music. A sibling of the same title that
 * YouTube Music has no page for is usually the longer edition that only "Other versions" leads to, and it
 * carries the count the metadata sources know (a refresh can leave the linked release without one).
 */
internal fun expectedEditionCounts(releases: List<LibraryRelease>, ytBrowseId: String): List<Int> {
    val release = releases.firstOrNull { it.ytBrowseId == ytBrowseId } ?: return emptyList()
    val key = baseTitleKey(release.title)
    val siblings = releases.filter { it.key != release.key && it.ytBrowseId == null && baseTitleKey(it.title) == key }
    return (listOfNotNull(release.trackCount) + siblings.mapNotNull { it.trackCount }).filter { it > 0 }.distinct()
}

/**
 * [album], swapped for the edition the library expects when that is a different one; the library's
 * link for the release is moved to it (and told how many tracks that edition has), so its Play button
 * and every later open use it directly.
 */
internal suspend fun libraryEdition(catalog: MusicCatalog, album: MusicAlbum): MusicAlbum {
    val releases = MusicLibraryRepository.state.value.artistOfAlbum(album.browseId)?.releases.orEmpty()
    val expected = expectedEditionCounts(releases, album.browseId)
    var edition = matchingEdition(album, expected) { catalog.album(it) }
    if (edition.browseId == album.browseId && expected.any { it > 0 && it != album.tracks.size }) {
        val query = (album.artists.firstOrNull()?.name.orEmpty() + " " + album.title).trim()
        val found = try {
            catalog.searchReleases(query).ifEmpty { catalog.searchAlbums(query) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            emptyList()
        }
        edition = searchedEdition(album, expected, found) { catalog.album(it) }
    }
    log.i {
        "album ${album.browseId} has ${album.tracks.size} tracks, the library expects $expected, " +
            "${album.otherVersionIds.size} other versions listed -> ${edition.browseId} (${edition.tracks.size} tracks)"
    }
    if (edition.browseId != album.browseId) {
        MusicLibraryRepository.relinkRelease(album.browseId, edition.browseId, edition.tracks.size)
    } else if (expected.isNotEmpty() && album.tracks.isNotEmpty() && album.tracks.size !in expected) {
        // No edition with the expected count exists on YouTube Music: say so, rather than let the artist page's version chip
        // keep promising the metadata sources' number ("11 tracks · YouTube Music" for a 7-track album).
        MusicLibraryRepository.recordTrackCount(album.browseId, album.tracks.size)
    }
    return edition
}
