package com.spydermahn.nuvioremote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/*
 * The Now Playing stage's "Loud" lyrics, on the phone: every line in its own face, colour and entrance, stacked into one
 * to three chunks as big as the width allows; words wait as outlines and land, filled with a hard shadow, as they are
 * sung. A port of the TV's LoudLyrics.kt and the timing maths in LyricTiming.kt (Nuvio Desktop, commonMain/features/
 * music), so the two look alike; change them together. Word times come from the PC when the source timed every word,
 * else they are estimated from the words' lengths, as the TV does.
 */

internal enum class LoudEntrance { Drop, Right, Zoom, Diagonal }

private const val LOUD_FACES = 4
private const val LOUD_COLOURS = 6

internal fun loudFaceFor(lineIndex: Int): Int = lineIndex.mod(LOUD_FACES)
internal fun loudColourFor(lineIndex: Int): Int = lineIndex.mod(LOUD_COLOURS)
internal fun loudEntranceFor(lineIndex: Int): LoudEntrance = LoudEntrance.entries[lineIndex.mod(LoudEntrance.entries.size)]

/** Yellow, pink, orange, blue, red, green: the TV's six. */
internal val LOUD_PALETTE: List<Color> = listOf(
    Color(0xFFFFEA4F), Color(0xFFFF4D99), Color(0xFFFF8655), Color(0xFF5FA8FF), Color(0xFFFF5354), Color(0xFF46E046),
)

internal fun loudFaceIsCapitals(face: Int): Boolean = face == 1 || face == 2
internal fun loudFaceWidthFactor(face: Int): Float = if (face == 2) 1.12f else 1f

