package com.nuvio.app.features.discover

import com.nuvio.app.features.watched.WatchedItem

/**
 * "Have I watched any of this?" keyed on the **title**, not the episode.
 *
 * The per-episode watched keys cannot answer that question for a series. Watch history stores one
 * row per episode, so the show-level key only exists for the handful of titles the user marked
 * watched wholesale — on a real 6,000-row history that was two shows out of 388. Every other series
 * looked entirely unwatched to a show-level lookup, which is why recommendations kept suggesting
 * shows the user had finished.
 *
 * Episode counts deliberately play no part. "Seen every episode that exists" cannot be distinguished
 * from "seen three" without knowing the episode count, and the counts we could reach are wrong in
 * the direction that matters: a show with announced-but-unaired episodes never reads as complete, so
 * a title the user has genuinely exhausted would keep being recommended. One watched episode is
 * enough to say the user has met this show and does not need it suggested.
 */
fun buildWatchedParentKeys(items: List<WatchedItem>): Set<String> =
    items.asSequence()
        .filter { it.id.isNotBlank() }
        .mapTo(mutableSetOf()) { watchedParentKey(it.type, it.id) }

/**
 * Key for one title. [type] is normalised to the two TMDB-addressable forms, so history stored as
 * `anime`/`show`/`tv` still answers a lookup made with `series`.
 */
fun watchedParentKey(type: String, id: String): String {
    val normalized = when (type.trim().lowercase()) {
        "movie", "movies", "film" -> "movie"
        else -> "series"
    }
    return "$normalized:${id.trim()}"
}
