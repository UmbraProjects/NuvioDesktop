package com.nuvio.app.features.screensaver

import co.touchlab.kermit.Logger
import com.nuvio.app.features.input.GamepadInput
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import java.awt.AWTEvent
import java.awt.Color
import java.awt.Font
import java.awt.Frame
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.MouseInfo
import java.awt.Point
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import nuvio.composeapp.generated.resources.*
import com.nuvio.app.core.i18n.localizedString

/**
 * The screensaver proper: watches for idle input, drops a translucent black shade over the app
 * window after the configured time, and optionally counts down to a PC shutdown after a longer one.
 *
 * **Why a separate owned window rather than a Compose overlay.** The player is a native child HWND
 * (mpv) with a WebView2 HUD on top, and hero trailers are a heavyweight canvas; nothing drawn by
 * Compose can cover any of them. A top-level window owned by the app frame sits above the whole
 * hierarchy, and an owned window follows its owner in z-order, so it is never left hanging over
 * another program the user has switched to. `setOpacity` gives the dim level for free.
 *
 * **Idle time** is Windows' own (`GetLastInputInfo` via the bridge): mouse movement over the mpv
 * surface and keys into the HUD never reach AWT, so in-process listeners alone would dim the
 * screen on someone plainly using it. Controllers are not input to Windows, so the gamepad
 * poller's own timestamp is folded in. AWT events are still listened to, both as the fallback
 * when the bridge is missing (dev tree with no native build) and so the shade lifts on the very
 * first movement rather than at the next one-second tick.
 *
 * **Shutdown** is gated on the app being the foreground window on top of the idle time: idle with
 * a browser in front means someone is watching something else on this PC, not that the room is
 * asleep. The countdown is painted on the shade and any input cancels it.
 *
 * **Synthetic mouse moves.** Windows posts a `WM_MOUSEMOVE` to whatever window newly appears under
 * a stationary pointer, and showing the shade is exactly that. Taken at face value it would wake
 * the shade the moment it appeared, forever. So a motion event only counts as input when the
 * pointer has actually left the spot it was at when the shade went up; presses, wheel and keys
 * always count. The one-second tick is unaffected either way — `GetLastInputInfo` only knows
 * about real input.
 *
 * **The cursor.** A bright arrow parked on a black screen is the worst thing to leave on an OLED,
 * and nothing about dimming moves the pointer. The same synthetic move is what makes it go away:
 * the shade carries a blank cursor, and Windows re-evaluates the cursor against the window under
 * the pointer when that window changes. That only happens if the shade is hit-testable, and a
 * layered window at alpha 0 is not — so the fade starts from a barely visible alpha rather than
 * from nothing.
 *
 * Everything runs on the AWT event thread: the timer is a Swing one and the repository is only
 * ever touched from there (Compose Desktop's UI thread is the same thread).
 */
internal object DesktopScreensaver {

    private val log = Logger.withTag("Screensaver")

    /** Idle checks per second. Cheap: one JNI call and a handful of comparisons. */
    private const val TickIntervalMs = 1_000

    /** How the shade animates. Slow in — nobody should notice the moment it starts — and quick out. */
    private const val FadeFrameMs = 33
    private const val FadeInMs = 2_000
    private const val FadeOutMs = 250

    /** The countdown must be readable whatever the dim level, so the shade is at least this dark. */
    private const val CountdownMinOpacity = 0.9f

    /** Pointer travel below this is jitter or a synthetic move, not someone reaching for the mouse. */
    private const val WakeMotionThresholdPx = 3

    /**
     * The lowest alpha the shade is ever shown at. A layered window at alpha 0 is click-through,
     * which also means the pointer is not over it and its blank cursor is not the one on screen;
     * one step above that and it is. Invisible in practice.
     */
    private const val MinVisibleOpacity = 0.02f

    private val WakeEventMask: Long =
        AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK or
            AWTEvent.MOUSE_WHEEL_EVENT_MASK or AWTEvent.KEY_EVENT_MASK

