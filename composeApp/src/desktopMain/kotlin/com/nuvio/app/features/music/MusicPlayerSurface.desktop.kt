package com.nuvio.app.features.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.nuvio.app.features.music.visualiser.LiveTrackMap
import com.nuvio.app.features.music.visualiser.TrackMapBuilder
import com.nuvio.app.features.music.visualiser.TrackMapLive
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

/**
 * The decode the measurement reads: stereo at 48 kHz (the rate most streams are already at, so mpv does not resample, and the
 * analyser sees up to 24 kHz where 24 kHz sampling cut everything above 12 kHz), two minutes at a time (the native side stops a
 * call after 90 s, and keeps what it had by then). Every window opens a new connection to the stream, and the addon's CDN refuses
 * some of them, so fewer is better. The rate must give a whole number of samples per 25 ms map frame (see TrackMapBuilder).
 */
private const val MEASURE_SAMPLE_RATE = 48_000
private const val MEASURE_WINDOW_SECONDS = 120.0

/** A track longer than this is measured over its first quarter of an hour. */
private const val MEASURE_MAX_SECONDS = 900.0

/** A track shorter than this is not worth measuring. */
private const val MEASURE_MIN_SECONDS = 15.0

/** Playback gets this long to settle before the second connection opens. */
private const val MEASURE_START_DELAY_MS = 1_500L

/** How long to wait for the stream to start playing before giving up on measuring it. */
private const val MEASURE_WAIT_LIMIT_MS = 60_000L

/** fork:visualiser - the first window is short, so the visualiser has something to draw a second or two into a track. */
private const val FIRST_WINDOW_SECONDS = 15.0

/** A window that decodes nothing is asked again this many times, a little later each time, before the read gives up. */
private const val DECODE_RETRIES = 3
private const val DECODE_RETRY_DELAY_MS = 2_000L

/** The stream can end a little before the length mpv reported; that much short of it counts as the end. */
private const val DECODE_END_SLACK_SECONDS = 1.5

/** How far into the track the decode has read (seconds), kept between calls so a stopped read can be resumed where it stopped. */
private class DecodeProgress { var at = 0.0 }

/** A read that stopped is picked up again from where it stopped, this many times, after 20 s, 40 s, 60 s...: a server that refuses the extra connections for a while lets go. */
private const val DECODE_RESUMES = 12
private const val DECODE_RESUME_DELAY_MS = 20_000L

/**
 * Reads the track [controller] is playing through a headless decode of the same stream, feeding [meter] (the loudness
 * measurement) and [map] (the visualiser's track map) with the same samples; [onWindow] runs after each window. False when
 * it could not be read (a refused connection, or a build whose native bridge has no such decode).
 */