/** One to three chunks of about equal length, keeping order (word indices). As the TV's `loudChunks`. */
internal fun loudChunks(wordLengths: List<Int>): List<List<Int>> {
    val n = wordLengths.size
    if (n == 0) return emptyList()
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

/** [wordLengths] split into [rows] runs of about equal length, keeping order (word indices); fewer when words run out. */
internal fun loudChunksInto(wordLengths: List<Int>, rows: Int): List<List<Int>> {
    val n = wordLengths.size
    if (n == 0) return emptyList()
    val k = rows.coerceIn(1, n)
    val target = wordLengths.sum().toFloat() / k
    val out = mutableListOf<List<Int>>()
    var current = mutableListOf<Int>()
    var accumulated = 0
    wordLengths.forEachIndexed { i, length ->
        current += i
        accumulated += length
        val wordsLeft = n - 1 - i
        val rowsLeft = k - 1 - out.size
        // Close the row at its share of the length, or when every word left is needed for a row of its own.
        if (rowsLeft > 0 && i < n - 1 && (accumulated >= target * (out.size + 1) || wordsLeft == rowsLeft)) {
            out += current
            current = mutableListOf()
        }
    }
    if (current.isNotEmpty()) out += current
    return out
}

internal const val LOUD_WORD_LAND_MS = 300L

/** A word of a line and the share (0..1) of the line's sweep it takes. */
internal data class LyricWord(val text: String, val from: Float, val to: Float)

internal fun loudWordArrival(word: LyricWord, lineProgress: Float, lineDurationMs: Long): Float =
    (((lineProgress - word.from) * lineDurationMs.coerceAtLeast(1L)) / LOUD_WORD_LAND_MS).coerceIn(0f, 1f)

internal data class LoudPose(val dx: Float, val dy: Float, val rotation: Float, val scale: Float, val alpha: Float)

internal fun backOut(t: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val x = t - 1f
    return 1f + c3 * x * x * x + c1 * x * x
}

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

internal fun loudRowTilt(lineIndex: Int, chunk: Int): Float =
    (if (chunk % 2 == 1) 1f else -1f) * (1.5f + ((lineIndex * 7 + chunk * 3) % 3))

// ------------------------------------------------------------------------------------------------ Timing (LyricTiming.kt)

private const val MAX_SUNG_MS = 9_000L
private const val MIN_SUNG_MS = 700L
private const val MS_PER_CHAR = 95L
private const val LAST_LINE_MS = 4_000L

/** The line on at [positionMs]: the last one that has started, or -1 before the first. */
internal fun activeLyricIndex(lines: List<LyricLine>, positionMs: Long): Int =
    lines.indexOfLast { (it.timeMs ?: Long.MAX_VALUE) <= positionMs }

/** When line [index]'s words are assumed finished: its last word, or an estimate from its length, never past the next. */
internal fun sungEndMs(lines: List<LyricLine>, index: Int): Long {
    val line = lines[index]
    val start = line.timeMs ?: 0L
    val nextStart = lines.getOrNull(index + 1)?.timeMs ?: (start + LAST_LINE_MS)
    if (line.text.isBlank()) return nextStart
    if (line.words.isNotEmpty()) {
        val last = line.words.last().endMs
        return minOf(if (lines.getOrNull(index + 1) != null) nextStart else Long.MAX_VALUE, last).coerceAtLeast(start + 1)
    }
    val estimate = (line.text.length * MS_PER_CHAR + 400L).coerceIn(MIN_SUNG_MS, MAX_SUNG_MS)
    return minOf(nextStart, start + estimate).coerceAtLeast(start + 1)
}

internal fun lineProgress(lines: List<LyricLine>, index: Int, positionMs: Long): Float {
    if (index !in lines.indices) return 0f
    val start = lines[index].timeMs ?: return 1f
    val end = sungEndMs(lines, index)
    if (end <= start) return 1f
    return ((positionMs - start).toFloat() / (end - start).toFloat()).coerceIn(0f, 1f)
}

internal fun lyricWords(text: String): List<LyricWord> {
    val words = text.split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    val weights = words.map { it.length + 1 }
    val total = weights.sum().toFloat()
    var cursor = 0f
    return words.mapIndexed { i, word ->
        val from = cursor
        cursor += weights[i] / total
        LyricWord(word, from, if (i == words.lastIndex) 1f else cursor)
    }
}

/** Line [index]'s words: the source's own word times when it has them, else [lyricWords]'s estimate. */
internal fun lineWords(lines: List<LyricLine>, index: Int): List<LyricWord> {
    val line = lines.getOrNull(index) ?: return emptyList()
    if (line.words.isEmpty()) return lyricWords(line.text)
    val start = line.timeMs ?: 0L
    val span = (sungEndMs(lines, index) - start).coerceAtLeast(1L).toFloat()
    return line.words.map { word ->
        val from = ((word.startMs - start) / span).coerceIn(0f, 1f)
        val to = ((word.endMs - start) / span).coerceIn(from, 1f)
        LyricWord(word.text, from, to)
    }
}

internal fun fitRowCount(rawSizesPx: List<Float>, heightPx: Float, minPx: Float, lineFactor: Float = 0.98f): Int {
    var keep = rawSizesPx.size
    while (keep > 1) {
        val rows = rawSizesPx.take(keep)
        val total = rows.sumOf { (it * lineFactor).toDouble() }.toFloat().coerceAtLeast(1f)
        val shrink = minOf(1f, heightPx / total)
        if (rows.minOrNull()!! * shrink >= minPx) break
        keep--
    }
    return keep
}

internal fun withEllipsis(text: String): String = text.trimEnd(',', ';', ':', '-', ' ', '.') + "…"

/** The TV's lead: lines change a quarter of a second early, so the eye is not behind the voice. */
private const val LYRIC_LEAD_MS = 250L
private const val LOUD_MIN_SP = 22f
private const val NEXT_LINE_DP = 38f
/** The most rows a line is split into on the phone. */
private const val MAX_ROWS = 5
/** Room at the top for the List tag. */
private const val TOP_BAND_DP = 46f
/** A row's height for its font size: the faces run a little taller than their size once font padding is off. */
private const val ROW_HEIGHT_FACTOR = 1.08f

// ------------------------------------------------------------------------------------------------ The view

private val LoudFaces = listOf(
    FontFamily(Font(R.font.loud_permanent_marker, FontWeight.Normal)),
    FontFamily(Font(R.font.loud_luckiest_guy, FontWeight.Normal)),
    FontFamily(Font(R.font.loud_bangers, FontWeight.Normal)),
    FontFamily(Font(R.font.loud_rubik_spray_paint, FontWeight.Normal)),
)

/**
 * The lyrics in the stage's Loud style, filling [modifier]'s box: the line being sung, big, and the next one small under
 * it. The position runs on every frame between the PC's updates, so words land on time.
 */
@Composable
fun LoudLyricsView(lines: List<LyricLine>, playing: NowPlaying, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(playing) {
        while (true) withFrameMillis { now = SystemClock.elapsedRealtime() }
    }
    val position = playing.positionAt(now)
    val shown = activeLyricIndex(lines, position + LYRIC_LEAD_MS)
    val current = lines.getOrNull(shown)
    val next = lines.getOrNull(if (shown < 0) 0 else shown + 1)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    BoxWithConstraints(modifier) {
        // Rows are tilted and the diagonal entrance shifts them, so they are set a little narrower than the box.
        val widthPx = with(density) { (maxWidth - 40.dp).toPx() } * 0.92f
        val nextText = next?.text?.takeIf { it.isNotBlank() }
        // Fixed bands: the top one for the List tag (nothing is set under it), the bottom one for the next line, so
        // neither is ever covered or pushed off by a big line. The words get what is between.
        val heightPx = with(density) { (maxHeight - TOP_BAND_DP.dp - NEXT_LINE_DP.dp - 8.dp).coerceAtLeast(60.dp).toPx() }
        // The TV sizes for a 1000dp stage; a phone's pane is about a third of that, and its words travel a third as far.
        val stageScale = (maxWidth.value / 1000f).coerceIn(0.32f, 1f)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(TOP_BAND_DP.dp))
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.Center) {
            if (current != null && current.text.isNotBlank()) {
                val words = remember(lines, shown) { lineWords(lines, shown) }
                val durationMs = remember(lines, shown) { sungEndMs(lines, shown) - (current.timeMs ?: 0L) }
                val face = loudFaceFor(shown)
                val colour = LOUD_PALETTE[loudColourFor(shown)]
                val entrance = loudEntranceFor(shown)
                val caps = loudFaceIsCapitals(face)
                val texts = remember(words, caps) { words.map { if (caps) it.text.uppercase() else it.text } }
                // The whole line, always: it is split into as many rows as set it biggest (a phone's pane is narrow, so a
                // long line takes more, shorter rows than on the TV), and the stack is shrunk to the height. Never cut.
                val layout = remember(texts, face, widthPx, heightPx) {
                    val reference = TextStyle(fontFamily = LoudFaces[face], fontSize = with(density) { 100f.toSp() })
                    val lengths = texts.map { it.length }
                    val maxRows = minOf(texts.size, MAX_ROWS).coerceAtLeast(1)
                    (1..maxRows).map { rows ->
                        val chunks = if (rows == 1) listOf(texts.indices.toList()) else loudChunksInto(lengths, rows)
                        val raw = chunks.map { chunk ->
                            val measured = measurer.measure(AnnotatedString(chunk.joinToString(" ") { texts[it] }), reference, maxLines = 1, softWrap = false)
                                .size.width * loudFaceWidthFactor(face) * 1.14f
                            min(130f * density.density * 0.9f, 100f * widthPx / measured.coerceAtLeast(1f))
                        }
                        val fit = min(1f, heightPx / raw.sumOf { (it * ROW_HEIGHT_FACTOR).toDouble() }.toFloat().coerceAtLeast(1f))
                        chunks to raw.map { it * fit }
                    }.maxByOrNull { (_, sizes) -> sizes.minOrNull() ?: 0f }!!
                }
                val shownChunks = layout.first
                val sizesPx = layout.second
                val cut = false
                val progress = lineProgress(lines, shown, position + LYRIC_LEAD_MS)
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    shownChunks.forEachIndexed { ci, chunk ->
                        val sizePx = sizesPx.getOrElse(ci) { 40f }
                        val sizeSp = with(density) { sizePx.toSp() }
                        val shift = if (entrance == LoudEntrance.Diagonal) (ci - (shownChunks.size - 1) / 2f) * sizePx * 0.35f else 0f
                        val tilt = loudRowTilt(shown, ci)
                        Row(
                            Modifier.graphicsLayer {
                                translationX = shift
                                rotationZ = tilt
                            },
                            horizontalArrangement = Arrangement.spacedBy(with(density) { (sizePx * 0.22f).toDp() }),
                        ) {
                            chunk.forEachIndexed { at, wi ->
                                LoudWord(
                                    text = if (cut && ci == shownChunks.lastIndex && at == chunk.lastIndex) withEllipsis(texts[wi]) else texts[wi],
                                    sizeSp = sizeSp,
                                    face = LoudFaces[face],
                                    colour = colour,
                                    entrance = entrance,
                                    unitPx = density.density * stageScale,
                                    arrival = loudWordArrival(words[wi], progress, durationMs),
                                )
                            }
                        }
                    }
                }
            } else {
                Text("♪", color = Color.White.copy(alpha = 0.35f), fontSize = 40.sp)
            }
            }
            Box(Modifier.fillMaxWidth().height(NEXT_LINE_DP.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                nextText?.let {
                    Text(
                        it,
                        color = Color.White.copy(alpha = 0.40f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
    unitPx: Float,
    arrival: Float,
) {
    // Without Android's extra font padding, so a row is as tall as the fit above counts it.
    val base = TextStyle(
        fontFamily = face,
        fontSize = sizeSp,
        lineHeight = sizeSp * 0.98f,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
    )
    Box {
        Text(text, style = base.copy(color = colour.copy(alpha = 0.32f), drawStyle = Stroke(width = 3f)), maxLines = 1, softWrap = false)
        Text(
            text,
            style = base.copy(color = colour, shadow = Shadow(Color.Black, Offset(4f, 4f), 0f)),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.graphicsLayer {
                val pose = loudPose(entrance, arrival)
                alpha = pose.alpha
                translationX = pose.dx * unitPx
                translationY = pose.dy * unitPx
                rotationZ = pose.rotation
                scaleX = pose.scale
                scaleY = pose.scale
            },
        )
    }
}
