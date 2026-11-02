package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.nuvio.app.features.youtube.YOUTUBE_META_ID_PREFIX
import com.nuvio.app.features.youtube.YOUTUBE_META_TYPE
import com.nuvio.app.features.youtube.YouTubeFeedRepository
import com.nuvio.app.features.youtube.YouTubePlayQueue
import com.nuvio.app.features.youtube.isYouTubeVideoWatched
import com.nuvio.app.features.youtube.youTubeDisplayTitle
import kotlinx.coroutines.launch

/*
 * fork:youtube-mode
 *
 * A YouTube tab video in the player. It has no other sources and no episodes, so the HUD's two panels
 * are put to use: Episodes lists the videos YouTube suggests beside it (picking one hands the player
 * straight over to it, as a playlist moves on), and Sources lists its top comments, read-only.
 */

internal val PlayerScreenRuntime.isYouTubeVideoPlayback: Boolean
    get() = parentMetaType == YOUTUBE_META_TYPE

private val PlayerScreenRuntime.youTubeVideoId: String
    get() = parentMetaId.removePrefix(YOUTUBE_META_ID_PREFIX)

/** Loads the related videos when a YouTube tab video opens. */
@Composable
internal fun PlayerScreenRuntime.BindYouTubeRelatedEffect() {
    if (!isYouTubeVideoPlayback) return
    val videoId = youTubeVideoId
    LaunchedEffect(videoId) {
        youTubeRelated = emptyList()
        youTubeComments = emptyList()
        youTubeCommentsLoaded = false
        youTubeRelated = YouTubeFeedRepository.related(videoId)
    }
}

/** The Episodes and Sources buttons for a YouTube video: related videos (already loaded) and comments. */
internal fun PlayerScreenRuntime.handleYouTubeControlsAction(action: PlayerControlsAction): Boolean {
    if (!isYouTubeVideoPlayback) return false
    when (action) {
        PlayerControlsAction.Sources -> {
            showSourcesPanel = true
            loadYouTubeComments()
        }
        PlayerControlsAction.Episodes -> Unit
        else -> return false
    }
    return true
}

/** The panels' rows: a related video plays; a comment does nothing (there is nothing to switch to). */
internal fun PlayerScreenRuntime.handleYouTubeControlsEvent(type: String, value: Double): Boolean {
    if (!isYouTubeVideoPlayback) return false
    when (type) {
        "selectEpisode" -> openRelatedYouTubeVideo(value.toInt())
        "selectSource" -> Unit
        "reloadSources" -> loadYouTubeComments(force = true)
        else -> return false
    }
    return true
}

/** The first two pages of top comments (about forty), once per video unless [force]d. */
private fun PlayerScreenRuntime.loadYouTubeComments(force: Boolean = false) {
    if (youTubeCommentsLoading || (youTubeCommentsLoaded && !force)) return
    val videoId = youTubeVideoId
    youTubeCommentsLoading = true
    scope.launch {
        try {
            val first = YouTubeFeedRepository.comments(videoId, continuation = null)
            val second = first?.continuation?.let { YouTubeFeedRepository.comments(videoId, it) }
            if (youTubeVideoId != videoId) return@launch
            youTubeComments = (first?.comments.orEmpty() + second?.comments.orEmpty()).distinctBy { it.id }
            youTubeCommentsLoaded = first != null
        } finally {
            youTubeCommentsLoading = false
        }
    }
}

private fun PlayerScreenRuntime.openRelatedYouTubeVideo(index: Int) {
    val video = youTubeRelated.getOrNull(index) ?: return
    // Set up as a one-off hand-over, then leave as if this video had ended: the app opens the next
    // player behind the same shield as a playlist moving on (App's playback-completed path).
    YouTubePlayQueue.handOverFromPlayer(youTubeVideoId, video)
    playerControlsCloseModalsToken += 1
    playbackEndExitRequested = true
    flushWatchProgress()
    args.onPlaybackCompleted()
}

/** Related videos as the Episodes panel's cards: title, channel in the code line, views and age below. */
internal fun PlayerScreenRuntime.youTubeRelatedEpisodeItems(): List<PlayerControlEpisodeItem> =
    youTubeRelated.mapIndexed { index, video ->
        PlayerControlEpisodeItem(
            index = index,
            id = video.videoId,
            title = youTubeDisplayTitle(video),
            code = listOfNotNull(video.channel, video.duration).joinToString(" · "),
            overview = video.details.orEmpty(),
            thumbnail = video.thumbnailUrl.orEmpty(),
            isWatched = isYouTubeVideoWatched(video),
        )
    }

/**
 * Comments as the Sources panel's rows: author and age on top, the comment below, likes as the tag.
 * Not the reply count: the replies themselves are not loaded, so a count would point at nothing.
 */
internal fun PlayerScreenRuntime.youTubeCommentSourceItems(): List<PlayerControlSourceItem> =
    youTubeComments.mapIndexed { index, comment ->
        PlayerControlSourceItem(
            index = index,
            label = listOfNotNull(
                "Pinned".takeIf { comment.pinned },
                comment.author.takeIf { it.isNotBlank() },
                comment.published,
            ).joinToString(" · "),
            subtitle = comment.text,
            addonName = comment.likes?.let { "$it likes" }.orEmpty(),
        )
    }
