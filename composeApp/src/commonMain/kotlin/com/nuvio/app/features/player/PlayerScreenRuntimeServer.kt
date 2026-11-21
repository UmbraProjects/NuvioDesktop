package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.servers.ServerPlayback
import com.nuvio.app.features.servers.ServerStreams
import com.nuvio.app.features.servers.serverPlaybackMessage
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamSubtitle
import co.touchlab.kermit.Logger
import com.nuvio.app.features.usenet.UsenetEngine
import com.nuvio.app.features.usenet.UsenetFailedReleases
import com.nuvio.app.features.usenet.usenetCompressed7z
import com.nuvio.app.features.usenet.usenetStreamFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_error_playback_failed
import nuvio.composeapp.generated.resources.player_error_unable_to_play_stream
import nuvio.composeapp.generated.resources.player_failover_trying_next
import nuvio.composeapp.generated.resources.usenet_compressed_unplayable
import org.jetbrains.compose.resources.getString

/*
 * The player's side of media servers (upstream's servers feature, ported from NuvioMobile):
 * progress reports and the session stop, a transcode fallback after a playback error, and the
 * server's subtitle files in the subtitle list. Audio and subtitle switching through a transcode
 * is not ported: mpv direct-plays server files and picks their tracks itself.
 */

/** Server playback reports: progress every whole second of change, and a stop when the source goes. */
@Composable
internal fun PlayerScreenRuntime.BindServerPlaybackEffects() {
    DisposableEffect(activeSourceUrl) {
        val effectSourceUrl = activeSourceUrl
        onDispose {
            ServerPlayback.stop(effectSourceUrl)
            // A built-in Usenet session ends with the source that played it.
            com.nuvio.app.features.usenet.UsenetEngine.release(effectSourceUrl)
        }
    }
    BindUsenetStreamFailureEffect()
    LaunchedEffect(
        playbackSnapshot.positionMs / 1_000L,
        playbackSnapshot.isPlaying,
        playbackSnapshot.isLoading,
        playbackSnapshot.isEnded,
    ) {
        ServerPlayback.onPlaybackSnapshot(
            url = activeSourceUrl,
            positionMs = playbackSnapshot.positionMs,
            isPlaying = playbackSnapshot.isPlaying,
            isLoading = playbackSnapshot.isLoading,
            isEnded = playbackSnapshot.isEnded,
        )
    }
}

private val usenetLog = Logger.withTag("UsenetEngine")
private const val USENET_FAILURE_POLL_STARTUP_MS = 500L
private const val USENET_FAILURE_POLL_PLAYING_MS = 2_000L

/**
 * A Usenet session that dies (missing articles, a damaged archive) answers the player with HTTP
 * 410, which mpv only sees as the file ending: nothing fails, and the start watchdog waited out its
 * whole timeout (FraMeSToR, Nov 17). The engine records why in its diagnostics, so ask it: during
 * startup every half second, later every two, and hand over to failover the moment it says so.
 */
@Composable
private fun PlayerScreenRuntime.BindUsenetStreamFailureEffect() {
    LaunchedEffect(activeSourceUrl, playbackAttemptId) {
        val url = activeSourceUrl
        val attemptId = playbackAttemptId
        if (!UsenetEngine.isSessionUrl(url)) return@LaunchedEffect
        var reportedCompressed = false
        while (activeSourceUrl == url && playbackAttemptId == attemptId) {
            val started = playerStartedSourceUrl == url && playerStartedAttemptId == attemptId
            delay(if (started) USENET_FAILURE_POLL_PLAYING_MS else USENET_FAILURE_POLL_STARTUP_MS)
            val diagnostics = UsenetEngine.diagnostics(url) ?: continue
            if (!reportedCompressed && usenetCompressed7z(diagnostics)) {
                reportedCompressed = true
                usenetLog.i { "Usenet session plays a compressed 7z (decoded as it downloads)" }
            }
            val failure = usenetStreamFailure(diagnostics) ?: continue
            if (activeSourceUrl != url || playbackAttemptId != attemptId) return@LaunchedEffect
            usenetLog.i {
                "Usenet stream failed: $failure" + (if (usenetCompressed7z(diagnostics)) " (compressed 7z)" else "") +
                    (if (started) " during playback" else " before the first frame")
            }
            activeStream?.let { UsenetFailedReleases.record(it, failure) }
            activeStream?.nzbUrl?.let { com.nuvio.app.features.usenet.UsenetStats.failedWhilePlaying(it, com.nuvio.app.features.usenet.usenetIncompleteFailure(failure)) }
            StreamFailoverLog.event("usenet_stream_failed", buildJsonObject {
                put("attemptId", attemptId)
                put("failure", failure)
                put("started", started)
            })
            if (errorMessage != null || failoverInProgress) return@LaunchedEffect
            val message = getString(Res.string.player_error_unable_to_play_stream)
            val failedToast = getString(Res.string.player_error_playback_failed)
            if (!tryFailoverToNextSource(
                    message = message,
                    playbackFailedToast = failedToast,
                    tryingNextToast = getString(Res.string.player_failover_trying_next),
                    trigger = StreamFailoverTrigger.PlaybackError,
                )
            ) {
                exitAfterPlaybackFailure(message, failedToast)
            }
            return@LaunchedEffect
        }
    }
}

