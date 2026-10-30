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
     * fork:music-playlists - a YouTube Music or YouTube playlist by id (`PL...`, `OLAK5uy...`, `RDCLAK...`; a leading
     * `VL` is allowed), every page of it up to a few thousand tracks. Null when it could not be read.
     */
    suspend fun playlist(playlistId: String): MusicPlaylistPage? = null
}

/** The catalog the app uses: [MusicMetadataClient] on desktop. */
expect fun defaultMusicCatalog(): MusicCatalog
