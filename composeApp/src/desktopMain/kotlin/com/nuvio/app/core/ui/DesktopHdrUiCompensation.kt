package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.desktop.AwtNativeViewResolver
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

private val log = Logger.withTag("HdrUiCompensation")

/** Catches an HDR toggle in Windows Settings, which sends the window nothing it can listen for. */
private const val RECHECK_MS = 4_000L

/**
 * Whether the UI should pre-correct its fades for Windows HDR.
 *
 * With HDR on, DWM converts every SDR window into the HDR signal by decoding it with the piecewise
 * sRGB curve, while SDR content is graded for gamma 2.2. The two differ most just above black, and
 * Windows lifts those values: a long fade to black sits as a grey haze and then drops to black in
 * the last few levels ("a mess of white that changes to black instantly"). Fullscreen OpenGL looks
 * right only because it is direct-flipped past DWM, which is also why it can black out the player.
 *
 * Nothing in skiko can hand DWM an HDR frame, so the dithered scrim ([nuvioDitheredScrim]) applies
 * the inverse instead, `sRGB_encode(x^2.2)`, and DWM's decode lands on what gamma 2.2 would have
 * shown. It must only run while the window's own monitor is in HDR: on an SDR display the same
 * curve crushes the shadows. Settings -> Playback -> "HDR display correction" turns it off for
 * people who already fix this system-wide with the sRGB-to-gamma-2.2 calibration profile, who
 * would otherwise get it twice.
 */
internal object DesktopHdrUiCompensation {
    /** Read inside graphics-layer blocks, so a change re-runs those layers and nothing else. */
    var active by mutableStateOf(false)
        internal set
}

@Composable
internal fun DesktopHdrUiCompensationEffect(window: Window) {
    val settings by PlayerSettingsRepository.uiState.collectAsStateWithLifecycle()
    val enabled = settings.desktopHdrUiCompensationEnabled && DesktopHostOs.current == DesktopHostOs.WINDOWS
    val recheck = remember(window) { Channel<Unit>(CONFLATED) }

    DisposableEffect(window, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        // Moving to another monitor, or coming back to the window, re-asks straight away.
        val moveListener = object : ComponentAdapter() {
            override fun componentMoved(event: ComponentEvent) {
                recheck.trySend(Unit)
            }
        }
        val activationListener = object : WindowAdapter() {
            override fun windowActivated(event: WindowEvent) {
                recheck.trySend(Unit)
            }
        }
        window.addComponentListener(moveListener)
        window.addWindowListener(activationListener)
        onDispose {
            window.removeComponentListener(moveListener)
            window.removeWindowListener(activationListener)
        }
    }

    LaunchedEffect(window, enabled) {
        if (!enabled) {
            if (DesktopHdrUiCompensation.active) log.i { "off: disabled in settings" }
            DesktopHdrUiCompensation.active = false
            return@LaunchedEffect
        }
        while (isActive) {
            val hdr = withContext(Dispatchers.IO) { windowMonitorIsHdr(window) }
            if (hdr != DesktopHdrUiCompensation.active) {
                log.i { if (hdr) "on: window's display is in HDR" else "off: window's display is SDR" }
                DesktopHdrUiCompensation.active = hdr
            }
            withTimeoutOrNull(RECHECK_MS) { recheck.receive() }
            // Coalesces a drag across monitors into one query once it settles.
            delay(250)
        }
    }
}

private fun windowMonitorIsHdr(window: Window): Boolean {
    if (!window.isDisplayable) return false
    return runCatching {
        NativePlayerBridge.isWindowDisplayHdr(AwtNativeViewResolver.resolveNativeViewPointer(window))
    }.getOrDefault(false)
}
