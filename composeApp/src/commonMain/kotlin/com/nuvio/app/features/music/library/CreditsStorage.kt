package com.nuvio.app.features.music.library
// FORK UI (Spyder): Nick's album page lenses, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

/** fork:credits - every album's kept credits (desktop: the `nuvio_music_credits` store, no cap). */
internal expect object CreditsStorage {
    fun get(key: String): String?
    fun put(key: String, value: String?)
    fun keys(): Set<String>
}
