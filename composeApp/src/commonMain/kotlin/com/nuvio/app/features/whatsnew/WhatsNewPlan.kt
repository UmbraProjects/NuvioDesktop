package com.nuvio.app.features.whatsnew

import kotlin.math.max

/** Cards in one visit: the spotlight and up to four small ones. */
internal const val WHATS_NEW_MAX_CARDS = 5

/**
 * What one visit shows: a [spotlight] card, then [small] cards, and how many more there were than
 * fit ([moreCount], reached from Settings).
 */
internal data class WhatsNewPlan(
    val spotlight: WhatsNewEntry,
    val small: List<WhatsNewEntry>,
    val moreCount: Int,
) {
    val cards: List<WhatsNewEntry> get() = listOf(spotlight) + small
}

/**
 * The cards for entries newer than [lastSeen], or null when there is nothing new.
 *
 * The spotlight is the newest unseen entry flagged as one, or the newest entry when none is. After
 * skipping several builds that can be an older build's headline, which is the point: a catch-up
 * still opens on something worth stopping for. The rest follow newest first, up to [maxCards] in
 * all, and [WhatsNewPlan.moreCount] says how many were left out.
 */
internal fun planWhatsNew(
    entries: List<WhatsNewEntry>,
    lastSeen: Int,
    maxCards: Int = WHATS_NEW_MAX_CARDS,
): WhatsNewPlan? = planFrom(entries.filter { it.number > lastSeen }, maxCards)

/** Every entry, newest first, whether or not it was seen, for the Settings row. */
internal fun planWhatsNewHistory(entries: List<WhatsNewEntry>): WhatsNewPlan? =
    planFrom(entries, Int.MAX_VALUE)

private fun planFrom(candidates: List<WhatsNewEntry>, maxCards: Int): WhatsNewPlan? {
    val newestFirst = candidates.sortedByDescending { it.number }.distinctBy { it.number }
    val spotlight = newestFirst.firstOrNull { it.weight == WhatsNewWeight.Spotlight }
        ?: newestFirst.firstOrNull()
        ?: return null
    val rest = newestFirst.filter { it.number != spotlight.number }
    val small = rest.take((maxCards - 1).coerceAtLeast(0))
    return WhatsNewPlan(spotlight = spotlight, small = small, moreCount = rest.size - small.size)
}

/** What to do when the app starts. */
internal sealed interface WhatsNewLaunch {
    /** Nothing is newer than what was seen. */
    data object NothingNew : WhatsNewLaunch

    /** A fresh account: record [lastSeen] as seen and show nothing. */
    data class Seed(val lastSeen: Int) : WhatsNewLaunch

    /** Show these cards. */
    data class Show(val plan: WhatsNewPlan) : WhatsNewLaunch
}

/**
 * Decides at launch. [lastSeen] is null until a number has been recorded for this account.
 *
 * A fresh account is seeded with the newest number and sees nothing, so someone new is not handed a
 * history lesson straight after the setup wizard. An account that already existed when this feature
 * arrived has no number yet and is treated as having seen nothing, so its first update shows the
 * cards. Once a number is recorded [freshAccount] no longer matters, so it never re-seeds.
 */
internal fun decideWhatsNewOnLaunch(
    entries: List<WhatsNewEntry>,
    lastSeen: Int?,
    freshAccount: Boolean,
    maxCards: Int = WHATS_NEW_MAX_CARDS,
): WhatsNewLaunch {
    if (lastSeen == null && freshAccount) return WhatsNewLaunch.Seed(newestWhatsNewNumber(entries))
    val plan = planWhatsNew(entries, lastSeen ?: 0, maxCards) ?: return WhatsNewLaunch.NothingNew
    return WhatsNewLaunch.Show(plan)
}

internal fun newestWhatsNewNumber(entries: List<WhatsNewEntry>): Int =
    entries.maxOfOrNull { it.number } ?: 0

/**
 * The number to record when the screen closes, however it closed. Everything newer counts as seen,
 * including entries that did not fit (they stay reachable from Settings), and the number never
 * goes backwards, so reopening an old build's cards cannot make them new again.
 */
internal fun lastSeenAfterClosing(entries: List<WhatsNewEntry>, previous: Int?): Int =
    max(previous ?: 0, newestWhatsNewNumber(entries))
