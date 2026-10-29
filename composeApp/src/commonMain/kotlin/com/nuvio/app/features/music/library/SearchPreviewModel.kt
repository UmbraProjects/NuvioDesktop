package com.nuvio.app.features.music.library

import com.nuvio.app.features.music.MusicAlbumSummary
import com.nuvio.app.features.music.MusicTrack

/**
 * HTPC search split view (pure part): when the results sit beside a preview pane, which releases the artist preview
 * lists, how a track list reads, and a small memo so moving over rows does not ask the catalog twice.
 */

/** The window must be at least this wide (dp) for results and preview to sit side by side. */
internal const val SPLIT_MIN_WIDTH_DP = 840

/** Results take this share of the width in split view; the preview gets the rest. */
internal const val SPLIT_RESULTS_SHARE = 0.35f

/** How long focus must rest on a row before its preview is fetched, so arrowing down a list asks for nothing. */
internal const val PREVIEW_SETTLE_MS = 250L

/** Releases an artist preview lists. */
internal const val PREVIEW_RELEASE_LIMIT = 5

internal fun isSplitView(widthDp: Float): Boolean = widthDp >= SPLIT_MIN_WIDTH_DP

/**
 * The artist's releases the preview shows: full albums first (then EPs, then the rest), each group newest first, one
 * per title (spelling, case and a "(Deluxe Edition)" or "[Remastered]" tail ignored), at most [limit]. Input order is
 * the tie-break, so the answer never depends on anything else.
 */
internal fun previewReleases(albums: List<MusicAlbumSummary>, limit: Int = PREVIEW_RELEASE_LIMIT): List<MusicAlbumSummary> {
    fun rank(kind: String?): Int = when (kind?.trim()?.lowercase()) {
        "album" -> 0
        "ep" -> 1
        null, "" -> 2
        "single" -> 4
        else -> 3
    }
    fun year(a: MusicAlbumSummary): Int = a.year?.trim()?.take(4)?.toIntOrNull() ?: Int.MIN_VALUE
    val ordered = albums.withIndex()
        .sortedWith(compareBy<IndexedValue<MusicAlbumSummary>> { rank(it.value.kind) }.thenByDescending { year(it.value) }.thenBy { it.index })
        .map { it.value }
    return ordered.distinctBy { titleKey(it.title) }.take(limit.coerceAtLeast(0))
}

/** A title with case, punctuation and a trailing bracketed edition note removed, for telling duplicates apart. */
internal fun titleKey(title: String): String {
    val noTail = title.replace(Regex("""\s*[(\[][^)\]]*(deluxe|remaster|edition|expanded|anniversary|version)[^)\]]*[)\]]\s*$""", RegexOption.IGNORE_CASE), "")
    return noTail.lowercase().filter { it.isLetterOrDigit() }
}

/** "3:07", "12:00", "1:02:03"; "-" for a track with no length. */
internal fun durationText(seconds: Int): String {
    if (seconds <= 0) return "-"
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}" else "$m:${s.toString().padStart(2, '0')}"
}

/** "12 tracks · 41 min", counting only tracks that can play; "No playable tracks" when none can. */
internal fun trackSummary(tracks: List<MusicTrack>): String {
    val playable = tracks.filter { it.isAvailable }
    if (playable.isEmpty()) return "No playable tracks"
    val minutes = (playable.sumOf { it.durationSeconds.coerceAtLeast(0) } + 30) / 60
    val count = "${playable.size} track${if (playable.size == 1) "" else "s"}"
    return if (minutes > 0) "$count · $minutes min" else count
}

/** A least-recently-used memo of at most [capacity] entries (not thread-safe: one UI thread uses it). */
internal class PreviewMemo<K : Any, V : Any>(private val capacity: Int = 24) {
    private val map = LinkedHashMap<K, V>()

    val size: Int get() = map.size

    operator fun get(key: K): V? {
        val value = map.remove(key) ?: return null
        map[key] = value
        return value
    }

    operator fun set(key: K, value: V) {
        map.remove(key)
        map[key] = value
        while (map.size > capacity.coerceAtLeast(1)) map.remove(map.keys.first())
    }
}
