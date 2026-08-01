package com.nuvio.app.features.tmdb

/**
 * Builds a poster URL from the user's custom poster-service template (PostersPlus, RPDB, a
 * self-hosted service, …). Placeholders: `{imdb_id}`, `{tmdb_id}`, `{type}`; whichever id the
 * caller lacks is substituted as empty, since the template decides which one it actually needs.
 *
 * Returns null when the template is off/blank or neither id is known — callers then keep whatever
 * poster they already had (a plain TMDB image, or the addon's own).
 */
internal fun customPosterUrl(
    settings: TmdbSettings,
    imdbId: String?,
    tmdbId: String?,
    type: String,
): String? {
    if (!settings.libraryPosterEnabled) return null
    val template = settings.libraryPosterUrlTemplate
    if (template.isBlank()) return null

    val imdb = imdbId.orEmpty()
    val tmdb = tmdbId.orEmpty()
    if (imdb.isBlank() && tmdb.isBlank()) return null

    return template
        .replace("{imdb_id}", imdb)
        .replace("{tmdb_id}", tmdb)
        .replace("{type}", type)
}

/** True when the configured template can only be filled in with an IMDb id. */
internal fun TmdbSettings.customPosterTemplateNeedsImdbId(): Boolean =
    libraryPosterEnabled && libraryPosterUrlTemplate.contains("{imdb_id}")
