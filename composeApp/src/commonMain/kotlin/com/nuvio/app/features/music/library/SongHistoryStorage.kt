package com.nuvio.app.features.music.library
// FORK UI (Spyder): Nick's album page lenses, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

/** fork:history - where each song's plays and skips are kept (desktop: the `nuvio_music_history` store). */
internal expect object SongHistoryStorage {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}
