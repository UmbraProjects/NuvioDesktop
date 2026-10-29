package com.nuvio.app.features.music

private object MetadataClientCatalog : MusicCatalog {
    override suspend fun searchAlbums(query: String) = MusicMetadataClient.searchAlbums(query)
    override suspend fun searchArtists(query: String) = MusicMetadataClient.searchArtists(query)
    override suspend fun album(browseId: String) = MusicMetadataClient.album(browseId)
    override suspend fun artist(browseId: String) = MusicMetadataClient.artist(browseId)
    override suspend fun artistReleases(browseId: String) = MusicMetadataClient.artistReleases(browseId)
    override suspend fun searchReleases(query: String) = MusicMetadataClient.searchReleases(query)
}

actual fun defaultMusicCatalog(): MusicCatalog = MetadataClientCatalog
