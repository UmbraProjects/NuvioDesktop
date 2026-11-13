package com.spydermahn.nuvioremote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The phone's version of Nuvio's glass look on the TV (the album page, the Shelf): a near-black ground with soft
 * glows of colour, translucent panels with hairline edges, small uppercase labels in the accent colour with outlined
 * pills beside them, stat tiles, round glass keys that tint when active, and a pill tab bar. The colours are the
 * TV's theme, sent by the PC (RemoteTheme); everything here reads them from LocalGlass.
 */

/** Nuvio's typeface on the TV, JetBrains Sans, bundled in res/font. */
val NuvioSans = FontFamily(
    Font(R.font.jetbrains_sans_regular, FontWeight.Normal),
    Font(R.font.jetbrains_sans_semibold, FontWeight.SemiBold),
    Font(R.font.jetbrains_sans_bold, FontWeight.Bold),
)

/** The display face for what is playing (the TV's album title is a bold serif). */
val DisplaySerif: FontFamily = FontFamily.Serif

@Immutable
data class GlassColors(
    val accent: Color,
    /** The second stop of a custom gradient theme, used for the second glow; else a warm counter-colour. */
    val glow: Color,
    val ground: Color,
) {
    val text = Color(0xFFF5F7F8)
    val textSecondary = Color(0xFFA3A9B2)
    val textMuted = Color(0xFF8A9099)
    val glassFill = Color.White.copy(alpha = 0.045f)
    val glassEdge = Color.White.copy(alpha = 0.09f)
    val tagEdge = Color.White.copy(alpha = 0.22f)
    val track = Color.White.copy(alpha = 0.14f)
    val tint = accent.copy(alpha = 0.22f)
    val tintEdge = accent.copy(alpha = 0.53f)
    val success = Color(0xFF6FD08C)
    val warning = Color(0xFFF2C14E)
    val danger = Color(0xFFFF7A6B)

    companion object {
        fun of(theme: RemoteTheme): GlassColors {
            val ground = Color(theme.background)
            // The TV's grounds are all near-black; a light custom ground would wash the glass out, so it is dimmed.
            val safeGround = if (ground.red + ground.green + ground.blue > 0.6f) Color(0xFF0B0B10) else ground
            return GlassColors(
                accent = Color(theme.accent),
                glow = theme.accentEnd?.let(::Color) ?: Color(0xFF7A2E3A),
                ground = Color(
                    red = safeGround.red * 0.8f,
                    green = safeGround.green * 0.8f,
                    blue = safeGround.blue * 0.8f + 0.01f,
                ),
            )
        }
    }
}

val LocalGlass = staticCompositionLocalOf { GlassColors.of(RemoteTheme.Default) }

/** Material's colours from the glass ones, so the stock pieces (text fields, sheets, progress) follow the theme. */
fun materialColors(glass: GlassColors) = darkColorScheme(
    primary = glass.accent,
    onPrimary = Color.White,
    background = glass.ground,
    surface = glass.ground,
    surfaceVariant = Color(0xFF16171B),
    surfaceContainerLow = Color(0xFF14151A),
    surfaceContainer = Color(0xFF16171B),
    surfaceContainerHigh = Color(0xFF1B1C22),
    onSurface = glass.text,
    onSurfaceVariant = glass.textSecondary,
    outline = Color.White.copy(alpha = 0.22f),
)

// ------------------------------------------------------------------------------------------------ Ground and panels

/**
 * The ground every screen sits on: near-black with two soft glows. Drawn as radial gradients rather than a blur, so it
 * looks the same on every Android version. [glow] overrides the second glow (a film's or an album's colour).
 */
@Composable
fun GlassGround(modifier: Modifier = Modifier, glow: Color? = null, content: @Composable BoxScope.() -> Unit) {
    val g = LocalGlass.current
    val second = glow ?: g.glow
    Box(
        modifier
            .fillMaxSize()
            .background(g.ground)
            .background(
                Brush.radialGradient(
                    0f to g.accent.copy(alpha = 0.26f),
                    1f to Color.Transparent,
                    center = Offset(60f, 40f),
                    radius = 900f,
                ),
            )
            .background(
                Brush.radialGradient(
                    0f to second.copy(alpha = 0.24f),
                    1f to Color.Transparent,
                    center = Offset(1100f, 1700f),
                    radius = 1000f,
                ),
            ),
        content = content,
    )
}

