package com.nuvio.app.features.music

import com.nuvio.app.features.trailer.TrailerPlaybackResolver

private object MetadataClientCatalog : MusicCatalog {
    override suspend fun searchAlbums(query: String) = MusicMetadataClient.searchAlbums(query)
    override suspend fun searchArtists(query: String) = MusicMetadataClient.searchArtists(query)
    override suspend fun album(browseId: String) = MusicMetadataClient.album(browseId)
    override suspend fun artist(browseId: String) = MusicMetadataClient.artist(browseId)
    override suspend fun artistReleases(browseId: String) = MusicMetadataClient.artistReleases(browseId)
    override suspend fun searchReleases(query: String) = MusicMetadataClient.searchReleases(query)
    override suspend fun searchSongs(query: String) = MusicMetadataClient.searchSongs(query)
    override suspend fun playlist(playlistId: String) = MusicMetadataClient.playlist(playlistId)
    override suspend fun radio(playlistId: String, videoId: String?, maxTracks: Int) = MusicMetadataClient.radio(playlistId, videoId, maxTracks)
    override suspend fun accountHome() = MusicMetadataClient.accountHome()
    override suspend fun accountPlaylists() = MusicMetadataClient.accountPlaylists()
    override suspend fun accountHistory() = MusicMetadataClient.accountHistory()
    override suspend fun setSongLiked(videoId: String, liked: Boolean) = MusicMetadataClient.setSongLiked(videoId, liked)
    override suspend fun searchEverything(query: String) = MusicMetadataClient.searchEverything(query)
    override suspend fun searchSuggestions(typed: String) = MusicMetadataClient.searchSuggestions(typed)
    override suspend fun searchYouTube(query: String) =
        TrailerPlaybackResolver.searchYouTube(query).distinctBy { it.videoId }.map { it.toMusicTrack() }
}

actual fun defaultMusicCatalog(): MusicCatalog = MetadataClientCatalog
