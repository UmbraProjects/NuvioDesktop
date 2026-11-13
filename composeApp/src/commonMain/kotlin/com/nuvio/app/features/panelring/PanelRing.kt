package com.nuvio.app.features.panelring

import com.nuvio.app.features.navbar.NavBarEntry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The panel ring (Nov 13 2026): one binding opens a circle of every panel in the navigation, and
 * aiming at one switches to it. Inspired by the Opie radial menu for World of Warcraft: tiles in a
 * circle, a ring in the middle, and a pointer from the ring towards the slot being aimed at.
 *
 * Keyboard (R by default): the slots carry 1-0, a number switches; holding R and moving the mouse
 * also aims. Mouse: hold the middle button, drag towards a panel, let go. Controller: hold LB, tilt
 * either stick, let go (a tap of LB keeps its own action). The input side is desktop-only
 * (desktopMain features/panelring); this file is the state they share and the maths, kept pure for
 * PanelRingMathTest.
 */

/** One place the ring can go: an entry of the navigation, or Settings, which always closes it. */
sealed interface PanelRingSlot {
    data class Nav(val entry: NavBarEntry) : PanelRingSlot
    data object Settings : PanelRingSlot
}

/** The ring's slots: the navigation's entries as they show now, in its order, then Settings. */
fun panelRingSlots(navEntries: List<NavBarEntry>): List<PanelRingSlot> =
    navEntries.map { PanelRingSlot.Nav(it) } + PanelRingSlot.Settings

/** What opened the ring. It decides what closes it and whether the slots show numbers. */
enum class PanelRingSource { Keyboard, Mouse, Gamepad }

/**
 * An open ring. [hovered] is the slot aimed at, or null while the aim is in the middle.
 * [anchorX]/[anchorY] place the centre in window points (AWT units) for a ring opened by the mouse;
 * null centres it in the window.
 */
data class PanelRingState(
    val source: PanelRingSource,
    val hovered: Int? = null,
    val anchorX: Float? = null,
    val anchorY: Float? = null,
)

/** Ring size, a setting; the radius the tiles sit on and the tile edge, in dp. */
enum class PanelRingSize(val key: String, val radiusDp: Int, val tileDp: Int) {
    Small("small", 150, 60),
    Medium("medium", 200, 76),
    Large("large", 260, 92),
    ;

    companion object {
        fun fromKey(key: String?): PanelRingSize = entries.firstOrNull { it.key == key } ?: Medium
    }
}

/**
 * The open ring, shared by the input listeners (AWT thread and the pad's poll thread) and the
 * overlay that draws it. Every write goes through a StateFlow, so either thread may call in.
 */
object PanelRingController {
    private val _state = MutableStateFlow<PanelRingState?>(null)
    val state: StateFlow<PanelRingState?> = _state.asStateFlow()

    private val _selections = MutableSharedFlow<PanelRingSlot>(extraBufferCapacity = 4)

    /** Slots chosen; App collects these and switches panel the way a click on the bar does. */
    val selections: SharedFlow<PanelRingSlot> = _selections.asSharedFlow()

    /** The slots as drawn, published by the overlay so the pad thread can aim without Compose. */
    @Volatile
    var slots: List<PanelRingSlot> = emptyList()

    val isOpen: Boolean get() = _state.value != null

    fun open(source: PanelRingSource, anchorX: Float? = null, anchorY: Float? = null) {
        if (slots.isEmpty()) return
        _state.value = PanelRingState(source = source, anchorX = anchorX, anchorY = anchorY)
    }

    fun aim(index: Int?) {
        val current = _state.value ?: return
        val clamped = index?.takeIf { it in slots.indices }
        if (current.hovered != clamped) _state.value = current.copy(hovered = clamped)
    }

    /** Moves the aim one slot round, for arrow keys and the D-pad. */
    fun step(delta: Int) {
        val current = _state.value ?: return
        val count = slots.size
        if (count == 0) return
        val from = current.hovered ?: if (delta > 0) -1 else 0
        aim(((from + delta) % count + count) % count)
    }

    /** Switches to slot [index] and closes. */
    fun choose(index: Int) {
        val slot = slots.getOrNull(index) ?: return
        _state.value = null
        _selections.tryEmit(slot)
    }

    /** Switches to the aimed slot if there is one, then closes either way. True if it switched. */
    fun commit(): Boolean {
        val index = _state.value?.hovered
        if (index == null) {
            close()
            return false
        }
        choose(index)
        return true
    }

    fun close() {
        _state.value = null
    }
}

/** Number shown on slot [index] when the ring is opened from the keyboard: 1-9, then 0; none past ten. */
fun panelRingNumberLabel(index: Int): String? = when {
    index in 0..8 -> (index + 1).toString()
    index == 9 -> "0"
    else -> null
}

/** The slot a number key picks: 1 is the first slot, 0 the tenth. */
fun panelRingIndexForDigit(digit: Int): Int? = when (digit) {
    in 1..9 -> digit - 1
    0 -> 9
    else -> null
}

/**
 * Angle-to-slot maths. Screen coordinates throughout: x to the right, y DOWN, angles in degrees
 * measured clockwise from straight up, so slot 0 sits at twelve o'clock and the rest follow
 * clockwise in navigation order. A stick (y up) flips its y before calling in.
 */
object PanelRingMath {
    /** Extra degrees the aimed slot keeps past its own sector edge, so the aim does not flicker on a boundary. */
    const val DEFAULT_HYSTERESIS_DEGREES = 8f

    fun slotAngleDegrees(index: Int, count: Int): Float = if (count <= 0) 0f else 360f * index / count

    /** Unit offset (x, y-down) of slot [index]'s centre from the ring's centre. */
    fun slotUnitOffset(index: Int, count: Int): Pair<Float, Float> {
        val radians = slotAngleDegrees(index, count) * PI / 180.0
        return sin(radians).toFloat() to -cos(radians).toFloat()
    }

    /** Direction of ([dx], [dy]) in degrees clockwise from up, in [0, 360). */
    fun angleDegrees(dx: Float, dy: Float): Float {
        val degrees = (atan2(dx.toDouble(), -dy.toDouble()) * 180.0 / PI).toFloat()
        return if (degrees < 0f) degrees + 360f else degrees
    }

    /** Smallest angle between two directions, 0..180. */
    fun angularDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    /**
     * The slot ([dx], [dy]) points at, or null inside [deadZone] (same units as the vector).
     * Snaps to the nearest slot centre; [current] keeps its slot until the aim is
     * [hysteresisDegrees] past the sector edge.
     */
    fun slotFor(
        dx: Float,
        dy: Float,
        count: Int,
        deadZone: Float,
        current: Int? = null,
        hysteresisDegrees: Float = DEFAULT_HYSTERESIS_DEGREES,
    ): Int? {
        if (count <= 0) return null
        if (hypot(dx, dy) < deadZone) return null
        if (count == 1) return 0
        val angle = angleDegrees(dx, dy)
        val sector = 360f / count
        if (current != null && current in 0 until count) {
            val keep = sector / 2f + hysteresisDegrees.coerceAtMost(sector / 2f)
            if (angularDistance(angle, slotAngleDegrees(current, count)) <= keep) return current
        }
        return (angle / sector).roundToInt() % count
    }
}
