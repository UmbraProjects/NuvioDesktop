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

internal val PlayerScreenRuntime.youTubeVideoId: String
    get() = parentMetaId.removePrefix(YOUTUBE_META_ID_PREFIX)

/** Loads the related videos, the chapters and the seek previews when a YouTube tab video opens. */
@Composable
internal fun PlayerScreenRuntime.BindYouTubeRelatedEffect() {
    if (!isYouTubeVideoPlayback) return
    val videoId = youTubeVideoId
    // fork:youtube-watch-page - the page can show while this player is up (YouTubeWatchLayout).
    androidx.compose.runtime.DisposableEffect(Unit) {
        com.nuvio.app.features.youtube.YouTubeWatchLayout.begin()
        onDispose { com.nuvio.app.features.youtube.YouTubeWatchLayout.end() }
    }
    LaunchedEffect(videoId) {
        youTubeStoryboard = null
        youTubeStoryboardPending = true
        try {
            youTubeStoryboard = com.nuvio.app.features.youtube.youTubeStoryboard(videoId)
        } finally {
            youTubeStoryboardPending = false
        }
    }
    LaunchedEffect(videoId) {
        youTubeRelated = emptyList()
        youTubeRelatedContinuation = null
        youTubeChapters = emptyList()
        youTubeDetails = null
        youTubeComments = emptyList()
        youTubeCommentsLoaded = false
        val page = YouTubeFeedRepository.watchPage(videoId)
        youTubeRelated = page.related
        youTubeRelatedContinuation = page.relatedContinuation
        youTubeDetails = page.details
        // The stream itself carries no chapters; YouTube's player draws these (see the chapter effect).
        youTubeChapters = page.chapters.map { PlayerChapter(it.startSeconds, it.title) }
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
        // The panel has no end to scroll to here; each opening brings in one more page.
        PlayerControlsAction.Episodes -> loadMoreYouTubeRelated()
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

/**
 * fork:youtube-mini-player - the player's Back on a YouTube tab video. Over the tab (the mini player) it
 * steps back inside the tab and never closes the video, which the box's X does; otherwise, with
 * Settings > YouTube account > Mini player on, it goes to the tab with the video in the corner. False
 * leaves Back to close the player as before.
 */
internal fun PlayerScreenRuntime.handleYouTubeMiniPlayerBack(): Boolean {
    if (!isYouTubeVideoPlayback) return false
    val layout = com.nuvio.app.features.youtube.YouTubeWatchLayout
    // Why Back closed the video rather than going to the corner, for the times it does (Nov 4: Escape
    // sometimes closed it, apparently depending on where the video was opened from).
    fun closing(reason: String): Boolean {
        miniPlayerLog.i {
            "Back closes $youTubeVideoId: $reason (available=${layout.available.value} windowed=${layout.windowed.value} " +
                "fullFromPage=${layout.fullFromPage} pip=$pictureInPictureActive)"
        }
        return false
    }
    if (pictureInPictureActive) return closing("picture in picture")
    if (layout.browsing.value) {
        com.nuvio.app.core.ui.TabBackInterceptor.handle()
        return true
    }
    if (!YouTubeFeedRepository.miniPlayerOnBack.value) return closing("mini player off")
    if (com.nuvio.app.features.youtube.YouTubeMiniPlayerHost.page == null) return closing("no tab to show around it")
    if (!layout.available.value) miniPlayerLog.i { "Back: $youTubeVideoId has no layout yet; nothing done" }
    miniPlayerLog.i { "Back: $youTubeVideoId to the mini player" }
    playerControlsCloseModalsToken += 1
    layout.startBrowsing()
    return true
}

private val miniPlayerLog = co.touchlab.kermit.Logger.withTag("YouTubeMiniPlayer")

/**
 * The related videos as shown: those already watched left out when the tab's Hide watched is on (the
 * one setting for the feeds and this column). The HUD's Episodes panel and the watch page both read
 * this, so a panel row's index points at the video it shows.
 */
internal val PlayerScreenRuntime.visibleYouTubeRelated: List<com.nuvio.app.features.youtube.YouTubeVideo>
    get() = if (YouTubeFeedRepository.hideWatched.value) youTubeRelated.filterNot(::isYouTubeVideoWatched) else youTubeRelated

/** The related column's next page, appended; nothing when one is on its way or the column has ended. */
internal fun PlayerScreenRuntime.loadMoreYouTubeRelated() {
    val token = youTubeRelatedContinuation ?: return
    if (youTubeRelatedLoading) return
    val videoId = youTubeVideoId
    youTubeRelatedLoading = true
    scope.launch {
        try {
            val page = YouTubeFeedRepository.moreRelated(token) ?: return@launch
            if (youTubeVideoId != videoId || youTubeRelatedContinuation != token) return@launch
            youTubeRelated = (youTubeRelated + page.videos.filter { it.videoId != videoId }).distinctBy { it.videoId }
            // A page that added nothing new ends it, so a token YouTube keeps handing back is not asked forever.
            youTubeRelatedContinuation = page.continuation?.takeIf { page.videos.isNotEmpty() && it != token }
        } finally {
            youTubeRelatedLoading = false
        }
    }
}

internal fun PlayerScreenRuntime.openRelatedYouTubeVideo(index: Int) {
    visibleYouTubeRelated.getOrNull(index)?.let(::openYouTubeVideoFromPlayer)
}

/** Hands the player straight over to [video] (a related video, or one from the queue on the watch page). */
internal fun PlayerScreenRuntime.openYouTubeVideoFromPlayer(video: com.nuvio.app.features.youtube.YouTubeVideo) {
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
    visibleYouTubeRelated.mapIndexed { index, video ->
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

/**
 * A YouTube tab video that would not open or stopped (a CDN server not answering: Nov 3 a connection to
 * one timed out after 65 s while the server had served five videos that morning; or a link gone stale):
 * its streams are looked up again, once, and it carries on from where it was. A fresh lookup almost
 * always lands on another server. False when this source was already retried, so the error shows.
 */
internal fun PlayerScreenRuntime.tryReresolveYouTubeAfterError(message: String?): Boolean {
    val failedUrl = activeSourceUrl
    if (credentialRefreshJob?.isActive == true) return true
    if (credentialRefreshAttemptedSourceUrl == failedUrl) return false
    credentialRefreshAttemptedSourceUrl = failedUrl
    val failedAttemptId = playbackAttemptId
    val savedPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L)
    val videoId = youTubeVideoId
    errorMessage = null
    controlsVisible = !playerControlsLocked
    credentialRefreshJob = scope.launch {
        // Not the streams looked up ahead (YouTubeStreamsAhead): those may be the ones that just failed.
        val source = runCatching { com.nuvio.app.features.youtube.youTubeOpenVideo(videoId, ahead = false) }.getOrNull()
        if (playbackAttemptId != failedAttemptId || activeSourceUrl != failedUrl) return@launch
        if (source == null || source.videoUrl == failedUrl) {
            errorMessage = message
            controlsVisible = !playerControlsLocked
            return@launch
        }
        co.touchlab.kermit.Logger.withTag("YouTubeFeed").i { "$videoId: playback failed (${message?.take(80)}); retrying with a fresh lookup" }
        flushWatchProgress()
        activeSourceAudioUrl = source.audioUrl
        activeSourceHeaders = sanitizePlaybackHeaders(source.headers)
        activeInitialPositionMs = savedPositionMs
        activeInitialProgressFraction = null
        controlsVisible = true
        beginPlaybackAttempt()
        activeSourceUrl = source.videoUrl
    }
    return true
}
