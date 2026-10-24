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
}

/** The catalog the app uses: [MusicMetadataClient] on desktop. */
expect fun defaultMusicCatalog(): MusicCatalog
