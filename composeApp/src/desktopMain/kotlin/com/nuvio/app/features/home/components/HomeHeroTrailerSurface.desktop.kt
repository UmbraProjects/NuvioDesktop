package com.nuvio.app.features.home.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import com.nuvio.app.core.ui.LocalNuvioBaseDensity
import com.nuvio.app.features.details.HeroTrailerAudioState
import com.nuvio.app.features.player.PlayerControlsAction
import com.nuvio.app.features.player.PlayerControlsState
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.NativePlayerController
import com.nuvio.app.features.player.desktop.NativePlayerHost
import com.nuvio.app.features.player.desktop.TRAILER_AUDIO_NORMALIZATION_FILTER
import kotlinx.coroutines.delay

private val trailerSurfaceLog = Logger.withTag("HomeHeroTrailerSurface")

/**
 * Desktop TV-mode home hero trailer. Reuses the native mpv player surface
 * ([NativePlayerHost] + [NativePlayerController]) directly — without the full-screen
 * player's keyboard dispatcher, launch shield or HDR pipeline — and drives the controls
 * overlay into hero-trailer mode so it renders only the fade gradients over the video.
 *
 * The SwingPanel is heavyweight, so Compose cannot fade it with alpha; instead the panel is
 * kept at 1px until mpv reports its first paint (mirroring the full-screen player) so there
 * is no black flash before the trailer has a frame.
 */
