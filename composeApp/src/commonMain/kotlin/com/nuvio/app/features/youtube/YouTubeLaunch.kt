package com.nuvio.app.features.youtube

import com.nuvio.app.features.player.PlayerLaunch
import kotlinx.coroutines.CancellationException

/*
 * fork:youtube-mode
 *
 * A YouTube video as the player opens it: its streams resolved (the music resolver's restricted-client
 * retry and probe), titled as the tab shows it. Shared by the tab and by a playlist moving on in the
 * player (YouTubePlayQueue).
 */

/** The title [video] shows: DeArrow's when it has one and DeArrow is on. */
internal fun youTubeDisplayTitle(video: YouTubeVideo): String =
    YouTubeDeArrow.cached(video.videoId)?.title?.takeIf { YouTubeFeedRepository.deArrow.value } ?: video.title

/** [video] ready for the player, or null when none of its streams would open. */
internal suspend fun youTubePlayerLaunch(video: YouTubeVideo): PlayerLaunch? {
    val source = try {
        youTubeOpenVideo(video.videoId)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        null
    } ?: return null
    val title = youTubeDisplayTitle(video)
    return PlayerLaunch(
        title = title,
        sourceUrl = source.videoUrl,
        sourceAudioUrl = source.audioUrl,
        sourceHeaders = source.headers,
        streamTitle = title,
        streamSubtitle = video.channel,
        providerName = "YouTube",
        poster = video.thumbnailUrl,
        background = video.thumbnailUrl,
        contentType = YOUTUBE_META_TYPE,
        videoId = video.videoId,
        parentMetaId = YOUTUBE_META_ID_PREFIX + video.videoId,
        parentMetaType = YOUTUBE_META_TYPE,
        // Picks up where YouTube says this account left off, unless it was finished. A source that
        // cannot be opened part way in starts from the top instead: mpv would wait on that seek for good.
        initialProgressFraction = video.watchedPercent
            ?.takeIf { it in 1..94 && source.openableMidway }
            ?.div(100f),
        // Kept out of Continue Watching and the trackers; YouTube's own history gets it
        // instead (PlayerYouTubeWatchTime.kt).
        disableProgressTracking = true,
    )
}
