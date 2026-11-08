package com.nuvio.app.features.youtube

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.remote.RemoteYouTubeOpen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * fork:phone-remote — a YouTube video shared from the phone ("Send to Nuvio"), ready for the player. The link carries
 * only the id, so the watch page is read first for its title and channel (a few seconds at most; without it the video
 * still opens, titled "YouTube video"). Null, after saying so, when none of its streams would open.
 */
internal suspend fun youTubeRemoteLaunch(request: RemoteYouTubeOpen): PlayerLaunch? {
    NuvioToastController.show("Opening a video from your phone…")
    val details = withTimeoutOrNull(DETAILS_WAIT_MS) {
        try {
            YouTubeFeedRepository.watchPage(request.videoId).details
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
    }
    val video = YouTubeVideo(
        videoId = request.videoId,
        title = details?.title?.takeIf { it.isNotBlank() } ?: "YouTube video",
        channel = details?.channelName,
        details = null,
        duration = null,
        thumbnailUrl = "https://i.ytimg.com/vi/${request.videoId}/hqdefault.jpg",
        channelId = details?.channelId,
    )
    val launch = youTubePlayerLaunch(video)
    if (launch == null) {
        NuvioToastController.show("Couldn't play the video from your phone")
        return null
    }
    // A link with a time in it (?t=90) starts there rather than where YouTube's history left off.
    val start = request.startSeconds?.takeIf { it > 0 } ?: return launch
    return launch.copy(initialPositionMs = start * 1000L, initialProgressFraction = null)
}

private const val DETAILS_WAIT_MS = 5_000L
