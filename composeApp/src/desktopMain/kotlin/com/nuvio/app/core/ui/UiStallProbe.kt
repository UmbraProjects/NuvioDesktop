package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import com.nuvio.app.core.perf.PerfTelemetry
import com.nuvio.app.core.perf.UiStallSampler
import com.nuvio.app.features.screensaver.DesktopScreensaver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent

/**
 * Feeds [UiStallSampler] (perf logging only): arms it on key presses, clicks and wheel ticks, and
 * keeps the frame clock running while it is armed so that a gap between frames is a stall rather
 * than an idle window. Renders every vsync for ~1.5 s after input, which is why it stays behind the
 * perf-logging switch.
 */
@Composable
internal fun UiStallProbe() {
    if (!PerfTelemetry.enabled) return
    DisposableEffect(Unit) {
        UiFreezeWatchdog.start()
        val listener = AWTEventListener { event ->
            val reason = when (event.id) {
                KeyEvent.KEY_PRESSED -> "key"
                MouseEvent.MOUSE_PRESSED -> "click"
                MouseEvent.MOUSE_WHEEL -> "wheel"
                else -> null
            }
            // mpv presents the video itself; forcing Compose frames under it would only cost GPU.
            if (reason != null && !DesktopScreensaver.playerActive) UiStallSampler.arm(reason)
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(
            listener,
            AWTEvent.KEY_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_WHEEL_EVENT_MASK,
        )
        onDispose { Toolkit.getDefaultToolkit().removeAWTEventListener(listener) }
    }
    LaunchedEffect(Unit) {
        UiStallSampler.armedUntil.collectLatest { until ->
            while (System.currentTimeMillis() < until) {
                withFrameNanos { UiStallSampler.markFrame() }
            }
            // One more beat so the sampler sees the last frame and closes the gap it was in.
            delay(50)
        }
    }
}
