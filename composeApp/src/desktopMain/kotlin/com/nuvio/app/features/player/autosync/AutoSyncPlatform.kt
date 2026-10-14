package com.nuvio.app.features.player.autosync

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** One timed subtitle line as AutoSync sees it (text is only used to classify dialogue cues). */
internal data class AutoSyncCue(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String,
)

/**
 * Desktop stand-ins for the Android/Media3 pieces the NuvioTV AutoSync engine was written against.
 * The values only need to be consistent within AutoSync; they mirror Media3's so ported code reads
 * the same as upstream.
 */
internal object C {
    const val TIME_UNSET: Long = Long.MIN_VALUE + 1
    const val TRACK_TYPE_TEXT: Int = 3
    const val SELECTION_FLAG_FORCED: Int = 1 shl 1
    const val ROLE_FLAG_COMMENTARY: Int = 1 shl 3
    const val ROLE_FLAG_SUBTITLE: Int = 1 shl 7
    const val ROLE_FLAG_DESCRIBES_VIDEO: Int = 1 shl 9
    const val ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND: Int = 1 shl 10
    const val ROLE_FLAG_TRANSCRIBES_DIALOG: Int = 1 shl 12
}

internal object AutoSyncPlatform {
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .eventListenerFactory(com.nuvio.app.core.perf.PerfOkHttpEventListenerFactory("autosync"))
            .build()
    }

    fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000L
}

/** Upstream exposes these as user settings; desktop keeps upstream's defaults. */
internal object AutoSyncPreferences {
    /** Thorough search: stop only on a stronger match; the applied match is gated the same. */
    val aggressiveMode: kotlinx.coroutines.flow.StateFlow<Boolean> =
        kotlinx.coroutines.flow.MutableStateFlow(true)
}
