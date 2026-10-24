package com.nuvio.app.features.streams

import com.nuvio.app.features.player.PlayerAutoPlayMode
import com.nuvio.app.features.player.PlayerSourceAffinity

data class StreamLaunch(
    val type: String,
    val videoId: String,
    val streamVideoId: String? = null,
    val parentMetaId: String? = null,
    val parentMetaType: String? = null,
    val watchProgressSource: String? = null,
    val title: String,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val releaseYear: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val pauseDescription: String? = null,
    val resumePositionMs: Long? = null,
    val resumeProgressFraction: Float? = null,
    val manualSelection: Boolean = false,
    // Captured at play time from the detail page that launched playback, so the streams request
    // doesn't re-read a shared flag that a later, unrelated detail load may have overwritten.
    val preferLocalStreams: Boolean = false,
    val startFromBeginning: Boolean = false,
    val disableProgressTracking: Boolean = false,
    val autoPlayMode: PlayerAutoPlayMode = PlayerAutoPlayMode.NextEpisode,
    val sourceAffinity: PlayerSourceAffinity = PlayerSourceAffinity.Stream,
)

object StreamLaunchStore {
    private var nextLaunchId = 1L
    private val launches = mutableMapOf<Long, StreamLaunch>()

    fun put(launch: StreamLaunch): Long {
        val launchId = nextLaunchId++
        launches[launchId] = launch
        return launchId
    }

    fun get(launchId: Long): StreamLaunch? = launches[launchId]

    fun remove(launchId: Long) {
        launches.remove(launchId)
    }

    fun clear() {
        nextLaunchId = 1L
        launches.clear()
    }
}
