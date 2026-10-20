package com.nuvio.app.features.tmdb

enum class HeroImageSource {
    /** Use addon meta endpoints (current behaviour — may return CDN-resized images). */
    Addon,
    /** TMDB for all content types. */
    TmdbOnly,
    /** TMDB for movies, TheTVDB for TV series and anime. */
    TmdbMoviesTvdbShows,
}

/** How TMDB's text and episode data treat a show or anime the addon already describes. */
enum class TmdbShowEnrichment {
    /** TMDB's title, synopsis, details, credits and episodes replace the addon's for every title. */
    Always,
    /**
     * A show or anime whose addon record comes from TheTVDB or Kitsu keeps it; TMDB only fills what
     * the record leaves blank. Cinemeta / IMDb-style records and films are upgraded as before.
     */
    SourceAware,
}

data class TmdbSettings(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val language: String = "en",
    val useTrailers: Boolean = true,
    val useArtwork: Boolean = true,
    val useBasicInfo: Boolean = true,
    val useDetails: Boolean = true,
    val useCredits: Boolean = true,
    val useProductions: Boolean = true,
    val useNetworks: Boolean = true,
    val useEpisodes: Boolean = true,
    val useSeasonPosters: Boolean = true,
    val useMoreLikeThis: Boolean = true,
    val useCollections: Boolean = true,
    // Cloud-library catalogs (TorBox, the AIOStreams library addon) list release filenames with no
    // metadata; when set, those rows are looked up on TMDB by parsed name + year. Needs an API key.
    val resolveFilenameCatalogs: Boolean = true,
    val heroImageSource: HeroImageSource = HeroImageSource.Addon,
    val showEnrichment: TmdbShowEnrichment = TmdbShowEnrichment.SourceAware,
) {
    val hasApiKey: Boolean
        get() = apiKey.isNotBlank()
}
