package com.nuvio.app.features.tonight

import kotlin.math.abs
import kotlin.math.hypot

/*
 * fork:tonight - The Map (design-notes/tonight-picker.md, phase 5). Across is Light (left) to
 * Heavy (right); up is Comfort (bottom) to Something new (top). The rules place every title; with
 * the user's AI key and consent the AI's reading of a title's tone (cached per title, never
 * expiring) refines the Light/Heavy axis. Comfort/New stays the user's own history.
 */

/** A title's place on the field, both axes 0..1 (x: 0 light, 1 heavy; y: 0 new at the top, 1 comfort). */
data class MapSpot(val title: TonightTitle, val x: Float, val y: Float, val aiRefined: Boolean)

/** A small, stable offset per title so titles with the same reading do not sit on one point. */
private fun jitter(key: String, salt: Int, size: Float): Float {
    val h = (key.hashCode() * 31 + salt * 7919).toLong() and 0xffff
    return (h / 65535f - 0.5f) * 2f * size
}

/** The AI's tone weighs more than the rules (it has read about the film); both -1 heavy .. +1 light. */
fun tonightMixedLightness(rules: Float, ai: Float?): Float =
    if (ai == null) rules else (rules * 0.35f + ai * 0.65f).coerceIn(-1f, 1f)

fun tonightMapSpots(pool: List<TonightTitle>, tones: Map<String, Float>): List<MapSpot> =
    pool.distinctBy { it.key }.map { t ->
        val ai = tones[t.key]
        val light = tonightMixedLightness(tonightLightness(t), ai)
        val novelty = tonightNovelty(t)
        MapSpot(
            title = t,
            x = ((1f - light) / 2f + jitter(t.key, 1, 0.05f)).coerceIn(0.03f, 0.97f),
            // Novelty comes in a few steps, so the vertical spread is wider to open each band out.
            y = ((1f - novelty) / 2f + jitter(t.key, 2, 0.09f)).coerceIn(0.04f, 0.96f),
            aiRefined = ai != null,
        )
    }

/** Spots by distance from the lens at ([x], [y]), nearest first. */
fun List<MapSpot>.nearest(x: Float, y: Float): List<MapSpot> = sortedBy { hypot(it.x - x, it.y - y) }

fun MapSpot.distanceTo(x: Float, y: Float): Float = hypot(this.x - x, this.y - y)

/** Where the lens starts: on the user's favourite corner (the middle of what they keep). */
fun tonightLensStart(spots: List<MapSpot>): Pair<Float, Float> {
    val kept = spots.filter { it.title.started || it.title.watched || TonightOrigin.Library in it.title.origins }
        .ifEmpty { spots }
    if (kept.isEmpty()) return 0.5f to 0.5f
    return kept.map { it.x }.average().toFloat() to kept.map { it.y }.average().toFloat()
}

/** "Light, and new to you" for the side panel. */
fun MapSpot.readingLabel(): String {
    val tone = when {
        x < 0.35f -> "Light"
        x > 0.65f -> "Heavy"
        else -> "Middle-weight"
    }
    val novelty = when {
        title.started -> "you're part way through"
        title.watched -> "seen before"
        y < 0.45f -> "new to you"
        else -> "familiar ground"
    }
    return "$tone, and $novelty"
}

// ---- The AI's tone -----------------------------------------------------------------------------

const val TONIGHT_TONE_BATCH = 12

const val TONIGHT_TONE_SYSTEM_PROMPT =
    "You rate the emotional weight of films and TV shows for a 'what to watch tonight' map. " +
        "For each numbered title give tone from -1 (heavy: harrowing, bleak, demanding) to 1 (light: " +
        "breezy, funny, comforting). Judge the actual work, not just its genre label (a dark comedy " +
        "is not light; a gentle drama is not heavy). If you do not know a title, leave it out. " +
        "Answer with JSON only: {\"items\":[{\"i\":1,\"tone\":0.6}]}"

fun tonightTonePrompt(batch: List<TonightTitle>): String = buildString {
    appendLine("Titles:")
    batch.forEachIndexed { i, t ->
        append(i + 1).append(". ").append(t.name)
        val bits = listOfNotNull(
            t.year?.toString(),
            if (t.kind == TonightKind.Film) "film" else "TV show",
            t.genres.take(3).joinToString(", ").ifBlank { null },
        )
        if (bits.isNotEmpty()) append(" (").append(bits.joinToString("; ")).append(")")
        appendLine()
    }
}

private val ItemPattern = Regex("""\{[^{}]*?"i"\s*:\s*(\d+)[^{}]*?"tone"\s*:\s*(-?\d+(?:\.\d+)?)[^{}]*?}""")
private val ItemPatternReversed = Regex("""\{[^{}]*?"tone"\s*:\s*(-?\d+(?:\.\d+)?)[^{}]*?"i"\s*:\s*(\d+)[^{}]*?}""")

/**
 * The tones in [reply] by position in the batch (0-based). Tolerant of prose around the JSON and of
 * either key order; numbers outside 1..[count] and tones outside -1..1 (beyond rounding) are dropped.
 */
fun parseToneReply(reply: String, count: Int): Map<Int, Float> {
    val out = linkedMapOf<Int, Float>()
    fun take(index: Int?, tone: Float?) {
        if (index == null || tone == null || index !in 1..count) return
        if (abs(tone) > 1.05f) return
        out.putIfAbsent(index - 1, tone.coerceIn(-1f, 1f))
    }
    ItemPattern.findAll(reply).forEach { take(it.groupValues[1].toIntOrNull(), it.groupValues[2].toFloatOrNull()) }
    ItemPatternReversed.findAll(reply).forEach { take(it.groupValues[2].toIntOrNull(), it.groupValues[1].toFloatOrNull()) }
    return out
}

/** The stored form: one "key=tone" per line (tone 2 decimals; "key=?" a miss with its time). */
fun encodeTones(tones: Map<String, Float>, misses: Map<String, Long>): String = buildString {
    tones.forEach { (k, v) -> append(k).append('=').append(((v * 100).toInt() / 100f).toString()).append('\n') }
    misses.forEach { (k, at) -> append(k).append("=?").append(at).append('\n') }
}

fun decodeTones(text: String?): Pair<Map<String, Float>, Map<String, Long>> {
    val tones = linkedMapOf<String, Float>()
    val misses = linkedMapOf<String, Long>()
    text?.lineSequence()?.forEach { line ->
        val cut = line.lastIndexOf('=')
        if (cut <= 0) return@forEach
        val key = line.substring(0, cut)
        val value = line.substring(cut + 1)
        if (value.startsWith("?")) value.drop(1).toLongOrNull()?.let { misses[key] = it }
        else value.toFloatOrNull()?.let { tones[key] = it.coerceIn(-1f, 1f) }
    }
    return tones to misses
}
