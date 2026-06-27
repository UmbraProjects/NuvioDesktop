package com.nuvio.app.features.home

/**
 * Controls what content [HomeScreen] shows in its catalog area.
 *
 * [Normal]  — standard home catalog rows + CW + collections.
 * [Search]  — search text input replaces the catalog area; CW/collections hidden.
 *             [autoFocusCount] increments each time the tab is re-selected so the
 *             composable can auto-focus the text field each time.
 * [Library] — library sections replace the catalog area; CW/collections hidden.
 */
sealed class HomeContentMode {
    object Normal : HomeContentMode()
    data class Search(val autoFocusCount: Int = 0) : HomeContentMode()
    object Library : HomeContentMode()
}