    /**
     * Whether a player surface is mounted. Published from the player's own lifecycle effect, next
     * to the gamepad's copy of the same fact. Volatile because the player sets it from Compose
     * (the event thread, in practice) and this object reads it from the Swing timer (also the
     * event thread) — cheap insurance against either moving.
     */
    @Volatile
    var playerActive: Boolean = false

    private var owner: Window? = null
    private var shade: Shade? = null
    private var tickTimer: Timer? = null
    private var wakeListener: AWTEventListener? = null
    private var exitApplication: () -> Unit = {}

    /** Last AWT input seen, for the fallback idle clock when the bridge cannot be loaded. */
    @Volatile
    private var lastAwtInputAtMs = System.currentTimeMillis()

    /** Where the pointer was when the shade went up; motion is measured against this. */
    @Volatile
    private var restingPointer: Point? = null

    /** Wall time the shutdown countdown began, or null while it is not running. */
    private var countdownStartedAtMs: Long? = null

    /** Set once the shutdown command has gone out, so a slow exit cannot issue it twice. */
    private var shutdownIssued = false

    /** Latched on the first failed bridge call: no point retrying a missing DLL every second. */
    private var nativeIdleUnavailable = false

    /**
     * Installs the idle watch against [window]. Returns the uninstaller. No-op off Windows: the
     * idle probe and the shutdown command are both Windows-only, and this fork ships nowhere else.
     */
    fun install(window: Window, exitApplication: () -> Unit): () -> Unit {
        if (DesktopHostOs.current != DesktopHostOs.WINDOWS) return {}
        uninstall()
        owner = window
        this.exitApplication = exitApplication
        ScreensaverSettingsRepository.ensureLoaded()

        val listener = AWTEventListener { event ->
            // Synthetic gamepad keys land here too; the pad keeps its own clock, so counting them
            // twice is harmless.
            if (event is InputEvent && isRealInput(event)) onInput()
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, WakeEventMask)
        wakeListener = listener

        tickTimer = Timer(TickIntervalMs) { tick() }.apply {
            isRepeats = true
            start()
        }
        return { uninstall() }
    }

    private fun uninstall() {
        tickTimer?.stop()
        tickTimer = null
        wakeListener?.let { Toolkit.getDefaultToolkit().removeAWTEventListener(it) }
        wakeListener = null
        shade?.dispose()
        shade = null
        owner = null
        countdownStartedAtMs = null
    }

    private fun isRealInput(event: InputEvent): Boolean = when (event) {
        is MouseWheelEvent -> true
        is KeyEvent -> true
        is MouseEvent -> when (event.id) {
            MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED -> true
            MouseEvent.MOUSE_MOVED, MouseEvent.MOUSE_DRAGGED -> pointerHasMoved()
            // Enter/exit are consequences of windows appearing, never of the user doing anything.
            else -> false
        }
        else -> false
    }

    private fun pointerHasMoved(): Boolean {
        val resting = restingPointer ?: return true
        val now = MouseInfo.getPointerInfo()?.location ?: return true
        return abs(now.x - resting.x) >= WakeMotionThresholdPx ||
            abs(now.y - resting.y) >= WakeMotionThresholdPx
    }

    private fun onInput() {
        lastAwtInputAtMs = System.currentTimeMillis()
        // Lift the shade straight away rather than on the next tick: the shade is also where the
        // first mouse movement lands, and a second of black after touching the mouse feels stuck.
        if (shade?.isVisible == true) {
            if (SwingUtilities.isEventDispatchThread()) wake() else SwingUtilities.invokeLater { wake() }
        }
    }

    private fun wake() {
        countdownStartedAtMs = null
        restingPointer = null
        shade?.fadeOut()
    }

