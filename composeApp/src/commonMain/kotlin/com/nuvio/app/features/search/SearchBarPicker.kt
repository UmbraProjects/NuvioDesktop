package com.nuvio.app.features.search

/**
 * fork:navbar-search - the keys of the top bar's search panel, kept apart from the UI so they can be tested.
 *
 * The panel lists [Prediction]s for what is typed (or the recent searches while the box is empty). Nothing is highlighted
 * at first, so Enter searches exactly what was typed. Down, Up and Tab walk the list and put the highlighted line in the
 * box; while walking, the list is held still ([held]) so it does not change under the highlight as the box's text changes.
 * Up past the top line, or typing, gives back what was typed.
 *
 * - Down: next line (stops at the last).
 * - Up: previous line; from the first, back to what was typed.
 * - Tab: the top line, then each next one, round again after the last.
 */
internal data class SearchBarPicker(
    /** What the person typed, given back when they leave the list. */
    val typed: String = "",
    /** The list being walked, or null while none is (the panel then shows the live list). */
    val held: List<Prediction>? = null,
    /** The highlighted line in [held], or -1. */
    val index: Int = -1,
) {
    /** What the panel lists, given the [live] list for the box's text. */
    fun shown(live: List<Prediction>): List<Prediction> = held ?: live

    /** What the box should hold. */
    val fieldText: String get() = held?.getOrNull(index)?.text ?: typed

    /** The person typed [text] (or cleared the box). */
    fun typed(text: String): SearchBarPicker = SearchBarPicker(typed = text)

    fun down(live: List<Prediction>): SearchBarPicker {
        val list = held ?: live
        if (list.isEmpty()) return this
        return copy(held = list, index = (index + 1).coerceAtMost(list.lastIndex))
    }

    fun up(live: List<Prediction>): SearchBarPicker {
        if (held == null || index < 0) return this
        return if (index == 0) SearchBarPicker(typed = typed) else copy(index = index - 1)
    }

    fun tab(live: List<Prediction>): SearchBarPicker {
        val list = held ?: live
        if (list.isEmpty()) return this
        return copy(held = list, index = if (index < 0) 0 else (index + 1) % list.size)
    }
}
