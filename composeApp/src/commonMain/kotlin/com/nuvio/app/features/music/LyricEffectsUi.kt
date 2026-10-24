package com.nuvio.app.features.music

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Lines light up this long before their stamp, so the eye is on the line as it is sung. */
internal const val LYRIC_LEAD_MS = 250L

/** The colour a song is tinted with: same track, same colour, every time. */
internal fun moodColor(seed: String): Color = Color.hsv(moodHue(seed), 0.62f, 1f)

/**
 * The player reports its position a few times a second; a sweep driven by that alone steps. This
 * carries the position forward on the frame clock between reports (at most 1.5 s, so a stalled
 * stream cannot run away) and snaps to every fresh report. Read it in a draw or layer block and
 * nothing recomposes per frame.
 */
@Composable
internal fun rememberSmoothPositionMs(positionMs: Long, playing: Boolean): State<Long> {
    val smooth = remember { mutableStateOf(positionMs) }
    LaunchedEffect(positionMs, playing) {
        smooth.value = positionMs
        if (!playing) return@LaunchedEffect
        var origin = -1L
        while (true) {
            withFrameNanos { now ->
                if (origin < 0) origin = now
                smooth.value = positionMs + ((now - origin) / 1_000_000L).coerceAtMost(1_500L)
            }
        }
    }
    return smooth
}

/**
 * Synced lyrics with effects: the sung line sweeps like karaoke, each word hops and glows as its turn
 * comes, lines far from the action fall out of focus, a rest becomes breathing dots, and the hook of
 * the song (a line sung three or more times) burns in the mood colour. With [effects] off it is a calm
 * list: lit line, fading neighbours, nothing that costs a frame.
 */
@Composable
internal fun SyncedLyricsFx(
    lines: List<LyricLine>,
    positionMs: Long,
    playing: Boolean,
    effects: Boolean,
    accent: Color,
    onHype: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
) {
    val smooth = rememberSmoothPositionMs(positionMs, playing)
    val active by remember(lines) { derivedStateOf { activeLyricIndex(lines, smooth.value + LYRIC_LEAD_MS) } }
    val chorus = remember(lines) { chorusLineIndices(lines) }
    val listState = rememberLazyListState()

    LaunchedEffect(active, lines) {
        onHype(active in chorus)
        if (active >= 0) {
            val height = listState.layoutInfo.viewportSize.height
            // Item 0 is the intro header, so line n is item n + 1.
            listState.animateScrollToItem(active + 1, scrollOffset = -(height / 3))
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 220.dp),
    ) {
        item(key = "intro") {
            val first = lines.firstOrNull()?.timeMs ?: 0L
            if (active < 0 && first >= 3_000L) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 18.dp)) {
                    BreathingDots(progress = { (smooth.value.toFloat() / first).coerceIn(0f, 1f) }, accent = accent, effects = effects)
                }
            } else {
                Spacer(Modifier.height(1.dp))
            }
        }
        itemsIndexed(lines) { index, line ->
            LyricRow(
                lines = lines,
                index = index,
                line = line,
                active = active,
                smooth = smooth,
                isChorus = index in chorus,
                accent = accent,
                effects = effects,
                onSeek = onSeek,
            )
        }
    }
}

