package com.nuvio.app.features.player

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize

internal fun Modifier.playerSurfaceTapGestures(
    layoutSize: IntSize,
    playerControlsLockedState: State<Boolean>,
    onSurfaceTap: State<(Offset) -> Unit>,
    onSurfaceDoubleTap: State<(Offset) -> Unit>,
    revealLockedOverlayState: State<() -> Unit>,
): Modifier =
    pointerInput(layoutSize) {
        detectTapGestures(
            onTap = { offset -> onSurfaceTap.value(offset) },
            onDoubleTap = { offset -> onSurfaceDoubleTap.value(offset) },
            onLongPress = {
                if (playerControlsLockedState.value) {
                    revealLockedOverlayState.value()
                }
            },
        )
    }
