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
 * through like a playlist ([YouTubePlayQueue]) from the video picked; a video added while the queue
 * plays joins its end, and each video played to its end there leaves it, as on YouTube.
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

    /** Moves the queued video [videoId] to [index] (clamped), by a drag in the queue. */
    fun move(videoId: String, index: Int) {
        _videos.update { list ->
            val video = list.firstOrNull { it.videoId == videoId } ?: return@update list
            val without = list - video
            without.toMutableList().apply { add(index.coerceIn(0, without.size), video) }
        }
    }

    fun clear() {
        _videos.value = emptyList()
    }
}

/** [YouTubePlayQueue]'s name for a play-through of [YouTubeWatchQueue]. */
internal const val WATCH_QUEUE_NAME = "Queue"
