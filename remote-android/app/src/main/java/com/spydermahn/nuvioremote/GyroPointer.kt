package com.spydermahn.nuvioremote

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.hypot
import kotlin.math.sqrt

/** POINT units across the width of the TV's screen. Mirrors `UNITS_PER_WIDTH` in Nuvio's `PhoneRemotePointer.kt`. */
const val POINT_UNITS_PER_WIDTH = 10_000f

/** How fast the pointer moves the cursor; remembered across launches. */
enum class PointerSpeed(val label: String, val factor: Float) {
    Slow("Slow", 0.7f), Normal("Normal", 1f), Fast("Fast", 1.45f);

    fun next(): PointerSpeed = entries[(ordinal + 1) % entries.size]
}

/**
 * The phone's gyro as an air mouse, as a Magic Remote does it. Pure maths, so it is unit-tested apart from Android.
 *
 * Turning the phone left or right is measured around the world's vertical (the gravity sensor says which way that is
 * in the phone), so it moves the cursor sideways whether the phone is held flat like a remote or upright like a phone.
 * Tilting it is measured around the phone's own left-right axis, which is level in both grips.
 */
class GyroShaper(var speed: PointerSpeed = PointerSpeed.Normal) {
    private var smoothX = 0f
    private var smoothY = 0f
    private var restX = 0f
    private var restY = 0f

    /**
     * One gyroscope sample: [gyro] in rad/s around the phone's x, y and z axes, [gravity] the gravity vector in the
     * phone's axes (null when the phone has no gravity sensor, which assumes it is held flat), [dtSeconds] since the
     * last sample. Returns the whole POINT units to send now; fractions wait for the next sample.
     */
    fun step(gyro: FloatArray, gravity: FloatArray?, dtSeconds: Float): Pair<Int, Int> {
        val (yaw, pitch) = screenRates(gyro, gravity)
        val moving = hypot(yaw, pitch) >= DEAD_ZONE
        // Lightly smoothed against the tremor of a hand; a phone at rest settles to zero at once.
        smoothX = if (moving) smoothX * SMOOTHING + yaw * (1 - SMOOTHING) else 0f
        smoothY = if (moving) smoothY * SMOOTHING + pitch * (1 - SMOOTHING) else 0f
        if (!moving) return 0 to 0
        val rate = hypot(smoothX, smoothY)
        // Slow turns are precise, quick ones cross the screen: like mouse acceleration.
        val accel = (ACCEL_BASE + ACCEL_PER_RAD * rate).coerceAtMost(ACCEL_MAX)
        val gain = UNITS_PER_RAD * accel * speed.factor * dtSeconds.coerceIn(0f, MAX_DT)
        restX += smoothX * gain
        restY += smoothY * gain
        val x = restX.toInt()
        val y = restY.toInt()
        restX -= x
        restY -= y
        return x to y
    }

    fun reset() {
        smoothX = 0f
        smoothY = 0f
        restX = 0f
        restY = 0f
    }

    companion object {
        /** Below this (rad/s) the phone counts as still: a gyro's own noise, or a phone lying on the sofa. */
        const val DEAD_ZONE = 0.03f
        const val SMOOTHING = 0.45f
        /** About 30 degrees of turn crosses the screen at a medium pace. */
        const val UNITS_PER_RAD = POINT_UNITS_PER_WIDTH / 0.52f
        const val ACCEL_BASE = 0.55f
        const val ACCEL_PER_RAD = 0.9f
        const val ACCEL_MAX = 2.4f
        /** A late sample after a pause must not throw the cursor across the screen. */
        const val MAX_DT = 0.05f

        /**
         * The phone's turn as the screen sees it, in rad/s: x right, y down. Turning left (anticlockwise seen from
         * above) is a positive rotation about "up", so it is negated to move the cursor left; tilting the top up is a
         * positive rotation about the phone's x axis, and the cursor goes up.
         */
        fun screenRates(gyro: FloatArray, gravity: FloatArray?): Pair<Float, Float> {
            val yawAboutUp = gravity?.let { g ->
                val length = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
                if (length < 1f) null else (gyro[0] * g[0] + gyro[1] * g[1] + gyro[2] * g[2]) / length
            } ?: gyro[2]
            return -yawAboutUp to -gyro[0]
        }
    }
}

/**
 * What the gyro may do while a finger is on the zone. The cursor holds still under a touch, so a tap clicks where the
 * phone pointed and a scroll scrolls what is under the cursor. A finger held still for a moment is a hold: turning the
 * phone then (past [dragStartUnits]) presses the left button and drags, as holding OK does on a Magic Remote, and
 * lifting without turning is a right-click. Plain logic, unit-tested apart from Android.
 */
