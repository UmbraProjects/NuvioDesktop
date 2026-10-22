package com.nuvio.app.features.cast

import com.nuvio.app.features.player.ExternalPlaybackTracking
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.externalPlaybackTracking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A renderer on the local network that can be sent a stream (a DLNA MediaRenderer). */
data class CastDevice(
    val id: String,
    val name: String,
    val model: String? = null,
)

/** A resolved stream to send to a [CastDevice], with what progress tracking needs. */
data class CastRequest(
    val sourceUrl: String,
    val sourceHeaders: Map<String, String>,
    val title: String,
    val resumePositionMs: Long,
    val tracking: ExternalPlaybackTracking?,
)

sealed interface CastStartResult {
    data object Started : CastStartResult
    data class Failed(val message: String) : CastStartResult
}

internal expect object DeviceCastPlatform {
    val isSupported: Boolean

    /** Renderers that answered a network search; blocks for a few seconds. */
    suspend fun discover(): List<CastDevice>

    /** Whether the Dolby Vision remux can run here (it needs ffmpeg). */
    fun dolbyVisionRemuxAvailable(): Boolean

    /** The remembered "Dolby Vision (beta)" choice. Off by default. */
    var dolbyVisionEnabled: Boolean

    suspend fun cast(device: CastDevice, request: CastRequest, dolbyVision: Boolean): CastStartResult

    fun stopCasting()
}

internal fun PlayerLaunch.toCastRequest(): CastRequest = CastRequest(
    sourceUrl = sourceUrl,
    sourceHeaders = sourceHeaders,
    title = if (seasonNumber != null && episodeNumber != null) {
        val code = "S${seasonNumber.toString().padStart(2, '0')}E${episodeNumber.toString().padStart(2, '0')}"
        listOfNotNull(title, code, episodeTitle?.takeIf { it.isNotBlank() }).joinToString(" - ")
    } else {
        title
    },
    resumePositionMs = initialPositionMs,
    tracking = externalPlaybackTracking(),
)

/** Holds the stream waiting for a device to be picked. Hosted once by [CastDialogHost]. */
object CastController {
    private val _pending = MutableStateFlow<CastRequest?>(null)
    val pending: StateFlow<CastRequest?> = _pending.asStateFlow()

    val isSupported: Boolean get() = DeviceCastPlatform.isSupported

    fun request(request: CastRequest) {
        _pending.value = request
    }

    fun dismiss() {
        _pending.value = null
    }
}
