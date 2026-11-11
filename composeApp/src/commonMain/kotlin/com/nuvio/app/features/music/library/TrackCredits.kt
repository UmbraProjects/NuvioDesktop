package com.nuvio.app.features.music.library
// FORK UI (Spyder): Nick's album page redesign, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// fork:essentials - who wrote and produced each song, from MusicBrainz: the fallback line for "About this song" when the AI
// has no note (or no key). One release lookup holds the whole album.

private val CREDITS_JSON = Json { ignoreUnknownKeys = true }

private val WRITER_TYPES = setOf("writer", "composer", "lyricist", "librettist")

private fun JsonElement?.obj() = this as? JsonObject
private fun JsonElement?.arr() = this as? JsonArray
private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }

/** The id of the first release in a MusicBrainz release search, null when there is none. */
internal fun parseMbReleaseSearchIds(body: String): List<String> =
    runCatching { CREDITS_JSON.parseToJsonElement(body) }.getOrNull().obj()?.get("releases").arr()
        ?.mapNotNull { it.obj()?.text("id") }.orEmpty()

internal fun parseMbReleaseSearchId(body: String): String? =
    runCatching { CREDITS_JSON.parseToJsonElement(body) }.getOrNull().obj()?.get("releases").arr()
        ?.firstNotNullOfOrNull { it.obj()?.text("id") }

/**
 * One line per track of a MusicBrainz release (looked up with its recordings and their relationships), keyed by the track's
 * place on the album, 1 for the first, counting on across discs: "Written by A, B · Produced by C". A track with no credits
 * is left out.
 */
internal fun parseMbTrackCredits(body: String): Map<Int, String> {
    val release = runCatching { CREDITS_JSON.parseToJsonElement(body) }.getOrNull().obj() ?: return emptyMap()
    val out = LinkedHashMap<Int, String>()
    var number = 0
    for (medium in release["media"].arr().orEmpty()) {
        for (track in medium.obj()?.get("tracks").arr().orEmpty()) {
            number++
            val relations = track.obj()?.get("recording").obj()?.get("relations").arr().orEmpty().mapNotNull { it.obj() }
            val writers = LinkedHashSet<String>()
            val producers = LinkedHashSet<String>()
            for (rel in relations) {
                val type = rel.text("type")?.lowercase() ?: continue
                if (type == "producer") rel["artist"].obj()?.text("name")?.let { producers += it }
                val work = rel["work"].obj() ?: continue
                for (wr in work["relations"].arr().orEmpty().mapNotNull { it.obj() }) {
                    if (wr.text("type")?.lowercase() in WRITER_TYPES) wr["artist"].obj()?.text("name")?.let { writers += it }
                }
            }
            val line = listOfNotNull(
                writers.takeIf { it.isNotEmpty() }?.let { "Written by ${it.take(3).joinToString(", ")}" },
                producers.takeIf { it.isNotEmpty() }?.let { "Produced by ${it.take(3).joinToString(", ")}" },
            ).joinToString(" · ")
            if (line.isNotEmpty()) out[number] = line
        }
    }
    return out
}
