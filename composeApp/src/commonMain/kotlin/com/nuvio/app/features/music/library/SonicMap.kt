package com.nuvio.app.features.music.library
// FORK UI (Spyder): Nick's album page lenses, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import com.nuvio.app.features.music.visualiser.SoundPrint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.atan2
import kotlin.math.hypot

/*
 * fork:sonic-map - the Sonic map lens: each song placed by energy (up) and Warm to Bright (across). A song you have
 * played is placed by its measured sound print; one you haven't, by the AI's estimate (one request per album, asked when
 * the tab is opened), until it is played. Positions are for this album only: its calmest song at the bottom, its loudest
 * at the top. Corners and walks are worked out from the positions. Pure, so it is tested without Compose.
 */

/** A song's place: [energy] up, [bright] across, both 0..1; [measured] false for an AI estimate. */
internal data class SonicPoint(val energy: Float, val bright: Float, val measured: Boolean)

/** The AI's estimate for one song, both 0..1. */
internal data class SonicGuess(val energy: Float, val bright: Float)

/** Keeps dots off the map's edge. */
private const val EDGE = 0.07f

private fun spread(values: List<Float?>): List<Float?> {
    val known = values.filterNotNull()
    if (known.isEmpty()) return values
    val lo = known.min()
    val hi = known.max()
    return values.map { v ->
        v?.let { if (hi - lo < 1e-6f) 0.5f else EDGE + (1 - 2 * EDGE) * (it - lo) / (hi - lo) }
    }
}

/** A measured print's raw energy: loudness most, then how busy the drums are, then tempo. Comparable within one album. */
private fun rawEnergy(p: SoundPrint, loud: Pair<Float, Float>, hits: Pair<Float, Float>, tempo: Pair<Float, Float>?): Float {
    fun n(v: Float, r: Pair<Float, Float>) = if (r.second - r.first < 1e-6f) 0.5f else (v - r.first) / (r.second - r.first)
    val t = if (p.bpm != null && tempo != null) n(p.bpm, tempo) else 0.5f
    return 0.5f * n(p.loudness, loud) + 0.35f * n(p.hitRate, hits) + 0.15f * t
}

/**
 * Every song's place, by track index (null: neither measured nor estimated). Measured songs are spread over the map among
 * themselves; estimates keep the AI's own scale. A song with both is placed by its print.
 */
internal fun sonicPlaces(prints: List<SoundPrint?>, guesses: List<SonicGuess?>): List<SonicPoint?> {
    val m = prints.filterNotNull()
    val loud = m.minOfOrNull { it.loudness }?.let { it to m.maxOf { p -> p.loudness } }
    val hits = m.minOfOrNull { it.hitRate }?.let { it to m.maxOf { p -> p.hitRate } }
    val tempos = m.mapNotNull { it.bpm }
    val tempo = tempos.minOrNull()?.let { it to tempos.max() }
    val energy = spread(prints.map { p -> if (p != null && loud != null && hits != null) rawEnergy(p, loud, hits, tempo) else null })
    val bright = spread(prints.map { it?.brightness })
    return prints.indices.map { i ->
        val e = energy[i]
        val b = bright[i]
        when {
            e != null && b != null -> SonicPoint(e, b, measured = true)
            else -> guesses.getOrNull(i)?.let {
                SonicPoint(EDGE + (1 - 2 * EDGE) * it.energy.coerceIn(0f, 1f), EDGE + (1 - 2 * EDGE) * it.bright.coerceIn(0f, 1f), measured = false)
            }
        }
    }
}

/** The four corners, in grid order: energetic above calm, warm left of bright. */
internal enum class SonicCorner(val energetic: Boolean, val bright: Boolean, val label: String) {
    EnergeticWarm(true, false, "Energetic · Warm"),
    EnergeticBright(true, true, "Energetic · Bright"),
    CalmWarm(false, false, "Calm · Warm"),
    CalmBright(false, true, "Calm · Bright"),
}

internal fun cornerOf(p: SonicPoint): SonicCorner = SonicCorner.entries.first { it.energetic == (p.energy >= 0.5f) && it.bright == (p.bright >= 0.5f) }

/** The track indices in [corner], in album order. */
internal fun cornerSongs(places: List<SonicPoint?>, corner: SonicCorner): List<Int> =
    places.indices.filter { i -> places[i]?.let { cornerOf(it) == corner } == true }

