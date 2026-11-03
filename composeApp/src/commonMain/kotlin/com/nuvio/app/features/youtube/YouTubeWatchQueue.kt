package com.nuvio.app.features.youtube

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/*
 * fork:youtube-mode
 *
 * The YouTube tab's queue: videos put by for watching soon, in the order added, kept for this run of
 * Nuvio only (memory, never saved, never sent to YouTube), as the Music tab's queue is. Played
 * through like a playlist ([YouTubePlayQueue]); a video added while the queue plays joins its end.
 */
object YouTubeWatchQueue {
    private val _videos = MutableStateFlow<List<YouTubeVideo>>(emptyList())

    val videos: StateFlow<List<YouTubeVideo>> = _videos.asStateFlow()

    fun contains(videoId: String): Boolean = _videos.value.any { it.videoId == videoId }

    /** Adds [video] at the end; false when it was already queued. */
    fun add(video: YouTubeVideo): Boolean {
        if (contains(video.videoId)) return false
        _videos.update { it + video }
        YouTubePlayQueue.onWatchQueueAdded(video)
        return true
    }

    fun remove(videoId: String) {
        _videos.update { list -> list.filterNot { it.videoId == videoId } }
    }

    fun clear() {
        _videos.value = emptyList()
    }
}

/** [YouTubePlayQueue]'s name for a play-through of [YouTubeWatchQueue]. */
internal const val WATCH_QUEUE_NAME = "Queue"
