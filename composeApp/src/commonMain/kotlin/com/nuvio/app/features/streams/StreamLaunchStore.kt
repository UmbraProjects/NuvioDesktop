package com.nuvio.app.features.streams

import com.nuvio.app.features.player.PlayerAutoPlayMode

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
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val pauseDescription: String? = null,
    val resumePositionMs: Long? = null,
    val resumeProgressFraction: Float? = null,
    val manualSelection: Boolean = false,
    val startFromBeginning: Boolean = false,
    val disableProgressTracking: Boolean = false,
    val autoPlayMode: PlayerAutoPlayMode = PlayerAutoPlayMode.NextEpisode,
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
