package com.nuvio.app.features.tonight

/*
 * fork:tonight - the launcher's tile art (user, Nov 12: "pull backdrops of some of the items that would be in each
 * category" instead of stock art). Each mode's tile shows the backdrop of a title that mode would actually offer
 * tonight, chosen by that mode's own rules, and no two tiles share a title. Pure, so TonightTileArtTest pins it.
 */

/**
 * One title per mode, or none when nothing it would offer has a backdrop. [pool] is tonight's bedtime-filtered pool
 * (what most modes draw from); [allTitles] is everything gathered (Ends By and The Pile show all of it).
 */
fun tonightTileArt(
    pool: List<TonightTitle>,
    allTitles: List<TonightTitle>,
    taste: TonightTaste,
    seed: Long,
    minutesUntilBed: Int,
    nowEpochMs: Long,
): Map<TonightMode, TonightTitle> {
    val taken = mutableSetOf<String>()
    val art = linkedMapOf<TonightMode, TonightTitle>()
    val withArt = { t: TonightTitle -> !t.backdrop.isNullOrBlank() }

    fun claim(mode: TonightMode, candidates: Sequence<TonightTitle>) {
        val pick = candidates.firstOrNull { withArt(it) && it.key !in taken } ?: return
        taken += pick.key
        art[mode] = pick
    }

    // The most specific modes first, so a small pool still gives each its most telling title.
    claim(TonightMode.ThePile, tonightPile(allTitles, nowEpochMs).asSequence().map { it.title })
    claim(
        TonightMode.EndsBy,
        tonightEndsByRows(allTitles, taste, minutesUntilBed).asSequence().filter { it.fits }.map { it.title },
    )
    val hand = tonightDeal(pool, taste, seed)
    claim(TonightMode.ThreeCards, hand.asSequence().filter { it.slot == TonightSlot.Safe }.map { it.title } + hand.asSequence().map { it.title })
    claim(TonightMode.ThreeQuestions, hand.asSequence().filter { it.slot != TonightSlot.Safe }.map { it.title } + pool.asSequence())
    claim(TonightMode.Knockout, tonightKnockoutEntrants(pool, taste, seed).asSequence())
    claim(TonightMode.CoinToss, listOfNotNull(tonightToss(pool, taste, emptyList(), seed)).asSequence() + pool.asSequence().sortedByDescending { it.rating ?: 0.0 })
    claim(TonightMode.ChannelFlick, tonightFlickOrder(pool, taste, seed).asSequence().filter { TonightOrigin.Trending in it.origins } + tonightFlickOrder(pool, taste, seed).asSequence())
    // The Map spreads light against heavy: a title from the far end of the pool's ratings reads as its range.
    claim(TonightMode.TheMap, pool.asSequence().sortedBy { (it.rating ?: 6.5) + (it.key.hashCode() + seed).mod(7) * 0.1 })

    // Anything still bare borrows any free backdrop the gathering found, so a tile is only plain with no art at all.
    val spare = (pool + allTitles).asSequence().distinctBy { it.key }
    for (mode in TonightMode.entries) if (mode !in art) claim(mode, spare)
    return art
}
