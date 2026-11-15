package com.nuvio.app.features.music.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.ViewColumn
import androidx.compose.material.icons.rounded.ViewModule
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The Library screen's views, in header order. The choice is remembered like the Library/Search choice in
 * `MusicTabScreen`. Each can be hidden in Settings > Music ([MusicLibrarySettings.hiddenLibraryViews]).
 *
 *  - [Shelf]: one row per artist with a rail of covers ([ClassicLibraryShelf]).
 *  - [Crates]: the collection in three panels, pages that turn ([ShelfBrowser]).
 *  - [Constellation]: the library as a map of similar artists ([ConstellationView]).
 *  - [Eras]: releases by year ([LibraryErasView]).
 *  - [Playlists].
 */
internal enum class LibraryView(val label: String, val icon: ImageVector, val summary: String) {
    Shelf("Shelf", Icons.Rounded.ViewModule, "One row per artist, with every album on a rail of covers"),
    Crates("Crates", Icons.Rounded.ViewColumn, "Artists, their albums and the album in focus, in three panels"),
    Constellation("Constellation", Icons.Rounded.Hub, "Your artists as a map, near the artists they sound like"),
    Eras("Eras", Icons.Rounded.Timeline, "Your albums laid out by the year they came out"),
    Playlists("Playlists", Icons.Rounded.QueueMusic, "Your playlists"),
}

/** The views switched on, in header order; never empty (with every one hidden, Crates still shows). */
internal fun MusicLibrarySettings.visibleLibraryViews(): List<LibraryView> =
    LibraryView.entries.filter { it.name !in hiddenLibraryViews }.ifEmpty { listOf(LibraryView.Crates) } // fork: the three-panel browser is this fork's Shelf

/** [current] if it is still switched on, else the first view that is. */
internal fun MusicLibrarySettings.shownLibraryView(current: LibraryView): LibraryView =
    visibleLibraryViews().let { if (current in it) current else it.first() }