@Composable
actual fun HomeHeroTrailerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    playWhenReady: Boolean,
    muted: Boolean,
    volume: Int,
    backgroundColor: Color,
    logoUrl: String?,
    title: String,
    meta: String,
    description: String,
    modifier: Modifier,
    onReady: () -> Unit,
    onEnded: () -> Unit,
    onError: () -> Unit,
    onVolumeChange: (Int) -> Unit,
    onSurfaceDisposed: () -> Unit,
) {
    val supported = DesktopHostOs.current == DesktopHostOs.WINDOWS ||
        DesktopHostOs.current == DesktopHostOs.MACOS
    val latestOnError = rememberUpdatedState(onError)
    val latestOnReady = rememberUpdatedState(onReady)
    val latestOnEnded = rememberUpdatedState(onEnded)
    val latestOnVolumeChange = rememberUpdatedState(onVolumeChange)
    val latestOnSurfaceDisposed = rememberUpdatedState(onSurfaceDisposed)
    // The settings toggle gates sound on/off; the shared slider sets the level.
    val effectiveVolume = if (muted) 0 else volume.coerceIn(0, 100)

    if (!supported) {
        LaunchedEffect(sourceUrl) {
            trailerSurfaceLog.w { "unsupported host ${DesktopHostOs.current}; cannot play hero trailer" }
            latestOnError.value()
        }
        return
    }

    val host = remember {
        NativePlayerHost().apply {
            // Passive background surface: must not take keyboard focus from the app, or TV
            // navigation breaks while a trailer is playing.
            isFocusable = false
            focusTraversalKeysEnabled = false
        }
    }
    val controller = remember(host) { NativePlayerController(host) }
    val backgroundHex = remember(backgroundColor) { backgroundColor.toHeroTrailerHex() }
    var firstPaintComplete by remember { mutableStateOf(false) }
    var playbackRevealReady by remember { mutableStateOf(false) }

    DisposableEffect(host) {
        host.onFirstPaint = {
            trailerSurfaceLog.i { "first paint" }
            firstPaintComplete = true
        }
        host.onDisplayableChanged = { displayable ->
            trailerSurfaceLog.i { "displayable=$displayable" }
            if (!displayable) {
                firstPaintComplete = false
                playbackRevealReady = false
            }
        }
        onDispose {
            host.onFirstPaint = null
            host.onDisplayableChanged = null
        }
    }

    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }

    // The trailer surface is passive: it never takes OS keyboard focus (the native container
    // refuses mouse activation and the WebView2 bounces any focus it grabs — see player_bridge.cpp),
    // so the Compose home root stays the sole keyboard owner and handles all navigation itself.
    // We no longer install a global AWT KeyEventDispatcher here; that parallel path fought Compose's
    // own key handling and caused stuck-key bugs (e.g. M then Right). All that remains is reclaiming
    // focus on dispose, in case the default home layout recycles the hero mid-playback.
    DisposableEffect(controller) {
        onDispose {
            latestOnSurfaceDisposed.value()
        }
    }

    LaunchedEffect(controller) {
        controller.setControlCallbacks(
            onAction = { action ->
                // Only trailer-specific chrome actions are handled here — navigation never routes
                // through the trailer. The chrome's Stop button sends Back (stop playback); the mute
                // button toggles the shared trailer audio state directly, without any focus change.
                when (action) {
                    PlayerControlsAction.Back -> {
                        latestOnEnded.value()
                        true
                    }
                    PlayerControlsAction.HeroTrailerMute -> {
                        HeroTrailerAudioState.toggleMuted()
                        true
                    }
                    else -> false
                }
            },
            onEvent = { type, value ->
                when (type) {
                    // Overlay volume slider (mouse-driven) — a trailer-specific control.
                    "heroTrailerVolume" -> {
                        latestOnVolumeChange.value(value.toInt().coerceIn(0, 100))
                        true
                    }
                    // The WebView2 grabbed OS focus (e.g. a click on the mute/volume chrome).
                    // Hand keyboard focus straight back to the Compose home root so navigation
                    // keeps working; the trailer surface itself never keeps focus.
                    "heroTrailerFocusEscaped" -> {
                        latestOnSurfaceDisposed.value()
                        true
                    }
                    else -> false
                }
            },
            onScrubChange = { false },
            onScrubFinished = { false },
        )
    }

    LaunchedEffect(controller, sourceUrl, backgroundHex) {
        playbackRevealReady = false
        // Push hero-trailer overlay state first so attach() forwards it to the controls page.
        controller.updateControls(
            PlayerControlsState(
                heroTrailerMode = true,
                heroTrailerBackgroundColor = backgroundHex,
                heroTrailerLogoUrl = logoUrl?.takeIf { it.isNotBlank() }.orEmpty(),
                heroTrailerTitle = title,
                heroTrailerMeta = meta,
                heroTrailerDescription = description,
                isLoading = true,
                heroTrailerMuted = muted,
                heroTrailerVolume = effectiveVolume,
            ),
        )
        trailerSurfaceLog.i { "attach video=${sourceUrl.take(80)} audio=${sourceAudioUrl?.take(60)} muted=$muted vol=$effectiveVolume" }
        controller.attach(
            sourceUrl = sourceUrl,
            sourceAudioUrl = sourceAudioUrl?.takeIf { it.isNotBlank() },
            sourceHeaders = emptyMap(),
            playWhenReady = playWhenReady,
            initialPositionMs = 0L,
            // Hero trailers are passive background video; never spend RTX VSR on them.
            nvidiaRtxSuperResolutionEnabled = false,
            nvidiaRtxHdrEnabled = false,
            // Likewise skip SVP/vapoursynth frame interpolation: it's wasted on a muted
            // preview and its Python runtime can hang the native UI thread (black surface).
            animeSvpEnabled = false,
            onError = { message ->
                trailerSurfaceLog.w { "playback error: $message" }
                latestOnError.value()
            },
            // Tells the controls page it is a passive hero surface from the very first script
            // run, so it never grabs keyboard focus (which would break TV navigation).
            controlsPageUrlSuffix = "?heroTrailer=1",
        )
    }

    // mpv "mute"/"volume" properties queue until the handle exists, so they survive the async attach.
    LaunchedEffect(controller, sourceUrl, muted, effectiveVolume) {
        controller.updateControls(
            PlayerControlsState(
                heroTrailerMode = true,
                heroTrailerBackgroundColor = backgroundHex,
                heroTrailerLogoUrl = logoUrl?.takeIf { it.isNotBlank() }.orEmpty(),
                heroTrailerTitle = title,
                heroTrailerMeta = meta,
                heroTrailerDescription = description,
                isLoading = true,
                heroTrailerMuted = muted,
                heroTrailerVolume = effectiveVolume,
            ),
        )
        controller.setMpvProperty("mute", if (muted) "yes" else "no")
        controller.setMpvProperty("volume", effectiveVolume.toString())
        // Reflect programmatic ([ / ]) volume changes on the overlay slider immediately.
        controller.setHeroTrailerVolume(effectiveVolume)
    }

    // Crop the trailer to fill the hero region (no letterbox) so it reads as part of the
    // hero rather than a pillarboxed PIP window. panscan queues until the handle exists.
    LaunchedEffect(controller, sourceUrl) {
        controller.setMpvProperty("panscan", "1.0")
        // Trailer loudness is wildly inconsistent between uploads; smooth it out in real time.
        controller.setMpvProperty("af", TRAILER_AUDIO_NORMALIZATION_FILTER)
    }

    LaunchedEffect(controller, playWhenReady) {
        if (playWhenReady) controller.play() else controller.pause()
    }

    LaunchedEffect(controller, sourceUrl, playWhenReady) {
        var endedReported = false
        var readyReported = false
        while (true) {
            val snapshot = controller.snapshot()
            // A first paint can be a static startup frame while mpv is still buffering.
            // Reveal only once playback is genuinely advancing, keeping the artwork visible
            // through that otherwise noticeable frozen-frame interval.
            if (
                playWhenReady &&
                firstPaintComplete &&
                !readyReported &&
                snapshot.isPlaying &&
                snapshot.positionMs >= 150L
            ) {
                readyReported = true
                playbackRevealReady = true
                trailerSurfaceLog.i { "playback moving; reveal at ${snapshot.positionMs}ms" }
                latestOnReady.value()
            }
            if (snapshot.isEnded && !endedReported) {
                endedReported = true
                latestOnEnded.value()
            }
            delay(50L)
        }
    }

    Box(modifier = modifier) {
        // See PlayerEngine.desktop.kt's SwingPanel for why this needs the real (unscaled)
        // window density rather than the ambient one.
        CompositionLocalProvider(LocalDensity provides LocalNuvioBaseDensity.current) {
            SwingPanel(
                factory = { host },
                modifier = if (playbackRevealReady && playWhenReady) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.align(Alignment.BottomEnd).requiredSize(1.dp)
                },
                background = Color.Transparent,
            )
        }
    }
}

private fun Color.toHeroTrailerHex(): String {
    fun channel(value: Float): String =
        (value * 255f).toInt().coerceIn(0, 255).toString(16).padStart(2, '0')
    return "#${channel(red)}${channel(green)}${channel(blue)}"
}
