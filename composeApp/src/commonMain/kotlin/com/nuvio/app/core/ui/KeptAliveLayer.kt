package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberUpdatedState
import co.touchlab.kermit.Logger
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner

private val keptAliveLog = Logger.withTag("KeptAliveLayer")

/**
 * True inside a [KeptAliveLayer] while another screen covers it. Anything that cannot simply stay
 * invisible must stop on it, above all heavyweight native surfaces (the Home hero trailer's
 * SwingPanel), which an unplaced layout does not hide and which would paint over the covering screen.
 */
// Dynamic, not static: a static local would recompose the whole kept screen on every cover and
// uncover, which is the cost this layer exists to avoid. Only its readers recompose.
val LocalScreenCovered = compositionLocalOf { false }

/**
 * A lifecycle that follows the surrounding one while [active], and stops at CREATED while not, for
 * a [KeptAliveLayer] that has no back-stack entry of its own (a tab hidden behind another tab).
 */
@Composable
fun rememberChildLifecycleOwner(active: Boolean): LifecycleOwner {
    val parent = LocalLifecycleOwner.current
    // In step from the first composition, before any effect runs, so content composed in that
    // frame already sees a started lifecycle.
    val owner = remember { ChildLifecycleOwner().also { it.follow(parent, active) } }
    DisposableEffect(parent, active) {
        owner.follow(parent, active)
        val observer = LifecycleEventObserver { _, _ -> owner.follow(parent, active) }
        parent.lifecycle.addObserver(observer)
        onDispose { parent.lifecycle.removeObserver(observer) }
    }
    return owner
}

private class ChildLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this)
    override val lifecycle: Lifecycle get() = registry

    fun follow(parent: LifecycleOwner, active: Boolean) {
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        val parentState = parent.lifecycle.currentState
        registry.currentState = if (active || parentState < Lifecycle.State.CREATED) {
            parentState
        } else {
            minOf(parentState, Lifecycle.State.CREATED)
        }
    }
}

/**
 * Keeps [content] composed while another screen covers it, so uncovering it shows a frame that
 * already exists instead of rebuilding the screen from scratch.
 *
 * While [visible] is false the content is still measured, but placed off-screen and not drawn: it
 * takes no pointer input, and if it still holds keyboard focus at that point the focus is
 * released, so keys cannot steer a screen nobody can see. Off-screen rather than unplaced: an
 * unplaced node never gets the hover exit for the pointer that was over it, so a poster's hover
 * preview stayed "hovered" while Details was open and came back over that poster on return,
 * taking the click meant for its neighbour and opening the title just left (2026-10-19 07:53). [lifecycleOwner] (the covered route's
 * back-stack entry) stands in for the window's, so lifecycle-aware collection pauses while
 * covered and catches up on the latest values when it is shown again. Keep it stable: the local
 * is static, so a new owner recomposes everything inside.
 */
private const val OffscreenMarginPx = 64

@Composable
fun KeptAliveLayer(
    visible: Boolean,
    lifecycleOwner: LifecycleOwner,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var layerHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        // The covering screen usually takes focus itself in the same frame; only let go when it
        // has not, so its own request is never undone.
        if (!visible && layerHasFocus) focusManager.clearFocus(force = true)
    }
    val currentlyVisible by rememberUpdatedState(visible)
    Box(
        modifier = modifier
            .onFocusChanged { state ->
                layerHasFocus = state.hasFocus
                // Something inside asked for focus while covered (its own requests should check
                // LocalScreenCovered). Keys would steer a screen nobody can see; drop it instead.
                if (state.hasFocus && !currentlyVisible) {
                    keptAliveLog.w { "focus moved into a covered screen; released" }
                    focusManager.clearFocus(force = true)
                }
            }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    if (visible) {
                        placeable.place(0, 0)
                    } else {
                        placeable.place(-(placeable.width + OffscreenMarginPx), 0)
                    }
                }
            }
            .drawWithContent { if (visible) drawContent() },
    ) {
        CompositionLocalProvider(
            LocalLifecycleOwner provides lifecycleOwner,
            // Nested layers: covered when this one is hidden or the one around it is.
            LocalScreenCovered provides (!visible || LocalScreenCovered.current),
            content = content,
        )
    }
}
