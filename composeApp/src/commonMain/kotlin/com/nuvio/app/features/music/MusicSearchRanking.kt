package com.nuvio.app.features.music

/*
 * fork:music
 *
 * Search ranking for the Music tab. YouTube Music's album and artist filters match on title alone,
 * so "Take Me to Church" listed tribute EPs and covers before Hozier, and "Espresso" listed a dozen
 * songs called Espresso before Sabrina Carpenter's. Its unfiltered search has a "Top result" that
 * does weigh popularity ([MusicTopResult]); these functions put what that card points at first and
 * push the obvious covers down. Pure, so they are tested without the network.
 */

/**
 * Phrases in a release title that mark a cover, tribute or re-edit rather than the original. Not a
 * bare "cover", which would sink real titles such as "Cover Me".
 */
private val COVER_MARKERS = listOf(
    "(cover", "[cover", "cover version", "covers of", "tribute", "karaoke", "in the style of", "originally performed", "made famous",
    "instrumental", "lullaby", "lullabies", "sped up", "slowed", "nightcore", "8-bit", "8 bit",
    "piano version", "acoustic version of", "as made famous", "reimagined", "remake",
)

/** True when [title] reads as someone else's version of a song (a cover, tribute, karaoke or re-edit). */
internal fun looksLikeCover(title: String): Boolean {
    val t = title.lowercase()
    return COVER_MARKERS.any { marker -> Regex("(^|[^a-z])" + Regex.escape(marker) + "([^a-z]|$)").containsMatchIn(t) }
}

private fun MusicArtistRef.sameAs(other: MusicArtistRef): Boolean =
    if (browseId != null && other.browseId != null) browseId == other.browseId
    else name.equals(other.name, ignoreCase = true)

/** True when [album] is by one of [artists]. */
internal fun MusicAlbumSummary.isBy(artists: List<MusicArtistRef>): Boolean =
    this.artists.any { own -> artists.any { it.sameAs(own) } }

/**
 * The album results in the order to show them. The album the top result is (or the album its top
 * song is on, [topAlbum]) comes first, then the top result's artist's releases (from [results],
 * else from [artistResults], a search that named the artist), then the rest as YouTube Music
 * listed them with covers and tributes last. With no [top], only the covers move.
 */
internal fun rankAlbumResults(
    results: List<MusicAlbumSummary>,
    top: MusicTopResult?,
    topAlbum: MusicAlbumSummary? = null,
    artistResults: List<MusicAlbumSummary> = emptyList(),
): List<MusicAlbumSummary> {
    val topArtists = top?.artists.orEmpty()
    val byTopArtist = if (topArtists.isEmpty()) emptyList() else {
        (results + artistResults).filter { it.isBy(topArtists) && !looksLikeCover(it.title) }
    }
    val direct = top?.albumBrowseId?.let { id -> (results + artistResults).firstOrNull { it.browseId == id } }
    // The filtered search's entry for the same album is the richer one (it knows Album / Single / EP).
    val lead = (topAlbum?.let { album -> results.firstOrNull { it.browseId == album.browseId } ?: album }) ?: direct
    val (covers, others) = results.partition { looksLikeCover(it.title) }
    return (listOfNotNull(lead, direct) + byTopArtist + others + covers).distinctBy { it.browseId }
}

/**
 * The artist results in the order to show them: the top result's artists first (the found entry
 * when the filtered search has them, else [extra], else a bare entry from the card), then the rest.
 */
internal fun rankArtistResults(
    results: List<MusicArtistSummary>,
    top: MusicTopResult?,
    extra: List<MusicArtistSummary> = emptyList(),
): List<MusicArtistSummary> {
    val leading = top?.artists.orEmpty().mapNotNull { ref ->
        val id = ref.browseId ?: return@mapNotNull null
        results.firstOrNull { it.browseId == id }
            ?: extra.firstOrNull { it.browseId == id }
            ?: MusicArtistSummary(id, ref.name, if (top?.kind.equals("Artist", ignoreCase = true)) top?.thumbnailUrl else null)
    }
    return (leading + results).distinctBy { it.browseId }
}
