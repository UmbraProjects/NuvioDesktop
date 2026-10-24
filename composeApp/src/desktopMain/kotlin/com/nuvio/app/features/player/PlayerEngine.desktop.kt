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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.nuvio.app.features.input.GamepadContext
import com.nuvio.app.features.screensaver.DesktopScreensaver
import com.nuvio.app.features.player.desktop.NativePlayerHost
import com.nuvio.app.features.player.desktop.PlaybackRedirectResolution
import com.nuvio.app.features.player.desktop.PlaybackRedirectResolver
import com.nuvio.app.features.player.desktop.SeekRateLimitRecovery
import com.nuvio.app.features.player.desktop.SeekThumbnailRateLimitGate
import com.nuvio.app.features.player.desktop.TorBoxNodeHop
import com.nuvio.app.features.player.desktop.DESKTOP_PLAYBACK_FALLBACK_USER_AGENT
import com.nuvio.app.features.player.desktop.desktopAppFullscreenState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import java.awt.KeyEventDispatcher
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_error_debrid_rate_limited
import nuvio.composeapp.generated.resources.player_rate_limit_reconnecting
import nuvio.composeapp.generated.resources.player_rate_limit_switching_server
import org.jetbrains.compose.resources.getString
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
    // The attempt whose source was last handed to the controller, and the attempt whose file has
    // rendered its first frame. The snapshot loop below publishes nothing real until the second
    // equals the current attempt — see the comment there for what went wrong without it.
    val attachedAttemptId = remember { mutableStateOf<Long?>(null) }
    val startedAttemptId = remember { mutableStateOf<Long?>(null) }
    // Redirect resolutions for this surface's lifetime, keyed by the stream's source URL. A
    // re-attach of the same stream (RTX/settings toggles re-key the attach effect) must hand the
    // controller the *same* pinned URL: its carried-position logic compares URLs, and a fresh
    // resolve would mint a different CDN token and restart the file from initialPositionMs.
    val redirectResolutions = remember { mutableMapOf<String, PlaybackRedirectResolution>() }
    // Last real playhead this surface reported, for resuming after a pinned link is re-resolved.
    val lastKnownPositionMs = remember { mutableStateOf(0L) }
    // Its duration, so a rate-limit reopen can refuse a resume target at the very end.
    val lastKnownDurationMs = remember { mutableStateOf(0L) }
    val surfaceScope = rememberCoroutineScope()
    LaunchedEffect(playbackAttemptId, sourceUrl) {
        DesktopPlayerLaunchShield.showForActiveWindow()
    }
    val playbackHeaders = remember(sourceHeaders) { sanitizePlaybackHeaders(sourceHeaders) }
    // What mpv will send, so a TorBox node probe looks like the player it is standing in for.
    val playbackUserAgent = remember(playbackHeaders) {
        playbackHeaders.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
            ?: DESKTOP_PLAYBACK_FALLBACK_USER_AGENT
    }
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
                    // Attribute the frame to the attempt that issued the attach, not to whatever
                    // attempt is current: the controller drops events from superseded handles, so
                    // this restart can only have come from the most recent attach.
                    startedAttemptId.value = attachedAttemptId.value
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
        // Tells the gamepad poller which of a button's two meanings applies. Scoped to this
        // effect so it tracks the dispatcher exactly: while the player owns the keys, the pad
        // uses its player layout.
        GamepadContext.playerActive = true
        // The screensaver's "during playback" switch keys off the same fact.
        DesktopScreensaver.playerActive = true
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
            if (event.keyCode == AppShortcutsRepository.keyCode(AppShortcutAction.DismissOverlay) &&
                controller.dismissSkipSubmitToastIfShown()
            ) {
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
            GamepadContext.playerActive = false
            DesktopScreensaver.playerActive = false
            GamepadContext.animeShaderActive = false
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
        // Follow the addon's redirect chain once here rather than letting FFmpeg re-walk it on
        // every seek (see PlaybackRedirectResolver). The source URL stays the stream's identity;
        // only what mpv opens changes.
        var resolution = redirectResolutions[sourceUrl]
            ?: PlaybackRedirectResolver.resolve(sourceUrl, playbackHeaders)
                .also { redirectResolutions[sourceUrl] = it }
        // A TorBox node that rate-limited us earlier this session keeps refusing our IP for over
        // an hour, and addons hand back their cached link on that same node. Start on another
        // node instead of opening straight into the ban.
        if (TorBoxNodeHop.isBanned(resolution.playbackUrl)) {
            TorBoxNodeHop.findAlternative(resolution.playbackUrl, playbackUserAgent)?.let { hopped ->
                BingeAdvanceLog.i {
                    "desktop TorBox node avoided at attach attemptId=$playbackAttemptId" +
                        " from=${PlaybackRedirectResolver.hostOf(resolution.playbackUrl)}" +
                        " to=${PlaybackRedirectResolver.hostOf(hopped)}"
                }
                resolution = PlaybackRedirectResolution(
                    sourceUrl,
                    hopped,
                    resolution.hops,
                    PlaybackRedirectResolution.Outcome.Pinned,
                )
                redirectResolutions[sourceUrl] = resolution
            }
        }
        PlaybackStartTrace.mark("redirect:${resolution.outcome.name.lowercase()}")
        // Logged so a binge/next-episode stall can be diagnosed: if the common layer reports it
        // set a new activeSourceUrl (see BingeAdvance "switchToEpisodeStream" log) but this line
        // never follows while the window is minimized, that confirms the attach is waiting on a
        // paused recomposition rather than something in stream selection. The hosts are what a
        // 429 report needs: which resolver handed out the link, and which host mpv is actually
        // hammering with range requests.
        BingeAdvanceLog.i {
            "desktop attach firing attemptId=$playbackAttemptId" +
                " sourceHost=${PlaybackRedirectResolver.hostOf(sourceUrl)}" +
                " playbackHost=${PlaybackRedirectResolver.hostOf(resolution.playbackUrl)}" +
                " redirect=${resolution.outcome.name.lowercase()}/${resolution.hops}" +
                " sourceUrl=${sourceUrl.takeLast(48)}"
        }
        fun attachWith(
            playbackUrl: String,
            positionMs: Long,
            progressFraction: Float,
            tracePlaybackStart: Boolean,
            onError: (String?) -> Unit,
        ) {
            controller.attach(
                tracePlaybackStart = tracePlaybackStart,
                sourceUrl = playbackUrl,
                sourceAudioUrl = sourceAudioUrl,
                sourceHeaders = playbackHeaders,
                mediaTitle = preferredMpvMediaTitle(
                    streamTitle = playerControlsState.streamTitle,
                    streamFilename = playerControlsState.streamFilename,
                    title = playerControlsState.title,
                    episodeText = playerControlsState.episodeText,
                ),
                playWhenReady = playWhenReady,
                initialPositionMs = positionMs,
                initialProgressFraction = progressFraction,
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
                onError = onError,
            )
        }
        // A pinned CDN link can outlive its token where the resolver's own hop would have been
        // re-resolved by FFmpeg for free. On the first 401/403/404/410 from a pinned attach,
        // resolve again and re-attach at the last playhead instead of surfacing the error; a
        // second failure, or anything else, goes to the common layer as before. A 429 on a
        // surface that was already playing is handled first, by recoverFromRateLimit.
        var pinnedLinkRefreshUsed = false
        var rateLimitRecoveriesUsed = 0
        var lastRateLimitRecoveryAtMs = 0L
        var lastRateLimitResumeMs: Long? = null
        // Set when Prefer Failover handed the incident back because failover had nothing to try;
        // the rest of that incident stays with reconnect instead of re-running an empty failover.
        var reconnectingForFailover = false
        // Node hops spent on 429s before the first frame (see tryHopAtOpen).
        var openHopsUsed = 0
        val onSurfaceError = object : (String?) -> Unit {
            override fun invoke(message: String?) {
                if (SeekRateLimitRecovery.isRateLimited(message)) {
                    // Whatever happens next, previews must not keep opening ranges at a host
                    // that has just refused one.
                    SeekThumbnailRateLimitGate.onRateLimited()
                    TorBoxNodeHop.markBanned(redirectResolutions[sourceUrl]?.playbackUrl ?: sourceUrl)
                    val settings = PlayerSettingsRepository.uiState.value
                    val inRecentIncident = System.currentTimeMillis() - lastRateLimitRecoveryAtMs <
                        SeekRateLimitRecovery.EPISODE_WINDOW_MS
                    val reconnect = SeekRateLimitRecovery.shouldReconnect(
                        settings.desktopRateLimitRecoveryMode,
                        settings.streamFailoverEnabled,
                    ) || (reconnectingForFailover && inRecentIncident)
                    if (reconnect && tryStartReconnect(message.orEmpty())) return
                    if (tryHopAtOpen(message.orEmpty())) return
                }
                if (TorBoxNodeHop.looksLikeNodeStall(message)) {
                    val stalledUrl = redirectResolutions[sourceUrl]?.playbackUrl ?: sourceUrl
                    if (TorBoxNodeHop.isTorBoxNode(stalledUrl)) {
                        BingeAdvanceLog.i {
                            "desktop TorBox node stalled attemptId=$playbackAttemptId" +
                                " host=${PlaybackRedirectResolver.hostOf(stalledUrl)} after: $message"
                        }
                        TorBoxNodeHop.markStalled(stalledUrl)
                        if (tryHopAtOpen(message.orEmpty())) return
                    }
                }
                val current = redirectResolutions[sourceUrl]
                val refreshable = message != null &&
                    current?.pinned == true &&
                    !pinnedLinkRefreshUsed &&
                    looksLikeExpiredPinnedLink(message)
                if (!refreshable) {
                    latestOnError.value(message)
                    return
                }
                pinnedLinkRefreshUsed = true
                val retryErrorHandler = this
                surfaceScope.launch {
                    val fresh = PlaybackRedirectResolver.resolve(sourceUrl, playbackHeaders)
                    if (attachedAttemptId.value != playbackAttemptId) return@launch
                    if (!fresh.pinned || fresh.playbackUrl == current.playbackUrl) {
                        latestOnError.value(message)
                        return@launch
                    }
                    redirectResolutions[sourceUrl] = fresh
                    BingeAdvanceLog.i {
                        "desktop pinned link refreshed attemptId=$playbackAttemptId" +
                            " playbackHost=${PlaybackRedirectResolver.hostOf(fresh.playbackUrl)}" +
                            " resumeMs=${lastKnownPositionMs.value} after: $message"
                    }
                    attachWith(
                        playbackUrl = fresh.playbackUrl,
                        positionMs = lastKnownPositionMs.value,
                        progressFraction = 0f,
                        tracePlaybackStart = false,
                        onError = retryErrorHandler,
                    )
                }
            }

            /**
             * Starts one reconnect for a mid-playback 429 when the budget and a sane resume point
             * allow it. Only for a surface that has shown frames of this attempt: a 429 on the
             * initial open stays with the common layer's provider-scoped failover.
             */
            fun tryStartReconnect(message: String): Boolean {
                val nowMs = System.currentTimeMillis()
                // A separate incident much later in the file gets a fresh budget; a failure of
                // the reopen itself spends the remaining one.
                if (nowMs - lastRateLimitRecoveryAtMs >= SeekRateLimitRecovery.EPISODE_WINDOW_MS) {
                    rateLimitRecoveriesUsed = 0
                    lastRateLimitResumeMs = null
                    reconnectingForFailover = false
                }
                // A reopen that fails at open reports no seek target (the attach cleared it), so
                // keep heading for the first incident's target.
                val resumeMs = SeekRateLimitRecovery.safeResumeMs(
                    seekTargetMs = controller.lastSeekFailureTargetMs ?: lastRateLimitResumeMs,
                    lastKnownPositionMs = lastKnownPositionMs.value,
                    durationMs = lastKnownDurationMs.value,
                ) ?: return false
                if (startedAttemptId.value != playbackAttemptId ||
                    attachedAttemptId.value != playbackAttemptId ||
                    rateLimitRecoveriesUsed >= SeekRateLimitRecovery.MAX_ATTEMPTS
                ) {
                    return false
                }
                val settings = PlayerSettingsRepository.uiState.value
                val waitSeconds = if (rateLimitRecoveriesUsed == 0) {
                    settings.desktopRateLimitReconnectFirstDelaySeconds
                } else {
                    settings.desktopRateLimitReconnectSecondDelaySeconds
                }
                rateLimitRecoveriesUsed += 1
                lastRateLimitRecoveryAtMs = nowMs
                lastRateLimitResumeMs = resumeMs
                val failedUrl = redirectResolutions[sourceUrl]?.playbackUrl ?: sourceUrl
                recoverFromRateLimit(message, failedUrl, waitSeconds * 1000L, resumeMs, this)
                return true
            }

            /**
             * A TorBox node that 429s the initial open (or its resume seek) has banned our IP for
             * over an hour, and the attach-time hop only knows about nodes banned earlier this
             * session — so without this the first open of a banned node always failed over or
             * exited. Reopens the same link on another node at the original start position; no
             * wait, since a node ban does not clear on any useful timescale. Off still never
             * reconnects. When no node answers, the error goes on to failover / exit as before.
             *
             * A node that stalls the open or the resume seek ([TorBoxNodeHop.looksLikeNodeStall])
             * takes the same path: the addon hands back the same node on every retry, so moving
             * the link is the only retry that can land somewhere else.
             */
            fun tryHopAtOpen(message: String): Boolean {
                if (startedAttemptId.value == playbackAttemptId) return false
                if (openHopsUsed >= SeekRateLimitRecovery.MAX_ATTEMPTS) return false
                if (PlayerSettingsRepository.uiState.value.desktopRateLimitRecoveryMode ==
                    DesktopRateLimitRecoveryMode.Off
                ) {
                    return false
                }
                val failedUrl = redirectResolutions[sourceUrl]?.playbackUrl ?: sourceUrl
                if (!TorBoxNodeHop.isTorBoxNode(failedUrl)) return false
                openHopsUsed += 1
                val handler = this
                surfaceScope.launch {
                    BingeAdvanceLog.i {
                        "desktop TorBox hop at open attemptId=$playbackAttemptId" +
                            " try=$openHopsUsed/${SeekRateLimitRecovery.MAX_ATTEMPTS}" +
                            " from=${PlaybackRedirectResolver.hostOf(failedUrl)} after: $message"
                    }
                    val hopped = TorBoxNodeHop.findAlternative(failedUrl, playbackUserAgent)
                    if (attachedAttemptId.value != playbackAttemptId) return@launch
                    if (hopped == null) {
                        BingeAdvanceLog.i {
                            "desktop TorBox hop at open found no working node attemptId=$playbackAttemptId"
                        }
                        openHopsUsed = SeekRateLimitRecovery.MAX_ATTEMPTS
                        handler(message)
                        return@launch
                    }
                    BingeAdvanceLog.i {
                        "desktop TorBox hop at open attemptId=$playbackAttemptId" +
                            " to=${PlaybackRedirectResolver.hostOf(hopped)}"
                    }
                    redirectResolutions[sourceUrl] = PlaybackRedirectResolution(
                        sourceUrl,
                        hopped,
                        redirectResolutions[sourceUrl]?.hops ?: 0,
                        PlaybackRedirectResolution.Outcome.Pinned,
                    )
                    attachWith(
                        playbackUrl = hopped,
                        positionMs = initialPositionMs,
                        progressFraction = initialProgressFraction ?: 0f,
                        tracePlaybackStart = false,
                        onError = handler,
                    )
                }
                return true
            }

            /**
             * Prefer Failover's fallback: the common layer calls this when failover after a 429
             * found nothing to switch to (a single source, or only the throttled provider's).
             * Off still exits, and Prefer Reconnect has already spent its reconnects before
             * failover ran, so only Prefer Failover reconnects here.
             */
            fun reconnectAsFailoverFallback(message: String): Boolean {
                val mode = PlayerSettingsRepository.uiState.value.desktopRateLimitRecoveryMode
                if (mode != DesktopRateLimitRecoveryMode.PreferFailover) return false
                if (!tryStartReconnect(message)) return false
                reconnectingForFailover = true
                BingeAdvanceLog.i {
                    "desktop rate-limit reconnect after failover found no source attemptId=$playbackAttemptId"
                }
                return true
            }

            /**
             * A host throttled a mid-playback range open (see [SeekRateLimitRecovery]). The bridge
             * has already stopped the dead demuxer, so nothing counts as ended while this runs.
             *
             * TorBox: the node has banned our IP for over an hour, so waiting is pointless — reopen
             * the same link through another node right away ([TorBoxNodeHop]); when no node
             * answers, go straight on to failover / exit.
             *
             * Anything else: wait [waitMs], then reopen at the seek target. A resolver that is
             * still answering with its status clip is never opened (the clip would "finish" the
             * title from the resume position); once the budget runs out the error reaches the
             * common layer (failover / exit), exactly as before.
             */
            private fun recoverFromRateLimit(
                message: String,
                failedUrl: String,
                waitMs: Long,
                resumeMs: Long,
                handler: (String?) -> Unit,
            ) {
                surfaceScope.launch {
                    val rateLimitedTitle = runCatching {
                        getString(Res.string.player_error_debrid_rate_limited)
                    }.getOrDefault("Debrid Rate Limited")
                    if (TorBoxNodeHop.isTorBoxNode(failedUrl)) {
                        BingeAdvanceLog.i {
                            "desktop rate-limit recovery attemptId=$playbackAttemptId" +
                                " try=$rateLimitRecoveriesUsed/${SeekRateLimitRecovery.MAX_ATTEMPTS}" +
                                " torboxHopFrom=${PlaybackRedirectResolver.hostOf(failedUrl)}" +
                                " resumeMs=$resumeMs after: $message"
                        }
                        runCatching {
                            controller.showPresetPill(
                                rateLimitedTitle,
                                getString(Res.string.player_rate_limit_switching_server),
                                durationMs = 3000,
                            )
                        }
                        val hopped = TorBoxNodeHop.findAlternative(failedUrl, playbackUserAgent)
                        if (attachedAttemptId.value != playbackAttemptId) return@launch
                        if (hopped == null) {
                            BingeAdvanceLog.i {
                                "desktop TorBox hop found no working node attemptId=$playbackAttemptId"
                            }
                            // Nothing to reconnect to: spend the budget so the handler hands the
                            // error straight to failover / exit instead of waiting on a ban.
                            rateLimitRecoveriesUsed = SeekRateLimitRecovery.MAX_ATTEMPTS
                            handler(message)
                            return@launch
                        }
                        BingeAdvanceLog.i {
                            "desktop TorBox hop attemptId=$playbackAttemptId" +
                                " to=${PlaybackRedirectResolver.hostOf(hopped)} resumeMs=$resumeMs"
                        }
                        redirectResolutions[sourceUrl] = PlaybackRedirectResolution(
                            sourceUrl,
                            hopped,
                            redirectResolutions[sourceUrl]?.hops ?: 0,
                            PlaybackRedirectResolution.Outcome.Pinned,
                        )
                        attachWith(
                            playbackUrl = hopped,
                            positionMs = resumeMs,
                            progressFraction = 0f,
                            tracePlaybackStart = false,
                            onError = handler,
                        )
                        return@launch
                    }
                    BingeAdvanceLog.i {
                        "desktop rate-limit recovery attemptId=$playbackAttemptId" +
                            " try=$rateLimitRecoveriesUsed/${SeekRateLimitRecovery.MAX_ATTEMPTS}" +
                            " waitMs=$waitMs resumeMs=$resumeMs after: $message"
                    }
                    runCatching {
                        controller.showPresetPill(
                            rateLimitedTitle,
                            getString(Res.string.player_rate_limit_reconnecting, (waitMs / 1000L).toInt()),
                            durationMs = waitMs.toInt(),
                        )
                    }
                    delay(waitMs)
                    if (attachedAttemptId.value != playbackAttemptId) return@launch
                    // A resolver or proxy in front is asked what it answers now; for AIOStreams
                    // that re-runs its failover chain, which is exactly the retry it is built for.
                    val reopen = redirectResolutions[sourceUrl]
                        ?.takeIf(SeekRateLimitRecovery::canReopenWithoutProbe)
                        ?: PlaybackRedirectResolver.resolve(sourceUrl, playbackHeaders, forceProbe = true)
                    if (attachedAttemptId.value != playbackAttemptId) return@launch
                    if (!SeekRateLimitRecovery.isReopenable(reopen)) {
                        BingeAdvanceLog.i {
                            "desktop rate-limit recovery not reopening attemptId=$playbackAttemptId" +
                                " redirect=${reopen.outcome.name.lowercase()}"
                        }
                        // Feed the same error back through the handler: with budget left it waits
                        // again, otherwise it reaches the common layer as before.
                        handler(message)
                        return@launch
                    }
                    redirectResolutions[sourceUrl] = reopen
                    attachWith(
                        playbackUrl = reopen.playbackUrl,
                        positionMs = resumeMs,
                        progressFraction = 0f,
                        tracePlaybackStart = false,
                        onError = handler,
                    )
                }
            }
        }
        controller.rateLimitFailoverFallback = onSurfaceError::reconnectAsFailoverFallback
        attachWith(
            playbackUrl = resolution.playbackUrl,
            positionMs = initialPositionMs,
            progressFraction = initialProgressFraction ?: 0f,
            tracePlaybackStart = true,
            onError = onSurfaceError,
        )
        attachedAttemptId.value = playbackAttemptId
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
                        "colorGrade=${settings.desktopColorGrade()}, " +
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
                        customGrade = settings.desktopColorGrade(),
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
            // Until this attempt's file has rendered, the controller's handle is still the
            // *outgoing* player's: a new attempt begins before the attach effect above has run,
            // and the attach itself only swaps the handle on a later Swing turn. This loop
            // restarts on the new attempt id and samples immediately, so its first tick used to
            // hand the previous episode's position — playing at 92%, or ended at 100% — to the
            // common layer under the new attempt id, where nothing could tell it apart from the
            // new episode's own progress. The next flush (a pause, or the pause mpv makes while
            // the startup profile renders) then reported that position for the new episode:
            // Continue Watching marked it complete and the tracker got a stop scrobble at 92%,
            // so the "marked watched" toast appeared at the *start* of the following episode.
            // Reporting only a loading placeholder until the first rendered frame keeps every
            // sample the runtime sees attributable to the attempt it is published under.
            val snapshot = if (startedAttemptId.value == playbackAttemptId) {
                controller.snapshot()
            } else {
                PlayerPlaybackSnapshot(isLoading = true)
            }
            if (!snapshot.isLoading && snapshot.positionMs > 0L && !snapshot.isEnded) {
                lastKnownPositionMs.value = snapshot.positionMs
                if (snapshot.durationMs > 0L) lastKnownDurationMs.value = snapshot.durationMs
            }
            latestOnSnapshot.value(snapshot)
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

/**
 * The offsets a profile stands for. [custom] supplies Custom's four values; every other profile
 * ignores it. Shared with the HUD's grade panel, which seeds a fresh Custom grade from whichever
 * preset was on screen — grading from Cinematic should start at Cinematic, not at neutral.
 */
internal fun DesktopColorProfile.presetGrade(
    custom: DesktopColorGrade = DesktopColorGrade(0, 0, 0, 0),
): DesktopColorGrade = when (this) {
    DesktopColorProfile.Neutral -> DesktopColorGrade(0, 0, 0, 0)
    DesktopColorProfile.Cinematic -> DesktopColorGrade(contrast = 2, brightness = -6, saturation = 2, gamma = 2)
    DesktopColorProfile.Vivid -> DesktopColorGrade(contrast = 5, brightness = -4, saturation = 15, gamma = -2)
    DesktopColorProfile.Custom -> custom
}

/**
 * The [DesktopColorProfile.Custom] equalizer offsets, in mpv's own units. Only read when Custom is
 * the active profile; the presets carry their own hardcoded values.
 */
internal data class DesktopColorGrade(
    val contrast: Int,
    val brightness: Int,
    val saturation: Int,
    val gamma: Int,
)

internal fun PlayerSettingsUiState.desktopColorGrade(): DesktopColorGrade = DesktopColorGrade(
    contrast = desktopColorContrast,
    brightness = desktopColorBrightness,
    saturation = desktopColorSaturation,
    gamma = desktopColorGamma,
)

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
    customGrade: DesktopColorGrade,
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
        // Custom goes through the same gate as the presets: hand-dialled numbers are no less
        // SDR-tuned than ours, and a custom grade on a PQ signal crushes shadows the same way.
        else -> colorProfile.presetGrade(customGrade).let {
            listOf(it.contrast, it.brightness, it.saturation, it.gamma)
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

/**
 * An HTTP status that means "this link is no longer good", as mpv reports it
 * (`https: HTTP error 403 Forbidden`, possibly followed by `; Seek failed ...`). Deliberately
 * excludes 429: a rate limit is the provider refusing *any* link, and re-resolving would be one
 * more request at the host that is already saying no.
 */
private val EXPIRED_PINNED_LINK_STATUS =
    Regex("""http error (401|403|404|410)\b""", RegexOption.IGNORE_CASE)

private fun looksLikeExpiredPinnedLink(message: String): Boolean =
    EXPIRED_PINNED_LINK_STATUS.containsMatchIn(message)
