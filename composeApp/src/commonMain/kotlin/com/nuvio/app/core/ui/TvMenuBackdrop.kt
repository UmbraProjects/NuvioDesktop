package com.nuvio.app.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color

/**
 * A menu opened from the keyboard or a pad sits centred as a TV-style sheet (CursorMenuPositionProvider); the window
 * dims behind it. Drawn over the app and under the menus, which are popups in a layer of their own; it takes no input,
 * so a click on it still reaches the menu's dismissal as before. A menu opened with the mouse stays at the pointer,
 * with no dimming.
 */
@Composable
fun TvMenuBackdrop() {
    val open = KeyRouteLog.openMenus.intValue > 0
    val alpha by animateFloatAsState(if (open) 1f else 0f, tween(140), label = "tvMenuBackdrop")
    if (alpha == 0f) return
    Box(Modifier.fillMaxSize().alpha(alpha).background(Color.Black.copy(alpha = 0.5f)))
}
