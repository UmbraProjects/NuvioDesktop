package com.nuvio.app.features.music.library

import com.nuvio.app.features.music.MusicAlbumSummary
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/*
 * fork:music
 *
 * A collaboration often lives on the other artist's YouTube Music channel: "Caught It (with Chrissy
 * Costanza & Against The Current)" is VOILÀ's single, so Against The Current's own shelves never list
 * it and the release MusicBrainz knows stays unplayable. After the merge, each release still without a
 * YouTube Music album is looked for by title, and a hit is taken only when it is plainly the same
 * release by this artist.
 */

/** Releases looked for per artist at most, so a huge discography does not mean hundreds of searches. */
internal const val MAX_COLLAB_SEARCHES = 30

private const val COLLAB_SEARCH_PARALLEL = 3

/**
 * The hit that is [title] by this artist, or null. Same title once credits are set aside
 * ([baseTitleKey], so the "(with …)" a collaboration carries does not get in the way), and the artist
 * credited on it (by channel [artistId] or by [artistName]) or named in its title. [taken] are album ids
 * already on the page, which are never handed out twice.
 */
internal fun pickCollabRelease(
    title: String,
    artistName: String,
    artistId: String?,
    hits: List<MusicAlbumSummary>,
    taken: Set<String>,
): MusicAlbumSummary? {
    val key = baseTitleKey(title)
    val nameKey = strictTitleKey(artistName)
    return hits.firstOrNull { hit ->
        hit.browseId !in taken &&
            baseTitleKey(hit.title) == key &&
            (
                hit.artists.any { (artistId != null && it.browseId == artistId) || sameArtistName(it.name, artistName) } ||
                    (nameKey.isNotEmpty() && nameKey in strictTitleKey(hit.title))
                )
    }
}

/**
 * [releases] with the unplayable ones that turn up elsewhere on YouTube Music linked to it. [search]
 * is an album search (empty on failure). At most [MAX_COLLAB_SEARCHES] releases are looked for, newest
 * first, a few at a time.
 */
internal suspend fun withCollabReleases(
    releases: List<LibraryRelease>,
    artistName: String,
    artistId: String?,
    search: suspend (String) -> List<MusicAlbumSummary>,
): List<LibraryRelease> = coroutineScope {
    val missing = releases.filter { it.ytBrowseId == null && !it.isDemo() }
        .sortedByDescending { it.releaseDate ?: it.year ?: "" }
        .take(MAX_COLLAB_SEARCHES)
    if (missing.isEmpty()) return@coroutineScope releases
    val gate = Semaphore(COLLAB_SEARCH_PARALLEL)
    val results = missing.map { release ->
        async { release.key to gate.withPermit { runCatching { search("${release.title} $artistName") }.getOrDefault(emptyList()) } }
    }.awaitAll().toMap()
    val taken = releases.mapNotNull { it.ytBrowseId }.toMutableSet()
    releases.map { release ->
        val hits = results[release.key] ?: return@map release
        val hit = pickCollabRelease(release.title, artistName, artistId, hits, taken) ?: return@map release
        taken += hit.browseId
        release.copy(
            ytBrowseId = hit.browseId,
            art = if (release.art.covers.isEmpty() && hit.thumbnailUrl != null) {
                release.art.copy(covers = listOfNotNull(enlargeYtThumbnail(hit.thumbnailUrl)))
            } else {
                release.art
            },
        )
    }
}