    private fun tick() {
        val window = owner ?: return
        if (shutdownIssued) return
        val settings = ScreensaverSettingsRepository.snapshot()
        val windowShowing = window.isShowing &&
            ((window as? Frame)?.extendedState ?: 0) and Frame.ICONIFIED == 0
        val suppressedByPlayback = playerActive && !settings.activeDuringPlayback
        if (!settings.anyActionEnabled || !windowShowing || suppressedByPlayback) {
            wake()
            return
        }

        val idleMs = idleMs()
        val now = System.currentTimeMillis()
        // Sampled once per tick so both decisions see the same answer. Watching is idle to the
        // timer, so the player gets its own, longer delays.
        val inPlayer = playerActive

        val wantShutdown = settings.shutdownEnabled &&
            idleMs >= settings.shutdownDelayMinutes(inPlayer) * 60_000L &&
            isForeground(window)
        if (wantShutdown) {
            if (countdownStartedAtMs == null) {
                log.i { "idle ${idleMs / 1000}s with the window in front; shutdown countdown started" }
                countdownStartedAtMs = now
            }
        } else {
            countdownStartedAtMs = null
        }
        val countdownRemainingSeconds = countdownStartedAtMs?.let { startedAt ->
            SCREENSAVER_SHUTDOWN_COUNTDOWN_SECONDS - ((now - startedAt) / 1_000L).toInt()
        }
        if (countdownRemainingSeconds != null && countdownRemainingSeconds <= 0) {
            performShutdown()
            return
        }

        val wantDim = settings.enabled && idleMs >= settings.dimDelayMinutes(inPlayer) * 60_000L
        if (!wantDim && countdownRemainingSeconds == null) {
            shade?.fadeOut()
            return
        }
        val dimOpacity = settings.dimPercent / 100f
        val targetOpacity = if (countdownRemainingSeconds != null) max(dimOpacity, CountdownMinOpacity) else dimOpacity
        val shade = shade ?: Shade(window).also { shade = it }
        if (!shade.isVisible) {
            log.i {
                "idle ${idleMs / 1000}s; dimming to ${settings.dimPercent}%" +
                    (if (inPlayer) " (player open)" else "")
            }
            restingPointer = MouseInfo.getPointerInfo()?.location
        }
        shade.show(targetOpacity, countdownRemainingSeconds)
    }

    /**
     * Foreground per Windows when the bridge is there (see [NativePlayerBridge.isForegroundProcess]);
     * AWT's own notion otherwise.
     */
    private fun isForeground(window: Window): Boolean {
        if (nativeIdleUnavailable) return window.isActive
        return runCatching { NativePlayerBridge.isForegroundProcess() }.getOrElse { window.isActive }
    }

    /**
     * The shorter of the system's idle time and the controller's: Windows does not see gamepad
     * input, and a pad in someone's hands is not idle. Falls back to the AWT clock when the
     * bridge is unavailable, which then simply cannot see input the native player swallowed.
     */
    private fun idleMs(): Long {
        val systemIdle = systemIdleMs() ?: (System.currentTimeMillis() - lastAwtInputAtMs).coerceAtLeast(0L)
        val padIdle = GamepadInput.idleMs() ?: return systemIdle
        return min(systemIdle, padIdle)
    }

    private fun systemIdleMs(): Long? {
        if (nativeIdleUnavailable) return null
        val idle = runCatching { NativePlayerBridge.systemIdleMs() }
            .onFailure { log.w(it) { "native idle probe unavailable; falling back to in-app input events" } }
            .getOrNull()
        if (idle == null || idle < 0L) {
            nativeIdleUnavailable = true
            return null
        }
        return idle
    }

    private fun performShutdown() {
        shutdownIssued = true
        countdownStartedAtMs = null
        val systemRoot = System.getenv("SystemRoot")?.takeIf { it.isNotBlank() } ?: "C:\\Windows"
        val command = listOf("$systemRoot\\System32\\shutdown.exe", "/s", "/f", "/t", "0")
        log.i { "idle shutdown: ${command.joinToString(" ")}" }
        val started = runCatching { ProcessBuilder(command).start() }
            .onFailure { log.e(it) { "shutdown command failed to start" } }
            .isSuccess
        if (!started) {
            // Nothing is going to turn the PC off; behave as if the countdown was cancelled and
            // do not try again this session — the failure is not going to fix itself.
            shade?.fadeOut()
            return
        }
        // Leave through the normal exit so pending cache writes and watch progress are flushed
        // before Windows gets round to killing the process.
        runCatching { exitApplication() }
    }