@Composable
private fun LyricRow(
    lines: List<LyricLine>,
    index: Int,
    line: LyricLine,
    active: Int,
    smooth: State<Long>,
    isChorus: Boolean,
    accent: Color,
    effects: Boolean,
    onSeek: (Long) -> Unit,
) {
    val isActive = index == active
    val distance = if (active < 0) 0 else abs(index - active)
    val emphasis = remember(lines, index) { isEmphasisLine(lines, index) }

    val targetAlpha = when {
        active < 0 -> 0.55f
        isActive -> 1f
        index < active -> if (distance == 1) 0.4f else 0.22f
        distance == 1 -> 0.62f
        else -> 0.34f
    }
    val alpha by animateFloatAsState(targetAlpha, tween(350), label = "lineAlpha")
    val targetScale = when {
        isActive && isChorus -> 1.1f
        isActive -> 1.06f
        else -> 0.95f
    }
    val scale by animateFloatAsState(targetScale, spring(dampingRatio = 0.55f, stiffness = 220f), label = "lineScale")
    // Out of focus the further it is from the sung line; the first two neighbours stay readable.
    val blurTarget = if (!effects || isActive || active < 0) 0f else (distance - 1).coerceIn(0, 3) * 1.6f + 0.8f
    val blurDp by animateFloatAsState(blurTarget, tween(450), label = "lineBlur")

    val rowModifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .clickable { onSeek(line.timeMs) }
        .padding(horizontal = 12.dp, vertical = 10.dp)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(0f, 0.5f)
        }
        .then(if (blurDp > 0.05f) Modifier.blur(blurDp.dp) else Modifier)

    Box(rowModifier) {
        when {
            isActive && line.text.isBlank() ->
                BreathingDots(progress = { gapProgress(lines, index, smooth.value) }, accent = accent, effects = effects)

            isActive -> ActiveLine(
                text = line.text,
                progress = { lineProgress(lines, index, smooth.value) },
                accent = accent,
                isChorus = isChorus,
                emphasis = emphasis,
                effects = effects,
            )

            else -> Text(
                text = line.text.ifBlank { "♪" },
                color = (if (isChorus && effects) lerp(Color.White, accent, 0.35f) else Color.White).copy(alpha = alpha),
                fontSize = 30.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** The line being sung: its words sweep, hop and glow in turn. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveLine(
    text: String,
    progress: () -> Float,
    accent: Color,
    isChorus: Boolean,
    emphasis: Boolean,
    effects: Boolean,
) {
    val words = remember(text) { lyricWords(text) }
    val fontSize = if (emphasis) 40.sp else 30.sp
    val style = TextStyle(fontSize = fontSize, lineHeight = fontSize * 1.27f, fontWeight = FontWeight.Bold)
    val lit = if (isChorus && effects) lerp(Color.White, accent, 0.45f) else Color.White
    val glow = when {
        !effects -> 0f
        isChorus || emphasis -> 1f
        else -> 0.6f
    }
    FlowRow(
        modifier = Modifier.graphicsLayer {
            // A held note swells for as long as it is held.
            if (effects && emphasis) {
                val s = 1f + 0.05f * progress()
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
        },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        words.forEach { word ->
            SungWord(word, progress, style, lit, accent, glow, effects)
        }
    }
}

@Composable
private fun SungWord(
    word: LyricWord,
    lineProgress: () -> Float,
    style: TextStyle,
    lit: Color,
    accent: Color,
    glow: Float,
    effects: Boolean,
) {
    Box(
        Modifier.graphicsLayer {
            if (effects) {
                val p = wordProgress(word, lineProgress())
                val bell = sin(PI.toFloat() * p)
                translationY = -7.dp.toPx() * bell
                val s = 1f + 0.09f * bell
                scaleX = s
                scaleY = s
            }
        },
    ) {
        if (glow > 0f) {
            // A blurred copy underneath: the word's halo, brightest as it is sung, a dull ember after.
            Text(
                text = word.text,
                style = style,
                color = accent,
                modifier = Modifier
                    .blur(14.dp, BlurredEdgeTreatment.Unbounded)
                    .graphicsLayer {
                        val p = wordProgress(word, lineProgress())
                        alpha = if (p <= 0f) 0f else glow * (0.3f + 0.7f * sin(PI.toFloat() * p))
                    },
            )
        }
        Text(text = word.text, style = style, color = Color.White.copy(alpha = 0.38f))
        Text(
            text = word.text,
            style = style,
            color = lit,
            modifier = Modifier.drawWithContent {
                val p = wordProgress(word, lineProgress())
                if (p > 0f) clipRect(right = size.width * p) { this@drawWithContent.drawContent() }
            },
        )
    }
}

/** Three dots that fill as a rest runs out, breathing while they wait: "get ready". */
@Composable
private fun BreathingDots(progress: () -> Float, accent: Color, effects: Boolean) {
    val breath = if (effects) {
        val transition = rememberInfiniteTransition(label = "breath")
        transition.animateFloat(
            initialValue = 0.88f,
            targetValue = 1.12f,
            animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
            label = "breathScale",
        )
    } else {
        null
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 6.dp).graphicsLayer {
            // The last stretch swells and leaves, a beat before the next line takes the stage.
            val p = progress()
            val leave = ((p - 0.9f) / 0.1f).coerceIn(0f, 1f)
            val b = breath?.value ?: 1f
            scaleX = b * (1f + 0.35f * leave)
            scaleY = b * (1f + 0.35f * leave)
            alpha = 1f - leave
            transformOrigin = TransformOrigin(0f, 0.5f)
        },
    ) {
        repeat(3) { i ->
            Box(
                Modifier
                    .size(14.dp)
                    .graphicsLayer {
                        val lit = (progress() * 3f - i).coerceIn(0f, 1f)
                        alpha = 0.25f + 0.75f * lit
                        val s = 0.8f + 0.2f * lit
                        scaleX = s
                        scaleY = s
                    }
                    .background(lerp(Color.White, accent, 0.4f), CircleShape),
            )
        }
    }
}

/**
 * Slow colour fields drifting behind the words, in the song's mood colour. They flare while the hook
 * is being sung ([hype]) and settle between.
 */
@Composable
internal fun LyricAurora(accent: Color, hype: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "aurora")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(52_000, easing = LinearEasing)),
        label = "auroraPhase",
    )
    val energy by animateFloatAsState(if (hype) 1f else 0.5f, tween(1400), label = "auroraEnergy")
    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val radius = max(w, h) * 0.6f
        // Three blobs on slightly different orbits; hue offsets keep it from being one flat wash.
        val blobs = listOf(Triple(0f, 1.0f, 0.0f), Triple(0.9f, -1.0f, 2.1f), Triple(-0.9f, 2.0f, 4.2f))
        for ((hueShift, speed, offset) in blobs) {
            val center = Offset(
                x = w * (0.5f + 0.38f * cos(phase * speed + offset)),
                y = h * (0.5f + 0.32f * sin(phase * speed * 0.8f + offset)),
            )
            val color = shiftHue(accent, hueShift * 40f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = 0.26f * energy), Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }
    }
}

private fun shiftHue(color: Color, degrees: Float): Color {
    if (degrees == 0f) return color
    // The mood colour is built from a hue, so rebuild it from the neighbouring one.
    val r = color.red
    val g = color.green
    val b = color.blue
    val maxC = max(r, max(g, b))
    val minC = minOf(r, minOf(g, b))
    val delta = maxC - minC
    val hue = when {
        delta == 0f -> 0f
        maxC == r -> 60f * (((g - b) / delta) % 6f)
        maxC == g -> 60f * ((b - r) / delta + 2f)
        else -> 60f * ((r - g) / delta + 4f)
    }.let { if (it < 0f) it + 360f else it }
    val saturation = if (maxC == 0f) 0f else delta / maxC
    return Color.hsv(((hue + degrees) % 360f + 360f) % 360f, saturation, maxC)
}