class PointerGate(private val dragStartUnits: Int = DRAG_START_UNITS) {
    enum class Phase { Free, Touching, Held, Dragging }

    /** What lifting the finger did. */
    enum class Lift { Nothing, RightClick, EndDrag }

    var phase = Phase.Free
        private set
    private var heldX = 0
    private var heldY = 0

    /** A gyro step: sent, dropped (under a touch), or kept until a hold decides whether it is a drag. */
    fun gyro(dx: Int, dy: Int, send: (Int, Int) -> Unit, pressLeft: () -> Unit) {
        when (phase) {
            Phase.Free, Phase.Dragging -> send(dx, dy)
            Phase.Touching -> Unit
            Phase.Held -> {
                heldX += dx
                heldY += dy
                if (hypot(heldX.toFloat(), heldY.toFloat()) >= dragStartUnits) {
                    phase = Phase.Dragging
                    // Pressed where the hold began, then moved by what the turn has covered so far.
                    pressLeft()
                    send(heldX, heldY)
                    heldX = 0
                    heldY = 0
                }
            }
        }
    }

    fun fingerDown() {
        phase = Phase.Touching
    }

    fun hold() {
        phase = Phase.Held
        heldX = 0
        heldY = 0
    }

    fun fingerUp(): Lift {
        val lift = when (phase) {
            Phase.Held -> Lift.RightClick
            Phase.Dragging -> Lift.EndDrag
            else -> Lift.Nothing
        }
        phase = Phase.Free
        heldX = 0
        heldY = 0
        return lift
    }

    companion object {
        /** About 2% of the screen's width: a steady hand holding still never gets there, a deliberate turn soon does. */
        const val DRAG_START_UNITS = 200
    }
}

/**
 * Pointer mode: the phone as the TV's mouse, the whole Remote tab one zone so it works without looking down. It never
 * swaps itself for the What to watch, source or Live TV mirrors, or for the film and music screens; the TV's own
 * screens are driven with the cursor instead.
 *
 * The gyro moves the cursor. On the zone: tap = left click; drag up or down = scroll, left or right = scroll sideways
 * (the direction is fixed by the first movement, so a scroll never wanders into the other one); hold still = right
 * click, or hold and turn the phone = drag; two fingers = Back. Centre, the speed and the way out are tags at the top.
 */
