package com.nuvio.app.features.details.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.nuvio.app.features.player.PlayerControlsAction
import com.nuvio.app.features.player.PlayerControlsState
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.NativePlayerController
import com.nuvio.app.features.player.desktop.NativePlayerHost
import com.nuvio.app.features.player.desktop.TRAILER_AUDIO_NORMALIZATION_FILTER
import kotlinx.coroutines.delay
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import javax.swing.text.JTextComponent

private val detailHeroTrailerSurfaceLog = Logger.withTag("DetailHeroTrailerSurface")

@Composable
actual fun HeroTrailerPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    playWhenReady: Boolean,
    muted: Boolean,
    volume: Int,
    keyboardNavigationEnabled: Boolean,
    modifier: Modifier,
    onReady: () -> Unit,
    onEnded: () -> Unit,
    onError: () -> Unit,
    onMuteToggle: () -> Unit,
    onVolumeChange: (Int) -> Unit,
) {
    val supported = DesktopHostOs.current == DesktopHostOs.WINDOWS ||
        DesktopHostOs.current == DesktopHostOs.MACOS
    val latestOnReady = rememberUpdatedState(onReady)
    val latestOnEnded = rememberUpdatedState(onEnded)
    val latestOnError = rememberUpdatedState(onError)
    val latestOnMuteToggle = rememberUpdatedState(onMuteToggle)
    val latestOnVolumeChange = rememberUpdatedState(onVolumeChange)
    // Effective mpv volume: muting forces silence regardless of the slider position.
    val effectiveVolume = if (muted) 0 else volume.coerceIn(0, 100)

    if (!supported) {
        LaunchedEffect(sourceUrl) {
            detailHeroTrailerSurfaceLog.w { "unsupported host ${DesktopHostOs.current}; cannot play detail hero trailer" }
            latestOnError.value()
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
    var firstPaintComplete by remember { mutableStateOf(false) }
    // Sticky: stays true once this host has painted at all. onFirstPaint only fires for a
    // host's very first paint, so a re-attach (e.g. clicking a trailer, which pauses/shrinks
    // then re-attaches this same host) never re-fires it. Gating reveal on firstPaintComplete
    // alone would then leave the re-attached trailer stuck hidden. This flag lets a host that
    // has already proven it can paint reveal again on playback progress.
    var hasPaintedOnce by remember { mutableStateOf(false) }
    var playbackRevealReady by remember { mutableStateOf(false) }

    DisposableEffect(host) {
        host.onFirstPaint = {
            firstPaintComplete = true
            hasPaintedOnce = true
        }
        host.onDisplayableChanged = { displayable ->
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

    // The native mpv surface can become the AWT focus owner even though the Swing host is
    // marked non-focusable, which stops the details screen's Compose key handler from seeing
    // arrow/enter/escape and breaks keyboard navigation while a trailer plays. Route those
    // keys globally into the details keyboard bridge while this surface is mounted, matching
    // the home hero trailer surface and the full-screen player.
    DisposableEffect(keyboardNavigationEnabled) {
        if (!keyboardNavigationEnabled) {
            return@DisposableEffect onDispose { }
        }
        val dispatcher = KeyEventDispatcher { event ->
            if (event.id != KeyEvent.KEY_PRESSED) return@KeyEventDispatcher false
            if (event.isMetaDown || event.isControlDown || event.isAltDown) {
                return@KeyEventDispatcher false
            }
            val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            if (focusOwner is JTextComponent) return@KeyEventDispatcher false

            val key = when (event.keyCode) {
                KeyEvent.VK_UP -> DetailTvKey.Up
                KeyEvent.VK_DOWN -> DetailTvKey.Down
                KeyEvent.VK_LEFT -> DetailTvKey.Left
                KeyEvent.VK_RIGHT -> DetailTvKey.Right
                KeyEvent.VK_ENTER -> DetailTvKey.Select
                KeyEvent.VK_ESCAPE -> DetailTvKey.Dismiss
                KeyEvent.VK_BACK_SPACE -> DetailTvKey.Back
                else -> return@KeyEventDispatcher false
            }
            DetailTvKeyboardBridge.emit(key)
            event.consume()
            true
        }
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        focusManager.addKeyEventDispatcher(dispatcher)
        onDispose { focusManager.removeKeyEventDispatcher(dispatcher) }
    }

    DisposableEffect(controller) {
        controller.setControlCallbacks(
            onAction = { action ->
                when (action) {
                    PlayerControlsAction.Back -> {
                        latestOnEnded.value()
                        true
                    }
                    PlayerControlsAction.HeroTrailerMute -> {
                        latestOnMuteToggle.value()
                        true
                    }
                    else -> false
                }
            },
            onEvent = { type, value ->
                if (type == "heroTrailerVolume") {
                    latestOnVolumeChange.value(value.toInt().coerceIn(0, 100))
                    return@setControlCallbacks true
                }
                // The controls WebView owns OS focus (native child HWND) and forwards nav keys
                // as home* commands — the path that actually reaches us when the trailer plays
                // (AWT never sees them). Route them into the details keyboard bridge. The screen
                // ignores them when keyboard navigation isn't active.
                val navKey = when (type) {
                    "homeKeyUp" -> DetailTvKey.Up
                    "homeKeyDown" -> DetailTvKey.Down
                    "homeKeyLeft" -> DetailTvKey.Left
                    "homeKeyRight" -> DetailTvKey.Right
                    "homeKeySelect" -> DetailTvKey.Select
                    "homeKeyDismiss" -> DetailTvKey.Dismiss
                    else -> null
                }
                if (navKey != null) {
                    DetailTvKeyboardBridge.emit(navKey)
                    true
                } else {
                    false
                }
            },
            onScrubChange = { false },
            onScrubFinished = { false },
        )
        onDispose {
            controller.setControlCallbacks(
                onAction = { false },
                onEvent = { _, _ -> false },
                onScrubChange = { false },
                onScrubFinished = { false },
            )
        }
    }

    LaunchedEffect(controller, sourceUrl) {
        playbackRevealReady = false
        controller.updateControls(
            PlayerControlsState(
                heroTrailerMode = true,
                heroTrailerBackgroundColor = "#000000",
                isLoading = true,
                heroTrailerMuted = muted,
                heroTrailerVolume = effectiveVolume,
            ),
        )
        detailHeroTrailerSurfaceLog.i {
            "attach video=${sourceUrl.take(80)} audio=${sourceAudioUrl?.take(60)} muted=$muted vol=$effectiveVolume"
        }
        controller.attach(
            sourceUrl = sourceUrl,
            sourceAudioUrl = sourceAudioUrl?.takeIf { it.isNotBlank() },
            sourceHeaders = emptyMap(),
            playWhenReady = playWhenReady,
            initialPositionMs = 0L,
            nvidiaRtxSuperResolutionEnabled = false,
            nvidiaRtxHdrEnabled = false,
            // Never frame-interpolate hero trailers: the SVP/vapoursynth runtime can hang
            // the native UI thread (black surface), and it's wasted work on a muted preview.
            animeSvpEnabled = false,
            onError = { message ->
                detailHeroTrailerSurfaceLog.w { "playback error: $message" }
                latestOnError.value()
            },
            controlsPageUrlSuffix = "?heroTrailer=1",
        )
    }

    LaunchedEffect(controller, sourceUrl, muted, effectiveVolume) {
        controller.updateControls(
            PlayerControlsState(
                heroTrailerMode = true,
                heroTrailerBackgroundColor = "#000000",
                isLoading = true,
                heroTrailerMuted = muted,
                heroTrailerVolume = effectiveVolume,
            ),
        )
        controller.setMpvProperty("mute", if (muted) "yes" else "no")
        controller.setMpvProperty("volume", effectiveVolume.toString())
    }

    LaunchedEffect(controller, sourceUrl) {
        controller.setMpvProperty("panscan", "1.0")
        // Trailer loudness is wildly inconsistent between uploads; smooth it out in real time.
        controller.setMpvProperty("af", TRAILER_AUDIO_NORMALIZATION_FILTER)
    }

    LaunchedEffect(controller, playWhenReady) {
        if (playWhenReady) controller.play() else controller.pause()
    }

    LaunchedEffect(controller, sourceUrl, playWhenReady) {
        var readyReported = false
        var endedReported = false
        while (true) {
            val snapshot = controller.snapshot()
            if (
                playWhenReady &&
                (firstPaintComplete || hasPaintedOnce) &&
                !readyReported &&
                snapshot.isPlaying &&
                snapshot.positionMs >= 150L
            ) {
                readyReported = true
                playbackRevealReady = true
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