/** A translucent panel with a hairline edge. */
fun Modifier.glass(shape: Shape, fill: Color = Color.White.copy(alpha = 0.045f), edge: Color = Color.White.copy(alpha = 0.09f)): Modifier =
    clip(shape).background(fill, shape).border(1.dp, edge, shape)

// ------------------------------------------------------------------------------------------------ Type

/** "NOW PLAYING", "CONTINUE WATCHING": small, spaced, upper case, in the accent unless told otherwise. */
@Composable
fun GlassLabel(text: String, modifier: Modifier = Modifier, color: Color? = null, icon: ImageVector? = null, size: Int = 12) {
    val g = LocalGlass.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color ?: g.accent, modifier = Modifier.size((size + 2).dp))
            Spacer(Modifier.size(8.dp))
        }
        Text(
            text.uppercase(),
            color = color ?: g.accent,
            fontSize = size.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The serif display title for what is playing. */
@Composable
fun DisplayTitle(text: String, modifier: Modifier = Modifier, size: Int = 26, maxLines: Int = 2) {
    Text(
        text,
        modifier = modifier,
        style = TextStyle(fontFamily = DisplaySerif, fontWeight = FontWeight.Bold, fontSize = size.sp, lineHeight = (size * 1.15f).sp),
        color = LocalGlass.current.text,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** An outlined pill beside a label ("Library", "4K · HDR", "Queue · 26"); [onClick] makes it a small button. */
@Composable
fun GlassTag(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accent: Boolean = false,
    /** A long press (the season tags open the season's menu with it). */
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val g = LocalGlass.current
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(50)
    var m = modifier.clip(shape).border(1.dp, if (accent) g.tintEdge else g.tagEdge, shape)
    if (onClick != null) {
        m = m.pointerInput(onClick, onLongClick) {
            detectTapGestures(
                onTap = { onClick() },
                onLongPress = onLongClick?.let { hold ->
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        hold()
                    }
                },
            )
        }
    }
    Row(m.height(if (onClick != null) 34.dp else 26.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (accent) g.accent else Color(0xFFD5D9DE), modifier = Modifier.size(14.dp))
            Spacer(Modifier.size(6.dp))
        }
        Text(text, color = if (accent) g.accent else Color(0xFFD5D9DE), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A label and a value on glass ("Subtitles / English SDH"); with [onClick] the whole tile is the button. */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, onClick: (() -> Unit)? = null) {
    val g = LocalGlass.current
    val haptics = LocalHapticFeedback.current
    val base = modifier.glass(RoundedCornerShape(16.dp))
    // With a long press the tap has to wait for the finger to lift (to tell the two apart); without, it fires on touch-down.
    val gesture = when {
        onClick != null && onLongClick != null -> Modifier.pointerInput(onClick, onLongClick) {
            detectTapGestures(
                onTap = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
                onLongPress = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
        }
        onClick != null -> Modifier.pressable(onPress = onClick)
        else -> Modifier
    }
    Column(
        base.then(gesture).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(label, color = g.textSecondary, fontSize = 12.sp)
        Text(value, color = g.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A tap, and with [onHold] a long press too (a light buzz, then [onHold]): the Watch tab's posters, cards and episode
 * rows open their menu this way, like a long press on the TV.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.tapOrHold(onHold: (() -> Unit)?, onTap: () -> Unit): Modifier {
    if (onHold == null) return clickable(onClick = onTap)
    val haptics = LocalHapticFeedback.current
    return combinedClickable(
        onClick = onTap,
        onLongClickLabel = "More",
        onLongClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onHold()
        },
    )
}

// ------------------------------------------------------------------------------------------------ Keys

/**
 * Fires on touch-down (not release, so the remote feels immediate) with a light tick, and with [repeat] keeps firing
 * while held, the way a TV remote's arrows do.
 */
@Composable
fun Modifier.pressable(repeat: Boolean = false, onPress: () -> Unit): Modifier {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val press by rememberUpdatedState(onPress)
    return this.pointerInput(repeat) {
        detectTapGestures(onPress = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            press()
            var repeater: Job? = null
            if (repeat) {
                repeater = scope.launch {
                    delay(400)
                    while (true) {
                        press()
                        delay(110)
                    }
                }
            }
            tryAwaitRelease()
            repeater?.cancel()
        })
    }
}

/** A round glass key with its label under it. [active] tints it in the accent, as the TV's selected buttons are. */
@Composable
fun GlassKey(
    icon: ImageVector,
    label: String?,
    modifier: Modifier = Modifier,
    size: Dp = 60.dp,
    active: Boolean = false,
    repeat: Boolean = false,
    onPress: () -> Unit,
) {
    val g = LocalGlass.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(size)
                .glass(CircleShape, fill = if (active) g.tint else Color.White.copy(alpha = 0.06f), edge = if (active) g.tintEdge else g.glassEdge)
                .pressable(repeat = repeat, onPress = onPress),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = g.text, modifier = Modifier.size(size * 0.42f))
        }
        if (label != null) {
            Spacer(Modifier.height(7.dp))
            Text(label, color = g.textSecondary, fontSize = 12.sp, maxLines = 1)
        }
    }
}

// ------------------------------------------------------------------------------------------------ Tabs

/** Nuvio's pill tab bar: one rounded glass track, the chosen tab a tinted pill inside it. */
@Composable
fun <T> PillTabs(
    items: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 46.dp,
    icon: ((T) -> ImageVector?)? = null,
) {
    val g = LocalGlass.current
    Row(modifier.fillMaxWidth().glass(RoundedCornerShape(50)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEach { item ->
            val on = item == selected
            Row(
                Modifier
                    .weight(1f)
                    .height(height)
                    .clip(RoundedCornerShape(50))
                    .background(if (on) g.tint else Color.Transparent)
                    .pointerInput(item) { detectTapGestures(onTap = { onSelect(item) }) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val vector = if (on) icon?.invoke(item) else null
                if (vector != null) {
                    Icon(vector, contentDescription = null, tint = g.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                }
                Text(
                    label(item),
                    color = if (on) g.accent else Color(0xFFC9CDD2),
                    fontSize = 14.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ Seek bar

/**
 * A thin seek bar with a small round marker, as on the TV. Tap or drag anywhere on it; [onScrub] follows the finger and
 * [onScrubEnd] runs on release, which is when the PC is told to seek.
 */
@Composable
fun SeekBar(fraction: Float, onScrub: (Float) -> Unit, onScrubEnd: () -> Unit, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    val scrub by rememberUpdatedState(onScrub)
    val end by rememberUpdatedState(onScrubEnd)
    val markerRadius = 7.dp
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(22.dp)
            .pointerInput(Unit) {
                val inset = markerRadius.toPx()
                fun at(x: Float) = ((x - inset) / (size.width - 2 * inset)).coerceIn(0f, 1f)
                detectTapGestures(onPress = { offset ->
                    scrub(at(offset.x))
                    // A press that turns into a drag is cancelled here; the drag's own end does the seek then.
                    if (tryAwaitRelease()) end()
                })
            }
            .pointerInput(Unit) {
                val inset = markerRadius.toPx()
                fun at(x: Float) = ((x - inset) / (size.width - 2 * inset)).coerceIn(0f, 1f)
                detectHorizontalDragGestures(
                    onDragStart = { offset -> scrub(at(offset.x)) },
                    onDragEnd = { end() },
                    onDragCancel = { end() },
                ) { change, _ ->
                    change.consume()
                    scrub(at(change.position.x))
                }
            },
    ) {
        val inset = markerRadius.toPx()
        val y = size.height / 2
        val x = inset + (size.width - 2 * inset) * fraction.coerceIn(0f, 1f)
        val stroke = 4.dp.toPx()
        drawLine(g.track, Offset(inset, y), Offset(size.width - inset, y), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(g.accent, Offset(inset, y), Offset(x, y), strokeWidth = stroke, cap = StrokeCap.Round)
        drawCircle(Color.White, radius = inset, center = Offset(x, y))
    }
}

/** 3:07 or 1:02:09. */
fun formatTime(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / 1000L
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = (total % 60).toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$seconds" else "$minutes:$seconds"
}
