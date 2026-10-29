package com.nuvio.app.features.music

actual suspend fun resolveMusicTrack(track: MusicTrack, album: MusicAlbum, mode: MusicPlayMode): MusicResolution =
    MusicResolver.resolve(track, album, mode)
