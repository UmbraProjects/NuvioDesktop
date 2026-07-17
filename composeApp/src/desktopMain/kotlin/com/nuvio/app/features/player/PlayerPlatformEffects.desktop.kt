package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.IntSize
import com.nuvio.app.features.player.desktop.setDesktopPictureInPicture
import com.nuvio.app.features.player.desktop.desktopPictureInPictureState

@Composable
actual fun LockPlayerToLandscape() = Unit

@Composable
actual fun EnterImmersivePlayerMode(keepScreenAwake: Boolean) = Unit

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
