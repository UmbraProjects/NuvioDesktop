package com.nuvio.app.features.music

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.library.musicKeyFocusRing
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.permanent_marker
import org.jetbrains.compose.resources.Font
import kotlin.math.pow

/*
 * "Poster" lyrics: the same lyric column as the scrolling list, but one line is the whole show. It is set big in a
 * marker-pen face, centred, and its words land one at a time as they are sung, with the line before and the line after
 * left small and dim either side. The timing maths is here, free of Compose, so it can be tested.
 */

/** How long one word takes to land, from the moment it is sung. */
internal const val POSTER_WORD_LAND_MS = 380L

/** How far (0..1) word [word] has landed on a line [lineDurationMs] long that is [lineProgress] of the way through: 0 = not sung yet, 1 = settled. */
internal fun posterWordArrival(word: LyricWord, lineProgress: Float, lineDurationMs: Long): Float {
    val sinceMs = (lineProgress - word.from) * lineDurationMs.coerceAtLeast(1L)
    return (sinceMs / POSTER_WORD_LAND_MS).coerceIn(0f, 1f)
}

/** Ease-out with an overshoot: runs past 1 and settles back, so a word lands with a thump. */
internal fun backOut(t: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val x = t - 1f
    return 1f + c3 * x * x * x + c1 * x * x
}

/** A steady crooked angle for word [wordIndex] of line [lineIndex], within +-[MAX_TILT] degrees: a tag is never level. */
internal fun posterTiltDegrees(lineIndex: Int, wordIndex: Int): Float {
    val h = (lineIndex * 73856093) xor (wordIndex * 19349663)
    val unit = ((h ushr 3) and 0xFFFF) / 65535f // 0..1
    return (unit * 2f - 1f) * MAX_TILT
}

private const val MAX_TILT = 4.5f

/** How much larger (0.3 = 30%) a word is at the instant it is thrown in, and how far above its place. Kept small so it never covers the word beside it. */
private const val POSTER_LAND_SCALE = 0.3f
private const val POSTER_LAND_DROP_DP = 22f

/** Longer lines are set smaller so the line stays at most about three rows. */
internal fun posterFontSizeSp(characters: Int): Int = when {
    characters <= 24 -> 64
    characters <= 44 -> 54
    characters <= 70 -> 44
    else -> 36
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PosterLyrics(
    lines: List<LyricLine>,
    positionMs: Long,
    playing: Boolean,
    reduced: Boolean,
    tapping: Boolean,
    onSeek: (Long) -> Unit,
    onTapLine: (lineTimeMs: Long, positionMs: Long) -> Unit,
) {
    val accent = MaterialTheme.nuvio.colors.accent
    val smooth = rememberSmoothPositionMs(positionMs, playing)
    val active by remember(lines) { derivedStateOf { activeLyricIndex(lines, smooth.value + NOW_PLAYING_LYRIC_LEAD_MS) } }
    val chorus = remember(lines) { chorusLineIndices(lines) }
    val marker = FontFamily(Font(Res.font.permanent_marker, FontWeight.Normal))

    // Before the first line the stage waits on the first one, small, so there is something to read.
    val shown = active
    val previous = lines.getOrNull(shown - 1)
    val current = lines.getOrNull(shown)
    val next = lines.getOrNull(if (shown < 0) 0 else shown + 1)
    fun pick(line: LyricLine) { if (tapping) onTapLine(line.timeMs, positionMs) else onSeek(line.timeMs) }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ContextLine(previous?.text, reduced) { previous?.let(::pick) }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (current != null && current.text.isNotBlank()) {
                val words = remember(lines, shown) { lineWords(lines, shown) }
                val durationMs = remember(lines, shown) { sungEndMs(lines, shown) - current.timeMs }
                val size = posterFontSizeSp(current.text.length)
                val style = TextStyle(fontFamily = marker, fontSize = size.sp, lineHeight = (size * 1.18f).sp, textAlign = TextAlign.Center)
                val isChorus = shown in chorus
                val lit = if (isChorus) lerp(Color.White, accent, 0.45f) else Color.White
                FlowRow(
                    // Not clipped: words drop in from above and swell as they land, so they reach past the box.
                    Modifier.fillMaxWidth().musicKeyFocusRing(RoundedCornerShape(12.dp)).clickable { pick(current) }.padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(size.dp * 0.28f, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    words.forEachIndexed { i, word ->
                        PosterWord(
                            word = word,
                            style = style,
                            lit = lit,
                            accent = accent,
                            tilt = posterTiltDegrees(shown, i),
                            reduced = reduced,
                            arrival = { posterWordArrival(word, lineProgress(lines, shown, smooth.value), durationMs) },
                        )
                    }
                }
            } else if (current != null) {
                Text("♪", color = Color.White.copy(alpha = 0.35f), style = TextStyle(fontFamily = marker, fontSize = 54.sp))
            }
        }
        ContextLine(next?.text, reduced) { next?.let(::pick) }
    }
}

/** The line before or after the sung one: small, dim, and a click away from a seek. */
@Composable
private fun ContextLine(text: String?, reduced: Boolean, onClick: () -> Unit) {
    val alpha by animateFloatAsState(if (text.isNullOrBlank()) 0f else 0.42f, tween(if (reduced) 0 else 350), label = "posterContext")
    Text(
        text.orEmpty().ifBlank { " " },
        color = Color.White.copy(alpha = alpha),
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        maxLines = 2,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = !text.isNullOrBlank(), onClick = onClick).padding(vertical = 4.dp),
    )
}

/**
 * One word: a ghost until it is sung, then it lands: thrown in from above and larger, overshooting into place at its own
 * crooked angle, flashing the accent (a halo of it) that cools to white.
 */
@Composable
private fun PosterWord(
    word: LyricWord,
    style: TextStyle,
    lit: Color,
    accent: Color,
    tilt: Float,
    reduced: Boolean,
    arrival: () -> Float,
) {
    Box(
        Modifier.graphicsLayer {
            val a = arrival()
            if (reduced) {
                alpha = if (a > 0f) 1f else 0.22f
            } else if (a <= 0f) {
                // Not sung yet: a quiet ghost at rest. It must not be scaled or thrown about, because a graphics
                // layer does not move its neighbours, so an enlarged ghost drew over the words beside it.
                alpha = 0.22f
                rotationZ = tilt
            } else {
                val e = backOut(a)
                val away = 1f - e
                alpha = 0.22f + 0.78f * (a * 4f).coerceAtMost(1f)
                val s = 1f + POSTER_LAND_SCALE * away
                scaleX = s
                scaleY = s
                translationY = -POSTER_LAND_DROP_DP.dp.toPx() * away
                rotationZ = tilt * (1f + 1f * away)
            }
        },
    ) {
        if (!reduced) {
            // The spray: a blurred copy in the accent, bright as the word lands and gone a moment later.
            Text(
                word.text,
                style = style,
                color = accent,
                modifier = Modifier.blur(18.dp, BlurredEdgeTreatment.Unbounded).graphicsLayer {
                    val a = arrival()
                    alpha = if (a <= 0f) 0f else (1f - a).pow(1.5f) * 0.95f
                },
            )
        }
        Text(
            word.text,
            style = style,
            color = lit,
        )
    }
}
