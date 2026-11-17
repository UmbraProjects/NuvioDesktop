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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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

/** Opens a Usenet release in the engine first, then hands the playable stream to [onResolved]. */
internal fun PlayerScreenRuntime.resolveUsenetForPlayer(
    stream: StreamItem,
    season: Int?,
    episode: Int?,
    onResolved: (StreamItem) -> Unit,
): Boolean {
    if (!stream.needsUsenetPreparation) return false
    scope.launch {
        val opened = runCatching { com.nuvio.app.features.usenet.UsenetEngine.resolve(stream, season, episode) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrElse { error ->
                NuvioToastController.show(error.message ?: "This Usenet release could not be opened")
                return@launch
            }
        onResolved(opened)
    }
    return true
}
