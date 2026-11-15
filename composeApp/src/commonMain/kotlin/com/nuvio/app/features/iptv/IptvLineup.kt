package com.nuvio.app.features.iptv

/*
 * fork:live-tv-lineup - "Your 40": the whole of Live TV as a numbered lineup of about forty channels, like a
 * television. The numbers are positions in a stored list, so they never move by themselves; the viewer moves,
 * adds and removes. "Never again" takes a channel out of the lineup, the On Now wall and the lists for good
 * (search still finds it, and Settings brings it back). Pure Kotlin, tested by IptvLineupTest.
 */
object IptvLineupRules {
    /** The most channels in the lineup. */
    const val LIMIT = 40

    /** The sidebar section id of the lineup. */
    const val SECTION = "__lineup__"

    /** Whether [channel] is one of the channels thrown out ([never] holds the key of every copy). */
    fun isNever(channel: IptvMergedChannel, never: Set<String>): Boolean = channel.sources.any { it.key in never }

    /** [merged] without the channels thrown out. */
    fun visible(merged: List<IptvMergedChannel>, never: Set<String>): List<IptvMergedChannel> =
        if (never.isEmpty()) merged else merged.filterNot { isNever(it, never) }

    /**
     * The first lineup: what is watched most and what is starred (a channel with plays first, then the starred),
     * then filled from what the triage kept in the playlist's own order, up to [limit]. Channels thrown out never
     * enter it. [plays] and [favourites] are channel refs, [refOf] makes one from a copy.
     */
    fun seed(
        merged: List<IptvMergedChannel>,
        refOf: (IptvChannel) -> String,
        plays: List<String>,
        favourites: Collection<String>,
        never: Set<String>,
        keptCategories: Set<String>?,
        limit: Int = LIMIT,
    ): List<String> {
        val allowed = visible(merged, never).filter { keptCategories.keeps(it) }
        val watched = plays.groupingBy { it }.eachCount()
        val starred = favourites.toHashSet()
        fun playCount(channel: IptvMergedChannel) = channel.sources.sumOf { watched[refOf(it)] ?: 0 }
        fun isStarred(channel: IptvMergedChannel) = channel.sources.any { refOf(it) in starred }
        val earned = allowed.filter { playCount(it) > 0 || isStarred(it) }
            .sortedWith(compareByDescending<IptvMergedChannel> { playCount(it) }.thenByDescending { isStarred(it) })
            .take(limit)
        val chosen = earned.toHashSet()
        val filled = earned + allowed.filter { it !in chosen }.take((limit - earned.size).coerceAtLeast(0))
        return filled.map { it.primary.key }
    }

    /** The channels the stored keys stand for, in order; a key whose channel is gone is skipped. */
    fun resolve(lineup: List<String>, byKey: Map<String, IptvMergedChannel>): List<IptvMergedChannel> =
        lineup.mapNotNull { byKey[it] }.distinct()

    /** Every copy's key to its merged channel (the first one wins a key that two channels share). */
    fun keyIndex(merged: List<IptvMergedChannel>): Map<String, IptvMergedChannel> =
        buildMap { merged.forEach { channel -> channel.sources.forEach { putIfAbsent(it.key, channel) } } }

    /** [channel]'s place in the stored list, or -1. */
    fun position(lineup: List<String>, channel: IptvMergedChannel): Int {
        val keys = channel.sources.mapTo(HashSet()) { it.key }
        return lineup.indexOfFirst { it in keys }
    }

    /** The lineup with [channel] at the end; null when it is already full. */
    fun add(lineup: List<String>, channel: IptvMergedChannel, limit: Int = LIMIT): List<String>? {
        if (position(lineup, channel) >= 0) return lineup
        if (lineup.size >= limit) return null
        return lineup + channel.primary.key
    }

    fun remove(lineup: List<String>, channel: IptvMergedChannel): List<String> {
        val at = position(lineup, channel)
        return if (at < 0) lineup else lineup.filterIndexed { index, _ -> index != at }
    }

    /** [channel] [delta] places up (negative) or down; it stops at either end. */
    fun move(lineup: List<String>, channel: IptvMergedChannel, delta: Int): List<String> {
        val at = position(lineup, channel)
        if (at < 0) return lineup
        val to = (at + delta).coerceIn(0, lineup.lastIndex)
        if (to == at) return lineup
        val moved = lineup.toMutableList()
        moved.add(to, moved.removeAt(at))
        return moved
    }

    /** The keys of every copy of [channel], for the thrown-out set. */
    fun neverKeys(channel: IptvMergedChannel): Set<String> = channel.sources.mapTo(HashSet()) { it.key }

    /** The channel typed as [number] on the remote: the lineup's place [number], counted from 1. */
    fun byNumber(lineup: List<IptvChannel>, number: Int): IptvChannel? = lineup.getOrNull(number - 1)
}

/** What the channel menu can do. */
enum class IptvLineupAction(val label: String) {
    MoveUp("Move up"),
    MoveDown("Move down"),
    Add("Add to Your 40"),
    Remove("Take out of Your 40"),
    Never("Never again"),
    ;

    companion object {
        /**
         * The actions for a channel: [position] is its place in the lineup (-1 when out), [size] the lineup's length.
         * A full lineup offers no Add.
         */
        fun forChannel(position: Int, size: Int, limit: Int = IptvLineupRules.LIMIT): List<IptvLineupAction> = buildList {
            if (position >= 0) {
                if (position > 0) add(MoveUp)
                if (position < size - 1) add(MoveDown)
                add(Remove)
            } else if (size < limit) {
                add(Add)
            }
            add(Never)
        }
    }
}
