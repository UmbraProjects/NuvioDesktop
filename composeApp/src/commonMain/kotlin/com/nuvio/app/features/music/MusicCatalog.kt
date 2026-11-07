package com.nuvio.app.features.music

/**
 * What the music screens ask for. The real thing is [MusicMetadataClient], which lives in the
 * desktop-only source set (it reaches into the trailer extractor), so screens in common code go
 * through this and tests can hand them a fake.
 *
 * Like the client, every call returns an empty list or null on failure and never throws.
 */
interface MusicCatalog {
    suspend fun searchAlbums(query: String): List<MusicAlbumSummary>
    suspend fun searchArtists(query: String): List<MusicArtistSummary>
    suspend fun album(browseId: String): MusicAlbum?
    suspend fun artist(browseId: String): MusicArtist?

    /**
     * Every album, single and EP of an artist, not just the first few its page shows. Empty on
     * failure. The default is empty so test fakes need not implement it.
     */
    suspend fun artistReleases(browseId: String): List<MusicAlbumSummary> = emptyList()

    /**
     * A plain album search as YouTube Music orders it, without the popularity ranking [searchAlbums] adds
     * (one request, for background look-ups). Empty on failure; the default is empty for test fakes.
     */
    suspend fun searchReleases(query: String): List<MusicAlbumSummary> = emptyList()

    /** fork:music-playlists - songs for [query], best match first, each with its album and cover. Empty on failure. */
    suspend fun searchSongs(query: String): List<MusicTrack> = emptyList()

    /**
     * fork:music-youtube - plain YouTube's videos for [query] as typed, in YouTube's order, as tracks: for songs YouTube
     * Music has no album for. Empty on failure; the default is empty for test fakes.
     */
    suspend fun searchYouTube(query: String): List<MusicTrack> = emptyList()

    /**
     * fork:music-playlists - a YouTube Music or YouTube playlist by id (`PL...`, `OLAK5uy...`, `RDCLAK...`; a leading
     * `VL` is allowed), every page of it up to a few thousand tracks. Null when it could not be read.
     */
    suspend fun playlist(playlistId: String): MusicPlaylistPage? = null

    /**
     * fork:music-radio - a radio or mix as YouTube Music makes it while it plays (its watch-next "up next" queue): the list
     * [playlistId] (a station's `RD...`, an artist's `RDEM...`, a song's `RDAMVM<videoId>`), optionally started from
     * [videoId], read page by page up to [maxTracks]. Null when it could not be read.
     */
    suspend fun radio(playlistId: String, videoId: String? = null, maxTracks: Int = 100): MusicPlaylistPage? = null

    /**
     * fork:music-account - YouTube Music's home for the signed-in account: its shelves that hold playlists and mixes ("Mixed
     * for you" with Discover Mix, My Supermix and the rest; featured playlists). Null when nobody is signed in or it could
     * not be read.
     */
    suspend fun accountHome(): List<MusicHomeShelf>? = null

    /** fork:music-account - the signed-in account's playlists (its library's Playlists, Liked Music among them). Null when nobody is signed in or it could not be read. */
    suspend fun accountPlaylists(): List<MusicPlaylistSummary>? = null

    /** fork:music-account - the signed-in account's YouTube Music History, newest first (up to three pages). Null when nobody is signed in or it could not be read. */
    suspend fun accountHistory(): List<MusicHistoryItem>? = null

    /** fork:music-account - likes ([liked] true) or un-likes the song for the signed-in account (it joins or leaves Liked Music). False when nobody is signed in or YouTube Music refused. */
    suspend fun setSongLiked(videoId: String, liked: Boolean): Boolean = false

    /**
     * fork:music-home - one search for the first screen: YouTube Music's unfiltered answer read whole (its Top result,
     * songs, albums, artists, music videos). When that answer holds no songs or no albums (YouTube Music sometimes
     * sends only artists and videos), the filtered song and album searches fill them in, in parallel. Empty on failure;
     * the default is empty for test fakes.
     */
    suspend fun searchEverything(query: String): MusicSearchEverything = MusicSearchEverything.EMPTY

    /**
     * fork:music-home - YouTube Music's completions for what has been typed so far ("yela" gives "yelawolf",
     * "yelawolf best friend"...), lower case, in its order. Empty on failure; the default is empty for test fakes.
     */
    suspend fun searchSuggestions(typed: String): List<String> = emptyList()
}

/** The catalog the app uses: [MusicMetadataClient] on desktop. */
expect fun defaultMusicCatalog(): MusicCatalog
