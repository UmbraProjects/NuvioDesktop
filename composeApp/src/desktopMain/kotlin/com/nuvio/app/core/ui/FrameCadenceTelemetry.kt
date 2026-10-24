package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import co.touchlab.kermit.Logger

/**
 * Frame cadence, always measured - including when steady pacing is OFF.
 *
 * Whether the mitigation above actually does what it claims has to be checked, not assumed: if the
 * intervals are already even when it is on and the panel still flickers, the app cannot influence
 * the refresh rate and the cause is below it. If they are ragged, the mitigation is not working and
 * the theory is still untested. Reported as the distribution, because a mean hides exactly the
 * stalls and bursts that swing a variable-refresh panel.
 */
private object FrameCadence {
    private const val ReportIntervalNanos = 5_000_000_000L
    private var lastFrameNanos = 0L
    private var windowStartedNanos = 0L
    private var frames = 0
    private var totalGapNanos = 0L
    private var maxGapNanos = 0L
    private var longGaps = 0
    private var previousGapNanos = 0L
    private var swings = 0
    private var worstSwingNanos = 0L

    fun onFrame(nowNanos: Long) {
        if (lastFrameNanos != 0L) {
            val gap = nowNanos - lastFrameNanos
            frames++
            totalGapNanos += gap
            if (gap > maxGapNanos) maxGapNanos = gap
            // Two 60Hz frames: a gap this long is a present the panel had to wait for.
            if (gap > 33_000_000L) longGaps++
            // A refresh-rate SWING, which is what a variable-refresh panel reacts to. Max frame
            // time cannot see this: one isolated 27ms frame and a frame time alternating 9/27/9/27
            // report the same max, but the second swings the panel between 110Hz and 40Hz every
            // frame. White and a cached Sweep produced identical mean/max while only one flickered,
            // so the difference has to be in the shape rather than the extremes.
            if (previousGapNanos != 0L) {
                val swing = kotlin.math.abs(gap - previousGapNanos)
                if (swing > 8_000_000L) swings++
                if (swing > worstSwingNanos) worstSwingNanos = swing
            }
            previousGapNanos = gap
            // Logged inline rather than in the 5s summary: with no per-line timestamps, adjacency
            // in the log is the only way to see what a stall coincided with.
            if (gap > 50_000_000L) {
                Logger.withTag("FramePacing").i { "STALL ${"%.1f".format(gap / 1_000_000.0)}ms" }
            }
        }
        lastFrameNanos = nowNanos
        if (windowStartedNanos == 0L) windowStartedNanos = nowNanos
        if (nowNanos - windowStartedNanos >= ReportIntervalNanos && frames > 0) {
            Logger.withTag("FramePacing").i {
                "frames=$frames mean=${"%.1f".format(totalGapNanos / 1_000_000.0 / frames)}ms " +
                    "max=${"%.1f".format(maxGapNanos / 1_000_000.0)}ms gaps>33ms=$longGaps " +
                    "swings>8ms=$swings worstSwing=${"%.1f".format(worstSwingNanos / 1_000_000.0)}ms"
            }
            windowStartedNanos = nowNanos
            frames = 0
            totalGapNanos = 0L
            maxGapNanos = 0L
            longGaps = 0
            swings = 0
            worstSwingNanos = 0L
        }
    }
}

/**
 * Measures the window's frame cadence. Kept after the flicker investigation because it is nearly
 * free and it is the only view of presentation this app has: mean interval, worst gap, and the
 * frame-to-frame SWING that a variable-refresh display reacts to.
 */
@Composable
internal fun FrameCadenceTelemetry() {
    // Keyed on the switch so turning it off cancels the loop and releases the frame clock; see
    // FrameClockProbes for why a permanent awaiter is suspected of keeping the app rendering.
    // Gated, not merely toggleable: an always-on frame-clock awaiter costs 25% of the GPU on an
    // idle window. See FrameClockProbes.
    val enabled = FrameBenchMode.probesEnabled && FrameClockProbes.enabled
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        while (true) {
            withFrameNanos { FrameCadence.onFrame(it) }
        }
    }
}
