package com.spydermahn.nuvioremote

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Dark mode, for watching in a dark room: the whole phone goes black and dimmed to Low, Medium or High (5, 15 or 35%; chosen under the hints and kept), the screen stays on, and the remote is
 * gestures anywhere on the glass, with a tick under the thumb for each step.
 *
 * - Tap: play / pause.
 * - Swipe left or right: back or ahead 10 s for each step of travel (a film); the previous or next song (music).
 * - Swipe up or down: volume, a step at a time.
 * - Hold anywhere for a second: leave. Back does not leave, so a stray palm cannot drop you out of it.
 *
 * The hints show for a few seconds on the way in and fade; each gesture flashes what it did, faintly.
 */
@Composable
fun DarkModeScreen(
    playing: NowPlaying?,
    link: Link,
    level: DarkLevel,
    onKey: (RemoteKey) -> Unit,
    onLevel: (DarkLevel) -> Unit,
    onLeave: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val send by rememberUpdatedState(onKey)
    val leave by rememberUpdatedState(onLeave)
    val isMusic = playing?.kind == "music"
    val music by rememberUpdatedState(isMusic)
    val isPlaying by rememberUpdatedState(playing?.isPlaying == true)
    val density = LocalDensity.current
    val seekStepPx = with(density) { SEEK_STEP.toPx() }
    val volumeStepPx = with(density) { VOLUME_STEP.toPx() }
    val songStepPx = with(density) { SONG_STEP.toPx() }

    KeepAwakeAndDim(level)

    var hints by remember { mutableStateOf(true) }
    var hintsShown by remember { mutableIntStateOf(0) }
    LaunchedEffect(hintsShown) {
        hints = true
        delay(HINT_MS)
        hints = false
    }
    BackHandler { hintsShown++ }

    var flash by remember { mutableStateOf<String?>(null) }
    var flashes by remember { mutableIntStateOf(0) }
    LaunchedEffect(flashes) {
        if (flash == null) return@LaunchedEffect
        delay(FLASH_MS)
        flash = null
    }
    fun show(text: String) {
        flash = text
        flashes++
    }

    var holdAt by remember { mutableStateOf<Offset?>(null) }
    val holdProgress = remember { Animatable(0f) }
    LaunchedEffect(holdAt) {
        if (holdAt == null) {
            holdProgress.snapTo(0f)
        } else {
            holdProgress.snapTo(0f)
            holdProgress.animateTo(1f, tween(HOLD_MS.toInt() - HOLD_RING_DELAY_MS.toInt(), delayMillis = HOLD_RING_DELAY_MS.toInt(), easing = LinearEasing))
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    holdAt = down.position
                    var dx = 0f
                    var dy = 0f
                    var moved = false
                    var lifted = false
                    var usedByControl = false
                    // Phase one: until the finger lifts (a tap), moves (a swipe) or stays a second (hold to leave).
                    val decided = withTimeoutOrNull(HOLD_MS) {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                lifted = true
                                // A tap a control used (a brightness pill) is not also play/pause.
                                usedByControl = change.isConsumed
                                break
                            }
                            dx += change.positionChange().x
                            dy += change.positionChange().y
                            if (abs(dx) > viewConfiguration.touchSlop || abs(dy) > viewConfiguration.touchSlop) {
                                moved = true
                                break
                            }
                        }
                        true
                    }
                    holdAt = null
                    when {
                        decided == null -> {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            leave()
                            return@awaitEachGesture
                        }
                        lifted && usedByControl -> return@awaitEachGesture
                        lifted && !moved -> {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            send(RemoteKey.PlayPause)
                            show(if (isPlaying) "Pause" else "Play")
                            return@awaitEachGesture
                        }
                        !moved -> return@awaitEachGesture
                    }
                    // Phase two: a swipe. The first direction travelled decides what it does, for the whole swipe.
                    val horizontal = abs(dx) >= abs(dy)
                    var travel = if (horizontal) dx else dy
                    var songSent = false
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        travel += if (horizontal) change.positionChange().x else change.positionChange().y
                        val step = when {
                            horizontal && music -> songStepPx
                            horizontal -> seekStepPx
                            else -> volumeStepPx
                        }
                        while (abs(travel) >= step) {
                            val forward = travel > 0
                            travel -= if (forward) step else -step
                            if (horizontal && music) {
                                // One song per swipe, however long.
                                if (songSent) continue
                                songSent = true
                                send(if (forward) RemoteKey.Next else RemoteKey.Previous)
                                show(if (forward) "Next song" else "Previous song")
                            } else if (horizontal) {
                                send(if (forward) RemoteKey.SeekForward else RemoteKey.SeekBack)
                                show(if (forward) "+10 s" else "−10 s")
                            } else {
                                // Up the screen is louder.
                                send(if (forward) RemoteKey.VolumeDown else RemoteKey.VolumeUp)
                                show(if (forward) "Volume −" else "Volume +")
                            }
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                }
            },
    ) {
        holdAt?.let { at ->
            val ringColor = Color.White.copy(alpha = 0.40f)
            Canvas(Modifier.fillMaxSize()) {
                val radius = 44.dp.toPx()
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * holdProgress.value,
                    useCenter = false,
                    topLeft = Offset(at.x - radius, at.y - radius),
                    size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }

        flash?.let {
            Text(
                it,
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        AnimatedVisibility(
            visible = hints,
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(1_200)),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp, start = 32.dp, end = 32.dp),
        ) {
            val dim = Color.White.copy(alpha = 0.50f)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("DARK MODE", color = dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
                Text("Tap  ·  play / pause", color = dim, fontSize = 15.sp)
                Text(
                    if (isMusic) "Swipe ← →  ·  previous / next song" else "Swipe ← →  ·  10 s back / ahead",
                    color = dim,
                    fontSize = 15.sp,
                )
                Text("Swipe ↑ ↓  ·  volume", color = dim, fontSize = 15.sp)
                Text("Hold anywhere  ·  leave", color = dim, fontSize = 15.sp)
                // Brightness: Low, Medium or High, kept for next time; tapping one keeps the hints up. Only while the hints show, so a tap on the dark
                // screen is always play/pause.
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DarkLevel.entries.forEach { option ->
                        val chosen = option == level
                        Text(
                            option.label,
                            color = if (chosen) Color.Black else dim,
                            fontSize = 14.sp,
                            fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (chosen) Color.White.copy(alpha = 0.6f) else Color.Transparent)
                                .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(50))
                                .clickable {
                                    onLevel(option)
                                    hintsShown++
                                }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
                when {
                    link != Link.Connected -> Text("Not connected to the PC", color = Color(0x99FF8A80), fontSize = 13.sp, textAlign = TextAlign.Center)
                    playing == null -> Text("Nothing is playing", color = dim, fontSize = 13.sp)
                }
            }
        }

        // A faint dot while the PC is out of reach, so a dead remote is not mistaken for a stuck film.
        if (link != Link.Connected) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp)
                    .size(6.dp)
                    .background(Color(0x88FF5252), androidx.compose.foundation.shape.CircleShape),
            )
        }
    }
}

/** While dark mode shows: the screen stays on, dimmed to [level], with the status and navigation bars hidden. */
@Composable
private fun KeepAwakeAndDim(level: DarkLevel) {
    val view = LocalView.current
    val activity = LocalContext.current as? Activity
    DisposableEffect(view, activity) {
        view.keepScreenOn = true
        val window = activity?.window
        val previousBrightness = window?.attributes?.screenBrightness
        window?.let {
            WindowCompat.getInsetsController(it, view).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            view.keepScreenOn = false
            window?.let {
                it.attributes = it.attributes.apply {
                    screenBrightness = previousBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
                WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    // Apart from the above, so changing the level does not show the bars again for a moment.
    LaunchedEffect(activity, level) {
        activity?.window?.let { it.attributes = it.attributes.apply { screenBrightness = level.brightness } }
    }
}

private val SEEK_STEP = 64.dp
private val VOLUME_STEP = 40.dp
private val SONG_STEP = 72.dp
private const val HOLD_MS = 1_000L
/** The ring only starts after a moment, so a tap does not flash it. */
private const val HOLD_RING_DELAY_MS = 200L
private const val HINT_MS = 4_000L
private const val FLASH_MS = 700L
