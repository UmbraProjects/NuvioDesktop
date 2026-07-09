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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import com.nuvio.app.core.ui.LocalNuvioBaseDensity
import com.nuvio.app.features.player.desktop.DesktopAnimeShaders
import com.nuvio.app.features.player.desktop.DesktopAnimeSvp
import com.nuvio.app.features.player.desktop.DesktopCustomShaders
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.DesktopPlayerLaunchShield
import com.nuvio.app.features.player.desktop.NativePlayerController
import com.nuvio.app.features.player.desktop.NativePlayerHost
import com.nuvio.app.features.player.desktop.desktopAppFullscreenState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
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
    val videoVsrScale = remember { mutableStateOf<Double?>(null) }
    val videoProfileRefreshToken = remember { mutableIntStateOf(0) }
    // F10/F7 are documented as forcing the anime preset "regardless of detection" for this
    // playback session — but the auto-detect gate below only ever looks at the persisted
    // "Auto-apply to Anime" setting, so without this it silently overrides an explicit F10/F7
    // press whenever detection says isAnimeContent=false (e.g. continue-watching, which skips
    // the meta details screen where genre detection normally runs).
    val animeModeSessionForced = remember { mutableStateOf(false) }
    LaunchedEffect(sourceUrl) {
        DesktopPlayerLaunchShield.showForActiveWindow()
    }
    val playbackHeaders = remember(sourceHeaders) { sanitizePlaybackHeaders(sourceHeaders) }
    val playerSettings by PlayerSettingsRepository.uiState.collectAsState()
    // The native side pins the D3D11 device to the NVIDIA GPU (and captures the diagnostic mpv
    // log) when VSR is on — the d3d11vpp video processor needs the NVIDIA adapter, which matters
    // on hybrid-GPU machines.
    val nvidiaRtxSuperResolutionEnabled = playerSettings.nvidiaRtxSuperResolutionEnabled
    val nvidiaRtxHdrEnabled = playerSettings.nvidiaRtxHdrEnabled
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
                } else if (type == "videoVsrScale") {
                    videoVsrScale.value = value
                    true
                } else if (type == "fileLoaded") {
                    PlaybackStartTrace.mark("fileLoaded")
                    videoProfileRefreshToken.intValue += 1
                    true
                } else if (type == "playbackRestart") {
                    // First decoded/rendered frame of the current file (once per load).
                    PlaybackStartTrace.complete("firstFrame")
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
        PlayerShortcutsRepository.ensureLoaded()
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
            // Tab skips the intro/outro, but only while the skip prompt is on screen; otherwise
            // let Tab keep its normal behavior. Fixed (not rebindable).
            if (event.keyCode == KeyEvent.VK_TAB) {
                if (!controller.triggerSkipIntervalIfAvailable()) {
                    return@KeyEventDispatcher false
                }
                event.consume()
                return@KeyEventDispatcher true
            }
            // Fixed, non-rebindable alternates preserved exactly from the historical bindings:
            // the arrow keys (seek/volume) and K (play/pause). These always apply.
            val fixedType = when (event.keyCode) {
                KeyEvent.VK_LEFT -> "keyboardSeekBack"
                KeyEvent.VK_RIGHT -> "keyboardSeekForward"
                KeyEvent.VK_UP -> "volumeUp"
                KeyEvent.VK_DOWN -> "volumeDown"
                KeyEvent.VK_K -> "keyboardToggle"
                else -> null
            }
            if (fixedType != null) {
                controller.dispatchKeyboardShortcut(fixedType, 1.0)
                event.consume()
                return@KeyEventDispatcher true
            }
            // Rebindable actions, resolved against the user's current bindings.
            val action = PlayerShortcutsRepository.actionForKeyCode(event.keyCode)
                ?: return@KeyEventDispatcher false
            when (action) {
                PlayerShortcutAction.PlayPause -> controller.dispatchKeyboardShortcut("keyboardToggle", 1.0)
                PlayerShortcutAction.SeekBackward -> controller.dispatchKeyboardShortcut("keyboardSeekBack", 1.0)
                PlayerShortcutAction.SeekForward -> controller.dispatchKeyboardShortcut("keyboardSeekForward", 1.0)
                PlayerShortcutAction.SpeedUp -> controller.dispatchKeyboardShortcut("keyboardSpeedStep", 1.0)
                PlayerShortcutAction.SpeedDown -> controller.dispatchKeyboardShortcut("keyboardSpeedStep", -1.0)
                PlayerShortcutAction.NextSubtitle -> controller.dispatchKeyboardShortcut("keyboardNextSubtitle", 1.0)
                PlayerShortcutAction.NextAudio -> controller.dispatchKeyboardShortcut("keyboardNextAudio", 1.0)
                PlayerShortcutAction.OpenSources -> controller.openKeyboardPanel("sources")
                PlayerShortcutAction.OpenEpisodes -> controller.openKeyboardPanel("episodes")
                PlayerShortcutAction.CycleZoom -> controller.dispatchKeyboardShortcut("resize", 1.0)
                PlayerShortcutAction.CycleSvp -> {
                    animeModeSessionForced.value = true
                    controller.cycleDesktopAnimeSvpMode()
                }
                PlayerShortcutAction.CycleHdr -> controller.cycleDesktopHdrMode()
                PlayerShortcutAction.CycleColorProfile -> controller.cycleDesktopColorProfile()
                PlayerShortcutAction.CycleAnime -> {
                    animeModeSessionForced.value = true
                    controller.cycleDesktopAnimeMode()
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

    LaunchedEffect(controller, sourceUrl, playbackHeaders, nvidiaRtxSuperResolutionEnabled, nvidiaRtxHdrEnabled, hostFirstFullSizePaintComplete.value) {
        if (!hostFirstFullSizePaintComplete.value) {
            return@LaunchedEffect
        }
        delay(16L)
        PlaybackStartTrace.mark("playerAttach")
        // Logged so a binge/next-episode stall can be diagnosed: if the common layer reports it
        // set a new activeSourceUrl (see BingeAdvance "switchToEpisodeStream" log) but this line
        // never follows while the window is minimized, that confirms the attach is waiting on a
        // paused recomposition rather than something in stream selection.
        BingeAdvanceLog.i { "desktop attach firing sourceUrl=${sourceUrl.takeLast(48)}" }
        controller.attach(
            tracePlaybackStart = true,
            sourceUrl = sourceUrl,
            sourceAudioUrl = sourceAudioUrl,
            sourceHeaders = playbackHeaders,
            playWhenReady = playWhenReady,
            initialPositionMs = initialPositionMs,
            nvidiaRtxSuperResolutionEnabled = nvidiaRtxSuperResolutionEnabled,
            nvidiaRtxHdrEnabled = nvidiaRtxHdrEnabled,
            enableUserMpvOptions = true,
            restoreVolume = true,
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
        videoVsrScale.value = null
        animeModeSessionForced.value = false
    }

    LaunchedEffect(controller, sourceUrl, isAnimeContent) {
        // Apply the saved colour/HDR presets whenever they change or the file's HDR
        // state is (re)detected. Deliberately NOT gated on HDR detection: the colour
        // profile (and F8/F9 changes) must take effect even if the video-params event
        // never arrives, otherwise nothing would visibly change.
        // Keyed on isAnimeContent too: it starts false and can flip true a moment later once
        // the async genre lookup for continue-watching/resume playback resolves (see
        // PlayerScreenRuntimeUi's fallback meta fetch) — without this key the anime profile
        // below would be stuck using whatever isAnimeContent was captured at launch.
        combine(
            snapshotFlow {
                Triple(videoIsHdr.value, videoVsrScale.value, videoProfileRefreshToken.intValue)
            },
            PlayerSettingsRepository.uiState,
        ) { videoState, settings -> Triple(videoState, settings, videoState.third > 0) }
            .collect { (videoState, settings, fileLoaded) ->
                val isHdr = videoState.first
                val vsrScale = videoState.second
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
                    isHdr = isHdr == true,
                )
                controller.applyDesktopBufferPreset(settings.desktopBufferPreset)
                applyDesktopAnimeProfile(
                    controller = controller,
                    mode = settings.desktopAnimeMode,
                    autoEnabled = settings.desktopAnimeModeAutoEnabled && !animeModeSessionForced.value,
                    // Do not queue vapoursynth before mpv has resolved a real video stream.
                    // Some HLS sources expose odd probe tracks during startup, and applying SVP
                    // in that window can kill the native process before fileLoaded is emitted.
                    animeSvpEnabled = settings.desktopAnimeSvpEnabled && fileLoaded,
                    isAnime = isAnimeContent,
                    isHdr = isHdr == true,
                    nvidiaRtxSuperResolutionEnabled = settings.nvidiaRtxSuperResolutionEnabled,
                    nvidiaRtxSuperResolutionScale = vsrScale,
                    nvidiaRtxHdrEnabled = settings.nvidiaRtxHdrEnabled,
                    customShaderPaths = settings.desktopCustomShaderPaths,
                    customShaderSelectedPath = settings.desktopCustomShaderSelectedPath,
                )
            }
    }

    LaunchedEffect(controller) {
        // The F11 borderless-fullscreen toggle restyles/resizes the top-level window via a raw
        // native SetWindowPos, bypassing Compose's own resize path. The embedded D3D11 video
        // surface doesn't reliably pick that up on its own — same underlying issue as the HDR/
        // colour profile case above ("mpv won't repaint... otherwise the change only appears
        // after a window resize") — leaving it black until something else nudges it. Force a
        // redraw once the transition has had a moment to settle. drop(1) skips the initial
        // emission so mounting the player doesn't force a redraw before it's ever painted.
        snapshotFlow { desktopAppFullscreenState.value }
            .drop(1)
            .collect {
                delay(150)
                controller.forceVideoRedraw()
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
        // SwingPanel positions/sizes the native mpv surface (a real OS window) using whatever
        // LocalDensity is ambient — but that surface must match real screen pixels, not the
        // app's (possibly artificially inflated, see NuvioDesktopViewportDensityScaler) UI
        // density. Use the window's real density here so the video always fills the space it's
        // given instead of being sized as a fraction of it.
        CompositionLocalProvider(LocalDensity provides LocalNuvioBaseDensity.current) {
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
}

private fun applyDesktopVideoProfile(
    controller: NativePlayerController,
    hdrMode: DesktopHdrMode,
    colorProfile: DesktopColorProfile,
    isHdr: Boolean,
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
    //
    // These offsets are SDR-tuned (Kai's SDR presets): mpv's brightness/contrast/gamma
    // equalizer operates on the encoded video signal, so on an HDR PQ passthrough signal the
    // steep near-black PQ curve turns e.g. Cinematic's -6 brightness into badly crushed/darkened
    // shadows. Kai avoids this by forcing "original" (neutral) colors whenever HDR passthrough is
    // active (profile-manager.lua). We can't probe the display's HDR state from here (mpv owns
    // that via target-colorspace-hint=auto), so mirror the intent by neutralizing the grade for
    // HDR content unless we're actively tonemapping down to SDR, where the SDR presets are correct.
    val forceNeutral = isHdr && hdrMode != DesktopHdrMode.AlwaysTonemap
    val (contrast, brightness, saturation, gamma) = when {
        forceNeutral -> listOf(0, 0, 0, 0)
        else -> when (colorProfile) {
            DesktopColorProfile.Neutral -> listOf(0, 0, 0, 0)
            DesktopColorProfile.Cinematic -> listOf(2, -6, 2, 2)
            DesktopColorProfile.Vivid -> listOf(5, -4, 15, -2)
        }
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
    autoEnabled: Boolean,
    animeSvpEnabled: Boolean,
    isAnime: Boolean,
    isHdr: Boolean,
    nvidiaRtxSuperResolutionEnabled: Boolean = false,
    nvidiaRtxSuperResolutionScale: Double? = null,
    nvidiaRtxHdrEnabled: Boolean = false,
    customShaderPaths: String = "",
    customShaderSelectedPath: String = "",
) {
    val customShaderChain = if (mode == DesktopAnimeMode.CustomShader && !(autoEnabled && !isAnime)) {
        DesktopCustomShaders.shaderChain(
            pathsText = customShaderPaths,
            selectedPath = customShaderSelectedPath,
        )
    } else {
        ""
    }
    val effectivePreset = when {
        mode == DesktopAnimeMode.Off -> null
        mode == DesktopAnimeMode.CustomShader -> null
        autoEnabled && !isAnime -> null
        else -> mode
    }

    // This function is the single owner of the mpv `vf` chain. NVIDIA RTX VSR and RTX True HDR
    // are both d3d11vpp sub-options; they must be set here so a profile rebuild doesn't wipe them.
    // Anime4K (when active) takes the vf entirely — RTX features are suppressed while Anime4K runs.
    val baselineVf = buildString {
        val vsrActive = nvidiaRtxSuperResolutionEnabled &&
            nvidiaRtxSuperResolutionScale != null &&
            nvidiaRtxSuperResolutionScale > 1.01
        // RTX True HDR requires mpv master ≥ Feb 19 2026: mpv sets IMGFMT_X2BGR10 output
        // automatically when nvidia-true-hdr is present, and uses ID3D11VideoContext1 for
        // proper DXGI HDR colour-space signalling. Init-time d3d11-output-csp=auto and
        // target-colorspace-hint=auto are set in player_bridge.cpp when HDR is enabled.
        val hdrActive = nvidiaRtxHdrEnabled
        if (vsrActive || hdrActive) {
            append("d3d11vpp=")
            if (vsrActive) {
                append("scale=${nvidiaRtxSuperResolutionScale!!.coerceIn(1.0, 4.0)}:scaling-mode=nvidia")
                if (hdrActive) append(":")
            }
            if (hdrActive) append("nvidia-true-hdr=yes")
        }
    }

    // If Anime4k is forced via F10 (effectivePreset != null) OR if it's auto-detected (isAnime),
    // we consider this video to be Anime for the purposes of SVP interpolation.
    val isEffectivelyAnime = isAnime || effectivePreset != null || customShaderChain.isNotEmpty()

    val svpFilter = if (isEffectivelyAnime && animeSvpEnabled) {
        DesktopAnimeSvp.vapoursynthArgument()
    } else null
    val svpActive = svpFilter != null
    applyDesktopSvpRuntimeProfile(controller, svpActive)

    if (customShaderChain.isNotEmpty()) {
        controller.setMpvProperty("scale", "ewa_lanczos")
        controller.setMpvProperty("cscale", "ewa_lanczos")
        controller.setMpvProperty("scale-blur", "1.05")
        controller.setMpvProperty("deband-threshold", "45")
        controller.setMpvProperty("deband-grain", "20")
        controller.setMpvProperty("glsl-shaders", customShaderChain)

        if (svpActive) {
            controller.setMpvProperty("hwdec", "d3d11va-copy")
        } else {
            controller.setMpvProperty("hwdec", "d3d11va")
        }

        controller.setMpvProperty("vf", listOfNotNull(svpFilter).joinToString(","))
        controller.forceVideoRedraw()
        return
    }

    if (effectivePreset == null) {
        // Restore the bridge's baseline live-action rendering (see startMpv in player_bridge.cpp).
        controller.setMpvProperty("glsl-shaders", "")
        
        // Restore the standard D3D11VA hardware decoder so d3d11vpp (RTX features) works.
        // However, if SVP is active, we MUST use a copy-back decoder for the CPU filter.
        if (svpActive) {
            controller.setMpvProperty("hwdec", "d3d11va-copy")
        } else {
            controller.setMpvProperty("hwdec", "d3d11va")
        }
        
        val finalVf = listOfNotNull(baselineVf.takeIf { it.isNotEmpty() }, svpFilter).joinToString(",")
        controller.setMpvProperty("vf", finalVf)
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

    controller.setMpvProperty("glsl-shaders", DesktopAnimeShaders.shaderChain(effectivePreset))

    // hqdn3d temporal/spatial denoise (Kai's standard anime VF). Skipped on HDR to avoid the heavier
    // filter chain fighting the tonemap path, matching Kai's denoise removal for HDR anime.
    val hqdn3d = if (isHdr) "" else "@HQDN3D:lavfi=[hqdn3d=luma_spatial=5:chroma_spatial=5:luma_tmp=6:chroma_tmp=6]"
    val finalVf = listOfNotNull(hqdn3d.takeIf { it.isNotEmpty() }, svpFilter).joinToString(",")

    // When injecting CPU/software-based lavfi filters (hqdn3d, vapoursynth), we MUST dynamically switch
    // the hardware decoder to a copy-back mode, otherwise FFmpeg fails to map the d3d11 surface to RAM.
    controller.setMpvProperty("hwdec", "d3d11va-copy")
    controller.setMpvProperty("vf", finalVf)
    controller.forceVideoRedraw()
}

private fun applyDesktopSvpRuntimeProfile(
    controller: NativePlayerController,
    enabled: Boolean,
) {
    if (enabled) {
        controller.setMpvProperty("vd-queue-enable", "yes")
        controller.setMpvProperty("vd-queue-max-bytes", "512MiB")
        controller.setMpvProperty("vd-queue-max-samples", "35")
        controller.setMpvProperty("vd-queue-max-secs", "60")
        controller.setMpvProperty("hr-seek-framedrop", "no")
        controller.setMpvProperty("video-latency-hacks", "yes")
        controller.setMpvProperty("mc", "0")
        controller.setMpvProperty("autosync", "30")
    } else {
        controller.setMpvProperty("vd-queue-enable", "no")
        controller.setMpvProperty("hr-seek-framedrop", "yes")
        controller.setMpvProperty("video-latency-hacks", "no")
        // mpv's default --mc is 0.1; it does not accept "auto" ("The mc option must be a
        // floating point number"), which left mc stuck at the SVP profile's 0 after a session.
        controller.setMpvProperty("mc", "0.1")
        controller.setMpvProperty("autosync", "0")
    }
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
