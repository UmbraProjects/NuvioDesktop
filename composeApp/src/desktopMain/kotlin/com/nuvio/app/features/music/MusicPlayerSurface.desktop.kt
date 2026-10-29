package com.nuvio.app.features.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import com.nuvio.app.core.ui.LocalNuvioBaseDensity
import com.nuvio.app.features.home.components.TrailerStallWatchdog
import com.nuvio.app.features.player.PlayerControlsState
import com.nuvio.app.features.player.PlayerHeroTrailerState
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.NativePlayerController
import com.nuvio.app.features.player.desktop.NativePlayerHost
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import kotlinx.coroutines.delay

private val log = Logger.withTag("MusicPlayerSurface")

private const val POLL_MS = 250L

@Composable
actual fun MusicPlayerSurface(
    source: TrailerPlaybackSource,
    loadId: Long,
    videoVisible: Boolean,
    startPositionMs: Long,
    playWhenReady: Boolean,
    volume: Int,
    seekRequestId: Long,
    seekRequestMs: Long,
    letterbox: Boolean,
    modifier: Modifier,
    onSnapshot: (loadId: Long, snapshot: PlayerPlaybackSnapshot) -> Unit,
    onEnded: (loadId: Long) -> Unit,
    onError: (loadId: Long) -> Unit,
) {
    val supported = DesktopHostOs.current == DesktopHostOs.WINDOWS || DesktopHostOs.current == DesktopHostOs.MACOS
    val latestOnSnapshot = rememberUpdatedState(onSnapshot)
    val latestOnEnded = rememberUpdatedState(onEnded)
    val latestOnError = rememberUpdatedState(onError)
    // A video-mode source plays its picture and its separate audio stream; an audio-mode source only
    // needs the audio stream. A muxed or HLS source has no separate audio and plays as it is.
    val sourceUrl = if (videoVisible) source.videoUrl else source.audioUrl?.takeIf { it.isNotBlank() } ?: source.videoUrl
    val sourceAudioUrl = if (videoVisible) source.audioUrl?.takeIf { it.isNotBlank() } else null

    if (!supported) {
        LaunchedEffect(loadId) {
            log.w { "unsupported host ${DesktopHostOs.current}; cannot play music" }
            latestOnError.value(loadId)
        }
        return
    }

    val host = remember {
        NativePlayerHost().apply {
            isFocusable = false
            focusTraversalKeysEnabled = false
        }
    }
    val controller = remember(host) { NativePlayerController(host) }
    var revealed by remember { mutableStateOf(false) }

    DisposableEffect(host) {
        host.onFirstPaint = { }
        host.onDisplayableChanged = { displayable -> if (!displayable) revealed = false }
        onDispose {
            host.onFirstPaint = null
            host.onDisplayableChanged = null
        }
    }
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }

    // Same chrome-less setup the theme-song player uses: the controls page runs in hero-trailer mode
    // with nothing to show, and keyboard focus stays with the app.
    DisposableEffect(controller) {
        controller.setControlCallbacks(
            onAction = { false },
            onEvent = { _, _ -> false },
            onScrubChange = { false },
            onScrubFinished = { false },
        )
        onDispose { }
    }

    // Lets lyric auto sync read this stream's audio (see DesktopLyricSync).
    DisposableEffect(controller, loadId) {
        DesktopLyricSync.attach(loadId) { startMs, lengthMs ->
            controller.decodeSpeechSpot(startMs / 1_000.0, lengthMs / 1_000.0)
        }
        onDispose { DesktopLyricSync.detach(loadId) }
    }

    LaunchedEffect(controller, loadId) {
        revealed = false
        controller.updateControls(
            PlayerControlsState(
                isLoading = true,
                heroTrailer = PlayerHeroTrailerState(
                    enabled = true,
                    backgroundColor = "#000000",
                    muted = false,
                    volume = volume,
                ),
            ),
        )
        log.i { "attach load=$loadId video=${sourceUrl.take(80)} audio=${sourceAudioUrl?.take(60)} from=${startPositionMs}ms visible=$videoVisible" }
        controller.attach(
            sourceUrl = sourceUrl,
            sourceAudioUrl = sourceAudioUrl,
            sourceHeaders = emptyMap(),
            playWhenReady = playWhenReady,
            initialPositionMs = startPositionMs,
            nvidiaRtxSuperResolutionEnabled = false,
            nvidiaRtxHdrEnabled = false,
            animeSvpEnabled = false,
            onError = { message ->
                log.w { "playback error on load $loadId: $message" }
                latestOnError.value(loadId)
            },
            controlsPageUrlSuffix = "?heroTrailer=1&chromeless=1",
            initMpvOptions = listOf("volume=${volume.coerceIn(0, 100)}"),
        )
        controller.setMpvProperty("panscan", if (letterbox) "0.0" else "1.0")
    }

    // A thumbnail fills its frame (cropped); a big view shows the whole picture.
    LaunchedEffect(controller, letterbox) {
        controller.setMpvProperty("panscan", if (letterbox) "0.0" else "1.0")
    }

    LaunchedEffect(controller, loadId, volume) {
        controller.setMpvProperty("volume", volume.coerceIn(0, 100).toString())
        controller.setHeroTrailerVolume(volume.coerceIn(0, 100))
    }

    LaunchedEffect(controller, playWhenReady) {
        if (playWhenReady) controller.play() else controller.pause()
    }

    // Each seek request is applied once. The id this load began with belongs to an earlier track.
    var appliedSeekId by remember(loadId) { mutableLongStateOf(seekRequestId) }
    LaunchedEffect(controller, seekRequestId) {
        if (seekRequestId != appliedSeekId) {
            appliedSeekId = seekRequestId
            controller.seekTo(seekRequestMs)
        }
    }

    // playWhenReady is read through this so a pause does not restart the poll (and the watchdog).
    val latestPlayWhenReady = rememberUpdatedState(playWhenReady)
    LaunchedEffect(controller, loadId) {
        var endedReported = false
        var readyReported = false
        // A googlevideo stream can hang "reconnecting" with no error and no end of file, which held
        // the player on a frozen frame (or a spinner) for good. No progress for a while is treated as
        // a failed open, so the player tries the stream's fallback or moves on to the next track.
        val stallWatchdog = TrailerStallWatchdog()
        while (true) {
            val snapshot = controller.snapshotOffUiThread()
            if (snapshot.isPlaying && snapshot.positionMs >= 150L) {
                revealed = true
                readyReported = true
            }
            stallWatchdog.poll(snapshot.positionMs, latestPlayWhenReady.value, readyReported, endedReported)?.let { stuckMs ->
                log.w { "load $loadId stalled for ${stuckMs}ms at ${snapshot.positionMs}ms" }
                latestOnError.value(loadId)
            }
            latestOnSnapshot.value(loadId, snapshot)
            if (snapshot.isEnded && !endedReported) {
                endedReported = true
                latestOnEnded.value(loadId)
            }
            delay(POLL_MS)
        }
    }

    Box(modifier = modifier) {
        CompositionLocalProvider(LocalDensity provides LocalNuvioBaseDensity.current) {
            SwingPanel(
                factory = { host },
                modifier = if (videoVisible && revealed) {
                    Modifier.fillMaxSize()
                } else {
                    // Parked: a one-pixel panel keeps the native player alive without painting over the app.
                    Modifier.align(Alignment.BottomEnd).requiredSize(1.dp)
                },
                background = Color.Black,
            )
        }
    }
}
