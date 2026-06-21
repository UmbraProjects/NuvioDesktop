package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.desktop.DesktopAnimeShaders
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.DesktopPlayerLaunchShield
import com.nuvio.app.features.player.desktop.NativePlayerController
import com.nuvio.app.features.player.desktop.NativePlayerHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import javax.swing.text.JTextComponent

@Composable
actual fun PlatformPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    sourceHeaders: Map<String, String>,
    sourceResponseHeaders: Map<String, String>,
    streamType: String?,
    useYoutubeChunkedPlayback: Boolean,
    isAnimeContent: Boolean,
    modifier: Modifier,
    playWhenReady: Boolean,
    resizeMode: PlayerResizeMode,
    initialPositionMs: Long,
    useNativeController: Boolean,
    playerControlsState: PlayerControlsState,
    onPlayerControlsAction: (PlayerControlsAction) -> Boolean,
    onPlayerControlsEvent: (String, Double) -> Boolean,
    onPlayerControlsScrubChange: (Long) -> Boolean,
    onPlayerControlsScrubFinished: (Long) -> Boolean,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    if (DesktopHostOs.current == DesktopHostOs.MACOS || DesktopHostOs.current == DesktopHostOs.WINDOWS) {
        NativePlayerSurface(
            sourceUrl = sourceUrl,
            sourceAudioUrl = sourceAudioUrl,
            sourceHeaders = sourceHeaders,
            isAnimeContent = isAnimeContent,
            modifier = modifier,
            playWhenReady = playWhenReady,
            resizeMode = resizeMode,
            initialPositionMs = initialPositionMs,
            playerControlsState = playerControlsState,
            onPlayerControlsAction = onPlayerControlsAction,
            onPlayerControlsEvent = onPlayerControlsEvent,
            onPlayerControlsScrubChange = onPlayerControlsScrubChange,
            onPlayerControlsScrubFinished = onPlayerControlsScrubFinished,
            onControllerReady = onControllerReady,
            onSnapshot = onSnapshot,
            onError = onError,
        )
        return
    }

    DesktopStubPlayerSurface(
        modifier = modifier,
        onControllerReady = onControllerReady,
        onSnapshot = onSnapshot,
    )
}

