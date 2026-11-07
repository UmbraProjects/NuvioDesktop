package com.nuvio.app.features.music

/** How YouTube Music classifies a video id. Audio mode wants [ArtTrack]; video mode wants [OfficialVideo]. */
enum class MusicVideoType {
    /** The album's own audio-only upload ("art track"). */
    ArtTrack,
    /** The official music video. */
    OfficialVideo,
    /** Anything a user uploaded: lyric videos, live cuts, covers. */
    UserGenerated,
    Other,
}

data class MusicArtistRef(
    val name: String,
    /** Channel browse id (`UC...`); null when the response names the artist without linking it. */
    val browseId: String? = null,
)

data class MusicAlbumSummary(
    /** Album browse id (`MPREb_...`), the key for [MusicMetadataClient.album]. */
    val browseId: String,
    val title: String,
    val artists: List<MusicArtistRef>,
    val year: String?,
    /** "Album", "Single", "EP" when the response says. */
    val kind: String?,
    val thumbnailUrl: String?,
)

data class MusicArtistSummary(
    val browseId: String,
    val name: String,
    val thumbnailUrl: String?,
)

data class MusicTrack(
    /** Id of the album's audio track. Plays in audio mode as it is. */
    val videoId: String,
    val title: String,
    val artists: List<MusicArtistRef>,
    val durationSeconds: Int,
    val trackNumber: Int,
    /** False when YouTube Music greys the track out (removed or blocked here). */
    val isAvailable: Boolean,
    /**
     * fork:music-playlists - the track's own cover, album and album page. Set for a playlist's tracks, which come from
     * many albums; an album's own tracks leave them null and the album's are used ([MusicPlayerState.coverUrl]).
     */
    val coverUrl: String? = null,
    val albumTitle: String? = null,
    val albumBrowseId: String? = null,
)

data class MusicAlbum(
    val browseId: String,
    val title: String,
    val artists: List<MusicArtistRef>,
    val year: String?,
    val kind: String?,
    val thumbnailUrl: String?,
    val tracks: List<MusicTrack>,
    /** The album's audio playlist (`OLAK5uy_...`) when the page names it. */
    val audioPlaylistId: String?,
    /** Browse ids of the page's "Other versions" (a deluxe or expanded edition, say), when it lists any. */
    val otherVersionIds: List<String> = emptyList(),
    /** The page's description when YouTube Music says it is "From Wikipedia" (the album's own Wikipedia opening); null otherwise. */
    val wikipediaBlurb: String? = null,
    /** fork:lenses - true when YouTube Music marks the album, or any of its tracks, explicit (its "E" badge). */
    val explicit: Boolean = false,
)

data class MusicArtist(
    val browseId: String,
    val name: String,
    val thumbnailUrl: String?,
    val albums: List<MusicAlbumSummary>,
    /** The page's "Songs" shelf: YouTube Music's own most-played list for the artist, most popular first. */
    val topSongs: List<MusicTopSong> = emptyList(),
    /** fork:music-radio - the artist's radio (`RDEM...`/`RDAO...`) when the page offers one. */
    val radioPlaylistId: String? = null,
)

/**
 * fork:music-radio - a playlist or a station found by search (or, later, on YouTube Music's home): its id (`PL...`,
 * `RDCLAK...` and the other fixed lists, or a radio's `RD...`), name, the line under it and its cover. [radio] lists are
 * made as they play (YouTube Music's "up next"), so they are read with the watch-next call rather than as a page.
 */
data class MusicPlaylistSummary(
    val playlistId: String,
    val title: String,
    val subtitle: String?,
    val thumbnailUrl: String?,
) {
    val radio: Boolean get() = isRadioPlaylistId(playlistId)
}

/** True for a list YouTube Music makes as it plays (a radio or mix, `RD...`), false for a fixed one (`PL...`, `OLAK5uy...`, `RDCLAK...`). */
fun isRadioPlaylistId(id: String): Boolean {
    val bare = id.removePrefix("VL")
    return bare.startsWith("RD") && !bare.startsWith("RDCLAK")
}

/** One entry of an artist's most-played list. [albumBrowseId] is the album it is listed under, when the page names one. */
data class MusicTopSong(
    val videoId: String,
    val title: String,
    val albumBrowseId: String? = null,
)

/** A track as the watch-next queue lists it, with the counterpart YouTube Music links to it. */
data class MusicWatchTrack(
    val videoId: String,
    val title: String,
    val durationSeconds: Int,
    val videoType: MusicVideoType,
    val counterpart: MusicWatchTrack? = null,
)

/** The video that goes with an audio track (or the audio that goes with a video). */
data class MusicCounterpart(
    val sourceVideoId: String,
    val videoId: String,
    val videoType: MusicVideoType,
    val title: String,
    val durationSeconds: Int,
)
