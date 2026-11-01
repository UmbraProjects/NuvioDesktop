package com.nuvio.app.features.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.features.music.library.musicKeyFocusRing
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.bangers
import nuvio.composeapp.generated.resources.luckiest_guy
import nuvio.composeapp.generated.resources.permanent_marker
import nuvio.composeapp.generated.resources.rubik_spray_paint
import org.jetbrains.compose.resources.Font
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/*
 * "Loud" lyrics, after the Aeon Nox Silvo Kodi skin's lyric screen: every line has its own typeface, colour and way of
 * arriving, and is stacked into one to three chunks set as large as the width allows. Words wait as outlines (so the
 * line can be read ahead) and each fills in, with a hard shadow, as it is sung. The maths is Compose-free so it is tested.
 */

internal enum class LoudEntrance { Drop, Right, Zoom, Diagonal }

private const val LOUD_FACES = 4
private const val LOUD_COLOURS = 6

/** Which of the four faces line [lineIndex] is set in (marker, comic, poster-bold, spray), turn and turn about. */
internal fun loudFaceFor(lineIndex: Int): Int = lineIndex.mod(LOUD_FACES)

/** Which of the six colours of the palette line [lineIndex] wears. */
internal fun loudColourFor(lineIndex: Int): Int = lineIndex.mod(LOUD_COLOURS)

internal fun loudEntranceFor(lineIndex: Int): LoudEntrance = LoudEntrance.entries[lineIndex.mod(LoudEntrance.entries.size)]

/** The skin's six: yellow, pink, orange, blue, red, green. */
internal val LOUD_PALETTE: List<Color> = listOf(
    Color(0xFFFFEA4F), Color(0xFFFF4D99), Color(0xFFFF8655), Color(0xFF5FA8FF), Color(0xFFFF5354), Color(0xFF46E046),
)

/** Comic and poster faces are capitals; the other two are set as written. */
internal fun loudFaceIsCapitals(face: Int): Boolean = face == 1 || face == 2

/** How much wider than the measured text a face is allowed to run before it is called full (Bangers is set a little wide). */
internal fun loudFaceWidthFactor(face: Int): Float = if (face == 2) 1.12f else 1f

/**
 * Splits the line's words into one to three chunks of about equal length, keeping order; returns the word indices of
 * each chunk. One or two words are a chunk each, up to five words two chunks, more than that three.
 */
internal fun loudChunks(wordLengths: List<Int>): List<List<Int>> {
    val n = wordLengths.size
    if (n == 0) return emptyList()
    // A word or two: each is its own chunk (the length rule below never reaches its threshold for so few).
    if (n <= 2) return List(n) { listOf(it) }
    val k = if (n <= 5) 2 else 3
    val target = wordLengths.sum().toFloat() / k
    val out = mutableListOf<List<Int>>()
    var current = mutableListOf<Int>()
    var accumulated = 0
    wordLengths.forEachIndexed { i, length ->
        current += i
        accumulated += length
        if (out.size < k - 1 && accumulated >= target * (out.size + 1) && i < n - 1) {
            out += current
            current = mutableListOf()
        }
    }
    if (current.isNotEmpty()) out += current
    return out
}

/** How long a word takes to arrive once it is sung. */
internal const val LOUD_WORD_LAND_MS = 300L

internal fun loudWordArrival(word: LyricWord, lineProgress: Float, lineDurationMs: Long): Float =
    (((lineProgress - word.from) * lineDurationMs.coerceAtLeast(1L)) / LOUD_WORD_LAND_MS).coerceIn(0f, 1f)

/** Where a word is while it arrives: offsets in the reference size (scaled by the stage's width), degrees, scale and opacity. */
internal data class LoudPose(val dx: Float, val dy: Float, val rotation: Float, val scale: Float, val alpha: Float)

internal fun loudPose(entrance: LoudEntrance, arrival: Float): LoudPose {
    if (arrival <= 0f) return LoudPose(0f, 0f, 0f, 1f, 0f)
    val away = 1f - sin(arrival * (PI.toFloat() / 2f))
    val alpha = min(1f, arrival * 5f)
    return when (entrance) {
        LoudEntrance.Drop -> LoudPose(0f, -420f * away, 0f, 1f, alpha)
        LoudEntrance.Right -> LoudPose(420f * away, 0f, 0f, 1f, alpha)
        LoudEntrance.Diagonal -> LoudPose(-260f * away, -260f * away, -12f * away, 1f, alpha)
        LoudEntrance.Zoom -> LoudPose(0f, 0f, 0f, backOut(arrival), alpha)
    }
}

/** The crooked angle of chunk [chunk] of line [lineIndex]: opposite ways row on row, 1.5 to 3.5 degrees. */
internal fun loudRowTilt(lineIndex: Int, chunk: Int): Float =
    (if (chunk % 2 == 1) 1f else -1f) * (1.5f + ((lineIndex * 7 + chunk * 3) % 3))