@Composable
private fun NativePlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    sourceHeaders: Map<String, String>,
    isAnimeContent: Boolean,
    modifier: Modifier,
    playWhenReady: Boolean,
    resizeMode: PlayerResizeMode,
    initialPositionMs: Long,
    playerControlsState: PlayerControlsState,
    onPlayerControlsAction: (PlayerControlsAction) -> Boolean,
    onPlayerControlsEvent: (String, Double) -> Boolean,
    onPlayerControlsScrubChange: (Long) -> Boolean,
    onPlayerControlsScrubFinished: (Long) -> Boolean,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val host = remember { NativePlayerHost() }
    val controller = remember(host) { NativePlayerController(host) }
    val hostFirstPaintComplete = remember { mutableStateOf(false) }
    val hostFirstFullSizePaintComplete = remember { mutableStateOf(false) }
    val videoIsHdr = remember { mutableStateOf<Boolean?>(null) }
    val videoProfileRefreshToken = remember { mutableIntStateOf(0) }
    LaunchedEffect(sourceUrl) {
        DesktopPlayerLaunchShield.showForActiveWindow()
    }
    val playbackHeaders = remember(sourceHeaders) { sanitizePlaybackHeaders(sourceHeaders) }
    val latestOnPlayerControlsAction = rememberUpdatedState(onPlayerControlsAction)
    val latestOnPlayerControlsEvent = rememberUpdatedState(onPlayerControlsEvent)
    val latestOnPlayerControlsScrubChange = rememberUpdatedState(onPlayerControlsScrubChange)
    val latestOnPlayerControlsScrubFinished = rememberUpdatedState(onPlayerControlsScrubFinished)
    val latestOnError = rememberUpdatedState(onError)

    LaunchedEffect(controller, sourceUrl) {
        onControllerReady(controller)
    }

    DisposableEffect(host) {
        host.onDisplayableChanged = { displayable ->
            if (!displayable) {
                hostFirstPaintComplete.value = false
                hostFirstFullSizePaintComplete.value = false
            }
        }
        host.onFirstPaint = {
            hostFirstPaintComplete.value = true
        }
        host.onFirstFullSizePaint = {
            hostFirstFullSizePaintComplete.value = true
            DesktopPlayerLaunchShield.hideAfter()
        }
        onDispose {
            host.onDisplayableChanged = null
            host.onFirstPaint = null
            host.onFirstFullSizePaint = null
            DesktopPlayerLaunchShield.hide()
        }
    }

    LaunchedEffect(controller) {
        controller.setControlCallbacks(
            onAction = { action -> latestOnPlayerControlsAction.value(action) },
            onEvent = { type, value ->
                if (type == "videoParams") {
                    videoIsHdr.value = value != 0.0
                    true
                } else if (type == "fileLoaded") {
                    videoProfileRefreshToken.intValue += 1
                    true
                } else {
                    latestOnPlayerControlsEvent.value(type, value)
                }
            },
            onScrubChange = { positionMs -> latestOnPlayerControlsScrubChange.value(positionMs) },
            onScrubFinished = { positionMs -> latestOnPlayerControlsScrubFinished.value(positionMs) },
        )
    }

    DisposableEffect(controller) {
        val dispatcher = KeyEventDispatcher { event ->
            if (event.id != KeyEvent.KEY_PRESSED) return@KeyEventDispatcher false
            if (event.isMetaDown || event.isControlDown || event.isAltDown) return@KeyEventDispatcher false
            val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            if (focusOwner is JTextComponent) return@KeyEventDispatcher false
            val panelKey = when (event.keyCode) {
                KeyEvent.VK_UP -> "ArrowUp"
                KeyEvent.VK_DOWN -> "ArrowDown"
                KeyEvent.VK_LEFT -> "ArrowLeft"
                KeyEvent.VK_RIGHT -> "ArrowRight"
                KeyEvent.VK_ENTER -> "Enter"
                KeyEvent.VK_ESCAPE -> "Escape"
                else -> null
            }
            if (panelKey != null && controller.dispatchKeyboardPanelKey(panelKey)) {
                event.consume()
                return@KeyEventDispatcher true
            }
            when (event.keyCode) {
                KeyEvent.VK_F8 -> {
                    controller.cycleDesktopHdrMode()
                }
                KeyEvent.VK_F9 -> {
                    controller.cycleDesktopColorProfile()
                }
                KeyEvent.VK_F10 -> {
                    controller.cycleDesktopAnimeMode()
                }
                KeyEvent.VK_TAB -> {
                    // Tab skips the intro/outro, but only while the skip prompt is on screen;
                    // otherwise let Tab keep its normal behavior.
                    if (!controller.triggerSkipIntervalIfAvailable()) {
                        return@KeyEventDispatcher false
                    }
                }
                else -> {
                    val type = when (event.keyCode) {
                        KeyEvent.VK_LEFT, KeyEvent.VK_J -> "keyboardSeekBack"
                        KeyEvent.VK_RIGHT, KeyEvent.VK_L -> "keyboardSeekForward"
                        KeyEvent.VK_UP -> "volumeUp"
                        KeyEvent.VK_DOWN -> "volumeDown"
                        KeyEvent.VK_SPACE, KeyEvent.VK_K -> "keyboardToggle"
                        KeyEvent.VK_C -> "resize"
                        KeyEvent.VK_OPEN_BRACKET -> "keyboardSpeedStep"
                        KeyEvent.VK_CLOSE_BRACKET -> "keyboardSpeedStep"
                        KeyEvent.VK_S -> "keyboardNextSubtitle"
                        KeyEvent.VK_A -> "keyboardNextAudio"
                        KeyEvent.VK_O -> {
                            controller.openKeyboardPanel("sources")
                            event.consume()
                            return@KeyEventDispatcher true
                        }
                        KeyEvent.VK_E -> {
                            controller.openKeyboardPanel("episodes")
                            event.consume()
                            return@KeyEventDispatcher true
                        }
                        else -> return@KeyEventDispatcher false
                    }
                    val value = if (event.keyCode == KeyEvent.VK_OPEN_BRACKET) -1.0 else 1.0
                    controller.dispatchKeyboardShortcut(type, value)
                }
            }
            event.consume()
            true
        }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
        onDispose {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher)
        }
    }

    // Source changes are handled by controller.attach(), which replaces the current native
    // handle. Disposing here on every URL/header change permanently marks the remembered
    // controller as unusable and can synchronously block the UI while mpv/WebView2 shut down.
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }

    LaunchedEffect(controller, sourceUrl, playbackHeaders, hostFirstFullSizePaintComplete.value) {
        if (!hostFirstFullSizePaintComplete.value) {
            return@LaunchedEffect
        }
        delay(16L)
        controller.attach(
            sourceUrl = sourceUrl,
            sourceAudioUrl = sourceAudioUrl,
            sourceHeaders = playbackHeaders,
            playWhenReady = playWhenReady,
            initialPositionMs = initialPositionMs,
            onError = { message -> latestOnError.value(message) },
        )
    }

    LaunchedEffect(controller, playWhenReady) {
        if (playWhenReady) {
            controller.play()
        } else {
            controller.pause()
        }
    }

    LaunchedEffect(controller, resizeMode) {
        controller.setResizeMode(resizeMode)
    }

    LaunchedEffect(controller, playerControlsState) {
        controller.updateControls(playerControlsState)
    }

    LaunchedEffect(sourceUrl) {
        videoIsHdr.value = null
    }

    LaunchedEffect(controller, sourceUrl) {
        // Apply the saved colour/HDR presets whenever they change or the file's HDR
        // state is (re)detected. Deliberately NOT gated on HDR detection: the colour
        // profile (and F8/F9 changes) must take effect even if the video-params event
        // never arrives, otherwise nothing would visibly change.
        combine(
            snapshotFlow { videoIsHdr.value to videoProfileRefreshToken.intValue },
            PlayerSettingsRepository.uiState,
        ) { videoState, settings -> videoState.first to settings }
            .collect { (isHdr, settings) ->
                System.out.println(
                    "Desktop video profile: detectedHdr=${isHdr ?: "unknown"}, " +
                        "hdrMode=${settings.desktopHdrMode.name}, colorProfile=${settings.desktopColorProfile.name}, " +
                        "bufferPreset=${settings.desktopBufferPreset.name}, " +
                        "animeMode=${settings.desktopAnimeMode.name}, isAnime=$isAnimeContent",
                )
                applyDesktopVideoProfile(
                    controller = controller,
                    hdrMode = settings.desktopHdrMode,
                    colorProfile = settings.desktopColorProfile,
                )
                controller.applyDesktopBufferPreset(settings.desktopBufferPreset)
                applyDesktopAnimeProfile(
                    controller = controller,
                    mode = settings.desktopAnimeMode,
                    isAnime = isAnimeContent,
                    isHdr = isHdr == true,
                )
            }
    }

    LaunchedEffect(controller) {
        while (true) {
            onSnapshot(controller.snapshot())
            delay(500L)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        SwingPanel(
            factory = {
                host
            },
            modifier = if (hostFirstPaintComplete.value) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .align(Alignment.BottomEnd)
                    .requiredSize(1.dp)
            },
            background = Color.Black,
        )
    }
}

