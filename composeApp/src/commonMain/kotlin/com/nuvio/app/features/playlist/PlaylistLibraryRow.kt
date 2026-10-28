package com.nuvio.app.features.playlist

import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import nuvio.composeapp.generated.resources.*
import com.nuvio.app.core.i18n.localizedString

/**
 * Content type of the synthetic posters the Library tab's Playlists row is made of. Like the cloud
 * library's type, it names no addon content: a poster click on one opens the playlist screen, and
 * nothing may send it to an addon, a tracker or the poster service.
 */
const val PlaylistContentType = "nuvio_playlist"

internal const val PlaylistLibraryRowKey = "nuvio_playlists_row"

fun MetaPreview.isPlaylistPreview(): Boolean = type == PlaylistContentType

/**
 * The Playlists row for the Library tab, or null when there are none — the row appears once the
 * first playlist is created from an "Add to playlist" menu, rather than as an empty shelf.
 */
internal fun playlistLibrarySection(playlists: List<Playlist>): HomeCatalogSection? {
    if (playlists.isEmpty()) return null
    return HomeCatalogSection(
        key = PlaylistLibraryRowKey,
        title = localizedString(Res.string.playlist_playlists),
        subtitle = "",
        addonName = "",
        target = null,
        items = playlists.map(Playlist::toLibraryPreview),
        inlineOnly = true,
    )
}

private fun Playlist.toLibraryPreview(): MetaPreview {
    // The card shows whatever Play would start with, so a half-watched playlist looks like where
    // it left off rather than always like its first entry.
    val cover = resumeEntry ?: entries.firstOrNull()
    val count = if (entries.size == 1) localizedString(Res.string.playlist_one_item) else localizedString(Res.string.playlist_n_items, entries.size)
    return MetaPreview(
        id = id,
        type = PlaylistContentType,
        name = name,
        poster = cover?.poster,
        // The show's backdrop, never the episode still: the Library hero is drawn from this, and an
        // episode thumbnail is a ~300px frame grab that turns to mush at hero size.
        banner = cover?.background ?: cover?.episodeThumbnail,
        description = cover?.let { "$count · Up next: ${it.displayTitle()}" } ?: count,
    )
}
