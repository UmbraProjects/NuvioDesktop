package com.nuvio.app.features.tonight

import kotlin.random.Random

/*
 * fork:tonight - Three Cards (design-notes/tonight-picker.md): only three titles, deliberately
 * different. Safe = like what you finish; New to you = unseen but inside your taste;
 * Wildcard = outside your usual taste. One reshuffle a night deals three others.
 */

/**
 * Deals up to three cards from [pool], never one of [exclude] (keys of cards already dealt
 * tonight). [seed] makes a deal repeatable: the same seed and pool give the same cards.
 * Each card is drawn at random from the best [pickFrom] of its kind (Settings, 1 to 20; 5 by
 * default). Fewer than three come back only when the pool has fewer than three titles left.
 */
fun tonightDeal(
    pool: List<TonightTitle>,
    taste: TonightTaste,
    seed: Long,
    exclude: Set<String> = emptySet(),
    pickFrom: Int = TONIGHT_PICK_FROM_TOP_DEFAULT,
): List<TonightCard> {
    val random = Random(seed)
    val topCount = pickFrom.coerceAtLeast(1)
    val candidates = pool.filter { it.key !in exclude }.distinctBy { it.key }
    if (candidates.isEmpty()) return emptyList()
    val taken = linkedSetOf<String>()
    val usedGenres = linkedSetOf<String>()

    fun TonightTitle.free() = key !in taken
    fun TonightTitle.freshGenre() = genres.firstOrNull()?.genreKey()?.let { it !in usedGenres } ?: true
    fun rating10(t: TonightTitle) = ((t.rating ?: 6.5) / 10.0).toFloat()

    fun pick(list: List<TonightTitle>, score: (TonightTitle) -> Float): TonightTitle? {
        val open = list.filter { it.free() }
        if (open.isEmpty()) return null
        // Prefer a genre not on the table yet, so the three read as three different evenings.
        val varied = open.filter { it.freshGenre() }.ifEmpty { open }
        val top = varied.sortedByDescending(score).take(topCount)
        return top[random.nextInt(top.size)]
    }

    fun take(t: TonightTitle?): TonightTitle? = t?.also {
        taken += it.key
        it.genres.firstOrNull()?.let { g -> usedGenres += g.genreKey() }
    }

    val unseen = candidates.filter { !it.watched && !it.started }
    val median = unseen.map(taste::score).sorted().let { if (it.isEmpty()) 0.5f else it[it.size / 2] }

    val safe = take(
        pick(candidates.filter { it.started || it.watched }) { t ->
            taste.score(t) * 0.5f + rating10(t) * 0.3f + (if (t.started) 0.4f else 0f)
        } ?: pick(candidates) { t -> taste.score(t) * 0.7f + rating10(t) * 0.3f },
    )
    val new = take(
        pick(unseen.filter { taste.score(it) >= median }) { t -> taste.score(t) * 0.5f + rating10(t) * 0.5f },
    )
    val wild = take(
        pick(unseen.filter { taste.score(it) < median }) { t -> rating10(t) - taste.score(t) * 0.3f },
    )

    val cards = mutableListOf<TonightCard>()
    safe?.let { cards += TonightCard(TonightSlot.Safe, it, safeWhy(it)) }
    new?.let { cards += TonightCard(TonightSlot.New, it, newWhy(it, taste)) }
    wild?.let { cards += TonightCard(TonightSlot.Wildcard, it, "Off your usual path") }

    // A small or one-sided pool can leave a slot empty: fill it with the best of what is left, so
    // there are three cards whenever there are three titles.
    for (slot in TonightSlot.entries) {
        if (cards.size >= 3) break
        if (cards.any { it.slot == slot }) continue
        val fillScore = { t: TonightTitle -> taste.score(t) * 0.5f + rating10(t) * 0.5f }
        // New and Wildcard stay unseen whenever anything unseen is left.
        val preferred = if (slot == TonightSlot.Safe) null else pick(unseen, fillScore)
        val filler = take(preferred ?: pick(candidates, fillScore)) ?: break
        cards += TonightCard(slot, filler, fillerWhy(slot, filler, taste))
    }
    return cards.sortedBy { it.slot.ordinal }
}

private fun safeWhy(t: TonightTitle): String = when {
    t.started && t.nextEpisodeLabel != null -> "You're on ${t.nextEpisodeLabel}"
    t.started -> "You're part way through"
    t.watched -> "An old favourite"
    else -> "Right up your street"
}

private fun newWhy(t: TonightTitle, taste: TonightTaste): String =
    t.recommendedBy
        ?: taste.topGenre(t)?.let { "New to you, and you like ${it.lowercase()}" }
        ?: "New to you"

private fun fillerWhy(slot: TonightSlot, t: TonightTitle, taste: TonightTaste): String = when (slot) {
    TonightSlot.Safe -> safeWhy(t)
    TonightSlot.New -> newWhy(t, taste)
    TonightSlot.Wildcard -> "Off your usual path"
}

/** The label a slot wears on its card. */
fun TonightSlot.label(): String = when (this) {
    TonightSlot.Safe -> "SAFE"
    TonightSlot.New -> "NEW TO YOU"
    TonightSlot.Wildcard -> "WILDCARD"
}