private fun applyDesktopVideoProfile(
    controller: NativePlayerController,
    hdrMode: DesktopHdrMode,
    colorProfile: DesktopColorProfile,
) {
    when (hdrMode) {
        DesktopHdrMode.Auto -> {
            controller.setMpvProperty("target-colorspace-hint", "auto")
            controller.setMpvProperty("target-colorspace-hint-mode", "target")
            controller.setMpvProperty("target-prim", "auto")
            controller.setMpvProperty("target-trc", "auto")
            controller.setMpvProperty("target-peak", "auto")
            controller.setMpvProperty("target-contrast", "auto")
        }
        DesktopHdrMode.AlwaysTonemap -> {
            // Force an SDR BT.709/BT.1886 target. Merely disabling the colorspace hint does
            // not request HDR-to-SDR conversion and was effectively a no-op on HDR desktops.
            controller.setMpvProperty("target-colorspace-hint", "yes")
            controller.setMpvProperty("target-colorspace-hint-mode", "target")
            controller.setMpvProperty("target-prim", "bt.709")
            controller.setMpvProperty("target-trc", "bt.1886")
            controller.setMpvProperty("target-peak", "203")
            controller.setMpvProperty("target-contrast", "1000")
        }
        DesktopHdrMode.AlwaysPassthrough -> {
            // Source mode signals the source metadata to the compositor/display and is mpv's
            // traditional HDR passthrough path.
            controller.setMpvProperty("target-colorspace-hint", "yes")
            controller.setMpvProperty("target-colorspace-hint-mode", "source")
            controller.setMpvProperty("target-prim", "auto")
            controller.setMpvProperty("target-trc", "auto")
            controller.setMpvProperty("target-peak", "auto")
            controller.setMpvProperty("target-contrast", "auto")
        }
    }
    // Color-preset equalizer values adopted from Stremio-Kai by allecsc, used with
    // permission and attribution. The desktop player already mirrors Stremio-Kai's mpv
    // rendering pipeline (gpu-next, spline36/lanczos/mitchell scaling, sigmoid upscaling,
    // fruit dithering, deband, bt.2446a tonemapping), so these match its film-accurate
    // Original / Kai / Vivid presets instead of the previous heavier-handed values.
    val (contrast, brightness, saturation, gamma) = when (colorProfile) {
        DesktopColorProfile.Neutral -> listOf(0, 0, 0, 0)
        DesktopColorProfile.Cinematic -> listOf(2, -6, 2, 2)
        DesktopColorProfile.Vivid -> listOf(5, -4, 15, -2)
    }
    controller.setMpvProperty("contrast", contrast.toString())
    controller.setMpvProperty("brightness", brightness.toString())
    controller.setMpvProperty("saturation", saturation.toString())
    controller.setMpvProperty("gamma", gamma.toString())
    // mpv won't repaint the embedded surface for these property changes while idle/paused,
    // so force a redraw — otherwise the change only appears after a window resize.
    controller.forceVideoRedraw()
}