@Composable
internal fun LoudLyrics(
    lines: List<LyricLine>,
    positionMs: Long,
    playing: Boolean,
    reduced: Boolean,
    tapping: Boolean,
    onSeek: (Long) -> Unit,
    onTapLine: (lineTimeMs: Long, positionMs: Long) -> Unit,
) {
    val smooth = rememberSmoothPositionMs(positionMs, playing)
    val active by remember(lines) { derivedStateOf { activeLyricIndex(lines, smooth.value + NOW_PLAYING_LYRIC_LEAD_MS) } }
    val faces = listOf(
        FontFamily(Font(Res.font.permanent_marker, FontWeight.Normal)),
        FontFamily(Font(Res.font.luckiest_guy, FontWeight.Normal)),
        FontFamily(Font(Res.font.bangers, FontWeight.Normal)),
        FontFamily(Font(Res.font.rubik_spray_paint, FontWeight.Normal)),
    )
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val shown = active
    val current = lines.getOrNull(shown)
    val next = lines.getOrNull(if (shown < 0) 0 else shown + 1)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(density) { (maxWidth * 0.9f - 48.dp).toPx() }
        val heightPx = with(density) { (maxHeight - 70.dp).toPx() }
        val stageScale = (maxWidth.value / 1000f).coerceIn(0.4f, 1f)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (current != null && current.text.isNotBlank()) {
                val words = remember(lines, shown) { lineWords(lines, shown) }
                val durationMs = remember(lines, shown) { sungEndMs(lines, shown) - current.timeMs }
                val face = loudFaceFor(shown)
                val colour = LOUD_PALETTE[loudColourFor(shown)]
                val entrance = loudEntranceFor(shown)
                val caps = loudFaceIsCapitals(face)
                val texts = remember(words, caps) { words.map { if (caps) it.text.uppercase() else it.text } }
                val chunks = remember(words) { loudChunks(words.map { it.text.length }) }

                // Each chunk is set as wide as the stage allows, then the whole stack is shrunk to fit the height.
                val sizesPx = remember(chunks, texts, face, widthPx, heightPx, stageScale) {
                    val reference = TextStyle(fontFamily = faces[face], fontSize = with(density) { 100f.toSp() })
                    val raw = chunks.map { chunk ->
                        val measured = measurer.measure(AnnotatedString(chunk.joinToString(" ") { texts[it] }), reference, maxLines = 1, softWrap = false)
                            .size.width * loudFaceWidthFactor(face) * 1.14f
                        min(130f * density.density * stageScale * 1.25f, 100f * widthPx / measured.coerceAtLeast(1f))
                    }
                    val fit = min(1f, heightPx / raw.sumOf { (it * 0.98f).toDouble() }.toFloat().coerceAtLeast(1f))
                    raw.map { it * fit }
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        // Not clipped: the words are set tighter than the font's own line height and fly in from
                        // above, so their tops and entrances reach past the box; a clip here cut the top off the
                        // first row. The padding below gives the ring and the click area room around them.
                        .musicKeyFocusRing(RoundedCornerShape(12.dp))
                        .clickable { if (tapping) onTapLine(current.timeMs, positionMs) else onSeek(current.timeMs) }
                        .padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    chunks.forEachIndexed { ci, chunk ->
                        val sizePx = sizesPx.getOrElse(ci) { 40f }
                        val sizeSp = with(density) { sizePx.toSp() }
                        val shift = if (entrance == LoudEntrance.Diagonal) (ci - 1) * sizePx * 0.35f else 0f
                        val tilt = loudRowTilt(shown, ci)
                        Row(
                            Modifier.graphicsLayer {
                                translationX = shift
                                rotationZ = tilt
                            },
                            horizontalArrangement = Arrangement.spacedBy(with(density) { (sizePx * 0.22f).toDp() }),
                        ) {
                            chunk.forEach { wi ->
                                LoudWord(
                                    text = texts[wi],
                                    sizeSp = sizeSp,
                                    face = faces[face],
                                    colour = colour,
                                    entrance = entrance,
                                    reduced = reduced,
                                    unitPx = density.density * stageScale,
                                    arrival = { loudWordArrival(words[wi], lineProgress(lines, shown, smooth.value), durationMs) },
                                )
                            }
                        }
                    }
                }
            }
            next?.text?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    color = Color.White.copy(alpha = 0.35f),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** An outline waiting, and over it the filled word that flies in when it is sung. */
@Composable
private fun LoudWord(
    text: String,
    sizeSp: androidx.compose.ui.unit.TextUnit,
    face: FontFamily,
    colour: Color,
    entrance: LoudEntrance,
    reduced: Boolean,
    unitPx: Float,
    arrival: () -> Float,
) {
    val base = TextStyle(fontFamily = face, fontSize = sizeSp, lineHeight = sizeSp * 0.98f)
    Box {
        Text(
            text,
            style = base.copy(color = colour.copy(alpha = 0.32f), drawStyle = Stroke(width = 4f)),
            maxLines = 1,
            softWrap = false,
        )
        Text(
            text,
            style = base.copy(color = colour, shadow = Shadow(Color.Black, Offset(6f, 6f), 0f)),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.graphicsLayer {
                val a = arrival()
                if (reduced) {
                    alpha = if (a > 0f) 1f else 0f
                } else {
                    val pose = loudPose(entrance, a)
                    alpha = pose.alpha
                    translationX = pose.dx * unitPx
                    translationY = pose.dy * unitPx
                    rotationZ = pose.rotation
                    scaleX = pose.scale
                    scaleY = pose.scale
                }
            },
        )
    }
}