/** A media-server file that failed: ask the server again (it falls back to a transcode). */
internal fun PlayerScreenRuntime.retryServerSourceAfterError(message: String?): Boolean {
    if (credentialRefreshJob?.isActive == true) return true
    val failedUrl = activeSourceUrl
    if (credentialRefreshAttemptedSourceUrl == failedUrl) return false
    credentialRefreshAttemptedSourceUrl = failedUrl
    val savedPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L)
    errorMessage = null
    credentialRefreshJob = scope.launch {
        val playback = ServerPlayback.fallback(failedUrl)
        if (playback == null || activeSourceUrl != failedUrl) {
            if (activeSourceUrl == failedUrl) {
                errorMessage = message
                controlsVisible = !playerControlsLocked
            }
            return@launch
        }
        externalSubtitles = playback.subtitles
        activeSourceHeaders = playback.headers
        activeInitialPositionMs = savedPositionMs
        activeInitialProgressFraction = null
        beginPlaybackAttempt()
        activeSourceUrl = playback.url
    }
    return true
}

internal fun PlayerScreenRuntime.isServerSourcePlaying(): Boolean =
    ServerStreams.isServerSourceId(activeProviderAddonId)

/**
 * Asks the server for [stream]'s playback URL first when it has none yet, then hands the prepared
 * stream to [onResolved]. False when [stream] is not a media-server file waiting for one.
 */
internal fun PlayerScreenRuntime.resolveServerForPlayer(
    stream: StreamItem,
    onResolved: (StreamItem) -> Unit,
): Boolean {
    if (!stream.needsServerPreparation) return false
    scope.launch {
        val prepared = runCatching { ServerPlayback.prepare(stream) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrElse { error ->
                NuvioToastController.show(error.serverPlaybackMessage())
                return@launch
            }
        onResolved(prepared)
    }
    return true
}

private fun String.isLocalSubtitleUri(): Boolean {
    val trimmed = trim()
    val scheme = trimmed.substringBefore(':', missingDelimiterValue = "").lowercase()
    return scheme == "file" || (':' !in trimmed && trimmed.startsWith("/"))
}

private fun StreamSubtitle.toAddonSubtitle(): AddonSubtitle = AddonSubtitle(
    id = url,
    url = url,
    language = language,
    display = name?.takeIf { it.isNotBlank() } ?: language,
    addonName = name,
)

/** The stream's own subtitle files (a media server's) first, then the addons'. */
internal fun mergeStreamAndAddonSubtitles(
    addonSubtitles: List<AddonSubtitle>,
    streamSubtitles: List<StreamSubtitle>,
): List<AddonSubtitle> {
    val remote = streamSubtitles.filterNot { it.url.isLocalSubtitleUri() }
    if (remote.isEmpty()) return addonSubtitles
    return (remote.map { it.toAddonSubtitle() } + addonSubtitles).distinctBy { "${it.id}|${it.url}" }
}

/**
 * Opens a Usenet release in the engine first, then hands the playable stream to [onResolved]. When
 * it cannot be opened, [onFailed] takes over (failover moves on to the next source); without one,
 * the reason is shown and the current playback stays.
 */
internal fun PlayerScreenRuntime.resolveUsenetForPlayer(
    stream: StreamItem,
    season: Int?,
    episode: Int?,
    onFailed: (suspend (Throwable) -> Unit)? = null,
    onResolved: (StreamItem) -> Unit,
): Boolean {
    if (!stream.needsUsenetPreparation) return false
    scope.launch {
        val opened = runCatching { com.nuvio.app.features.usenet.UsenetEngine.resolve(stream, season, episode) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrElse { error ->
                if (onFailed != null) {
                    onFailed(error)
                } else if (com.nuvio.app.features.usenet.usenetCompressedSkip(error)) {
                    NuvioToastController.show(getString(Res.string.usenet_compressed_unplayable))
                } else {
                    NuvioToastController.show(error.message ?: "This Usenet release could not be opened")
                }
                return@launch
            }
        onResolved(opened)
    }
    return true
}
