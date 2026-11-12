package com.nuvio.app.features.music.library
// FORK UI (Spyder): Nick's artist page redesign, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import com.nuvio.app.features.music.MusicAlbum
import com.nuvio.app.features.music.MusicArtistRef
import com.nuvio.app.features.music.MusicTopSong
import com.nuvio.app.features.music.MusicTrack

/*
 * fork:artist-stage - the new artist page's Essentials: YouTube Music's own most-played list for the artist (the artist
 * page's "Songs" shelf), filled in from the albums the songs sit on (length, cover, album title), and the queue that the
 * page's Play Essentials button starts. Pure functions, so they are tested without Compose.
 */

/** How many songs the Essentials list shows and plays. */
internal const val ARTIST_ESSENTIAL_SONGS = 10

/** How many of those songs' albums are opened to fill them in; the rest show without a length. */
internal const val ARTIST_ESSENTIAL_ALBUMS = 6

/** One song of the Essentials list, in YouTube Music's order ([rank] 1 is the most played). */
internal data class EssentialRow(
    val rank: Int,
    val videoId: String,
    val title: String,
    val albumTitle: String?,
    val albumBrowseId: String?,
    val durationSeconds: Int,
    /** The song as its album lists it, when the album was opened and holds it. */
    val track: MusicTrack?,
    val coverUrl: String?,
)

/** The albums to open for [top]: those of its first [ARTIST_ESSENTIAL_SONGS] songs, each once, at most [limit]. */
internal fun essentialAlbumIds(top: List<MusicTopSong>, limit: Int = ARTIST_ESSENTIAL_ALBUMS): List<String> =
    top.take(ARTIST_ESSENTIAL_SONGS).mapNotNull { it.albumBrowseId }.distinct().take(limit)

/**
 * The Essentials rows for [top], each song once (by title, brackets left out), filled in from [albums] (by browse id) where
 * the song's album was opened: matched there by video id, else by title.
 */
internal fun essentialRows(top: List<MusicTopSong>, albums: Map<String, MusicAlbum>, limit: Int = ARTIST_ESSENTIAL_SONGS): List<EssentialRow> {
    val seen = HashSet<String>()
    val rows = ArrayList<EssentialRow>()
    for (song in top) {
        if (rows.size >= limit) break
        val key = songKey(song.title)
        if (key.isNotEmpty() && !seen.add(key)) continue
        val album = song.albumBrowseId?.let { albums[it] }
        val track = album?.tracks?.firstOrNull { it.videoId == song.videoId }
            ?: album?.tracks?.firstOrNull { key.isNotEmpty() && songKey(it.title) == key }
        rows += EssentialRow(
            rank = rows.size + 1,
            videoId = track?.videoId ?: song.videoId,
            title = song.title,
            albumTitle = album?.title,
            albumBrowseId = album?.browseId ?: song.albumBrowseId,
            durationSeconds = track?.durationSeconds ?: 0,
            track = track,
            coverUrl = album?.thumbnailUrl,
        )
    }
    return rows
}

/**
 * Whether YouTube Music's list is short enough that Deezer's should fill it up: fewer than [limit] distinct songs (Gang Starr
 * comes back with 5). A full list never asks Deezer.
 */
internal fun needsDeezerFill(top: List<MusicTopSong>, limit: Int = ARTIST_ESSENTIAL_SONGS): Boolean =
    top.distinctBy { songKey(it.title) }.size < limit

/** One of Deezer's most played songs that YouTube Music's list lacks, with the YouTube Music album to find it on. */
internal data class DeezerFill(val song: DeezerTopSong, val browseId: String)

/**
 * The songs that fill [have] up to [limit]: Deezer's most played first, each only if it is not already in [have] (by title,
 * brackets left out), once however many editions Deezer lists, and only if one of the artist's [releases] can be played from
 * YouTube Music, since a song with nowhere to play it from would be a dead row.
 */
internal fun deezerFills(
    have: List<MusicTopSong>,
    deezer: List<DeezerTopSong>,
    releases: List<LibraryRelease>,
    limit: Int = ARTIST_ESSENTIAL_SONGS,
): List<DeezerFill> {
    val taken = have.map { songKey(it.title) }.filter { it.isNotEmpty() }.toHashSet()
    val room = limit - taken.size
    if (room <= 0) return emptyList()
    val fills = ArrayList<DeezerFill>()
    for (song in bestSongs(deezer, limit = deezer.size)) {
        if (fills.size >= room) break
        val key = songKey(song.title)
        if (key.isEmpty() || key in taken) continue
        val browseId = releaseForSong(song, releases)?.ytBrowseId ?: continue
        taken += key
        fills += DeezerFill(song, browseId)
    }
    return fills
}

/**
 * The [fills] whose album is open in [albums] and holds the song (matched by title, an available track), as top songs for
 * [essentialRows]. A fill whose album is not open yet, or does not hold the song after all, is left out.
 */
internal fun filledTopSongs(fills: List<DeezerFill>, albums: Map<String, MusicAlbum>): List<MusicTopSong> =
    fills.mapNotNull { fill ->
        val album = albums[fill.browseId] ?: return@mapNotNull null
        val key = songKey(fill.song.title)
        val track = album.tracks.firstOrNull { it.isAvailable && songKey(it.title) == key } ?: return@mapNotNull null
        MusicTopSong(track.videoId, track.title, album.browseId)
    }

/** The browse id the Essentials queue carries, so it is never taken for a real album. */
internal fun essentialsAlbumId(artistId: String): String = "essentials:$artistId"

/**
 * The queue Play Essentials starts: [rows] in order, as one album the way a playlist is, each song keeping its own cover
 * and album so the player shows the right ones. Songs its album greys out are left out. Null when nothing can be played.
 */