private suspend fun decodeTrack(
    controller: NativePlayerController,
    durationMs: Long,
    meter: LoudnessMeter?,
    map: TrackMapBuilder?,
    beats: com.nuvio.app.features.music.visualiser.BeatTracker?,
    progress: DecodeProgress,
    onWindow: suspend () -> Unit,
): Boolean {
    val total = (durationMs / 1_000.0).coerceAtMost(MEASURE_MAX_SECONDS)
    if (total < MEASURE_MIN_SECONDS) return false
    var at = progress.at
    var failures = 0
    try {
        while (at < total - DECODE_END_SLACK_SECONDS) {
            currentCoroutineContext().ensureActive()
            val window = if (at == 0.0 && map != null) FIRST_WINDOW_SECONDS else MEASURE_WINDOW_SECONDS
            val length = minOf(window, total - at)
            val pcm = withContext(Dispatchers.IO) {
                controller.decodeAudioWindow(at, length, MEASURE_SAMPLE_RATE, 2)
            }
            // fork:visualiser - what was actually decoded, which can be less than asked for (a slow connection, the native
            // side's time limit). The next window starts where this one stopped, so the map stays in time with the song;
            // moving on by the full window shrank the song and left the map short of its end.
            val got = (pcm?.size ?: 0) / 2.0 / MEASURE_SAMPLE_RATE
            if (pcm == null || got < 0.5) {
                if (++failures > DECODE_RETRIES) {
                    log.w { "track decode stopped at ${at.toInt()}s of ${total.toInt()}s after $DECODE_RETRIES retries" }
                    return false
                }
                log.i { "track decode window at ${at.toInt()}s gave nothing; retry $failures of $DECODE_RETRIES" }
                delay(DECODE_RETRY_DELAY_MS * failures)
                continue
            }
            failures = 0
            withContext(Dispatchers.Default) {
                meter?.add(pcm)
                map?.add(pcm)
                // fork:visualiser - the beat grid's network reads the same samples (it keeps only its spectrogram).
                beats?.add(pcm)
            }
            if (got < length - 0.5) log.i { "track decode window at ${at.toInt()}s gave ${got.toInt()}s of ${length.toInt()}s; continuing from there" }
            at += got
            progress.at = at
            onWindow()
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        // UnsatisfiedLinkError on a native bridge without the decode, or anything the decode itself raised.
        log.w { "track decode failed: ${error.message}" }
        return false
    }
    return true
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
    // fork:trailer-403 - outlives the attach effect, whose scope has finished by the time mpv reports a 403.
    val retryScope = rememberCoroutineScope()

    // fork:music - a song opened part way in (switching to Video mid-song, or past a non-music intro) on YouTube's
    // separate video and audio streams, or an HLS manifest. The native player's start-position hand-off (open, then seek
    // with playback held) stalled on these: the seek did not land within the stall watchdog's ten seconds. Opening at
    // 0:00 and jumping once playing worked but stalled too, on the jump. mpv's own start option opens the stream at
    // that time instead, as a ?t= link does on YouTube: no play-then-seek at all.
    val openAtMs = remember(loadId, forbiddenRetries) {
        if (startPositionMs > 0L && (sourceAudioUrl != null || "/manifest/" in sourceUrl)) startPositionMs else 0L
    }

    LaunchedEffect(controller, loadId, forbiddenRetries) {
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
                    retryScope.launch {
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
    LaunchedEffect(controller, cornerPx, revealed, loadId) { controller.setCornerRadius(cornerPx) }
    // fork:music-stage - the glass bezel laid over the picture (NowPlayingBezel): it covers the square corners the rounded cut
    // leaves, and follows the picture as it resizes and as the app window moves.
    // Only a load in progress (the next track) keeps the bezel up for its grace; leaving the page hides it at once.
    val onStage by rememberUpdatedState(framed && videoVisible)
    DisposableEffect(host) { onDispose { NowPlayingBezel.release(host, immediate = true) } }
    val bezelUp = framed && videoVisible && revealed
    DisposableEffect(host, bezelUp, density) {
        if (!bezelUp) return@DisposableEffect onDispose { NowPlayingBezel.release(host, immediate = !onStage) }
        // Opening the page, the picture moves onto the Stage while the page fades in and its layout settles (the liner notes
        // arriving moved it across the screen): a bezel shown at once sat over the page underneath and then jumped. It waits
        // for the fade and for the picture to hold still. Between songs it is already up and simply carries on.
        var settled = NowPlayingBezel.isUpFor(host)
        fun fit() {
            if (!settled) return
            val scale = host.graphicsConfiguration?.defaultTransform?.scaleX ?: density.toDouble()
            NowPlayingBezel.show(host, scale, density)
        }
        val listener = object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) = fit()
            override fun componentMoved(e: java.awt.event.ComponentEvent) = NowPlayingBezel.follow(host)
        }
        host.addComponentListener(listener)
        val window = javax.swing.SwingUtilities.getWindowAncestor(host)
        window?.addComponentListener(listener)
        val startedAt = System.currentTimeMillis()
        var lastBounds: java.awt.Rectangle? = null
        var stillSince = startedAt
        val settle = javax.swing.Timer(BEZEL_SETTLE_POLL_MS) { event ->
            val now = System.currentTimeMillis()
            val bounds = if (host.isShowing) java.awt.Rectangle(host.locationOnScreen, host.size) else null
            if (bounds != lastBounds) {
                lastBounds = bounds
                stillSince = now
            }
            if (bounds != null && now - startedAt >= BEZEL_FADE_MS && now - stillSince >= BEZEL_STILL_MS) {
                (event.source as javax.swing.Timer).stop()
                settled = true
                fit()
            }
        }
        if (settled) fit() else settle.start()
        onDispose {
            settle.stop()
            host.removeComponentListener(listener)
            window?.removeComponentListener(listener)
            NowPlayingBezel.release(host, immediate = !onStage)
        }
    }
    // The corners the rounded picture leaves show the host window. mpv's own "black" reaches the screen as about (8, 8, 8), so
    // a pure black host made a faint rounded shape inside a darker square; the host takes mpv's black instead.
    SideEffect { host.background = if (framed) java.awt.Color(8, 8, 8) else java.awt.Color.BLACK }
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
    // fork:visualiser - the same decode builds the track's visualiser map when none is kept.
    LaunchedEffect(controller, loadId, forbiddenRetries) {
        val key = gainPlan.key
        val keptMap = key?.let { TrackMapBook.get(it) }?.takeIf { it.complete }
        var knownLufs = key?.let { TrackLoudnessBook.get(it) }
        TrackMapLive.publish(LiveTrackMap(loadId, keptMap, knownLufs, analysing = keptMap == null))
        // fork:visualiser - a bar line moved by hand on the Beat Grid is kept with the track's map.
        if (key != null) {
            TrackMapLive.keeper = { id, moved ->
                if (id == loadId) kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { TrackMapBook.put(key, moved) }
            }
        }
        val needLoudness = key != null && !gainPlan.measured
        if (!needLoudness && keptMap != null) return@LaunchedEffect
        var durationMs = 0L
        var waitedMs = 0L
        while (durationMs <= 0L) {
            delay(500L)
            waitedMs += 500L
            if (waitedMs > MEASURE_WAIT_LIMIT_MS) {
                TrackMapLive.publish(LiveTrackMap(loadId, keptMap, knownLufs, analysing = false, unavailable = keptMap == null))
                return@LaunchedEffect
            }
            val snapshot = controller.snapshotOffUiThread()
            if (snapshot.isPlaying) durationMs = snapshot.durationMs
        }
        delay(MEASURE_START_DELAY_MS)
        // fork:visualiser - a music video whose song's album audio already has a complete map, at the same length within
        // 2 s, is the same recording: its map is reused (and kept under the video's id too) instead of reading the video.
        // A longer or shorter video (an intro, a skit) gets its own map as before.
        var mapNow = keptMap
        if (mapNow == null && key != null) {
            val audioKey = com.nuvio.app.features.music.visualiser.audioMapKey(MusicPlayback.player.state.value.track?.videoId)
            val borrowed = audioKey?.takeIf { it != key }
                ?.let { withContext(Dispatchers.IO) { TrackMapBook.get(it) } }
                ?.takeIf { it.complete && com.nuvio.app.features.music.visualiser.sameLengthForMap(it.analysedMs, durationMs) }
            if (borrowed != null) {
                withContext(Dispatchers.IO) { TrackMapBook.put(key, borrowed) }
                TrackMapLive.publish(LiveTrackMap(loadId, borrowed, knownLufs, analysing = false))
                log.i { "visualiser map $key: reused the album audio's ($audioKey), same length" }
                mapNow = borrowed
                if (!needLoudness) return@LaunchedEffect
            }
        }
        val meter = if (needLoudness) LoudnessMeter(MEASURE_SAMPLE_RATE, channels = 2) else null
        val builder = if (mapNow == null) TrackMapBuilder(MEASURE_SAMPLE_RATE) else null
        // fork:visualiser - the Beat This! network finds the beat grid; without it (no models, ONNX Runtime will not load, or it
        // fails part way) the signal-processing estimate in the builder is used as before.
        var beats = if (builder != null) {
            withContext(Dispatchers.Default) { com.nuvio.app.features.music.visualiser.BeatTracker.create(MEASURE_SAMPLE_RATE) }
        } else null
        fun beatGrid(map: com.nuvio.app.features.music.visualiser.TrackMap, final: Boolean): com.nuvio.app.features.music.visualiser.TrackMap {
            val tracker = beats ?: return map
            return runCatching { map.withBeat(tracker.grid(final)) }
                .onFailure {
                    log.w(it) { "beat grid network failed; using the signal-processing estimate" }
                    beats = null
                }
                .getOrDefault(map)
        }
        // fork:visualiser - one download of the track's audio, read from disk by every slice below, instead of a new connection to
        // the music server per slice (which it answered with HTTP 429). Without it (a playlist stream, a refusal) the stream is read as before.
        val downloaded = downloadSpotFile(sourceAudioUrl ?: sourceUrl, source.headers)
        if (downloaded != null) {
            controller.setAudioSpotFile(downloaded.absolutePath)
            currentCoroutineContext()[kotlinx.coroutines.Job]?.invokeOnCompletion {
                controller.setAudioSpotFile(null)
                downloaded.delete()
            }
        }
        // The file in use, until it runs out and the read goes on from the stream.
        var spotFile: java.io.File? = downloaded
        val progress = DecodeProgress()
        var resumes = 0
        var read = false
        while (true) {
            read = decodeTrack(controller, durationMs, meter, builder, beats, progress) {
                if (builder != null) {
                    val partial = withContext(Dispatchers.Default) { beatGrid(builder.snapshot(), final = false) }
                    TrackMapLive.publish(LiveTrackMap(loadId, partial, knownLufs, analysing = true))
                }
            }
            // fork:visualiser - a read that stopped part way (the stream's server refusing the extra connection for a
            // moment) is picked up again from where it stopped, so the map still ends up whole; the part already
            // read stays on screen meanwhile.
            if (read) break
            // fork:visualiser - the downloaded file ran out before the song did: carry on from the stream itself at once,
            // rather than asking the same file again (that left YouTube tracks "Analysing" for good).
            val usedFile = spotFile
            if (usedFile != null) {
                log.i { "track decode: the downloaded audio ran out at ${progress.at.toInt()}s; reading the stream from there" }
                controller.setAudioSpotFile(null)
                usedFile.delete()
                spotFile = null
                continue
            }
            if (++resumes > DECODE_RESUMES) break
            val wait = DECODE_RESUME_DELAY_MS * minOf(resumes, 3)
            log.i { "track decode paused at ${progress.at.toInt()}s; resuming in ${wait / 1000}s (try $resumes of $DECODE_RESUMES)" }
            delay(wait)
        }
        if (builder != null) {
            if (read) {
                val finished = withContext(Dispatchers.Default) { beatGrid(builder.finish(), final = true) }
                TrackMapLive.publish(LiveTrackMap(loadId, finished, knownLufs, analysing = false))
                // What is on screen: the finished map with any bar line moved by hand while it was being read.
                val shown = TrackMapLive.state.value?.takeIf { it.loadId == loadId }?.map ?: finished
                key?.let { k ->
                    withContext(Dispatchers.IO) {
                        TrackMapBook.put(k, shown)
                        // fork:sonic-map - the album page's Sonic map places the song by this, long after the map file is gone.
                        com.nuvio.app.features.music.library.keepSoundPrint(k, shown)
                    }
                }
                log.i { "visualiser map ${key ?: "(no key)"}: ${finished.frames} frames, beat ${finished.beat?.let { "%.1f BPM, %d a bar, %s".format(it.bpm, it.beatsPerBar, if (beats != null) "Beat This!" else "signal estimate") } ?: "none"}" }
            } else {
                val partial = withContext(Dispatchers.Default) { builder.snapshot().takeIf { it.frames > 0 }?.let { beatGrid(it, final = false) } }
                TrackMapLive.publish(LiveTrackMap(loadId, partial, knownLufs, analysing = false, unavailable = partial == null))
            }
        }
        if (key == null || meter == null || !read) return@LaunchedEffect
        val measured = meter.integratedLufs() ?: return@LaunchedEffect
        val lufs = measured + MEASURED_LOUDNESS_COMPENSATION_LU
        TrackLoudnessBook.put(key, lufs)
        knownLufs = lufs
        TrackMapLive.state.value?.takeIf { it.loadId == loadId }?.let { TrackMapLive.publish(it.copy(lufs = lufs)) }
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
                background = if (framed) Color(8, 8, 8) else Color.Black,
            )
        }
    }
}

/** The Now Playing page's fade-in (MusicNowPlayingHost), and a little over: the bezel never shows before it is done. */
private const val BEZEL_FADE_MS = 300L

/** How long the picture has to hold still before the bezel is laid round it, and how often that is checked. */
private const val BEZEL_STILL_MS = 150L
private const val BEZEL_SETTLE_POLL_MS = 40

/** The corner of the Now Playing picture, as the Stage's glass panels. */
private const val FRAMED_CORNER_DP = 16f

/**
 * The bars beside a picture that is not 16:9. Black, like the host window behind the rounded picture: mpv drew a Stage-toned
 * "#101018" lighter than asked (about #1E1E2A on screen), which showed as a navy band inside the host's black corners.
 */
private const val FRAMED_BARS_COLOR = "#000000"

/** An open part way in can land a few seconds short of its time (on a keyframe); that still counts as there. */
private const val OPEN_AT_SLACK_MS = 10_000L

/** mpv's start option in seconds, with a decimal point whatever the locale. */
private fun formatMpvSeconds(ms: Long): String = "${ms / 1000}.${(ms % 1000).toString().padStart(3, '0')}"
