package com.nuvio.app.features.servers

import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.watchprogress.WatchProgressRepository

/**
 * Where to start a prepared media-server file: the server's own resume point when it is newer
 * than the one saved here (upstream's `serverResumePositionMs` in StreamDestination), else null.
 */
internal suspend fun serverResumePositionMs(stream: StreamItem, videoId: String): Long? {
    if (stream.serverTarget == null) return null
    val saved = WatchProgressRepository.progressForVideo(videoId)
    return ServerPlayback.newerResumePositionMs(stream.playableDirectUrl, saved?.lastUpdatedEpochMs)
}