/**
 * Applies (or clears) the Stremio-Kai anime enhancement layer: Anime4K GLSL shaders plus anime-tuned
 * scaling/deband and an hqdn3d denoise pass. Resolves the effective preset from [mode]:
 * `Off` -> none, `Auto` -> Optimized only when [isAnime], otherwise the explicitly chosen preset.
 *
 * SVP / motion interpolation from Kai is intentionally not ported (it needs a paid external runtime).
 */
private fun applyDesktopAnimeProfile(
    controller: NativePlayerController,
    mode: DesktopAnimeMode,
    isAnime: Boolean,
    isHdr: Boolean,
) {
    val effectivePreset = when (mode) {
        DesktopAnimeMode.Off -> null
        DesktopAnimeMode.Auto -> if (isAnime) DesktopAnimeMode.Optimized else null
        DesktopAnimeMode.Optimized -> DesktopAnimeMode.Optimized
        DesktopAnimeMode.Fast -> DesktopAnimeMode.Fast
        DesktopAnimeMode.Hq -> DesktopAnimeMode.Hq
    }

    if (effectivePreset == null) {
        // Restore the bridge's baseline live-action rendering (see startMpv in player_bridge.cpp).
        controller.setMpvProperty("glsl-shaders", "")
        controller.setMpvProperty("vf", "")
        controller.setMpvProperty("scale", "spline36")
        controller.setMpvProperty("cscale", "lanczos")
        controller.setMpvProperty("scale-blur", "0.0")
        controller.setMpvProperty("deband-threshold", "35")
        controller.setMpvProperty("deband-grain", "0")
        controller.forceVideoRedraw()
        return
    }

    // Anime-tuned scaling + deband (Stremio-Kai's anime-sdr base profile).
    controller.setMpvProperty("scale", "ewa_lanczos")
    controller.setMpvProperty("cscale", "ewa_lanczos")
    controller.setMpvProperty("scale-blur", "1.05")
    controller.setMpvProperty("deband-threshold", "45")
    controller.setMpvProperty("deband-grain", "20")

    val shaderChain = DesktopAnimeShaders.shaderChain(effectivePreset)
    controller.setMpvProperty("glsl-shaders", shaderChain)

    // hqdn3d temporal/spatial denoise (Kai's standard anime VF). Skipped on HDR to avoid the heavier
    // filter chain fighting the tonemap path, matching Kai's denoise removal for HDR anime.
    if (isHdr) {
        controller.setMpvProperty("vf", "")
    } else {
        controller.setMpvProperty(
            "vf",
            "@HQDN3D:lavfi=[hqdn3d=luma_spatial=5:chroma_spatial=5:luma_tmp=6:chroma_tmp=6]",
        )
    }
    controller.forceVideoRedraw()
}

@Composable
private fun DesktopStubPlayerSurface(
    modifier: Modifier,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
) {
    val controller = remember { DesktopStubPlayerController() }

    LaunchedEffect(controller) {
        onControllerReady(controller)
        onSnapshot(PlayerPlaybackSnapshot(isLoading = false))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Desktop in-app playback is not available yet.",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private class DesktopStubPlayerController : PlayerEngineController {
    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun seekBy(offsetMs: Long) = Unit
    override fun retry() = Unit
    override fun setPlaybackSpeed(speed: Float) = Unit
    override fun getAudioTracks(): List<AudioTrack> = emptyList()
    override fun getSubtitleTracks(): List<SubtitleTrack> = emptyList()
    override fun selectAudioTrack(index: Int) = Unit
    override fun selectSubtitleTrack(index: Int) = Unit
    override fun setSubtitleUri(url: String) = Unit
    override fun clearExternalSubtitle() = Unit
    override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit
}
