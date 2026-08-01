package com.nuvio.app.features.home

/**
 * Session-scoped scroll memory for the Search tab, mirroring the Home and Library holders in
 * `HomeScreen.kt`.
 *
 * Unlike those two, this position is only meaningful for the results it was taken from: replaying
 * "row 4, item 7" against a different query drops the user into an unrelated place in a list they
 * have never seen. The query that produced the position is therefore part of the state, and
 * [resetIfQueryChanged] wipes it as soon as a new search runs — so the memory only ever survives the
 * round trip out to the details screen and back.
 *
 * Lives in its own file (rather than beside the other two holders) because it is the only one with
 * a rule worth testing.
 *
 * In-memory only: a fresh app launch starts at the top.
 */
internal object SearchScrollMemory {
    var query: String = ""
        private set
    var firstVisibleItemIndex: Int = 0
    var firstVisibleItemScrollOffset: Int = 0
    var immersiveRowIndex: Int = 0
    var immersiveItemIndex: Int = 0
    var hasImmersivePosition: Boolean = false

    fun resetIfQueryChanged(newQuery: String) {
        // A blank query is not a search: it is what the Home and Library tabs pass while sharing
        // the HomeScreen composable, and what Search itself shows before anything is submitted.
        // Treating it as a change would wipe the position on every Search -> Home -> Search switch.
        if (newQuery.isBlank() || newQuery == query) return
        query = newQuery
        clearPosition()
    }

    fun clearPosition() {
        firstVisibleItemIndex = 0
        firstVisibleItemScrollOffset = 0
        immersiveRowIndex = 0
        immersiveItemIndex = 0
        hasImmersivePosition = false
    }

    /** Test seam — the object is a process-wide singleton. */
    internal fun resetForTest() {
        query = ""
        clearPosition()
    }
}
