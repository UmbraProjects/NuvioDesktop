package com.nuvio.app.features.tmdb

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
    // Custom poster service for library items (which aren't catalog-backed and otherwise
    // show plain TMDB posters). The template is a full URL with {imdb_id}/{tmdb_id}/{type}
    // placeholders, e.g. a PostersPlus/RPDB/etc. endpoint. Applied only to the library.
    val libraryPosterEnabled: Boolean = false,
    val libraryPosterUrlTemplate: String = "",
) {
    val hasApiKey: Boolean
        get() = apiKey.isNotBlank()
}
