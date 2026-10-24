package com.nuvio.app.features.player

import com.nuvio.app.core.ui.DesktopArtworkCaches
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.IntSize
import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.desktop.DesktopIdleHeapTrim
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import com.nuvio.app.features.player.desktop.setDesktopPictureInPicture
import com.nuvio.app.features.player.desktop.desktopPictureInPictureState

@Composable
actual fun LockPlayerToLandscape() = Unit

@Composable
actual fun EnterImmersivePlayerMode(keepScreenAwake: Boolean) {
    // Main-player lifecycle only: hero trailers must keep the browsing cache warm. A brief open
    // and back cancels the delay; pause/resume and screen-awake changes do not restart it.
    LaunchedEffect(Unit) {
        delay(5_000)
        val session = DesktopArtworkCaches.beginPlayback()
        DesktopIdleHeapTrim.onPlaybackStarted()
        try {
            awaitCancellation()
        } finally {
            DesktopArtworkCaches.endPlayback(session)
        }
    }
    // Keep the display awake while a video plays or is buffering to play; paused, the PC may
    // sleep as usual. Nuvio's own screensaver reads the input idle clock, which this leaves alone.
    LaunchedEffect(keepScreenAwake) {
        setDisplaySleepInhibited(keepScreenAwake)
    }
    DisposableEffect(Unit) {
        onDispose { setDisplaySleepInhibited(false) }
    }
}

private fun setDisplaySleepInhibited(inhibited: Boolean) {
    runCatching { NativePlayerBridge.setWindowsDisplaySleepInhibited(inhibited) }
        .onFailure { PlayerKeepAwakeLog.w(it) { "keep-awake ${if (inhibited) "on" else "off"} failed" } }
}

private val PlayerKeepAwakeLog = Logger.withTag("PlayerKeepAwake")

@Composable
actual fun ManagePlayerPictureInPicture(
    isActive: Boolean,
    onActiveChange: (Boolean) -> Unit,
    isPlaying: Boolean,
    playerSize: IntSize,
) {
    DisposableEffect(isActive) {
        setDesktopPictureInPicture(isActive)
        onDispose {
            if (isActive) setDesktopPictureInPicture(false)
        }
    }
    val platformActive = desktopPictureInPictureState.value
    LaunchedEffect(platformActive) {
        if (platformActive != isActive) onActiveChange(platformActive)
    }
}

@Composable
actual fun rememberPlayerGestureController(): PlayerGestureController? = null
