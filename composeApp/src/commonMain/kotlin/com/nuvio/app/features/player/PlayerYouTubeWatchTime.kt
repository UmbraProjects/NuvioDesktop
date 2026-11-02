package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.nuvio.app.features.watchprogress.WatchProgressClock
import com.nuvio.app.features.youtube.YOUTUBE_META_ID_PREFIX
import com.nuvio.app.features.youtube.YOUTUBE_META_TYPE
import com.nuvio.app.features.youtube.YouTubeFeedRepository
import com.nuvio.app.features.youtube.YouTubeWatchSession

/**
 * fork:youtube-mode - reports a video opened from the YouTube tab to the account's watch history
 * (see [YouTubeWatchSession]). Nothing else in the player tracks these launches: they carry
 * `disableProgressTracking`, so Continue Watching and the trackers never see them.
 */
@Composable
internal fun PlayerScreenRuntime.BindYouTubeWatchTimeEffect() {
    if (parentMetaType != YOUTUBE_META_TYPE) return
    val videoId = parentMetaId.removePrefix(YOUTUBE_META_ID_PREFIX)
    val session = remember(videoId) {
        YouTubeWatchSession(
            videoId = videoId,
            pageId = YouTubeFeedRepository.channel.value.pageId,
            clock = WatchProgressClock::nowEpochMs,
        )
    }
    val snapshot = playbackSnapshot
    LaunchedEffect(session, snapshot.positionMs, snapshot.isPlaying, snapshot.isLoading, snapshot.durationMs) {
        session.onProgress(
            positionMs = snapshot.positionMs,
            durationMs = snapshot.durationMs,
            playing = snapshot.isPlaying && !snapshot.isLoading,
        )
    }
    // In a playlist being played through: the next video is resolved during this one's last minute
    // (or last tenth), so the player can be handed straight over to it when this one ends.
    val nearEnd = snapshot.durationMs > 0L &&
        snapshot.positionMs >= snapshot.durationMs - minOf(60_000L, snapshot.durationMs / 10)
    LaunchedEffect(videoId, nearEnd) {
        if (nearEnd) com.nuvio.app.features.youtube.YouTubePlayQueue.prepareNext(videoId)
    }
    DisposableEffect(session) {
        onDispose {
            // The feed shows the new bar straight away; YouTube's own copy catches up on the next load.
            session.close()?.let { percent ->
                YouTubeFeedRepository.noteWatched(videoId, percent)
                // 100 only when it was played to its end (see YouTubeWatchSession.close).
                if (percent == 100) com.nuvio.app.features.youtube.YouTubePlayQueue.onFinished(videoId)
            }
        }
    }
}
