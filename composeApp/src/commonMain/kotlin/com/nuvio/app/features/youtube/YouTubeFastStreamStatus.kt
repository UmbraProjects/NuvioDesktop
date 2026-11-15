package com.nuvio.app.features.youtube

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * fork:faststream-optin
 *
 * FastStream: the watch page's button that downloads the playing YouTube video's streams in parallel
 * byte ranges (as the FastStream browser extension does) into a temporary file, and plays from that.
 * googlevideo holds one request to about the playback rate; several bounded ranges at once are not.
 * Opt-in per video and off again with the next video: more connections than a browser opens could
 * draw YouTube's attention, and no request carries the account's cookies.
 */

/** What the watch page shows on the FastStream button. Written by the desktop player. */
internal data class YouTubeFastStreamStatus(
    /** The playing video's streams are ones FastStream can fetch (direct googlevideo, not a manifest). */
    val available: Boolean = false,
    /** The player is reading from the FastStream download. */
    val active: Boolean = false,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    /** YouTube refused some ranges; FastStream went on with fewer connections. */
    val throttled: Boolean = false,
    /** FastStream gave up; the player went back to streaming straight from YouTube. */
    val failed: Boolean = false,
) {
    val percent: Int?
        get() = if (active && totalBytes > 0L) ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100) else null
}

internal object YouTubeFastStreamState {
    private val _status = MutableStateFlow(YouTubeFastStreamStatus())
    val status: StateFlow<YouTubeFastStreamStatus> = _status.asStateFlow()

    fun update(transform: (YouTubeFastStreamStatus) -> YouTubeFastStreamStatus) {
        while (true) {
            val current = _status.value
            if (_status.compareAndSet(current, transform(current))) return
        }
    }
}

/** Turns FastStream on or off for the playing video; the player reopens it at the same place. */
internal expect fun requestYouTubeFastStream(on: Boolean)
