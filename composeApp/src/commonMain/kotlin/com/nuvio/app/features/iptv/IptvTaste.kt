package com.nuvio.app.features.iptv

import kotlinx.serialization.Serializable

/**
 * fork:live-tv-taste - what has been watched, per programme title: kept with the Live TV settings so the Stage's picks
 * can lean on it. [recent] is the last few viewings, newest last, true when that one was watched to the end.
 * [episodes] the episode numbers seen (newest last), so a repeat can be told from a new one.
 */
@Serializable
data class IptvTitleStat(
    val title: String = "",
    val seen: Int = 0,
    val finished: Int = 0,
    val last: Long = 0L,
    val recent: List<Boolean> = emptyList(),
    val episodes: List<String> = emptyList(),
)

/** The programme being watched now and how many minutes of it so far. */
data class IptvWatching(val key: String, val minutes: Int, val seenNoted: Boolean, val finishNoted: Boolean)

enum class IptvWatchEvent { None, Seen, Finished }

object IptvTasteRules {
    /** Minutes before a programme counts as watched. */
    const val SEEN_MIN = 5
    /** Share of a programme's length that counts as watched to the end. */
    const val FINISH_SHARE = 0.7
    const val MAX_TITLES = 300
    const val RECENT = 5
    const val MAX_EPISODES = 30
    /** Seen this often in [KEEP_DAYS] days (and not followed): "You keep watching". */
    const val KEEP_SEEN = 3
    const val KEEP_DAYS = 21
    private const val DAY_MS = 24 * 3_600_000L

    fun key(title: String): String = IptvDecide.normalise(title)

    private fun entryKey(channelKey: String, entry: IptvEpgEntry) = "$channelKey|${entry.startMs}|${key(entry.title)}"

    /**
     * One minute of [entry] played on [channelKey]: the new state and what to note. A programme is seen once at
     * [SEEN_MIN] minutes and finished once at [FINISH_SHARE] of its length; changing programme starts the count again.
     */
    fun step(watching: IptvWatching?, channelKey: String, entry: IptvEpgEntry): Pair<IptvWatching, IptvWatchEvent> {
        val k = entryKey(channelKey, entry)
        val w = if (watching?.key == k) watching.copy(minutes = watching.minutes + 1) else IptvWatching(k, 1, false, false)
        val lengthMin = ((entry.endMs - entry.startMs) / 60_000L).coerceAtLeast(1L)
        return when {
            !w.seenNoted && w.minutes >= minOf(SEEN_MIN.toLong(), lengthMin) -> {
                // A short programme can be seen and finished in the same minute: seen first, finished on the next.
                w.copy(seenNoted = true) to IptvWatchEvent.Seen
            }
            w.seenNoted && !w.finishNoted && w.minutes >= lengthMin * FINISH_SHARE -> w.copy(finishNoted = true) to IptvWatchEvent.Finished
            else -> w to IptvWatchEvent.None
        }
    }

    /** [stats] after [event] for [entry] at [now]; capped at [MAX_TITLES], the oldest dropped. */
    fun note(stats: Map<String, IptvTitleStat>, entry: IptvEpgEntry, event: IptvWatchEvent, now: Long): Map<String, IptvTitleStat> {
        val k = key(entry.title)
        if (k.isBlank() || event == IptvWatchEvent.None) return stats
        val old = stats[k] ?: IptvTitleStat(title = entry.title)
        val updated = when (event) {
            IptvWatchEvent.Seen -> old.copy(
                title = entry.title,
                seen = old.seen + 1,
                last = now,
                recent = (old.recent + false).takeLast(RECENT),
                episodes = entry.episode?.takeIf { it.isNotBlank() && it !in old.episodes }
                    ?.let { (old.episodes + it).takeLast(MAX_EPISODES) } ?: old.episodes,
            )
            IptvWatchEvent.Finished -> old.copy(
                finished = old.finished + 1,
                last = now,
                recent = if (old.recent.isEmpty()) listOf(true) else old.recent.dropLast(1) + true,
            )
            IptvWatchEvent.None -> old
        }
        val out = LinkedHashMap(stats)
        out[k] = updated
        if (out.size > MAX_TITLES) {
            out.entries.sortedBy { it.value.last }.take(out.size - MAX_TITLES).forEach { out.remove(it.key) }
        }
        return out
    }

    fun statOf(stats: Map<String, IptvTitleStat>, title: String): IptvTitleStat? = stats[key(title)]

    /** This airing is an episode already seen. */
    fun isRepeat(entry: IptvEpgEntry, stat: IptvTitleStat?): Boolean =
        stat != null && !entry.isNew && entry.episode?.let { it.isNotBlank() && it in stat.episodes } == true

    /**
     * How much the viewer is likely to want [entry]: followed, finished before, new rather than a repeat, and with a
     * picture and a description to go on.
     */
    fun score(entry: IptvEpgEntry, stat: IptvTitleStat?, followed: Boolean): Int {
        var s = 0
        if (followed) s += 40
        if (stat != null && stat.seen > 0) {
            s += 10
            if (stat.recent.size >= 2) s += (30 * stat.recent.count { it } / stat.recent.size)
        }
        if (entry.isNew) s += 15
        if (isRepeat(entry, stat)) s -= 25
        if (!entry.desc.isNullOrBlank()) s += 5
        if (!entry.image.isNullOrBlank()) s += 5
        if (entry.kind == IptvProgrammeKind.FILM) s += 5
        return s
    }

    /** "You finished 4 of the last 5", once there are two viewings to go on. */
    fun finishText(stat: IptvTitleStat?): String? {
        val recent = stat?.recent ?: return null
        if (recent.size < 2) return null
        val done = recent.count { it }
        return when (done) {
            0 -> null
            recent.size -> "You finished the last ${recent.size}"
            else -> "You finished $done of the last ${recent.size}"
        }
    }

    /** More than half of the last viewings (at least two) were watched to the end. */
    fun usuallyFinishes(stat: IptvTitleStat?): Boolean {
        val recent = stat?.recent ?: return false
        return recent.size >= 2 && recent.count { it } * 2 > recent.size
    }

    /** Titles watched at least [KEEP_SEEN] times in [KEEP_DAYS] days and not followed, the most finished first. */
    fun keepWatching(stats: Map<String, IptvTitleStat>, follows: List<String>, now: Long, limit: Int = 5): List<IptvTitleStat> =
        stats.values.filter { it.seen >= KEEP_SEEN && now - it.last <= KEEP_DAYS * DAY_MS && !IptvDecide.isFollowed(follows, it.title) }
            .sortedWith(compareByDescending<IptvTitleStat> { it.recent.count { done -> done } }.thenByDescending { it.seen }.thenByDescending { it.last })
            .take(limit)
}
