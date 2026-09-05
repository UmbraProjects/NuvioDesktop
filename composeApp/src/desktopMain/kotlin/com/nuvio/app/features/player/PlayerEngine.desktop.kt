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
import com.nuvio.app.features.player.desktop.DesktopWindowGeometryDiagnostics
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
    initialProgressFraction: Float?,
    initialPlaybackSpeed: Float,
    playbackAttemptId: Long,
    useNativeController: Boolean,
    playerControlsState: PlayerControlsState,
    onPlayerControlsAction: (PlayerControlsAction) -> Boolean,
    onPlayerControlsEvent: (String, Double) -> Boolean,
    onPlayerControlsScrubChange: (Long) -> Boolean,
    onPlayerControlsScrubFinished: (Long) -> Boolean,
    onControllerReady: (PlayerEngineController) -> Unit,
    onPlayerAttached: () -> Unit,
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
            initialProgressFraction = initialProgressFraction,
            initialPlaybackSpeed = initialPlaybackSpeed,
            playbackAttemptId = playbackAttemptId,
            playerControlsState = playerControlsState,
            onPlayerControlsAction = onPlayerControlsAction,
            onPlayerControlsEvent = onPlayerControlsEvent,
            onPlayerControlsScrubChange = onPlayerControlsScrubChange,
            onPlayerControlsScrubFinished = onPlayerControlsScrubFinished,
            onControllerReady = onControllerReady,
            onPlayerAttached = onPlayerAttached,
            onSnapshot = onSnapshot,
            onError = onError,
        )
        return
    }

    DesktopStubPlayerSurface(
        modifier = modifier,
        onControllerReady = onControllerReady,
        onPlayerAttached = onPlayerAttached,
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
    initialProgressFraction: Float?,
    initialPlaybackSpeed: Float,
    playbackAttemptId: Long,
    playerControlsState: PlayerControlsState,
    onPlayerControlsAction: (PlayerControlsAction) -> Boolean,
    onPlayerControlsEvent: (String, Double) -> Boolean,
    onPlayerControlsScrubChange: (Long) -> Boolean,
    onPlayerControlsScrubFinished: (Long) -> Boolean,
    onControllerReady: (PlayerEngineController) -> Unit,
    onPlayerAttached: () -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val host = remember { NativePlayerHost() }
    val controller = remember(host) { NativePlayerController(host) }
    val hostFirstPaintComplete = remember { mutableStateOf(false) }
    val hostFirstFullSizePaintComplete = remember { mutableStateOf(false) }
    val nativeSvpActive = remember { mutableStateOf(false) }
    val videoIsHdr = remember { mutableStateOf<Boolean?>(null) }
    val videoVsrScale = remember { mutableStateOf<Double?>(null) }
    // Decoded source dimensions, reported by the native bridge at FILE_LOADED and again whenever an
    // adaptive stream switches resolution. Null until the first report.
    val videoSourceSize = remember { mutableStateOf<DesktopVideoSourceSize?>(null) }
    // Whether Windows itself has HDR on for this display — reported by the native bridge per
    // file load. Null until the first report.
    val displayHdrEnabled = remember { mutableStateOf<Boolean?>(null) }
    val videoProfileRefreshToken = remember { mutableIntStateOf(0) }
    LaunchedEffect(playbackAttemptId, sourceUrl) {
        DesktopPlayerLaunchShield.showForActiveWindow()
    }
    val playbackHeaders = remember(sourceHeaders) { sanitizePlaybackHeaders(sourceHeaders) }
    val playerSettings by PlayerSettingsRepository.uiState.collectAsState()
    val initialAnimeSvpRequested = playerSettings.desktopAnimeSvpEnabled &&
        playerSettings.desktopMpvConfigMode != DesktopMpvConfigMode.Full &&
        isAnimeContent &&
        initialPlaybackSpeed < 1.5f
    // Keep one state holder for the controller callback's lifetime. Replacing it on a source
    // change would leave the callback writing to the previous file's state object.
    val videoPipelineReady = remember { mutableStateOf(!initialAnimeSvpRequested) }
    val svpStartupProfileAcknowledgementPending = remember { mutableStateOf(false) }
    LaunchedEffect(playbackAttemptId, sourceUrl) {
        videoPipelineReady.value = !initialAnimeSvpRequested
        svpStartupProfileAcknowledgementPending.value = false
        // Per-source, not per-player: this token is what `fileLoaded` below is derived from, and
        // leaving the previous file's count in place made that read true from the moment a new
        // source was attached. The profile pass would then queue vapoursynth into `vf` before mpv
        // had resolved the incoming stream at all — the window that kills the native process.
        videoProfileRefreshToken.intValue = 0
    }
    // The native side pins the D3D11 device to the NVIDIA GPU (and captures the diagnostic mpv
    // log) when VSR is on — the d3d11vpp video processor needs the NVIDIA adapter, which matters
    // on hybrid-GPU machines.
    val nvidiaRtxSuperResolutionEnabled = playerSettings.nvidiaRtxSuperResolutionEnabled
    val nvidiaRtxHdrEnabled = playerSettings.nvidiaRtxHdrEnabled
    val latestOnPlayerControlsAction = rememberUpdatedState(onPlayerControlsAction)
    val latestOnPlayerControlsEvent = rememberUpdatedState(onPlayerControlsEvent)
    val latestOnPlayerControlsScrubChange = rememberUpdatedState(onPlayerControlsScrubChange)
    val latestOnPlayerControlsScrubFinished = rememberUpdatedState(onPlayerControlsScrubFinished)
    val latestOnSnapshot = rememberUpdatedState(onSnapshot)
    val latestOnError = rememberUpdatedState(onError)

    LaunchedEffect(controller, playbackAttemptId, sourceUrl) {
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
                if (type == "mediaPlay") {
                    // Raised by the Windows media session (player_bridge.cpp), so these arrive
                    // whether or not Nuvio has focus. SMTC gives us discrete Play/Pause rather
                    // than one toggle, so honour the direction Windows asked for.
                    controller.play()
                    true
                } else if (type == "mediaPause") {
                    controller.pause()
                    true
                } else if (type == "mediaStop") {
                    controller.pause()
                    true
                } else if (type == "mediaNext") {
                    latestOnPlayerControlsEvent.value("nextEpisode", 0.0)
                    true
                } else if (type == "mediaPrevious") {
                    latestOnPlayerControlsEvent.value("previousEpisode", 0.0)
                    true
                } else if (type == "profileReady") {
                    PlaybackStartTrace.markStartupDetail(if (value != 0.0) "profileRendered" else "profileFallback")
                    true
                } else if (type == "svpState") {
                    nativeSvpActive.value = value != 0.0
                    true
                } else if (type == "videoParams") {
                    videoIsHdr.value = value != 0.0
                    true
                } else if (type == "videoVsrScale") {
                    videoVsrScale.value = value
                    true
                } else if (type == "videoSourceSize") {
                    videoSourceSize.value = DesktopVideoSourceSize.unpack(value)
                    true
                } else if (type == "displayHdr") {
                    displayHdrEnabled.value = value != 0.0
                    true
                } else if (type == "fileLoaded") {
                    PlaybackStartTrace.mark("fileLoaded")
                    videoProfileRefreshToken.intValue += 1
                    true
                } else if (type == "svpPrerollReady") {
                    // Native has a filtered frame at the requested start position, but remains
                    // paused. Allow exactly one full profile pass, then explicitly hand control
                    // back so shaders cannot compile after audio has already started.
                    svpStartupProfileAcknowledgementPending.value = true
                    videoPipelineReady.value = true
                    true
                } else if (type == "playbackRestart") {
                    // First decoded/rendered frame of the current file (once per load).
                    // For an initial 1x anime/SVP load this is also the hand-off point from the
                    // native pre-roll transaction to the normal runtime profile owner.
                    videoPipelineReady.value = true
                    PlaybackStartTrace.complete("playbackRestart")
                    // Take the launch shield down as soon as there is a real frame behind it.
                    // showForActiveWindow() re-arms a 3s fallback on every source change, but its
                    // only early dismissal hangs off host.onFirstFullSizePaint, which fires once
                    // per canvas peer — and an in-place source switch (next episode, source change)
                    // reuses the same NativePlayerHost, so the canvas is never removed and that
                    // callback never fires again. The shield then sat black over a playing file for
                    // the full 3 seconds: audio running, frames rendering underneath, nothing
                    // visible. This event is the frame-accurate signal the fallback was standing in
                    // for. Harmless on a first load, where both paths simply restart the same timer.
                    DesktopPlayerLaunchShield.hideAfter()
                    latestOnPlayerControlsEvent.value(type, value)
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
            if (event.isMetaDown || event.isControlDown || event.isAltDown || event.isShiftDown) {
                return@KeyEventDispatcher false
            }
            val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            if (focusOwner is JTextComponent) return@KeyEventDispatcher false
            // Media keys are deliberately NOT handled here. A KeyEventDispatcher only sees keys
            // routed to a focused Nuvio window, which defeats the point of a media key; they are
            // owned by the Windows media session in player_bridge.cpp and arrive as the
            // "media*" events handled in setControlCallbacks above.
            val panelKey = when (event.keyCode) {
                KeyEvent.VK_UP -> "ArrowUp"
                KeyEvent.VK_DOWN -> "ArrowDown"
                KeyEvent.VK_LEFT -> "ArrowLeft"
                KeyEvent.VK_RIGHT -> "ArrowRight"
                AppShortcutsRepository.keyCode(AppShortcutAction.SelectFocused) -> "Enter"
                AppShortcutsRepository.keyCode(AppShortcutAction.DismissOverlay) -> "Escape"
                else -> null
            }
            if (panelKey != null && controller.dispatchKeyboardPanelKey(panelKey)) {
                event.consume()
                return@KeyEventDispatcher true
            }
            // Directional controls stay fixed so keyboard/panel navigation remains recoverable.
            val fixedType = when (event.keyCode) {
                KeyEvent.VK_LEFT -> "keyboardSeekBack"
                KeyEvent.VK_RIGHT -> "keyboardSeekForward"
                KeyEvent.VK_UP -> "volumeUp"
                KeyEvent.VK_DOWN -> "volumeDown"
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
                PlayerShortcutAction.AlternatePlayPause -> controller.dispatchKeyboardShortcut("keyboardToggle", 1.0)
                PlayerShortcutAction.ToggleMute -> controller.dispatchKeyboardShortcut("keyboardToggleMute", 1.0)
                PlayerShortcutAction.SeekBackward -> controller.dispatchKeyboardShortcut("keyboardSeekBack", 1.0)
                PlayerShortcutAction.SeekForward -> controller.dispatchKeyboardShortcut("keyboardSeekForward", 1.0)
                PlayerShortcutAction.SpeedUp -> controller.dispatchKeyboardShortcut("keyboardSpeedStep", 1.0)
                PlayerShortcutAction.SpeedDown -> controller.dispatchKeyboardShortcut("keyboardSpeedStep", -1.0)
                PlayerShortcutAction.ToggleSpeed -> controller.dispatchKeyboardShortcut("keyboardSpeedToggle", 1.0)
                PlayerShortcutAction.NextSubtitle -> controller.dispatchKeyboardShortcut("keyboardNextSubtitle", 1.0)
                PlayerShortcutAction.NextAudio -> controller.dispatchKeyboardShortcut("keyboardNextAudio", 1.0)
                PlayerShortcutAction.OpenSources -> controller.openKeyboardPanel("sources")
                PlayerShortcutAction.OpenEpisodes -> controller.openKeyboardPanel("episodes")
                PlayerShortcutAction.CycleZoom -> controller.dispatchKeyboardShortcut("resize", 1.0)
                PlayerShortcutAction.SkipInterval -> {
                    if (!controller.triggerSkipIntervalIfAvailable()) return@KeyEventDispatcher false
                }
                PlayerShortcutAction.CycleSvp -> controller.cycleDesktopAnimeSvpMode()
                PlayerShortcutAction.CycleHdr -> controller.cycleDesktopHdrMode()
                PlayerShortcutAction.CycleColorProfile -> controller.cycleDesktopColorProfile()
                PlayerShortcutAction.CycleAnime -> controller.cycleDesktopAnimeMode()
                PlayerShortcutAction.ToggleMpvDiagnostics -> controller.toggleMpvDiagnosticsOverlay()
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
        onDispose {
            controller.dispose()
            // The playback session is over: drop any F10/F7 anime force so the next playback
            // falls back to the persisted "Auto-apply to Anime" behaviour.
            PlayerSettingsRepository.clearDesktopAnimeSessionState()
        }
    }

    LaunchedEffect(controller, isAnimeContent) {
        controller.isAnimeContentDetected = isAnimeContent
    }

    LaunchedEffect(
        controller,
        playbackAttemptId,
        sourceUrl,
        playbackHeaders,
        nvidiaRtxSuperResolutionEnabled,
        nvidiaRtxHdrEnabled,
        playerSettings.desktopMpvConfigMode,
        initialPositionMs,
        initialProgressFraction,
        hostFirstFullSizePaintComplete.value,
    ) {
        if (!hostFirstFullSizePaintComplete.value) {
            return@LaunchedEffect
        }
        delay(16L)
        PlaybackStartTrace.mark("playerAttach")
        // Logged so a binge/next-episode stall can be diagnosed: if the common layer reports it
        // set a new activeSourceUrl (see BingeAdvance "switchToEpisodeStream" log) but this line
        // never follows while the window is minimized, that confirms the attach is waiting on a
        // paused recomposition rather than something in stream selection.
        BingeAdvanceLog.i {
            "desktop attach firing attemptId=$playbackAttemptId sourceUrl=${sourceUrl.takeLast(48)}"
        }
        controller.attach(
            tracePlaybackStart = true,
            sourceUrl = sourceUrl,
            sourceAudioUrl = sourceAudioUrl,
            sourceHeaders = playbackHeaders,
            mediaTitle = preferredMpvMediaTitle(
                streamTitle = playerControlsState.streamTitle,
                title = playerControlsState.title,
                episodeText = playerControlsState.episodeText,
            ),
            playWhenReady = playWhenReady,
            initialPositionMs = initialPositionMs,
            initialProgressFraction = initialProgressFraction ?: 0f,
            // Apply the configured speed before mpv initializes. In particular, this prevents
            // the SVP/VapourSynth graph from being constructed at 1x only to be torn down when
            // the common player layer applies a >= 1.5x default speed after the first snapshot.
            initialPlaybackSpeed = initialPlaybackSpeed,
            // Native code defers this until MPV_EVENT_FILE_LOADED, then installs it before the
            // first PLAYBACK_RESTART. That avoids probing/HLS startup crashes while also avoiding
            // a visible/audio-disrupting filter rebuild after playback has already begun.
            animeSvpEnabled = initialAnimeSvpRequested,
            isAnimeContent = isAnimeContent,
            nvidiaRtxSuperResolutionEnabled = nvidiaRtxSuperResolutionEnabled &&
                playerSettings.desktopMpvConfigMode != DesktopMpvConfigMode.Full,
            nvidiaRtxHdrEnabled = nvidiaRtxHdrEnabled &&
                playerSettings.desktopMpvConfigMode != DesktopMpvConfigMode.Full,
            enableUserMpvOptions = true,
            restoreVolume = true,
            onError = { message -> latestOnError.value(message) },
        )
        onPlayerAttached()
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

    LaunchedEffect(playbackAttemptId, sourceUrl) {
        nativeSvpActive.value = false
        videoIsHdr.value = null
        videoVsrScale.value = null
        videoSourceSize.value = null
        displayHdrEnabled.value = null
        // Note: the anime session override deliberately survives source changes — a forced preset
        // should carry across binged episodes and only reset when the player closes.
    }

    LaunchedEffect(controller, playbackAttemptId, sourceUrl, isAnimeContent) {
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
                DesktopVideoProfileState(
                    isHdr = videoIsHdr.value,
                    displayHdr = displayHdrEnabled.value,
                    vsrScale = videoVsrScale.value,
                    sourceSize = videoSourceSize.value,
                    refreshToken = videoProfileRefreshToken.intValue,
                    pipelineReady = videoPipelineReady.value,
                    svpActive = nativeSvpActive.value,
                    startupProfileRequested = svpStartupProfileAcknowledgementPending.value,
                )
            },
            PlayerSettingsRepository.uiState,
        ) { videoState, settings -> videoState to settings }
            .collect { (videoState, settings) ->
                // Native code owns hwdec/vf from FILE_LOADED until the initial VapourSynth graph
                // has produced a real runtime-ready signal. Applying the ordinary profile in
                // this interval can otherwise replace d3d11va-copy with d3d11va and clear vf,
                // forcing two more decoder/filter rebuilds during startup.
                if (!videoState.pipelineReady) return@collect
                if (settings.desktopMpvConfigMode == DesktopMpvConfigMode.Full) {
                    // The user's own mpv.conf owns the picture here, so there is nothing truthful to
                    // say about HDR/SVP/shaders — but the panel still has a subtitle row to show,
                    // and it only ever appears once a session has been announced.
                    controller.setPlaybackInfo(
                        session = "$playbackAttemptId:${sourceUrl.hashCode()}",
                        hdrLabel = null,
                        svpActive = false,
                        videoLabel = "",
                        shaderLabel = null,
                    )
                    if (svpStartupProfileAcknowledgementPending.value) {
                        svpStartupProfileAcknowledgementPending.value = false
                        controller.completeSvpStartupProfile()
                    }
                    return@collect
                }

                val isHdr = videoState.isHdr
                val vsrScale = videoState.vsrScale
                val fileLoaded = videoState.refreshToken > 0
                if (!fileLoaded) return@collect
                System.out.println(
                    "Desktop video profile: detectedHdr=${isHdr ?: "unknown"}, " +
                        "hdrMode=${settings.desktopHdrMode.name}, colorProfile=${settings.desktopColorProfile.name}, " +
                        "bufferPreset=${settings.desktopBufferPreset.name}, " +
                        "animeMode=${settings.desktopAnimeMode.name}, " +
                        "animeAuto=${settings.desktopAnimeModeAutoEnabled}, " +
                        "animeSessionOverride=${settings.desktopAnimeSessionOverride?.mode?.name ?: "none"}, " +
                        "isAnime=$isAnimeContent, " +
                        "sourceSize=${videoState.sourceSize?.let { "${it.width}x${it.height}" } ?: "unknown"}, " +
                        "animeSkipUhd=${settings.desktopAnimeSkipUltraHdEnabled}",
                )
                val animeProfile = controller.withVideoProfile {
                    applyDesktopVideoProfile(
                        controller = controller,
                        hdrMode = settings.desktopHdrMode,
                        colorProfile = settings.desktopColorProfile,
                        isHdr = isHdr,
                    )
                    controller.applyDesktopBufferPreset(settings.desktopBufferPreset)
                    applyDesktopAnimeProfile(
                        controller = controller,
                        mode = settings.desktopAnimeMode,
                        autoEnabled = settings.desktopAnimeModeAutoEnabled,
                        sessionOverride = settings.desktopAnimeSessionOverride,
                        // Do not queue vapoursynth before mpv has resolved a real video stream.
                        // Some HLS sources expose odd probe tracks during startup, and applying SVP
                        // in that window can kill the native process before fileLoaded is emitted.
                        animeSvpEnabled = settings.desktopAnimeSvpEnabled && fileLoaded,
                        animeSvpSessionForced = settings.desktopAnimeSvpSessionForced,
                        isAnime = isAnimeContent,
                        isHdr = isHdr,
                        sourceSize = videoState.sourceSize,
                        skipShadersOnUltraHdSources = settings.desktopAnimeSkipUltraHdEnabled,
                        nvidiaRtxSuperResolutionEnabled = settings.nvidiaRtxSuperResolutionEnabled,
                        nvidiaRtxSuperResolutionScale = vsrScale,
                        nvidiaRtxHdrEnabled = settings.nvidiaRtxHdrEnabled,
                        customShaderPaths = settings.desktopCustomShaderPaths,
                        customShaderSelectedPath = settings.desktopCustomShaderSelectedPath,
                    )
                }
                // The outcome, not just the inputs above: whether a chain actually took is the
                // only thing a bug report about shaders can be checked against, and deriving it
                // from the inputs by hand is exactly the step that gets it wrong.
                System.out.println(
                    "Desktop anime profile applied: shader=${animeProfile.shaderLabel ?: "none"}, " +
                        "svp=${videoState.svpActive}",
                )
                // Same pass, same inputs: the info panel reports what was just applied rather than
                // re-deriving it from the settings, which would disagree with the picture whenever a
                // preset didn't actually take (SDR file + HDR mode, live action + anime preset).
                controller.setPlaybackInfo(
                    session = "$playbackAttemptId:${sourceUrl.hashCode()}",
                    hdrLabel = desktopHdrInfoLabel(
                        isHdr = isHdr,
                        hdrMode = settings.desktopHdrMode,
                        displayHdr = videoState.displayHdr,
                    ),
                    svpActive = videoState.svpActive,
                    // Both, not one or the other: a shader chain runs on top of the colour preset,
                    // it does not replace it.
                    videoLabel = desktopColorProfileInfoLabel(
                        colorProfile = settings.desktopColorProfile,
                        isHdr = isHdr,
                        hdrMode = settings.desktopHdrMode,
                    ),
                    shaderLabel = animeProfile.shaderLabel,
                )
                if (svpStartupProfileAcknowledgementPending.value) {
                    svpStartupProfileAcknowledgementPending.value = false
                    controller.completeSvpStartupProfile()
                }
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

    // Companion to the fullscreen sampling in Main.kt: that one runs before playback starts, so the
    // native video surface isn't in the component tree yet. Sampling again once it has painted at
    // full size shows whether the surface actually covers the window it was given — the edge strips
    // in the "white lines / black bars in fullscreen" report are whatever the surface leaves bare.
    LaunchedEffect(host, hostFirstFullSizePaintComplete.value) {
        if (!hostFirstFullSizePaintComplete.value) return@LaunchedEffect
        delay(750)
        javax.swing.SwingUtilities.getWindowAncestor(host)?.let { window ->
            DesktopWindowGeometryDiagnostics.log(window, "player surface painted")
        }
    }

    LaunchedEffect(controller, playbackAttemptId, sourceUrl) {
        while (true) {
            latestOnSnapshot.value(controller.snapshot())
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

/**
 * Display name for an active custom shader: its entry in the user's shader library, falling back to
 * the file's own name. The fallback matters — without it a shader the library lookup misses would
 * leave the info panel naming the colour preset while a shader was demonstrably running.
 */
private fun String.customShaderDisplayName(libraryPaths: String): String? {
    if (isBlank()) return null
    DesktopCustomShaders.availableShaders(libraryPaths)
        .firstOrNull { it.path == this }
        ?.let { return it.label }
    return substringAfterLast('\\').substringAfterLast('/').substringBeforeLast('.')
        .takeIf { it.isNotBlank() }
}

/** What [applyDesktopAnimeProfile] actually ended up doing, for the playback-info panel. */
private data class DesktopAnimeProfileResult(
    /** Active shader chain's display name, or null when the picture is running ungraded by one. */
    val shaderLabel: String?,
    val svpActive: Boolean,
)

/**
 * The HDR row's value, or null when the row should be left out entirely. Present only for genuinely
 * HDR video: on an SDR file every HDR mode is a no-op, and saying "HDR: Off" there is noise.
 *
 * The values name themselves ("HDR Passthrough") because the panel prints no captions.
 *
 * [displayHdr] decides the Auto case. Auto hands the choice to mpv's `target-colorspace-hint=auto`,
 * which passes HDR through to an HDR display and tone maps it down to an SDR one — so without
 * knowing the display's state the row cannot say which of the two actually happened. Unknown is
 * reported as passthrough, matching what Auto does whenever the display can take it.
 */
private fun desktopHdrInfoLabel(
    isHdr: Boolean?,
    hdrMode: DesktopHdrMode,
    displayHdr: Boolean?,
): String? = when {
    isHdr != true -> null
    hdrMode == DesktopHdrMode.AlwaysTonemap -> "HDR Tone Mapped to SDR"
    hdrMode == DesktopHdrMode.Auto && displayHdr == false -> "HDR Tone Mapped to SDR"
    else -> "HDR Passthrough"
}

/**
 * The colour preset as it is actually being applied — which is neutral on HDR video that isn't being
 * tonemapped, because the presets are SDR-tuned equalizer offsets and are deliberately suppressed
 * there (see the forceNeutral note in [applyDesktopVideoProfile]).
 */
private fun desktopColorProfileInfoLabel(
    colorProfile: DesktopColorProfile,
    isHdr: Boolean?,
    hdrMode: DesktopHdrMode,
): String = if (isHdr != false && hdrMode != DesktopHdrMode.AlwaysTonemap) {
    DesktopColorProfile.Neutral.label
} else {
    colorProfile.label
}

private data class DesktopVideoProfileState(
    val isHdr: Boolean?,
    val displayHdr: Boolean?,
    val vsrScale: Double?,
    val sourceSize: DesktopVideoSourceSize?,
    val refreshToken: Int,
    val pipelineReady: Boolean,
    val svpActive: Boolean,
    val startupProfileRequested: Boolean,
)

private fun applyDesktopVideoProfile(
    controller: NativePlayerController,
    hdrMode: DesktopHdrMode,
    colorProfile: DesktopColorProfile,
    isHdr: Boolean?,
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
    // Unknown input is kept neutral. Applying an SDR grade optimistically made native HDR show
    // one graded frame before its PQ/BT.2020 properties arrived.
    val forceNeutral = isHdr != false && hdrMode != DesktopHdrMode.AlwaysTonemap
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
 * scaling/deband and an hqdn3d denoise pass. The active mode is resolved as:
 * [sessionOverride] (an explicit in-player F10/menu force, session-scoped) if present; otherwise the
 * persisted [mode] — but only when [autoEnabled] and the title is detected as [isAnime]. A persisted
 * preset never applies to undetected content on its own, so live-action can't silently inherit an
 * Anime4K chain from an old force (see the Dutton Ranch 4K-HDR crash report).
 *
 * SVP / motion interpolation from Kai is intentionally not ported (it needs a paid external runtime).
 */
/**
 * Decoded source dimensions, as packed by the native bridge's `videoSourceSize` event
 * (`width * 65536 + height`). Both values arrive together so no caller can see half a size.
 */
internal data class DesktopVideoSourceSize(val width: Int, val height: Int) {
    val longestSide: Int get() = maxOf(width, height)
    val shortestSide: Int get() = minOf(width, height)

    /**
     * Whether this is 4K-class video. Both sides are tested because the boundary has to survive
     * anamorphic and cropped framings: a 3840x1600 scope master is a 4K source with a short side
     * well under 2160, and a portrait 2160x3840 one is 4K with a long side that is not 3840 wide.
     */
    val isUltraHd: Boolean get() = longestSide >= 3840 || shortestSide >= 2160

    companion object {
        fun unpack(packed: Double): DesktopVideoSourceSize? {
            val value = packed.toLong()
            if (value <= 0L) return null
            val width = (value / 65536L).toInt()
            val height = (value % 65536L).toInt()
            return if (width > 0 && height > 0) DesktopVideoSourceSize(width, height) else null
        }
    }
}

/** Why an anime shader chain is being held back on a session it would otherwise run on. */
internal enum class DesktopAnimeShaderSkipReason {
    /** The source dimensions have not been reported yet — the decision cannot be made safely. */
    SourceUnknown,

    /** The source is already 4K-class, so the chain is cost without an upscale to pay for it. */
    UltraHdSource,
}

/**
 * Whether to hold the anime shader chain back for this source.
 *
 * Reported by a user whose machine locked up hard enough to need a task-kill when the chain was
 * applied automatically to an already-4K stream. These chains are upscalers; on a 4K source they
 * buy very little and cost the most, and the failure mode on a modest GPU is the whole app becoming
 * unusable rather than a few dropped frames.
 *
 * The axis here is **automatic vs. explicitly forced**, not built-in vs. custom. An earlier cut
 * exempted custom shaders on the grounds that the user picked them by hand — but that describes
 * choosing the shader once in settings, not the per-file decision to apply it, and the guard then
 * did nothing at all for the most common real configuration (a custom ArtCNN/Anime4K chain on
 * auto-apply). A shader that ought to run on everything would not be sitting behind an
 * anime-detection gate, so anything reaching this point automatically is treated as an upscaler.
 *
 * Two rules, both conditional on [skipUltraHdSources] so the guard can be turned off wholesale by
 * anyone whose GPU is happy running these shaders at 4K:
 *
 *  - **Unknown size waits.** The video-profile pass runs once on attach, before any file is loaded
 *    (`videoPipelineReady` starts true unless SVP is pre-rolling), so without this the chain would
 *    already be installed by the time the dimensions arrive and the guard would have nothing left
 *    to prevent. The wait costs nothing visible: the size lands in the FILE_LOADED burst.
 *  - **4K sources are skipped**, including one an adaptive stream switches up into mid-file.
 *
 * An explicit session force (F10 / the shader menu) always wins — [isSessionForced] is the escape
 * hatch for the one case the automatic rule gets wrong.
 */
internal fun desktopAnimeShaderSkipReason(
    isSessionForced: Boolean,
    skipUltraHdSources: Boolean,
    sourceSize: DesktopVideoSourceSize?,
): DesktopAnimeShaderSkipReason? = when {
    isSessionForced || !skipUltraHdSources -> null
    sourceSize == null -> DesktopAnimeShaderSkipReason.SourceUnknown
    sourceSize.isUltraHd -> DesktopAnimeShaderSkipReason.UltraHdSource
    else -> null
}

/** Which shader chain, if any, survives the source guard, and why one did not. */
internal data class DesktopAnimeShaderPlan(
    val preset: DesktopAnimeMode?,
    val customShaderChain: String,
    val skipReason: DesktopAnimeShaderSkipReason?,
)

/**
 * Resolves the requested shader chain against [desktopAnimeShaderSkipReason].
 *
 * Both kinds of chain are decided together and by the same rule — see that function for why the
 * built-in/custom distinction is not the one that matters. At most one of them is ever non-empty:
 * `DesktopAnimeMode.CustomShader` is exactly the mode that has no preset.
 */
internal fun planDesktopAnimeShaders(
    requestedPreset: DesktopAnimeMode?,
    requestedCustomShaderChain: String,
    isSessionForced: Boolean,
    skipUltraHdSources: Boolean,
    sourceSize: DesktopVideoSourceSize?,
): DesktopAnimeShaderPlan {
    val hasRequest = requestedPreset != null || requestedCustomShaderChain.isNotEmpty()
    val skipReason = if (hasRequest) {
        desktopAnimeShaderSkipReason(
            isSessionForced = isSessionForced,
            skipUltraHdSources = skipUltraHdSources,
            sourceSize = sourceSize,
        )
    } else {
        null
    }
    return DesktopAnimeShaderPlan(
        preset = requestedPreset.takeIf { skipReason == null },
        customShaderChain = if (skipReason == null) requestedCustomShaderChain else "",
        skipReason = skipReason,
    )
}

internal fun shouldEnableDesktopRtxSuperResolution(
    enabled: Boolean,
    isHdr: Boolean?,
    isEffectivelyAnime: Boolean,
    scale: Double?,
): Boolean = enabled &&
    isHdr == false &&
    !isEffectivelyAnime &&
    scale != null &&
    scale > 1.01

private fun applyDesktopAnimeProfile(
    controller: NativePlayerController,
    mode: DesktopAnimeMode,
    autoEnabled: Boolean,
    sessionOverride: DesktopAnimeSessionOverride?,
    animeSvpEnabled: Boolean,
    animeSvpSessionForced: Boolean,
    isAnime: Boolean,
    isHdr: Boolean?,
    sourceSize: DesktopVideoSourceSize? = null,
    skipShadersOnUltraHdSources: Boolean = true,
    nvidiaRtxSuperResolutionEnabled: Boolean = false,
    nvidiaRtxSuperResolutionScale: Double? = null,
    nvidiaRtxHdrEnabled: Boolean = false,
    customShaderPaths: String = "",
    customShaderSelectedPath: String = "",
): DesktopAnimeProfileResult {
    val activeMode = when {
        sessionOverride != null -> sessionOverride.mode
        autoEnabled && isAnime -> mode
        else -> DesktopAnimeMode.Off
    }
    val activeShaderPath = sessionOverride?.customShaderPath ?: customShaderSelectedPath
    val requestedCustomShaderChain = if (activeMode == DesktopAnimeMode.CustomShader) {
        DesktopCustomShaders.shaderChain(
            pathsText = customShaderPaths,
            selectedPath = activeShaderPath,
        )
    } else {
        ""
    }
    val requestedPreset = when (activeMode) {
        DesktopAnimeMode.Off, DesktopAnimeMode.CustomShader -> null
        else -> activeMode
    }
    val shaderPlan = planDesktopAnimeShaders(
        requestedPreset = requestedPreset,
        requestedCustomShaderChain = requestedCustomShaderChain,
        isSessionForced = sessionOverride != null,
        skipUltraHdSources = skipShadersOnUltraHdSources,
        sourceSize = sourceSize,
    )
    val effectivePreset = shaderPlan.preset
    val customShaderChain = shaderPlan.customShaderChain

    // If Anime4k is forced via F10 (requestedPreset != null) OR if it's auto-detected (isAnime),
    // we consider this video to be Anime for the purposes of SVP interpolation. An explicit F7
    // press this session also counts, so SVP can be forced onto undetected content.
    //
    // `requestedPreset`, not `effectivePreset`: skipping the shader chain for a 4K source says
    // nothing about whether this is anime. SVP and the RTX VSR/True-HDR suppression below must
    // behave exactly as they did before the guard existed.
    val isEffectivelyAnime = isAnime || requestedPreset != null ||
        requestedCustomShaderChain.isNotEmpty() || animeSvpSessionForced

    // This function is the single owner of the mpv `vf` chain. NVIDIA RTX VSR and RTX True HDR
    // are both d3d11vpp sub-options; they must be set here so a profile rebuild doesn't wipe them.
    // Anime4K (when active) takes the vf entirely — RTX features are suppressed while Anime4K runs.
    //
    // RTX VSR and RTX True HDR are live-action enhancements; neither must stack on top of the
    // anime enhancement layer (Anime4K / custom GLSL / SVP interpolation). The heavier anime
    // branches below drop baselineVf entirely, but the SVP-only path reuses it, so gate both
    // off for any effectively anime session here too. (True HDR's AI SDR->HDR pass tends to
    // over-saturate/band flat cel-shaded anime and fights the SDR-tuned colour presets.)
    // Native HDR, especially Dolby Vision decoded as P010, can turn into a solid green video
    // plane when passed through NVIDIA's d3d11vpp VSR scaler. Wait for positive SDR detection;
    // unknown/HDR content stays on gpu-next's normal colour-managed path.
    val vsrActive = shouldEnableDesktopRtxSuperResolution(
        enabled = nvidiaRtxSuperResolutionEnabled,
        isHdr = isHdr,
        isEffectivelyAnime = isEffectivelyAnime,
        scale = nvidiaRtxSuperResolutionScale,
    )
    // RTX True HDR requires mpv master ≥ Feb 19 2026: mpv sets IMGFMT_X2BGR10 output
    // automatically when nvidia-true-hdr is present, and uses ID3D11VideoContext1 for
    // proper DXGI HDR colour-space signalling. Init-time d3d11-output-csp=auto and
    // target-colorspace-hint=auto are set in player_bridge.cpp when HDR is enabled.
    // True HDR is an SDR -> HDR enhancement. Wait until the stream is positively identified
    // as SDR before enabling it: treating the initial "unknown" state as SDR briefly applied
    // the filter to every file, and leaving this independent of isHdr applied it to native HDR.
    val trueHdrActive = nvidiaRtxHdrEnabled && isHdr == false && !isEffectivelyAnime
    val baselineVf = buildString {
        if (vsrActive || trueHdrActive) {
            append("d3d11vpp=")
            if (vsrActive) {
                append("scale=${nvidiaRtxSuperResolutionScale!!.coerceIn(1.0, 4.0)}:scaling-mode=nvidia")
                if (trueHdrActive) append(":")
            }
            if (trueHdrActive) append("nvidia-true-hdr=yes")
        }
    }

    val svpFilter = if (isEffectivelyAnime && animeSvpEnabled) {
        DesktopAnimeSvp.vapoursynthArgument(
            debugOverlay = PlayerSettingsRepository.uiState.value.desktopAnimeSvpDebugOverlayEnabled,
        )
    } else null
    val svpActive = svpFilter != null

    if (customShaderChain.isNotEmpty()) {
        controller.setMpvProperty("scale", "ewa_lanczos")
        controller.setMpvProperty("cscale", "ewa_lanczos")
        controller.setMpvProperty("scale-blur", "1.05")
        controller.setMpvProperty("deband-threshold", "45")
        controller.setMpvProperty("deband-grain", "20")
        controller.setMpvProperty("glsl-shaders", customShaderChain)


        controller.setMpvProperty("vf", listOfNotNull(svpFilter).joinToString(","))
        controller.forceVideoRedraw()
        return DesktopAnimeProfileResult(
            shaderLabel = customShaderChain.customShaderDisplayName(customShaderPaths),
            svpActive = svpActive,
        )
    }

    if (effectivePreset == null) {
        // Restore the bridge's baseline live-action rendering (see startMpv in player_bridge.cpp).
        controller.setMpvProperty("glsl-shaders", "")
        
        // Restore the standard D3D11VA hardware decoder so d3d11vpp (RTX features) works.
        // However, if SVP is active, we MUST use a copy-back decoder for the CPU filter.
        
        val finalVf = listOfNotNull(baselineVf.takeIf { it.isNotEmpty() }, svpFilter).joinToString(",")
        controller.setMpvProperty("vf", finalVf)
        // Honour the user's "Upscaling" choice from the Advanced (mpv) menu for live-action; fall
        // back to the baseline scaler. Re-applied here because this profile refresh would otherwise
        // clobber the override on every file load / setting change.
        val scaleOverride = PlayerSettingsRepository.uiState.value.desktopMpvPropertyOverrides["scale"]
        controller.setMpvProperty("scale", scaleOverride ?: "spline36")
        controller.setMpvProperty("cscale", "lanczos")
        controller.setMpvProperty("scale-blur", "0.0")
        controller.setMpvProperty("deband-threshold", "35")
        controller.setMpvProperty("deband-grain", "0")
        controller.forceVideoRedraw()
        // Say so rather than showing nothing: an anime title playing with no shader row looks like
        // the setting failed, and this is the one case where that is a deliberate decision. Named
        // by what was actually held back, since the two are configured in different places.
        val skippedLabel = when {
            shaderPlan.skipReason != DesktopAnimeShaderSkipReason.UltraHdSource -> null
            requestedPreset != null -> "Anime4K off — 4K source"
            else -> "Shader off — 4K source"
        }
        return DesktopAnimeProfileResult(shaderLabel = skippedLabel, svpActive = svpActive)
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
    val hqdn3d = if (isHdr == true) "" else "@HQDN3D:lavfi=[hqdn3d=luma_spatial=5:chroma_spatial=5:luma_tmp=6:chroma_tmp=6]"
    val finalVf = listOfNotNull(hqdn3d.takeIf { it.isNotEmpty() }, svpFilter).joinToString(",")

    // When injecting CPU/software-based lavfi filters (hqdn3d, vapoursynth), we MUST dynamically switch
    // the hardware decoder to a copy-back mode, otherwise FFmpeg fails to map the d3d11 surface to RAM.
    controller.setMpvProperty("vf", finalVf)
    controller.forceVideoRedraw()
    // Named as the shader family plus its preset. The chain behind a preset is six to ten
    // Anime4K_* files, which is not something to print, and the bare preset label ("Mode A (HQ)")
    // never says what is doing the work.
    return DesktopAnimeProfileResult(
        shaderLabel = "Anime4K ${effectivePreset.label}",
        svpActive = svpActive,
    )
}

@Composable
private fun DesktopStubPlayerSurface(
    modifier: Modifier,
    onControllerReady: (PlayerEngineController) -> Unit,
    onPlayerAttached: () -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
) {
    val controller = remember { DesktopStubPlayerController() }

    LaunchedEffect(controller) {
        onControllerReady(controller)
        onPlayerAttached()
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
    override fun selectAudioTrack(index: Int): Boolean = false
    override fun selectSubtitleTrack(index: Int): Boolean = false
    override fun setSubtitleUri(url: String) = Unit
    override fun clearExternalSubtitle() = Unit
    override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit
}
