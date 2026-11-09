package com.nuvio.app.features.tracking

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * One title the user has rated on a service, as the ids the service reported for it.
 *
 * [ids] maps an id kind ("imdb", "tmdb", "tvdb", "mal", "simkl", ...) to its value, lowercased, so a
 * title the app knows by any one of them can be found.
 */
internal data class SavedRatingRow(val ids: Map<String, String>, val rating: Int)

/** The ids of [pairs] that have a usable value, normalised the same way as [SavedRatingRow.ids]. */
internal fun savedRatingIds(vararg pairs: Pair<String, Any?>): Map<String, String> =
    pairs.mapNotNull { (kind, value) ->
        value?.toString()?.trim()?.lowercase()
            ?.takeIf { it.isNotEmpty() && it != "0" && it != "null" }
            ?.let { kind to it }
    }.toMap()

/**
 * True when the row and [target] share any one id of the same kind. Keys starting with `_` are
 * tags rather than ids (MDBList's movie/show split): they are never matched on, but when both
 * sides carry the same tag it has to agree.
 */
internal fun SavedRatingRow.matches(target: Map<String, String>): Boolean {
    val tagsDisagree = target.any { (key, value) ->
        key.startsWith("_") && ids[key] != null && ids[key] != value
    }
    if (tagsDisagree) return false
    return target.any { (kind, value) -> !kind.startsWith("_") && ids[kind] == value }
}

internal fun List<SavedRatingRow>.ratingFor(target: Map<String, String>): Int? =
    if (target.isEmpty()) null else firstOrNull { it.matches(target) }?.rating

/** Reads an `ids` object of a ratings response into [SavedRatingRow.ids] form. */
internal fun JsonObject.savedRatingIdsObject(): Map<String, String> =
    entries.mapNotNull { (kind, value) ->
        val text = (value as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        savedRatingIds(kind.lowercase() to text).entries.firstOrNull()?.toPair()
    }.toMap()

/**
 * Reads the rows of one array of a SIMKL or MDBList ratings response. Each row carries its score
 * under one of [ratingKeys] and the title under one of [mediaKeys] (or on the row itself), with an
 * `ids` object. Scores are rounded onto the app's whole 1-10 scale; rows without one are skipped.
 */
internal fun JsonArray?.savedRatingRows(
    ratingKeys: List<String>,
    mediaKeys: List<String>,
): List<SavedRatingRow> = orEmpty().mapNotNull { element ->
    val row = element as? JsonObject ?: return@mapNotNull null
    val score = ratingKeys.firstNotNullOfOrNull { key -> (row[key] as? JsonPrimitive)?.doubleOrNull }
        ?.let { kotlin.math.round(it).toInt() }
        ?.takeIf { it in 1..10 }
        ?: return@mapNotNull null
    val media = mediaKeys.firstNotNullOfOrNull { key -> row[key] as? JsonObject } ?: row
    val ids = (media["ids"] as? JsonObject)?.savedRatingIdsObject().orEmpty()
    if (ids.isEmpty()) null else SavedRatingRow(ids, score)
}

/**
 * The user's rated titles per service and list, kept for a few minutes so opening the rating
 * dialog again does not download them again. A rating written from the app is patched in at once,
 * so reopening the dialog shows it without a refetch.
 */
internal class SavedRatingsCache(private val ttlMs: Long = 10 * 60_000L) {
    private data class Entry(val fetchedAtMs: Long, val rows: List<SavedRatingRow>)

    private val entries = ConcurrentHashMap<String, Entry>()

    suspend fun rows(key: String, fetch: suspend () -> List<SavedRatingRow>): List<SavedRatingRow> {
        val now = System.currentTimeMillis()
        entries[key]?.takeIf { now - it.fetchedAtMs < ttlMs }?.let { return it.rows }
        return fetch().also { entries[key] = Entry(now, it) }
    }

    /** Records a write on every cached list in [keys]: the old score goes, the new one (if any) is added. */
    fun applyWrite(keys: Collection<String>, target: Map<String, String>, rating: Int?) {
        if (target.isEmpty()) return
        keys.forEach { key ->
            entries.computeIfPresent(key) { _, entry ->
                val kept = entry.rows.filterNot { it.matches(target) }
                entry.copy(rows = if (rating != null) kept + SavedRatingRow(target, rating) else kept)
            }
        }
    }

    fun clear() = entries.clear()
}
