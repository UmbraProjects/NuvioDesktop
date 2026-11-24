package com.nuvio.app.features.input

import com.nuvio.app.features.remote.PhoneRemote
import com.nuvio.app.features.remote.PhoneRemotePointer
import com.nuvio.app.features.remote.PointerButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.hypot
import kotlin.math.pow

/**
 * The controller as a mouse: holding the cursor button (L3 by default, GamepadSettingsRepository.cursorButton) unlocks
 * it, and the left stick then moves the real Windows pointer, through the phone remote's mover so aim assist and its
 * strength apply the same way. A clicks (held, it drags), X right-clicks, the right stick scrolls (sideways too).
 * Holding or tapping the button again locks it, and so does the D-pad, which goes back to moving focus as usual.
 *
 * For the odd screen focus does not reach (a web page, a game launcher, a stray dialog); arrow navigation stays the
 * way around the app. Driven from GamepadInput's poll thread.
 */
internal object GamepadCursor {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()
    val isActive: Boolean get() = _active.value

    private var lastTickNanos = 0L
    private var scrollRestY = 0.0
    private var scrollRestX = 0.0
    private var leftHeld = false

    fun unlock() {
        if (isActive) return
        PhoneRemote.ensureLoaded() // aim assist strength
        lastTickNanos = 0L
        scrollRestX = 0.0
        scrollRestY = 0.0
        _active.value = true
    }

    fun lock() {
        if (!isActive) return
        setLeft(false)
        _active.value = false
    }

    fun toggle() = if (isActive) lock() else unlock()

    /** A held: the left button stays down while A does, so a drag works. */
    fun setLeft(down: Boolean) {
        if (down == leftHeld) return
        leftHeld = down
        if (down) PhoneRemotePointer.press(PointerButton.Left) else PhoneRemotePointer.release(PointerButton.Left)
    }

    fun rightClick() = PhoneRemotePointer.click(PointerButton.Right)

    /**
     * One poll tick: moves by the left stick and scrolls by the right one. Sticks are XInput's -1..1, y up. Returns
     * whether either stick was in use, which counts as the pad being used.
     */
    fun tick(leftX: Float, leftY: Float, rightX: Float, rightY: Float, nowNanos: Long = System.nanoTime()): Boolean {
        val seconds = if (lastTickNanos == 0L) 0.016 else ((nowNanos - lastTickNanos) / 1e9).coerceIn(0.001, 0.05)
        lastTickNanos = nowNanos
        val (vx, vy) = pointerVelocity(leftX, leftY, GamepadSettingsRepository.cursorSpeed.maxPxPerSecond)
        val moving = vx != 0.0 || vy != 0.0
        if (moving) PhoneRemotePointer.moveBy1080p(vx * seconds, vy * seconds)

        val (sx, sy) = scrollRate(rightX, rightY)
        val scrolling = sx != 0.0 || sy != 0.0
        if (scrolling) {
            scrollRestY += sy * seconds
            scrollRestX += sx * seconds
            val notchesY = scrollRestY.toInt()
            val notchesX = scrollRestX.toInt()
            scrollRestY -= notchesY
            scrollRestX -= notchesX
            if (notchesY != 0) PhoneRemotePointer.scroll(notchesY)
            if (notchesX != 0) PhoneRemotePointer.scrollSideways(notchesX)
        } else {
            scrollRestX = 0.0
            scrollRestY = 0.0
        }
        return moving || scrolling
    }

    /** Below this tilt the stick is at rest (sticks rarely centre exactly). */
    const val MOVE_DEAD_ZONE = 0.14f

    /** Scrolling wants a firmer push, so a thumb resting on the right stick does not scroll. */
    const val SCROLL_DEAD_ZONE = 0.3f

    /** The curve's power: low tilts are fine aiming, the speed builds toward full tilt. */
    const val SPEED_CURVE = 2.2

    /** Wheel notches a second at full tilt. */
    const val MAX_NOTCHES_PER_SECOND = 14.0

    /**
     * Pointer velocity in pixels a second (1080p width, screen y down) for a stick at ([x], [y]) (XInput, y up).
     * Radial dead zone, then a power curve on the tilt beyond it; the direction is kept as it is.
     */
    fun pointerVelocity(x: Float, y: Float, maxPxPerSecond: Double): Pair<Double, Double> {
        val tilt = curved(x, y, MOVE_DEAD_ZONE, SPEED_CURVE) ?: return 0.0 to 0.0
        val magnitude = hypot(x, y).toDouble()
        val speed = maxPxPerSecond * tilt
        return (x / magnitude * speed) to (-y / magnitude * speed)
    }

    /** Wheel notches a second, (sideways, down) positive right and down, for the right stick. */
    fun scrollRate(x: Float, y: Float): Pair<Double, Double> {
        val tilt = curved(x, y, SCROLL_DEAD_ZONE, 1.5) ?: return 0.0 to 0.0
        val magnitude = hypot(x, y).toDouble()
        val rate = MAX_NOTCHES_PER_SECOND * tilt
        return (x / magnitude * rate) to (-y / magnitude * rate)
    }

    /** 0..1 past the dead zone, curved; null inside it. */
    private fun curved(x: Float, y: Float, deadZone: Float, power: Double): Double? {
        val magnitude = hypot(x, y).coerceAtMost(1f)
        if (magnitude <= deadZone) return null
        val past = ((magnitude - deadZone) / (1f - deadZone)).toDouble()
        return past.pow(power)
    }
}

/**
 * The cursor button's press: held [holdMs] it toggles the cursor ([Result.Hold], once); let go sooner and it was an
 * ordinary press ([Result.Tap]), sent on the release. Pure, for the test.
 */
internal class CursorHold(private val holdMs: Long = 450) {
    enum class Result { None, Tap, Hold }

    private var pressedAt = -1L
    private var held = false

    val pending: Boolean get() = pressedAt >= 0

    fun update(down: Boolean, now: Long): Result {
        if (down) {
            if (pressedAt < 0) {
                pressedAt = now
                held = false
            }
            if (!held && now - pressedAt >= holdMs) {
                held = true
                return Result.Hold
            }
            return Result.None
        }
        if (pressedAt < 0) return Result.None
        pressedAt = -1L
        return if (held) Result.None else Result.Tap
    }
}