internal fun essentialsMix(artistId: String, artistName: String, artistThumb: String?, rows: List<EssentialRow>): MusicAlbum? {
    val self = listOf(MusicArtistRef(artistName, artistId))
    val tracks = rows.filter { it.track?.isAvailable != false }.mapIndexed { i, row ->
        MusicTrack(
            videoId = row.videoId,
            title = row.track?.title ?: row.title,
            artists = row.track?.artists?.takeIf { it.isNotEmpty() } ?: self,
            durationSeconds = row.durationSeconds,
            trackNumber = i + 1,
            isAvailable = true,
            coverUrl = row.coverUrl,
            albumTitle = row.albumTitle,
            albumBrowseId = row.albumBrowseId,
        )
    }
    if (tracks.isEmpty()) return null
    return MusicAlbum(
        browseId = essentialsAlbumId(artistId),
        title = "$artistName: Essentials",
        artists = self,
        year = null,
        kind = "Mix",
        thumbnailUrl = artistThumb ?: rows.firstNotNullOfOrNull { it.coverUrl },
        tracks = tracks,
        audioPlaylistId = null,
    )
}

/**
 * The four facts the left panel shows about the artist: fans, rating, releases and playable when known, with a fact that
 * has a value (where they are from, when they began, their first release) taking the place of one that does not, so a
 * tile never shows a dash. [total] and [playable] are the discography's counts.
 */
internal fun artistStageFacts(artist: LibraryArtist, total: Int, playable: Int): List<Pair<String, String>> {
    val began = artist.beginYear?.takeIf { it.isNotBlank() }?.let { year ->
        when {
            artist.type.equals("Person", ignoreCase = true) -> "Born" to year
            artist.type?.lowercase() in setOf("group", "orchestra", "choir") -> "Formed" to year
            else -> "Since" to year
        }
    }
    val first = artist.releases.mapNotNull { (it.year ?: it.releaseDate)?.take(4)?.takeIf { y -> y.length == 4 && y.all(Char::isDigit) } }.minOrNull()
    val candidates = listOf(
        artist.fans?.let { "Fans" to formatCount(it) },
        ratingText(artist.rating)?.let { "Rating" to it },
        "Releases" to "$total",
        "Playable here" to "$playable",
        (artist.area ?: artist.country)?.takeIf { it.isNotBlank() }?.let { "From" to shortPlace(it) },
        began,
        first?.let { "First release" to it },
    )
    return candidates.filterNotNull().take(4)
}

/**
 * The facts under the artist's story, as the left panel draws them: the years active as a line of its own, the span of
 * releases under it, and the genres, style and mood as quiet tags. Anything unknown is left out.
 */
internal class ArtistFactSheet(
    /** "2011–present", "1958–2009". */
    val years: String?,
    /** "Releases 2012 to 2027", or "First release 2012" when there is one year. */
    val releases: String?,
    /** Genres (three at most), then the style when it is not one of them. */
    val tags: List<String>,
    val mood: String?,
) {
    val isEmpty: Boolean get() = years == null && releases == null && tags.isEmpty() && mood == null
}

internal fun artistFactSheet(artist: LibraryArtist): ArtistFactSheet {
    val years = artist.beginYear?.takeIf { it.isNotBlank() }?.let { begin ->
        when {
            artist.ended && artist.endYear != null -> "$begin–${artist.endYear}"
            artist.ended -> "$begin–"
            else -> "$begin–present"
        }
    }
    val dates = artist.releases.mapNotNull { (it.year ?: it.releaseDate)?.take(4)?.takeIf { y -> y.length == 4 && y.all(Char::isDigit) } }
    val first = dates.minOrNull()
    val last = dates.maxOrNull()
    val releases = when {
        first == null -> null
        last == null || last == first -> "First release $first"
        else -> "Releases $first to $last"
    }
    val genres = artist.genres.take(3)
    val style = artist.style?.takeIf { it.isNotBlank() && genres.none { g -> g.equals(it, ignoreCase = true) } }
    return ArtistFactSheet(
        years = years,
        releases = releases,
        tags = genres + listOfNotNull(style),
        mood = artist.mood?.takeIf { it.isNotBlank() },
    )
}

/** The release the centre header shows beside the name: the top album, else the newest album or EP with a cover. */
internal fun stageHeaderRelease(artist: LibraryArtist): LibraryRelease? =
    artistTopReleases(artist, limit = 1).firstOrNull()
        ?: artist.releases.filter { (it.kind == ReleaseKind.Album || it.kind == ReleaseKind.EP) && it.art.cover != null }
            .maxByOrNull { it.releaseDate ?: it.year ?: "" }

/**
 * Whether Wikipedia's page summary found by the artist's name is about a musician, not a namesake (a place, a word): it
 * must speak of music in some way. Used only when the library has no biography of its own.
 */
internal fun looksLikeMusicianText(text: String): Boolean {
    val t = text.lowercase()
    return MUSIC_WORDS.any { Regex("""\b$it""").containsMatchIn(t) }
}

private val MUSIC_WORDS = listOf(
    "music", "musician", "singer", "rapper", "band", "dj", "record producer", "songwriter", "composer", "album", "single",
    "drum and bass", "hip hop", "rock", "duo", "group", "vocalist", "guitarist",
)

/** A place short enough for a fact tile: "Boston, Massachusetts, USA" keeps its last part ("USA") when it would not fit. */
internal fun shortPlace(place: String, max: Int = 14): String =
    if (place.length <= max) place else place.substringAfterLast(',').trim().ifEmpty { place }
