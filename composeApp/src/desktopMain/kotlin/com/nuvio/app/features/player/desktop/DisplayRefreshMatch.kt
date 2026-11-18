package com.nuvio.app.features.player.desktop

import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.PlayerSettingsRepository
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Settings > Playback > Match display refresh rate. While a video plays, the monitor the player
 * sits on is switched to a refresh rate the video's frame rate divides evenly (24 fps to 24, 48,
 * 120 or 144 Hz; 25 fps to 50 Hz), so every frame is held for the same number of refreshes and
 * there is no 3:2 judder. The switch is temporary (CDS_FULLSCREEN) and is undone when the player
 * closes; if the app dies, Windows undoes it itself.
 */

/** Whole-number rates Windows lists for the NTSC family (23.976, 29.97, 59.94, 119.88 ...). */
internal fun effectiveRefreshHz(listedHz: Int): Double =
    if (listedHz >= 23 && ((listedHz + 1) % 24 == 0 || (listedHz + 1) % 30 == 0)) {
        (listedHz + 1) / 1.001
    } else {
        listedHz.toDouble()
    }

/** Relative error between the display rate and the nearest whole multiple of [fps]. */
internal fun refreshCadenceError(listedHz: Int, fps: Double): Double {
    val hz = effectiveRefreshHz(listedHz)
    val multiple = (hz / fps).roundToInt()
    if (multiple < 1) return Double.MAX_VALUE
    return abs(hz - multiple * fps) / (multiple * fps)
}

/** Each frame held for exactly the same number of refreshes (23.976 on a 23 Hz listing). */
private const val EXACT_CADENCE = 0.0005

/** Off by about 0.1% (23.976 on 24 Hz): one repeated frame every ~40 s, which mpv can resample away. */
private const val NEAR_CADENCE = 0.002

/**
 * The rate to switch to for [fps], or null to leave the display alone: when the frame rate is
 * unknown or odd, when the current rate already fits, or when no listed rate fits. Among the
 * rates that fit, an exact cadence beats a near one, and the highest rate wins (a smoother
 * desktop and HUD, and 120 Hz is as judder-free as 24 Hz).
 */
internal fun pickDisplayRefreshRate(fps: Double, currentHz: Int, availableHz: Collection<Int>): Int? {
    if (!fps.isFinite() || fps < 10.0 || fps > 121.0 || currentHz <= 1) return null
    val candidates = (availableHz + currentHz).filter { it > 1 }.distinct()
    for (tolerance in listOf(EXACT_CADENCE, NEAR_CADENCE)) {
        if (refreshCadenceError(currentHz, fps) <= tolerance) return null
        candidates.filter { refreshCadenceError(it, fps) <= tolerance }.maxOrNull()?.let { return it }
    }
    return null
}

/**
 * Owns the one display switch the app may hold. The restore after a player closes waits a few
 * seconds, so the next episode (often a new player) can claim the same rate without the monitor
 * blanking twice.
 */
private val refreshLog = Logger.withTag("DisplayRefresh")

internal object DisplayRefreshSwitcher {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "Nuvio-Display-Refresh").apply { isDaemon = true }
    }
    private var pendingRestore: ScheduledFuture<*>? = null
    private var switchedHz = 0
    private var generation = 0L

    init {
        Runtime.getRuntime().addShutdownHook(Thread({ restoreNow() }, "Nuvio-Display-Refresh-Exit"))
    }

    val enabled: Boolean
        get() = PlayerSettingsRepository.uiState.value.desktopMatchDisplayRefreshRate

    /**
     * Matches the monitor [hostHwnd] is on to the video, once [readFps] reports a frame rate
     * (mpv may need a moment after the file loads). Off the UI thread.
     */
    fun matchWhenKnown(hostHwnd: Long, readFps: () -> Double?) {
        if (!enabled) return
        if (hostHwnd == 0L) {
            refreshLog.w { "no player window to match" }
            return
        }
        val token = synchronized(this) {
            pendingRestore?.cancel(false)
            pendingRestore = null
            ++generation
        }
        executor.execute {
            var fps: Double? = null
            for (attempt in 0 until 20) {
                if (synchronized(this) { generation != token }) return@execute
                fps = runCatching { readFps() }.getOrNull()?.takeIf { it > 0.0 }
                if (fps != null) break
                Thread.sleep(150)
            }
            if (synchronized(this) { generation != token }) return@execute
            if (fps == null) {
                refreshLog.i { "video frame rate unknown; display left alone" }
                return@execute
            }
            val rates = runCatching { NativePlayerBridge.displayRefreshRates(hostHwnd) }
                .onFailure { refreshLog.w(it) { "could not read display modes" } }
                .getOrNull()
            if (rates == null || rates.isEmpty()) {
                refreshLog.w { "display modes unavailable; display left alone" }
                return@execute
            }
            // After an earlier switch this is the rate that switch left (50 Hz after a 25 fps
            // episode): kept when it still fits, replaced when it does not.
            val current = rates[0]
            val offered = rates.drop(1).sorted()
            val target = pickDisplayRefreshRate(fps, current, offered)
            if (target == null || target == current) {
                refreshLog.i { "video ${"%.3f".format(fps)} fps, display $current Hz (offers $offered): no switch" }
                return@execute
            }
            val switched = runCatching { NativePlayerBridge.setDisplayRefreshRate(hostHwnd, target) }.getOrDefault(false)
            refreshLog.i { "video ${"%.3f".format(fps)} fps, display $current Hz (offers $offered): switch to $target Hz ${if (switched) "done" else "FAILED"}" }
            if (switched) synchronized(this) { switchedHz = target }
        }
    }

    /** Puts the user's mode back a few seconds from now, unless another video claims it first. */
    fun releaseSoon() {
        synchronized(this) {
            ++generation
            if (switchedHz == 0) return
            pendingRestore?.cancel(false)
            pendingRestore = executor.schedule({ restoreNow() }, 3, TimeUnit.SECONDS)
        }
    }

    fun restoreNow() {
        val wasSwitched = synchronized(this) {
            pendingRestore = null
            val was = switchedHz != 0
            switchedHz = 0
            was
        }
        if (wasSwitched) runCatching { NativePlayerBridge.setDisplayRefreshRate(0L, 0) }
    }
}
