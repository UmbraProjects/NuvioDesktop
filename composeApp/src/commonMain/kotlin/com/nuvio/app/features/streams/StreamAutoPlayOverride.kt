package com.nuvio.app.features.streams

/**
 * fork:live-tv-on-demand - a play that must pick its source by itself, whatever the auto-play setting says (Live TV's
 * "From the start, on demand": the viewer chose a programme, not a release). Armed for one video id, used once, and
 * gone after [TTL_MS] if the stream list never opens.
 */
object StreamAutoPlayOverride {
    private const val TTL_MS = 120_000L

    @Volatile
    private var armed: Pair<String, Long>? = null

    fun arm(videoId: String, now: Long = System.currentTimeMillis()) {
        armed = videoId to now
    }

    /** True once for an armed [videoIds] entry still in time. */
    @Synchronized
    fun take(vararg videoIds: String?, now: Long = System.currentTimeMillis()): Boolean {
        val (id, at) = armed ?: return false
        if (now - at > TTL_MS) {
            armed = null
            return false
        }
        if (videoIds.none { it == id }) return false
        armed = null
        return true
    }

    /** The mode a forced pick uses: the viewer's own automatic mode, else the first stream. */
    fun modeFor(configured: StreamAutoPlayMode, regexConfigured: Boolean): StreamAutoPlayMode = when {
        configured == StreamAutoPlayMode.MANUAL -> StreamAutoPlayMode.FIRST_STREAM
        configured == StreamAutoPlayMode.REGEX_MATCH && !regexConfigured -> StreamAutoPlayMode.FIRST_STREAM
        else -> configured
    }
}
