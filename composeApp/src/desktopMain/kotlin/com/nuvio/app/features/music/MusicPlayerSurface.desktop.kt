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
import com.nuvio.app.features.preroll.LoudnessClass
import com.nuvio.app.features.preroll.StreamLoudnessBook
import com.nuvio.app.features.preroll.formatDb
import com.nuvio.app.features.preroll.gainCorrectionDb
import com.nuvio.app.features.preroll.loudnessAfValue
import com.nuvio.app.features.preroll.planGainDb
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val log = Logger.withTag("MusicPlayerSurface")

private const val POLL_MS = 250L

/**
 * fork:loudness - all music (album audio, alternate uploads, the music addon's lossless files) is brought to one level, the
 * film's (LoudnessTarget.kt), by a fixed gain chosen before the first sample, then a gentle compressor and a limiter (see
 * LoudnessPlan.kt). The gain comes from the track's own measurement when one was kept, else from YouTube's loudness figure
 * for the stream, else from what album masters usually are. A track that has not been measured is measured in the
 * background while it plays (a second, headless decode of the same stream); when the answer differs the gain is eased to
 * it over a few seconds, and the measurement is kept for next time.
 */
private class MusicGainPlan(
    /** Stable identity for the loudness cache; null for a source that has none. */
    val key: String?,
    /** What the gain was planned from: a kept measurement, else YouTube's figure, else null (the class's assumption). */
    val lufs: Double?,
    /** YouTube's figure alone, for the log. */
    val youTubeLufs: Double?,
    val gainDb: Double,
    /** True when [lufs] is a kept measurement, so there is nothing left to measure. */
    val measured: Boolean,
)

private fun musicGainPlan(source: TrailerPlaybackSource): MusicGainPlan {
    val key = source.loudnessKey
    val kept = key?.let { TrackLoudnessBook.get(it) }
    val youTube = StreamLoudnessBook.lufsFor(source.audioUrl, source.videoUrl)
    val lufs = kept ?: youTube
    return MusicGainPlan(key, lufs, youTube, planGainDb(lufs, LoudnessClass.Music), measured = kept != null)
}

/** The decode the measurement reads: stereo at 24 kHz, a minute at a time (the native side stops a call after 90 s). */
private const val MEASURE_SAMPLE_RATE = 24_000
private const val MEASURE_WINDOW_SECONDS = 60.0

/** A track longer than this is measured over its first quarter of an hour. */
private const val MEASURE_MAX_SECONDS = 900.0

/** A track shorter than this is not worth measuring. */
private const val MEASURE_MIN_SECONDS = 15.0

/** Playback gets this long to settle before the second connection opens. */
private const val MEASURE_START_DELAY_MS = 1_500L

/** How long to wait for the stream to start playing before giving up on measuring it. */
private const val MEASURE_WAIT_LIMIT_MS = 60_000L

/**
 * The integrated loudness of the track [controller] is playing, from a headless decode of the same stream, or null when
 * it could not be read (a refused connection, or a build whose native bridge has no such decode).
 */