@Composable
fun PointerBody(state: UiState, viewModel: RemoteViewModel, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val sensors = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val gyroscope = remember { sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) }
    val gravitySensor = remember { sensors.getDefaultSensor(Sensor.TYPE_GRAVITY) }
    val shaper = remember { GyroShaper() }
    val gate = remember { PointerGate() }
    shaper.speed = state.pointerSpeed
    val vm by rememberUpdatedState(viewModel)
    var holdShown by remember { mutableStateOf<PointerGate.Phase?>(null) }

    // Only while the app is in front: a phone in a pocket must not wander the cursor. Sensor events and the gesture
    // below both run on the main thread, so the gate needs no locking.
    LifecycleResumeEffect(gyroscope) {
        val gravity = FloatArray(3)
        var haveGravity = false
        var lastNs = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_GRAVITY -> {
                        event.values.copyInto(gravity, endIndex = 3)
                        haveGravity = true
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        val dt = if (lastNs == 0L) 0f else (event.timestamp - lastNs) / 1_000_000_000f
                        lastNs = event.timestamp
                        val (dx, dy) = shaper.step(event.values, gravity.takeIf { haveGravity }, dt)
                        if (dx == 0 && dy == 0) return
                        gate.gyro(dx, dy, send = { x, y -> vm.point(x, y) }, pressLeft = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            vm.press("left")
                            holdShown = PointerGate.Phase.Dragging
                        })
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (gyroscope != null) {
            shaper.reset()
            gravitySensor?.let { sensors.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
            sensors.registerListener(listener, gyroscope, SensorManager.SENSOR_DELAY_GAME)
        }
        onPauseOrDispose {
            sensors.unregisterListener(listener)
            if (gate.fingerUp() == PointerGate.Lift.EndDrag) vm.release("left")
            holdShown = null
        }
    }

    Column(modifier.fillMaxSize().padding(top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassLabel("Pointer", Modifier.weight(1f), icon = Icons.Rounded.Mouse)
            GlassTag("Centre", icon = Icons.Rounded.CenterFocusStrong, onClick = {
                shaper.reset()
                vm.pointCentre()
            })
            GlassTag(state.pointerSpeed.label, icon = Icons.Rounded.Speed, onClick = { vm.setPointerSpeed(state.pointerSpeed.next()) })
            GlassTag(PadMode.Pointer.next().label, onClick = { vm.setPadMode(PadMode.Pointer.next()) })
        }
        if (gyroscope == null) {
            Box(Modifier.weight(1f).fillMaxWidth().glass(RoundedCornerShape(26.dp)), contentAlignment = Alignment.Center) {
                Text(
                    "This phone has no gyroscope, so it cannot point. Use the Touchpad or the D-pad.",
                    color = g.textMuted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
            }
            return@Column
        }
        val stepPx = with(density) { SCROLL_STEP.toPx() }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .glass(RoundedCornerShape(26.dp), fill = Color.White.copy(alpha = 0.03f))
                .pointerInput(Unit) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        gate.fingerDown()
                        var travelX = 0f
                        var travelY = 0f
                        var twoFingers = false
                        // Until HOLD_MS: lifting is a tap, moving past the slop is a scroll, staying still is a hold.
                        val start: ZoneStart? = withTimeoutOrNull(HOLD_MS) {
                            var result: ZoneStart? = null
                            while (result == null) {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } >= 2) twoFingers = true
                                val change = event.changes.firstOrNull { it.id == down.id }
                                result = when {
                                    change == null || !change.pressed -> ZoneStart.Tap
                                    twoFingers -> null
                                    else -> {
                                        val delta = change.positionChange()
                                        travelX += delta.x
                                        travelY += delta.y
                                        if (hypot(travelX, travelY) > slop) ZoneStart.Scroll else null
                                    }
                                }
                            }
                            result
                        }
                        // Back, as a mouse's back button, once every finger is off. Checks the event already in hand
                        // first: when both fingers lift together that was the last event, and waiting for another
                        // one hung the gesture (no Back, and the cursor frozen) until the next touch.
                        suspend fun AwaitPointerEventScope.backOnLift() {
                            while (currentEvent.changes.any { it.pressed }) awaitPointerEvent()
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            vm.press(RemoteKey.Back)
                        }
                        try {
                            when {
                                twoFingers -> backOnLift()
                                start == ZoneStart.Tap -> {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    vm.click("left")
                                }
                                start == ZoneStart.Scroll -> {
                                    val sideways = kotlin.math.abs(travelX) > kotlin.math.abs(travelY)
                                    var rest = if (sideways) travelX else travelY
                                    var scrolled = false
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        // A second finger before anything scrolled: the first one slid a little on
                                        // the way to a two-finger tap. It is Back, not a scroll.
                                        if (!scrolled && event.changes.count { it.pressed } >= 2) {
                                            backOnLift()
                                            break
                                        }
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                        val delta = change.positionChange()
                                        change.consume()
                                        rest += if (sideways) delta.x else delta.y
                                        val notches = (rest / stepPx).toInt()
                                        if (notches != 0) {
                                            rest -= notches * stepPx
                                            scrolled = true
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            if (sideways) vm.scrollSideways(notches) else vm.scroll(notches)
                                        }
                                    }
                                }
                                else -> {
                                    // A hold: a buzz now; lifting decides (right-click, or the end of a drag).
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    gate.hold()
                                    holdShown = PointerGate.Phase.Held
                                    while (true) {
                                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                        change.consume()
                                    }
                                }
                            }
                        } finally {
                            when (gate.fingerUp()) {
                                PointerGate.Lift.RightClick -> {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    vm.click("right")
                                }
                                PointerGate.Lift.EndDrag -> vm.release("left")
                                PointerGate.Lift.Nothing -> Unit
                            }
                            holdShown = null
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Mouse, contentDescription = null, tint = Color.White.copy(alpha = 0.22f), modifier = Modifier.size(52.dp))
                Text(
                    when (holdShown) {
                        PointerGate.Phase.Dragging -> "Dragging · lift to drop"
                        PointerGate.Phase.Held -> "Lift to right-click · turn to drag"
                        else -> "Point at the TV"
                    },
                    color = Color(0xFFC9CDD2),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Tap to click · drag to scroll\nHold to right-click · hold and turn to drag\nTwo fingers to go back",
                    color = g.textMuted,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

private enum class ZoneStart { Tap, Scroll }

/** How long a still finger waits before it is a hold (right-click, or a drag once the phone turns). */
private const val HOLD_MS = 380L
private val SCROLL_STEP = 26.dp
