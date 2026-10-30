package com.nuvio.app.features.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.nuvio.app.features.home.components.forbiddenStreamRetryDelayMs
import com.nuvio.app.features.player.PlayerControlsState
import com.nuvio.app.features.player.PlayerHeroTrailerState
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.NativePlayerController
import com.nuvio.app.features.player.desktop.NativePlayerHost
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val log = Logger.withTag("MusicPlayerSurface")

private const val POLL_MS = 250L

/**
 * fork:alternate-upload — brings a quiet upload up to the level album audio plays at. `loudnorm` first aims at a
 * fixed target (-14 LUFS) however quiet the source is; album masters sit well above that, so a further 6 dB follows,
 * and `alimiter` holds the peaks under full scale so the extra gain cannot clip.
 */
private const val ALTERNATE_UPLOAD_LOUDNESS_FILTER = "lavfi=[loudnorm=I=-14:LRA=11:TP=-1.5,volume=6dB,alimiter=limit=0.97]"

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

    // Re-opens of this load's URL after a googlevideo 403 (see forbiddenStreamRetryDelayMs).
    var forbiddenRetries by remember(loadId) { mutableIntStateOf(0) }

    // fork:music - a video opened part way in (switching to Video mid-song) on YouTube's separate video and audio
    // streams, or an HLS manifest, sat in the native player's start-position hand-off: held paused until the seek
    // landed, which it did not within the stall watchdog's ten seconds, so the load was dropped and the next fallback
    // tried, down to the 360p stream from the beginning. Such a load now opens at 0:00, muted, and jumps to the
    // position once it is really playing; until then its position is not reported, so a fallback keeps the place.
    val deferStartMs = remember(loadId, forbiddenRetries) {
        if (startPositionMs > 0L && (sourceAudioUrl != null || "/manifest/" in sourceUrl)) startPositionMs else 0L
    }

    LaunchedEffect(controller, loadId, forbiddenRetries) {
        val attachScope = this
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
            sourceHeaders = source.headers,
            playWhenReady = playWhenReady,
            initialPositionMs = if (deferStartMs > 0L) 0L else startPositionMs,
            nvidiaRtxSuperResolutionEnabled = false,
            nvidiaRtxHdrEnabled = false,
            animeSvpEnabled = false,
            onError = { message ->
                log.w { "playback error on load $loadId: $message" }
                val retryInMs = forbiddenStreamRetryDelayMs(message, forbiddenRetries)
                if (retryInMs != null) {
                    log.i { "load $loadId: re-opening in ${retryInMs}ms (retry ${forbiddenRetries + 1})" }
                    attachScope.launch {
                        delay(retryInMs)
                        forbiddenRetries++
                    }
                } else {
                    latestOnError.value(loadId)
                }
            },
            controlsPageUrlSuffix = "?heroTrailer=1&chromeless=1",
            // fork:alternate-upload — an upload standing in for album audio is quieter; even its level out.
            initMpvOptions = buildList {
                add("volume=${volume.coerceIn(0, 100)}")
                if (source.evenOutLoudness) add("af=$ALTERNATE_UPLOAD_LOUDNESS_FILTER")
            },
        )
        controller.setMpvProperty("panscan", if (letterbox) "0.0" else "1.0")
        if (deferStartMs > 0L) controller.setMuted(true)
    }

    // A thumbnail fills its frame (cropped); a big view shows the whole picture.
    LaunchedEffect(controller, letterbox) {
        controller.setMpvProperty("panscan", if (letterbox) "0.0" else "1.0")
    }

    LaunchedEffect(controller, loadId, forbiddenRetries, volume) {
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
    LaunchedEffect(controller, loadId, forbiddenRetries, deferStartMs) {
        var endedReported = false
        var readyReported = false
        // mpv's end-of-file flag outlives the file: the first polls of a new load can still read the previous
        // track's "ended", which advanced the queue a second time and skipped every other track. A load only
        // counts as ended once it has been seen not ended (a load stuck on that flag is the watchdog's to catch).
        var seenUnended = false
        var pendingStartMs = deferStartMs
        // The first polls after an attach still read the stream that was playing before (it was at, say, 0:40): the jump
        // fired on that within a few milliseconds, before the new file had opened, and was lost, so the video restarted at
        // 0:00. It now waits until this load is seen at its own start (loading, or under a second in) and then playing.
        var freshStartSeen = false
        val attachedAt = System.currentTimeMillis()
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
            if (snapshot.isLoading || snapshot.positionMs < FRESH_START_MS) freshStartSeen = true
            val ownPlayback = freshStartSeen && snapshot.isPlaying && snapshot.positionMs >= 150L &&
                System.currentTimeMillis() - attachedAt >= MIN_JUMP_DELAY_MS
            if (pendingStartMs > 0L && ownPlayback) {
                log.i { "load $loadId: playing, jumping to ${pendingStartMs}ms" }
                controller.seekTo(pendingStartMs)
                pendingStartMs = 0L
                delay(350)
                controller.setMuted(false)
            }
            if (pendingStartMs == 0L) latestOnSnapshot.value(loadId, snapshot)
            if (!snapshot.isEnded) seenUnended = true
            if (snapshot.isEnded && seenUnended && !endedReported) {
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

/** A deferred start only jumps once its load was seen this near its own beginning, and no sooner than this after attaching. */
private const val FRESH_START_MS = 1_000L
private const val MIN_JUMP_DELAY_MS = 400L
