package com.nuvio.app.features.music

private object MetadataClientCatalog : MusicCatalog {
    override suspend fun searchAlbums(query: String) = MusicMetadataClient.searchAlbums(query)
    override suspend fun searchArtists(query: String) = MusicMetadataClient.searchArtists(query)
    override suspend fun album(browseId: String) = MusicMetadataClient.album(browseId)
    override suspend fun artist(browseId: String) = MusicMetadataClient.artist(browseId)
    override suspend fun artistReleases(browseId: String) = MusicMetadataClient.artistReleases(browseId)
    override suspend fun searchReleases(query: String) = MusicMetadataClient.searchReleases(query)
    override suspend fun searchSongs(query: String) = MusicMetadataClient.searchSongs(query)
    override suspend fun playlist(playlistId: String) = MusicMetadataClient.playlist(playlistId)
}

actual fun defaultMusicCatalog(): MusicCatalog = MetadataClientCatalog
