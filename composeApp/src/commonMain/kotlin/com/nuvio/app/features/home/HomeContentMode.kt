package com.nuvio.app.features.home

/**
 * Controls what content [HomeScreen] shows in its catalog area.
 *
 * [Normal]  — standard home catalog rows + CW + collections.
 * [Search]  — search text input replaces the catalog area; CW/collections hidden.
 *             [autoFocusCount] increments each time the tab is re-selected so the
 *             composable can auto-focus the text field each time.
 * [Library] — library sections replace the catalog area; CW/collections hidden.
 * [Discover] — discovery rows replace the catalog area; CW/collections hidden. Everything else
 *              (hero, TV mode, poster style, scroll restoration) behaves exactly as in [Normal] —
 *              Discover is Home with different rows in it, which is why it shares this renderer.
 */
sealed class HomeContentMode {
    object Normal : HomeContentMode()
    data class Search(val autoFocusCount: Int = 0) : HomeContentMode()
    object Library : HomeContentMode()
    object Discover : HomeContentMode()
    data class Catalogs(
        val key: String,
        val sections: List<HomeCatalogSection>,
    ) : HomeContentMode()
}
