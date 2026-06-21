package com.nuvio.app.features.player

/**
 * In-memory map of `parentMetaId -> isAnime`, populated when a meta detail screen loads (where the
 * title's genres are known) and read by the desktop player to decide whether to auto-apply the
 * anime enhancement preset.
 *
 * Nuvio Desktop has no online anime database like Stremio-Kai, so detection is genre-based (see
 * [isAnimeFromGenres]). Capturing it here decouples the detection point (meta load) from the many
 * playback entry points, none of which carry genre metadata. Titles played without first opening
 * their detail screen (e.g. some continue-watching paths) simply won't auto-detect — the in-player
 * F10 toggle still forces the preset on those.
 *
 * Follows the same object-store pattern as [PlayerLaunchStore] / StreamLaunchStore.
 */
object AnimeContentCache {
    private val animeByMetaId = mutableMapOf<String, Boolean>()

    fun record(metaId: String, genres: List<String>) {
        if (metaId.isBlank()) return
        animeByMetaId[metaId] = isAnimeFromGenres(genres)
    }

    fun isAnime(metaId: String?): Boolean = metaId?.let { animeByMetaId[it] } ?: false

    fun clear() {
        animeByMetaId.clear()
    }
}
