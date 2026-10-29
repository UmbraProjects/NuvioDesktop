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
}

/** The catalog the app uses: [MusicMetadataClient] on desktop. */
expect fun defaultMusicCatalog(): MusicCatalog
