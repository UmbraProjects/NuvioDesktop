package com.nuvio.app.features.player

/**
 * In-memory map of `parentMetaId -> what the metadata says it is`, populated when a meta detail
 * screen loads (where the title's genres are known) and read by the desktop player to decide
 * whether to auto-apply the anime enhancement preset.
 *
 * Nuvio Desktop has no online anime database like Stremio-Kai, so detection is genre-based, with
 * the title's provenance disambiguating a bare "Animation" tag (see [classifyAnimeContent]).
 * Callers pass the language/country they already hold: recording genres alone would silently
 * re-introduce the Western-cartoon over-match this cache exists to feed.
 *
 * What is stored is the classification, not the yes/no verdict. The "Include Western Animation"
 * preference is applied on read, so flipping it takes effect for titles already seen this session
 * instead of waiting for their metadata to be fetched again.
 *
 * Capturing detection here decouples it from the many playback entry points, none of which carry
 * genre metadata. Titles played without first opening their detail screen (e.g. some
 * continue-watching paths) simply won't auto-detect — the in-player F10 toggle still forces the
 * preset on those.
 *
 * Follows the same object-store pattern as [PlayerLaunchStore] / StreamLaunchStore.
 */
object AnimeContentCache {
    private val kindByMetaId = mutableMapOf<String, AnimeContentKind>()

    private val animeIdPrefixes = setOf("kitsu", "mal", "myanimelist", "anilist", "al", "anidb")

    fun record(
        metaId: String,
        genres: List<String>,
        originalLanguage: String? = null,
        originCountries: Iterable<String> = emptyList(),
    ) {
        if (metaId.isBlank()) return
        kindByMetaId[metaId] = classifyAnimeContent(
            genres = genres,
            originalLanguage = originalLanguage,
            originCountries = originCountries,
        )
    }

    /** What the metadata said, ignoring preferences. Null when this title was never recorded. */
    fun kindOf(metaId: String?): AnimeContentKind? {
        if (metaId.isNullOrBlank()) return null
        if (hasAnimeNativeId(metaId)) return AnimeContentKind.Anime
        return kindByMetaId[metaId]
    }

    /**
     * The app-wide answer, with the current "Include Western Animation" preference applied.
     *
     * An anime-native id is decisive on its own and never consults genres: those namespaces only
     * catalogue anime, and such a title may well be played before any metadata has been fetched.
     */
    fun isAnime(metaId: String?): Boolean {
        val kind = kindOf(metaId) ?: return false
        if (kind == AnimeContentKind.Anime) return true
        // Only the Western-animation case consults settings, so a cold read cannot cost a real
        // anime its detection even if this somehow runs before the repository has loaded.
        PlayerSettingsRepository.ensureLoaded()
        return kind.isAnime(PlayerSettingsRepository.uiState.value.desktopAnimeTreatAnimationAsAnime)
    }

    private fun hasAnimeNativeId(metaId: String): Boolean =
        metaId.substringBefore(':', missingDelimiterValue = "").lowercase() in animeIdPrefixes

    fun clear() {
        kindByMetaId.clear()
    }
}
