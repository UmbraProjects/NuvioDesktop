package com.nuvio.app.features.music.library

/**
 * Phase 4c: release palettes are cached on [LibraryRelease.palette], keyed by the cover URL they were
 * derived for ([com.nuvio.app.features.music.identity.ArtworkPalette.forUrl]).
 */

/** True when this release has a cover but no palette for it yet (or the cover has changed). */
internal val LibraryRelease.needsPalette: Boolean
    get() = art.cover != null && palette?.forUrl != art.cover

/**
 * A refresh rebuilds releases from the sources and would drop their cached palettes. This carries
 * them over from [old] by release key; [needsPalette] still catches a changed cover.
 */
internal fun LibraryArtist.keepingPalettesFrom(old: LibraryArtist?): LibraryArtist {
    val byKey = old?.releases.orEmpty().mapNotNull { r -> r.palette?.let { r.key to it } }.toMap()
    if (byKey.isEmpty()) return this
    return copy(releases = releases.map { r -> if (r.palette == null) byKey[r.key]?.let { r.copy(palette = it) } ?: r else r })
}
