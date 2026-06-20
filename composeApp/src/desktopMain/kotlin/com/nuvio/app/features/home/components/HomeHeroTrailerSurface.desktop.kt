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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.PlayerControlsState
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.NativePlayerController
import com.nuvio.app.features.player.desktop.NativePlayerHost
import kotlinx.coroutines.delay
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import javax.swing.text.JTextComponent

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
    backgroundColor: Color,
    logoUrl: String?,
    title: String,
    meta: String,
    description: String,
    modifier: Modifier,
    onReady: () -> Unit,
    onEnded: () -> Unit,
    onError: () -> Unit,
) {
    val supported = DesktopHostOs.current == DesktopHostOs.WINDOWS ||
        DesktopHostOs.current == DesktopHostOs.MACOS
    val latestOnError = rememberUpdatedState(onError)
    val latestOnReady = rememberUpdatedState(onReady)
    val latestOnEnded = rememberUpdatedState(onEnded)

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

    // The native mpv surface can become the AWT focus owner even though the Swing host is
    // marked non-focusable. Route home controls globally while this surface is mounted,
    // matching the dispatcher used by the full-screen player.
    DisposableEffect(controller) {
        val dispatcher = KeyEventDispatcher { event ->
            if (event.id != KeyEvent.KEY_PRESSED) return@KeyEventDispatcher false
            if (event.isMetaDown || event.isControlDown || event.isAltDown) {
                return@KeyEventDispatcher false
            }
            val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            if (focusOwner is JTextComponent) return@KeyEventDispatcher false

            val key = when (event.keyCode) {
                KeyEvent.VK_UP -> HomeTvKey.Up
                KeyEvent.VK_DOWN -> HomeTvKey.Down
                KeyEvent.VK_LEFT -> HomeTvKey.Left
                KeyEvent.VK_RIGHT -> HomeTvKey.Right
                KeyEvent.VK_ENTER -> HomeTvKey.Select
                KeyEvent.VK_T -> HomeTvKey.ToggleTrailer
                KeyEvent.VK_ESCAPE -> HomeTvKey.Dismiss
                KeyEvent.VK_S -> HomeTvKey.Search
                KeyEvent.VK_L -> HomeTvKey.Library
                else -> return@KeyEventDispatcher false
            }
            HomeTvKeyboardBridge.emit(key)
            event.consume()
            true
        }
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        focusManager.addKeyEventDispatcher(dispatcher)
        onDispose { focusManager.removeKeyEventDispatcher(dispatcher) }
    }

    LaunchedEffect(controller) {
        controller.setControlCallbacks(
            onAction = { false },
            onEvent = { type, _ ->
                val key = when (type) {
                    "homeKeyUp" -> HomeTvKey.Up
                    "homeKeyDown" -> HomeTvKey.Down
                    "homeKeyLeft" -> HomeTvKey.Left
                    "homeKeyRight" -> HomeTvKey.Right
                    "homeKeySelect" -> HomeTvKey.Select
                    "homeKeyToggleTrailer" -> HomeTvKey.ToggleTrailer
                    "homeKeyDismiss" -> HomeTvKey.Dismiss
                    "homeKeySearch" -> HomeTvKey.Search
                    "homeKeyLibrary" -> HomeTvKey.Library
                    else -> return@setControlCallbacks false
                }
                HomeTvKeyboardBridge.emit(key)
                true
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
            ),
        )
        trailerSurfaceLog.i { "attach video=${sourceUrl.take(80)} audio=${sourceAudioUrl?.take(60)} muted=$muted" }
        controller.attach(
            sourceUrl = sourceUrl,
            sourceAudioUrl = sourceAudioUrl?.takeIf { it.isNotBlank() },
            sourceHeaders = emptyMap(),
            playWhenReady = playWhenReady,
            initialPositionMs = 0L,
            onError = { message ->
                trailerSurfaceLog.w { "playback error: $message" }
                latestOnError.value()
            },
            // Tells the controls page it is a passive hero surface from the very first script
            // run, so it never grabs keyboard focus (which would break TV navigation).
            controlsPageUrlSuffix = "?heroTrailer=1",
        )
    }

    // mpv "mute" property queues until the handle exists, so it survives the async attach.
    LaunchedEffect(controller, sourceUrl, muted) {
        controller.setMpvProperty("mute", if (muted) "yes" else "no")
    }

    // Crop the trailer to fill the hero region (no letterbox) so it reads as part of the
    // hero rather than a pillarboxed PIP window. panscan queues until the handle exists.
    LaunchedEffect(controller, sourceUrl) {
        controller.setMpvProperty("panscan", "1.0")
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

private fun Color.toHeroTrailerHex(): String {
    fun channel(value: Float): String =
        (value * 255f).toInt().coerceIn(0, 255).toString(16).padStart(2, '0')
    return "#${channel(red)}${channel(green)}${channel(blue)}"
}