    /**
     * The shade window. Black, owned by the app frame, non-focusable so keys keep going to the app,
     * sized to the owner and re-sized with it. Mouse input over it is the wake signal, so nothing
     * is done to make it click-through.
     */
    private class Shade(private val owner: Window) : JWindow(owner) {
        private var currentOpacity = 0f
        private var targetOpacity = 0f
        private var countdownSeconds: Int? = null
        private val fadeTimer = Timer(FadeFrameMs) { stepFade() }.apply { isRepeats = true }

        private val panel = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val seconds = countdownSeconds ?: return
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                val headline = localizedString(Res.string.screensaver_shutdown_countdown, seconds)
                val hint = localizedString(Res.string.screensaver_shutdown_cancel_hint)
                val headlineSize = max(20f, height / 18f)
                val hintSize = max(14f, height / 40f)
                g2.font = Font(Font.SANS_SERIF, Font.BOLD, headlineSize.roundToInt())
                g2.color = Color.WHITE
                val headlineWidth = g2.fontMetrics.stringWidth(headline)
                val baseline = height / 2
                g2.drawString(headline, (width - headlineWidth) / 2, baseline)
                g2.font = Font(Font.SANS_SERIF, Font.PLAIN, hintSize.roundToInt())
                g2.color = Color(255, 255, 255, 170)
                val hintWidth = g2.fontMetrics.stringWidth(hint)
                g2.drawString(hint, (width - hintWidth) / 2, baseline + g2.fontMetrics.height + headlineSize.roundToInt() / 2)
            }
        }

        private val ownerTracker = object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) = syncBounds()
            override fun componentResized(e: ComponentEvent) = syncBounds()
        }

        init {
            focusableWindowState = false
            isAutoRequestFocus = false
            background = Color.BLACK
            panel.background = Color.BLACK
            panel.isOpaque = true
            contentPane = panel
            // Mouse input over the shade reaches the toolkit listener like any other; nothing
            // extra is needed here. What is needed is no arrow appearing over a dimmed film: the
            // pointer is parked over this window now, and it would otherwise show this window's
            // default cursor even where the player had hidden it.
            cursor = Toolkit.getDefaultToolkit().createCustomCursor(
                BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
                Point(0, 0),
                "screensaver-blank",
            )
            owner.addComponentListener(ownerTracker)
            applyOpacity(MinVisibleOpacity)
        }

        fun show(opacity: Float, countdown: Int?) {
            val countdownChanged = countdown != countdownSeconds
            countdownSeconds = countdown
            targetOpacity = opacity.coerceIn(MinVisibleOpacity, 1f)
            if (!isVisible) {
                syncBounds()
                // Hit-testable from the first frame, so the pointer lands on this window and its
                // blank cursor the moment it is shown; see the class comment on the cursor.
                applyOpacity(MinVisibleOpacity)
                isVisible = true
            }
            if (countdownChanged) panel.repaint()
            if (currentOpacity != targetOpacity && !fadeTimer.isRunning) fadeTimer.start()
        }

        fun fadeOut() {
            countdownSeconds = null
            if (!isVisible) return
            targetOpacity = 0f
            if (!fadeTimer.isRunning) fadeTimer.start()
        }

        private fun stepFade() {
            val towards = targetOpacity
            val perFrame = FadeFrameMs.toFloat() / (if (towards > currentOpacity) FadeInMs else FadeOutMs)
            val next = if (towards > currentOpacity) {
                min(towards, currentOpacity + perFrame)
            } else {
                max(towards, currentOpacity - perFrame)
            }
            applyOpacity(next)
            if (next == towards) {
                fadeTimer.stop()
                if (towards == 0f) {
                    isVisible = false
                    // Back to the floor, so the next show does not start from fully hidden.
                    applyOpacity(MinVisibleOpacity)
                }
            }
        }

        private fun applyOpacity(value: Float) {
            currentOpacity = value
            // Translucency is universal on Windows, but a refusal must not take the timer down
            // with it; the shade then simply stays solid black, which is the 100% case anyway.
            runCatching { opacity = value }
        }

        private fun syncBounds() {
            bounds = owner.bounds
        }

        override fun dispose() {
            fadeTimer.stop()
            owner.removeComponentListener(ownerTracker)
            super.dispose()
        }
    }
}