/** The walks, in grid order. */
internal enum class SonicWalk(val label: String, val brief: String) {
    Rise("Rise", "CALM TO ENERGETIC"),
    Fall("Fall", "ENERGETIC TO CALM"),
    Loop("Loop", "AROUND THE MAP"),
    Drift("Drift", "NEXT DOOR EACH TIME"),
}

/** How many songs a walk visits at most. */
internal const val WALK_MAX = 10

/** The songs [walk] visits, in play order (track indices), over the placed songs. */
internal fun walkSongs(places: List<SonicPoint?>, walk: SonicWalk): List<Int> {
    val placed = places.indices.filter { places[it] != null }
    if (placed.isEmpty()) return emptyList()
    fun p(i: Int) = places[i]!!
    return when (walk) {
        SonicWalk.Rise -> placed.sortedWith(compareBy<Int> { p(it).energy }.thenBy { it }).take(WALK_MAX)
        SonicWalk.Fall -> placed.sortedWith(compareByDescending<Int> { p(it).energy }.thenBy { it }).take(WALK_MAX)
        SonicWalk.Loop -> placed.sortedBy { i ->
            // Clockwise from the top: the angle from the centre, measured from straight up.
            val a = atan2((p(i).bright - 0.5f).toDouble(), (p(i).energy - 0.5f).toDouble())
            if (a < 0) a + 2 * kotlin.math.PI else a
        }.take(WALK_MAX)
        SonicWalk.Drift -> {
            val left = placed.toMutableList()
            var at = left.minWith(compareBy<Int> { p(it).energy }.thenBy { it })
            val out = arrayListOf(at)
            left.remove(at)
            while (left.isNotEmpty() && out.size < WALK_MAX) {
                val here = p(at)
                at = left.minWith(compareBy<Int> { hypot((p(it).energy - here.energy).toDouble(), (p(it).bright - here.bright).toDouble()) }.thenBy { it })
                out += at
                left.remove(at)
            }
            out
        }
    }
}

internal const val SONIC_SYSTEM_PROMPT =
    "You describe how songs sound for a listening app, from what is commonly known about them. You only use the track " +
        "numbers you are given. Reply with one JSON object and nothing else."

/** The prompt asking where each of an album's songs sits, relative to the rest of the album. */
internal fun sonicPrompt(artist: String, album: String, titles: List<String>): String = buildString {
    append("Album: ").append(album).append(" by ").append(artist).append("\nTracks:\n")
    titles.forEachIndexed { i, t -> append(i + 1).append(". ").append(t).append('\n') }
    append("\nFor every track, judge it against the other tracks on this album:\n")
    append("- energy: 0 = the album's calmest, 100 = its most energetic (loudness, drive, busy drums, tempo)\n")
    append("- bright: 0 = the warmest, darkest sound (bass-heavy, mellow), 100 = the brightest (crisp highs, airy, treble-forward)\n")
    append("Return JSON of this shape: {\"songs\": [{\"n\": 1, \"energy\": 40, \"bright\": 65}, ...]}, one entry per track.")
}

private val SONIC_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

/** The model's [reply] as one guess per track (null for a track it left out); null when it names no track. */
internal fun parseSonicGuesses(reply: String, trackCount: Int): List<SonicGuess?>? {
    val start = reply.indexOf('{')
    val end = reply.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val root = runCatching { SONIC_JSON.parseToJsonElement(reply.substring(start, end + 1)) }.getOrNull() as? JsonObject ?: return null
    val out = MutableList<SonicGuess?>(trackCount) { null }
    (root["songs"] as? JsonArray).orEmpty().forEach { el ->
        val o = el as? JsonObject ?: return@forEach
        val n = (o["n"] as? JsonPrimitive)?.intOrNull ?: return@forEach
        val e = (o["energy"] as? JsonPrimitive)?.floatOrNull ?: return@forEach
        val b = (o["bright"] as? JsonPrimitive)?.floatOrNull ?: return@forEach
        if (n in 1..trackCount && out[n - 1] == null) out[n - 1] = SonicGuess((e / 100f).coerceIn(0f, 1f), (b / 100f).coerceIn(0f, 1f))
    }
    return out.takeIf { g -> g.any { it != null } }
}
