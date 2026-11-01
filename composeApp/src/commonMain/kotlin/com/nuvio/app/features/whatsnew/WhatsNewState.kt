package com.nuvio.app.features.whatsnew

/**
 * What the card screen is showing: the plan, and which card of it. Pure, so the stepping rules are
 * tested without a screen. [fromSettings] is true when it was reopened from Settings rather than
 * opened by an update.
 */
internal data class WhatsNewUiState(
    val plan: WhatsNewPlan? = null,
    val index: Int = 0,
    val fromSettings: Boolean = false,
) {
    val cards: List<WhatsNewEntry> get() = plan?.cards.orEmpty()

    val visible: Boolean get() = cards.isNotEmpty()

    val current: WhatsNewEntry? get() = cards.getOrNull(index)

    val isFirst: Boolean get() = index <= 0

    val isLast: Boolean get() = index >= cards.lastIndex

    /** The spotlight is always the first card. */
    val isSpotlight: Boolean get() = index == 0

    /** Entries newer than the cards shown that did not fit. */
    val moreCount: Int get() = plan?.moreCount ?: 0

    /** One step forward; stays on the last card. */
    fun next(): WhatsNewUiState = copy(index = (index + 1).coerceAtMost(cards.lastIndex.coerceAtLeast(0)))

    /** One step back; stays on the first card. */
    fun back(): WhatsNewUiState = copy(index = (index - 1).coerceAtLeast(0))
}

internal fun whatsNewStateFor(plan: WhatsNewPlan?, fromSettings: Boolean = false): WhatsNewUiState =
    WhatsNewUiState(plan = plan, index = 0, fromSettings = fromSettings)
