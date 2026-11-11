package com.nuvio.app.features.music.library
// FORK UI (Spyder): Nick's album page lenses, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/*
 * fork:lenses - Cut to fit: the album trimmed to the time the person has, for the mood they are in. The mood decides which
 * songs fit and in what order of preference (the AI's pick, one request per album and mood; "Best of" needs no AI: the most
 * played songs, remixes and demos left out). The time then takes songs from the top of that list while they fit, and the
 * cut plays in the album's own order so the record's arc still works. Pure functions, so they are tested without Compose.
 */

/** The moods offered, in grid order. [ai] moods need the person's AI key; [BestOf] works without one. */
internal enum class CutMood(val label: String, val ai: Boolean, val brief: String) {
    BestOf("Best of", false, "the songs most people play"),
    Dinner("Dinner", true, "background for a dinner with friends: warm, mid-tempo, nothing that demands attention or jars"),
    Drive("Drive", true, "a drive: a steady tempo, no long quiet stretches, nothing that needs full attention"),
    Dancefloor("Dancefloor", true, "dancing: the most rhythmic, energetic songs, the grooves people move to"),
    Focus("Focus", true, "working or reading: even, unobtrusive songs, few sudden changes, little that pulls attention"),
    WindDown("Wind down", true, "the end of the night: the calmest, softest songs"),
}

/** The times offered, in minutes, in grid order; null is the whole of what the mood keeps. */
internal val CUT_TIMES: List<Int?> = listOf(20, 30, 45, 60, 90, null)

/** The time chosen when the lens opens. */
internal const val CUT_DEFAULT_MINUTES = 45

/** A mood's choice of songs: [order] are track indices (0-based), best fit first; the rest leave the album for this mood. */
internal data class MoodPick(
    val order: List<Int>,
    /** A few words on the energy of what was kept ("medium to high"). */
    val energy: String?,
    /** A few words on what was left out ("slow openers, remixes"). */
    val leavesOut: String?,
    /** One or two sentences: why these songs. */
    val why: String?,
    val ai: Boolean,
)

/**
 * "Best of" without the AI: songs with a popularity first, most played first, then the others in album order; remixes,
 * demos, live and instrumental copies are left out (unless that would leave nothing).
 */
internal fun bestOfPick(titles: List<String>, popularity: List<Float?>): MoodPick {
    val plain = titles.indices.filter { versionKind(titles[it]) == VersionKind.Album }.ifEmpty { titles.indices.toList() }
    val ranked = plain.filter { popularity.getOrNull(it) != null }.sortedByDescending { popularity[it] }
    val rest = plain.filter { popularity.getOrNull(it) == null }
    val dropped = titles.size - plain.size
    return MoodPick(
        order = ranked + rest,
        energy = null,
        leavesOut = if (dropped > 0) "Remixes and demos" else null,
        why = if (ranked.isNotEmpty()) "The songs played most come first, so a short cut keeps the best known ones." +
            (if (dropped > 0) " Remixes and demos are left out." else "")
        else "Nobody's listening figures are known for this album, so the cut keeps it in its own order.",
        ai = false,
    )
}

/** What the time leaves: [indices] in album order, [order] their play position (1 = first), [seconds] how long they run. */
internal data class CutResult(val indices: List<Int>, val seconds: Int) {
    val order: Map<Int, Int> get() = indices.withIndex().associate { (i, index) -> index to i + 1 }
}

/**
 * The cut for [budgetMinutes] (null keeps everything the mood picked): songs taken from the top of [pick]'s order while they
 * fit (a long one that does not is passed over for a shorter one after it), then put back in album order. At least the
 * mood's first song is kept, however short the time.
 */
internal fun cutToFit(order: List<Int>, durations: List<Int>, budgetMinutes: Int?): CutResult {
    val valid = order.filter { it in durations.indices }.distinct()
    val kept = if (budgetMinutes == null) valid else {
        val budget = budgetMinutes * 60
        var total = 0
        val out = ArrayList<Int>()
        for (i in valid) {
            val d = durations[i].coerceAtLeast(0)
            if (total + d <= budget) { out += i; total += d }
        }
        if (out.isEmpty() && valid.isNotEmpty()) out += valid.first()
        out
    }
    val inOrder = kept.sorted()
    return CutResult(inOrder, inOrder.sumOf { durations[it].coerceAtLeast(0) })
}

/** "44:55" or "1:02:10": a cut's running time. */
internal fun cutLength(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    fun two(n: Int) = n.toString().padStart(2, '0')
    return if (h > 0) "$h:${two(m)}:${two(s)}" else "$m:${two(s)}"
}

internal const val CUT_SYSTEM_PROMPT =
    "You pick songs from one album for a listening app. You only use the track numbers you are given, and you judge each " +
        "song by what is commonly known about it. Reply with one JSON object and nothing else."

/** The prompt for [mood]: the album's tracks numbered from 1 with their lengths. */
internal fun cutPrompt(artist: String, album: String, titles: List<String>, durations: List<Int>, mood: CutMood): String = buildString {
    append("Album: ").append(album).append(" by ").append(artist).append("\nTracks:\n")
    titles.forEachIndexed { i, t ->
        append(i + 1).append(". ").append(t)
        durations.getOrNull(i)?.takeIf { it > 0 }?.let { append(" (").append(cutLength(it)).append(')') }
        append('\n')
    }
    append("\nThe listener wants this album for ").append(mood.brief).append(".\n")
    append("Return JSON of this shape:\n")
    append("{\"order\": [track numbers that suit this, the best fit first],\n")
    append(" \"energy\": \"2 to 4 words on the energy of the songs you kept\",\n")
    append(" \"leaves_out\": \"2 to 5 words on what you left out, or empty\",\n")
    append(" \"why\": \"one or two plain sentences on why these songs, naming at most two of them\"}\n")
    append("Leave out songs that do not suit it (and near-duplicate remixes unless they suit it best). Keep at least three.")
}

private val CUT_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

private fun JsonObject.line(key: String, max: Int): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.replace(Regex("""\s+"""), " ")?.trim()
        ?.takeIf { it.isNotEmpty() }?.let { if (it.length <= max) it else it.take(max).substringBeforeLast(' ') + "…" }

/** The model's [reply] as a pick for an album of [trackCount] songs; null when it names no track that exists. */
internal fun parseCutPick(reply: String, trackCount: Int): MoodPick? {
    val start = reply.indexOf('{')
    val end = reply.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val root = runCatching { CUT_JSON.parseToJsonElement(reply.substring(start, end + 1)) }.getOrNull() as? JsonObject ?: return null
    val order = (root["order"] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.intOrNull ?: (it as? JsonPrimitive)?.content?.trim()?.toIntOrNull() }
        .filter { it in 1..trackCount }.distinct().map { it - 1 }
    if (order.isEmpty()) return null
    return MoodPick(order, root.line("energy", 40), root.line("leaves_out", 48), root.line("why", 260), ai = true)
}
