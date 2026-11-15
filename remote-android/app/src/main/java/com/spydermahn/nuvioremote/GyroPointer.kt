package com.spydermahn.nuvioremote

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.UnfoldMore
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
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
 * The pointer pad: the gyro moves the TV's cursor while this is on screen, a drag moves it too (and does it alone on a
 * phone without a gyroscope), a tap clicks, a long press right-clicks, and the strip on the right scrolls.
 */
@Composable
fun PointerPad(viewModel: RemoteViewModel, speed: PointerSpeed, compact: Boolean, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val sensors = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val gyroscope = remember { sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) }
    val gravitySensor = remember { sensors.getDefaultSensor(Sensor.TYPE_GRAVITY) }
    var gyroOn by remember { mutableStateOf(gyroscope != null) }
    val shaper = remember { GyroShaper() }
    shaper.speed = speed
    val vm by rememberUpdatedState(viewModel)

    // Only while the app is in front: a phone in a pocket must not wander the cursor.
    LifecycleResumeEffect(gyroOn, gyroscope) {
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
                        if (dx != 0 || dy != 0) vm.point(dx, dy)
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (gyroOn && gyroscope != null) {
            shaper.reset()
            gravitySensor?.let { sensors.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
            sensors.registerListener(listener, gyroscope, SensorManager.SENSOR_DELAY_GAME)
        }
        onPauseOrDispose { sensors.unregisterListener(listener) }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // The surface: tap, long press, drag.
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .glass(RoundedCornerShape(18.dp), fill = Color.White.copy(alpha = 0.03f))
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                vm.click("left")
                            },
                            onLongPress = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                vm.click("right")
                            },
                        )
                    }
                    .pointerInput(speed) {
                        // A drag the width of the pad moves the cursor about six tenths of the TV's width.
                        val unitsPerPx = DRAG_SHARE * POINT_UNITS_PER_WIDTH / size.width.coerceAtLeast(1) * speed.factor
                        var restX = 0f
                        var restY = 0f
                        detectDragGestures(onDragStart = {
                            restX = 0f
                            restY = 0f
                        }) { change, drag ->
                            change.consume()
                            restX += drag.x * unitsPerPx
                            restY += drag.y * unitsPerPx
                            val x = restX.toInt()
                            val y = restY.toInt()
                            restX -= x
                            restY -= y
                            if (x != 0 || y != 0) vm.point(x, y)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!compact) Icon(Icons.Rounded.Mouse, contentDescription = null, tint = Color.White.copy(alpha = 0.28f), modifier = Modifier.size(44.dp))
                    Text(
                        when {
                            gyroscope == null -> "Drag to move"
                            gyroOn -> "Point at the TV"
                            else -> "Drag to move"
                        },
                        color = Color(0xFFC9CDD2),
                        fontSize = if (compact) 14.sp else 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (gyroscope == null) "No gyroscope on this phone · tap to click, hold for right-click"
                        else "Tap to click · hold for right-click",
                        color = g.textMuted,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
            // The scroll strip: every step of a drag is one notch of the wheel, down for down.
            val stepPx = with(density) { SCROLL_STEP.toPx() }
            Box(
                Modifier
                    .width(if (compact) 40.dp else 48.dp)
                    .fillMaxHeight()
                    .glass(RoundedCornerShape(18.dp), fill = Color.White.copy(alpha = 0.03f))
                    .pointerInput(Unit) {
                        var rest = 0f
                        detectDragGestures(onDragStart = { rest = 0f }) { change, drag ->
                            change.consume()
                            rest += drag.y
                            val notches = (rest / stepPx).toInt()
                            if (notches != 0) {
                                rest -= notches * stepPx
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                vm.scroll(notches)
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.UnfoldMore, contentDescription = "Scroll", tint = g.textMuted, modifier = Modifier.size(24.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            GlassTag("Centre", icon = Icons.Rounded.CenterFocusStrong, onClick = {
                shaper.reset()
                vm.pointCentre()
            })
            if (gyroscope != null) {
                GlassTag(if (gyroOn) "Gyro on" else "Gyro off", icon = Icons.Rounded.ScreenRotation, accent = gyroOn, onClick = { gyroOn = !gyroOn })
            }
            GlassTag(speed.label, icon = Icons.Rounded.Speed, onClick = { vm.setPointerSpeed(speed.next()) })
        }
    }
}

private const val DRAG_SHARE = 0.6f
private val SCROLL_STEP = 26.dp
