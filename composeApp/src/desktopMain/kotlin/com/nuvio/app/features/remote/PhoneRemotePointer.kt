package com.nuvio.app.features.remote

import co.touchlab.kermit.Logger
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent

/**
 * fork:phone-remote pointer - the phone as an air mouse, like a Magic Remote: the phone turns its gyro (or a drag on
 * its touchpad) into POINT steps, and this moves the real Windows cursor by them. The real cursor rather than one drawn
 * by Nuvio, so it works over the Compose screens, the native player's HTML HUD, game mode and any other window alike.
 *
 * Steps are in ten-thousandths of the width of the screen the cursor is on ([UNITS_PER_WIDTH]), so the phone's
 * sensitivity feels the same on a 1080p and a 4K television. Every motion is relative: the phone never knows where the
 * cursor is, and Centre (POINT_CENTRE) is how the two are lined up again.
 */
internal object PhoneRemotePointer {
    private val log = Logger.withTag("PhoneRemotePointer")
    private val accumulator = PointerAccumulator()
    private var robot: Robot? = null
    private var unavailable = false

    @Volatile
    private var lastUseMs = 0L

    /**
     * Whether the phone pointed or clicked lately: the on-screen keyboard opens for a text field clicked this way, as
     * it does for a controller, since the mouse it moved is not a keyboard user's.
     */
    fun usedRecently(withinMs: Long = 60_000L, nowMs: Long = System.currentTimeMillis()): Boolean {
        val last = lastUseMs
        return last != 0L && nowMs - last <= withinMs
    }

    private fun used() {
        lastUseMs = System.currentTimeMillis()
    }

    @Synchronized
    fun move(dx: Int, dy: Int) {
        used()
        moveBy { width -> (dx * width / UNITS_PER_WIDTH) to (dy * width / UNITS_PER_WIDTH) }
    }

    /**
     * A controller's stick (GamepadCursor): a step in pixels of a 1080p-wide screen, scaled to the screen the cursor
     * is on, with the same aim assist and fraction keeping as the phone. Does not count as the phone being used.
     */
    @Synchronized
    fun moveBy1080p(dx: Double, dy: Double) {
        moveBy { width -> (dx * width / 1920.0) to (dy * width / 1920.0) }
    }

    private inline fun moveBy(step: (width: Double) -> Pair<Double, Double>) {
        val robot = robot() ?: return
        val at = MouseInfo.getPointerInfo()?.location ?: return
        val screen = screenAt(at) ?: return
        var (px, py) = step(screen.width.coerceAtLeast(1).toDouble())
        val strength = PhoneRemote.state.value.aimStrength.factor
        if (strength > 0.0) {
            // Speed from this step and the time since the last one; a first step after a pause counts as slow.
            val now = System.nanoTime()
            val seconds = ((now - lastMoveNanos) / 1_000_000_000.0).coerceIn(0.004, 0.25)
            lastMoveNanos = now
            val scale = screen.width / 1920.0
            AimTargets.refresh(at, screen, (AimAssist.PULL_RADIUS_PX * scale).toInt())
            val adjusted = AimAssist.adjust(px, py, at, kotlin.math.hypot(px, py) / seconds, AimTargets.over, AimTargets.near, scale, strength)
            px = adjusted.first
            py = adjusted.second
        }
        val (stepX, stepY) = accumulator.stepPixels(px, py)
        if (stepX == 0 && stepY == 0) return
        robot.mouseMove(at.x + stepX, at.y + stepY)
    }

    private var lastMoveNanos = 0L

    /** The middle of the screen Nuvio's window is on (else the main screen). */
    @Synchronized
    fun centre() {
        val robot = robot() ?: return
        val screen = Frame.getFrames().firstOrNull { it.isShowing }?.graphicsConfiguration?.bounds
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
        accumulator.reset()
        robot.mouseMove(screen.x + screen.width / 2, screen.y + screen.height / 2)
    }

    @Synchronized
    fun click(button: PointerButton) {
        used()
        val robot = robot() ?: return
        if (button in held) release(button)
        robot.mousePress(button.mask)
        robot.mouseRelease(button.mask)
    }

    /** Holds [button] down for a drag, until [release] (or [releaseAll] when the phone goes away). */
    @Synchronized
    fun press(button: PointerButton) {
        used()
        val robot = robot() ?: return
        if (!held.add(button)) return
        robot.mousePress(button.mask)
    }

    @Synchronized
    fun release(button: PointerButton) {
        val robot = robot() ?: return
        if (!held.remove(button)) return
        robot.mouseRelease(button.mask)
    }

    @Synchronized
    fun releaseAll() {
        held.toList().forEach(::release)
    }

    @Synchronized
    fun scroll(notches: Int) {
        robot()?.mouseWheel(notches)
    }

    /**
     * Sideways: Shift with the wheel, which Windows apps and Nuvio's poster rows read as horizontal scrolling (Robot
     * has no horizontal wheel). Positive is right.
     */
    @Synchronized
    fun scrollSideways(notches: Int) {
        val robot = robot() ?: return
        robot.keyPress(KeyEvent.VK_SHIFT)
        try {
            robot.mouseWheel(notches)
        } finally {
            robot.keyRelease(KeyEvent.VK_SHIFT)
        }
    }

    private val held = mutableSetOf<PointerButton>()

    private val PointerButton.mask: Int
        get() = when (this) {
            PointerButton.Left -> InputEvent.BUTTON1_DOWN_MASK
            PointerButton.Right -> InputEvent.BUTTON3_DOWN_MASK
            PointerButton.Middle -> InputEvent.BUTTON2_DOWN_MASK
        }

    private fun screenAt(point: Point): Rectangle? {
        if (GraphicsEnvironment.isHeadless()) return null
        val screens = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }
        return screens.firstOrNull { it.contains(point) } ?: screens.firstOrNull()
    }

    private fun robot(): Robot? {
        robot?.let { return it }
        if (unavailable) return null
        return try {
            Robot().also { robot = it }
        } catch (error: Exception) {
            // Headless, or a desktop that refuses synthetic input: say so once, then ignore the pointer.
            unavailable = true
            log.w(error) { "Phone remote pointer unavailable: the cursor cannot be moved" }
            null
        }
    }
}

/**
 * Turns POINT steps into whole pixels, keeping the fractions: a slow, steady turn of the phone sends many steps worth
 * less than a pixel each, and rounding each one away would make slow pointing stick.
 */
internal class PointerAccumulator {
    private var restX = 0.0
    private var restY = 0.0

    fun step(dx: Int, dy: Int, screenWidth: Int): Pair<Int, Int> {
        val scale = screenWidth.coerceAtLeast(1) / UNITS_PER_WIDTH
        return stepPixels(dx * scale, dy * scale)
    }

    /** Whole pixels of a step already in (fractional) pixels, keeping the remainder. */
    fun stepPixels(px: Double, py: Double): Pair<Int, Int> {
        restX += px
        restY += py
        val x = restX.toInt()
        val y = restY.toInt()
        restX -= x
        restY -= y
        return x to y
    }

    fun reset() {
        restX = 0.0
        restY = 0.0
    }
}

/** POINT units across the width of a screen. Mirrored by `GyroPointer.kt` in the phone app. */
internal const val UNITS_PER_WIDTH = 10_000.0
