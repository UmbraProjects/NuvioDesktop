package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.screensaver.DesktopScreensaver
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener

private val log = Logger.withTag("SteadyFramePacing")

/** Long enough that browsing never trips it; short enough that an abandoned window stops drawing. */
private const val IDLE_STOP_MS = 5 * 60_000L

/** How often a paused loop looks again at the player / idle state. Never a frame-clock wait. */
private const val PAUSED_RECHECK_MS = 500L

private const val INPUT_EVENT_MASK: Long =
    AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK or
        AWTEvent.MOUSE_WHEEL_EVENT_MASK or AWTEvent.KEY_EVENT_MASK

@Volatile
private var lastInputAtMs = System.currentTimeMillis()

/**
 * Last state written to the log. Module-level rather than per coroutine: at startup the effect
 * restarted with identical keys within ~50ms, alongside the borderless root-pane correction — most
 * likely a focus lost/regained pair too quick for the "not focused" coroutine to run (inferred from
 * timing, not instrumented). A per-coroutine flag then logged "running" twice for one continuous
 * run. LaunchedEffect cancels before restarting, so only one loop ever exists; this keeps the log
 * to real transitions.
 */
private var loggedState: String? = null

private fun logState(state: String) {
    if (state == loggedState) return
    loggedState = state
    log.i { state }
}

/**
 * Optional fix for VRR (G-Sync / FreeSync) flicker in the interface.
 *
 * Compose draws only when something changes, so the window presents frames irregularly: nothing at
 * all when still, then bursts as the pointer crosses items and hover animations start and stop. On a
 * variable-refresh display the panel's refresh rate follows those presents, and OLED and VA panels
 * shift brightness with refresh rate — the "frametimes all over the place when you hover" flicker.
 * (A different problem from the 2026-09-07 OLED dim, which was a brightness STEP in content and was
 * fixed by the hero backdrop crossfade.)
 *
 * While enabled, this keeps one frame-clock awaiter alive, so the window renders every vsync and the
 * panel holds its maximum rate. That is exactly the always-on loop the frame-budget work removed
 * from the default path (25% GPU on an idle window), which is why it is opt-in and stands down
 * whenever it cannot help: window not focused (VRR follows the foreground window, and a minimised
 * one loses focus), the native player mounted (mpv presents video itself), and after
 * [IDLE_STOP_MS] without input.
 *
 * Settings -> Playback -> Steady frame pacing. Performance logging's `FramePacing` lines measure
 * whether it worked: `swings>8ms` should drop to near zero while browsing.
 */
@Composable
internal fun SteadyFramePacingEffect(window: Window) {
    val settings by PlayerSettingsRepository.uiState.collectAsStateWithLifecycle()
    val enabled = settings.desktopSteadyFramePacingEnabled
    var focused by remember(window) { mutableStateOf(window.isFocused) }

    DisposableEffect(window, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val focusListener = object : WindowFocusListener {
            override fun windowGainedFocus(event: WindowEvent) {
                focused = true
            }

            override fun windowLostFocus(event: WindowEvent) {
                focused = false
            }
        }
        val inputListener = AWTEventListener { lastInputAtMs = System.currentTimeMillis() }
        window.addWindowFocusListener(focusListener)
        Toolkit.getDefaultToolkit().addAWTEventListener(inputListener, INPUT_EVENT_MASK)
        focused = window.isFocused
        lastInputAtMs = System.currentTimeMillis()
        onDispose {
            window.removeWindowFocusListener(focusListener)
            Toolkit.getDefaultToolkit().removeAWTEventListener(inputListener)
        }
    }

    LaunchedEffect(enabled, focused) {
        if (!enabled) {
            if (loggedState != null) logState("off")
            return@LaunchedEffect
        }
        if (!focused) {
            logState("paused: window not focused")
            return@LaunchedEffect
        }
        while (isActive) {
            val idle = System.currentTimeMillis() - lastInputAtMs >= IDLE_STOP_MS
            val inPlayer = DesktopScreensaver.playerActive
            val running = !idle && !inPlayer
            logState(
                when {
                    running -> "running: rendering every refresh"
                    inPlayer -> "paused: player active"
                    else -> "paused: no input for ${IDLE_STOP_MS / 60_000} min"
                },
            )
            if (running) {
                // An empty frame wait is the whole mechanism: a pending awaiter makes the Recomposer
                // schedule the next frame, so the window draws and presents on every vsync.
                withFrameNanos { }
            } else {
                delay(PAUSED_RECHECK_MS)
            }
        }
    }
}
