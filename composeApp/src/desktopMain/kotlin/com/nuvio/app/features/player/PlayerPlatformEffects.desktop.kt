package com.nuvio.app.features.player

import com.nuvio.app.core.ui.DesktopArtworkCaches
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.IntSize
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
        try {
            awaitCancellation()
        } finally {
            DesktopArtworkCaches.endPlayback(session)
        }
    }
}

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