private suspend fun measureTrackLoudness(controller: NativePlayerController, durationMs: Long): Double? {
    val total = (durationMs / 1_000.0).coerceAtMost(MEASURE_MAX_SECONDS)
    if (total < MEASURE_MIN_SECONDS) return null
    val meter = LoudnessMeter(MEASURE_SAMPLE_RATE, channels = 2)
    var at = 0.0
    try {
        while (at < total) {
            currentCoroutineContext().ensureActive()
            val length = minOf(MEASURE_WINDOW_SECONDS, total - at)
            val pcm = withContext(Dispatchers.IO) {
                controller.decodeAudioWindow(at, length, MEASURE_SAMPLE_RATE, 2)
            } ?: return null
            meter.add(pcm)
            at += length
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        // UnsatisfiedLinkError on a native bridge without the decode, or anything the decode itself raised.
        log.w { "loudness measurement failed: ${error.message}" }
        return null
    }
    return meter.integratedLufs()
}

/** Eases mpv's `volume-gain` by [deltaDb] over about three seconds, so a correction is not a step. */
private suspend fun easeVolumeGain(controller: NativePlayerController, deltaDb: Double) {
    val steps = 20
    for (step in 1..steps) {
        controller.setMpvProperty("volume-gain", formatDb(deltaDb * step / steps))
        delay(150L)
    }
}

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
    framed: Boolean,
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
    // fork:loudness - the gain this load starts at (see MusicGainPlan).
    val gainPlan = remember(loadId, source) { musicGainPlan(source) }

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

    // fork:music - a song opened part way in (switching to Video mid-song, or past a non-music intro) on YouTube's
    // separate video and audio streams, or an HLS manifest. The native player's start-position hand-off (open, then seek
    // with playback held) stalled on these: the seek did not land within the stall watchdog's ten seconds. Opening at
    // 0:00 and jumping once playing worked but stalled too, on the jump. mpv's own start option opens the stream at
    // that time instead, as a ?t= link does on YouTube: no play-then-seek at all.
    val openAtMs = remember(loadId, forbiddenRetries) {
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
            initialPositionMs = if (openAtMs > 0L) 0L else startPositionMs,
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
            // fork:alternate-upload — an upload standing in for album audio is quieter; it and everything else
            // goes to the shared loudness, set as an init option so the first audio frame already has it.
            initMpvOptions = buildList {
                add("volume=${volume.coerceIn(0, 100)}")
                add("af=${loudnessAfValue(LoudnessClass.Music, gainPlan.gainDb)}")
                // Set on the new instance itself (attach swaps the native player on a worker thread), so it applies to
                // this load's open and nothing else.
                if (openAtMs > 0L) add("start=${formatMpvSeconds(openAtMs)}")
                // Bounded range requests, so opening part way in does not stall (see youtubeStreamMpvOptions).
                addAll(com.nuvio.app.features.home.components.youtubeStreamMpvOptions(sourceUrl))
            },
        )
        controller.setMpvProperty("panscan", if (letterbox) "0.0" else "1.0")
    }

    // A thumbnail fills its frame (cropped); a big view shows the whole picture.
    LaunchedEffect(controller, letterbox) {
        controller.setMpvProperty("panscan", if (letterbox) "0.0" else "1.0")
    }

    // fork:music-stage - the Now Playing picture wears the glass panels' corners, and its side bars the Stage's tone.
    // The window is re-cut when it is resized (the surface moves between the lyric view and the bar).
    val density = LocalDensity.current.density
    val cornerPx = if (framed) (FRAMED_CORNER_DP * density).toInt() else 0
    DisposableEffect(host, controller, cornerPx) {
        val listener = object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) = controller.setCornerRadius(cornerPx)
        }
        host.addComponentListener(listener)
        onDispose { host.removeComponentListener(listener) }
    }
    LaunchedEffect(controller, cornerPx, revealed, loadId) {
        controller.setCornerRadius(cornerPx)
        // The corners the cut exposes belong to the app's own surface, which has to draw them again.
        if (cornerPx > 0) {
            delay(250L)
            runCatching { javax.swing.SwingUtilities.getWindowAncestor(host)?.repaint() }
        }
    }
    LaunchedEffect(controller, framed) {
        // Newer mpv builds call it background-color, older ones background; the one it does not know is ignored.
        val bars = if (framed) FRAMED_BARS_COLOR else "#000000"
        controller.setMpvProperty("background-color", bars)
        controller.setMpvProperty("background", bars)
    }

    LaunchedEffect(controller, loadId, forbiddenRetries, volume) {
        controller.setMpvProperty("volume", volume.coerceIn(0, 100).toString())
        controller.setHeroTrailerVolume(volume.coerceIn(0, 100))
    }

    // fork:loudness - measure a track that has no kept measurement while it plays, keep the answer, and ease the gain to it.
    LaunchedEffect(controller, loadId, forbiddenRetries) {
        val key = gainPlan.key ?: return@LaunchedEffect
        if (gainPlan.measured) return@LaunchedEffect
        var durationMs = 0L
        var waitedMs = 0L
        while (durationMs <= 0L) {
            delay(500L)
            waitedMs += 500L
            if (waitedMs > MEASURE_WAIT_LIMIT_MS) return@LaunchedEffect
            val snapshot = controller.snapshotOffUiThread()
            if (snapshot.isPlaying) durationMs = snapshot.durationMs
        }
        delay(MEASURE_START_DELAY_MS)
        val measured = measureTrackLoudness(controller, durationMs) ?: return@LaunchedEffect
        val lufs = measured + MEASURED_LOUDNESS_COMPENSATION_LU
        TrackLoudnessBook.put(key, lufs)
        log.i {
            "loudness $key: measured ${formatDb(lufs)} LUFS; started from " +
                "${gainPlan.lufs?.let(::formatDb) ?: "the class assumption"} (YouTube said ${gainPlan.youTubeLufs?.let(::formatDb) ?: "nothing"})"
        }
        val wanted = planGainDb(lufs, LoudnessClass.Music)
        gainCorrectionDb(gainPlan.gainDb, wanted)?.let { delta ->
            log.i { "loudness $key: easing the gain by ${formatDb(delta)} dB" }
            easeVolumeGain(controller, delta)
        }
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
    LaunchedEffect(controller, loadId, forbiddenRetries) {
        var endedReported = false
        var readyReported = false
        // mpv's end-of-file flag outlives the file: the first polls of a new load can still read the previous
        // track's "ended", which advanced the queue a second time and skipped every other track. A load only
        // counts as ended once it has been seen not ended (a load stuck on that flag is the watchdog's to catch).
        var seenUnended = false
        if (openAtMs > 0L) log.i { "load $loadId: opening at ${openAtMs}ms" }
        var openLogged = openAtMs == 0L
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
            // How long an open part way in takes to play, for comparing against the old jump in the log.
            // Near the target only: the first polls can still read the previous track's position.
            if (!openLogged && snapshot.isPlaying && snapshot.positionMs in (openAtMs - OPEN_AT_SLACK_MS)..(openAtMs + OPEN_AT_SLACK_MS)) {
                openLogged = true
                log.i { "load $loadId: playing at ${snapshot.positionMs}ms after ${System.currentTimeMillis() - attachedAt}ms" }
            }
            latestOnSnapshot.value(loadId, snapshot)
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

/** The corner of the Now Playing picture, as the Stage's glass panels. */
private const val FRAMED_CORNER_DP = 16f

/** The bars beside a picture that is not 16:9, in the Stage's dark tone rather than black. */
private const val FRAMED_BARS_COLOR = "#101018"

/** An open part way in can land a few seconds short of its time (on a keyframe); that still counts as there. */
private const val OPEN_AT_SLACK_MS = 10_000L

/** mpv's start option in seconds, with a decimal point whatever the locale. */
private fun formatMpvSeconds(ms: Long): String = "${ms / 1000}.${(ms % 1000).toString().padStart(3, '0')}"
