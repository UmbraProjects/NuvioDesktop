package com.nuvio.app.features.library

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.locallibrary.LocalMediaItem
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.tmdb.customPosterUrl
import kotlinx.serialization.Serializable

@Serializable
data class LibraryItem(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val banner: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val runtime: String? = null,
    val imdbRating: String? = null,
    val genres: List<String> = emptyList(),
    val posterShape: PosterShape = PosterShape.Poster,
    val addonBaseUrl: String? = null,
    val listKeys: Set<String> = emptySet(),
    val traktRank: Int? = null,
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val traktId: Int? = null,
    // Bumped when the user asks to refresh this item's poster; appended as a URL fragment so the
    // image loader re-requests even the poster-service (PostersPlus/RPDB) URL, which is keyed by id.
    val posterRefreshToken: Long? = null,
    // Set only for rows whose [id] addresses a file rather than a title (the built-in debrid cloud
    // library, whose ids are provider download keys). See [MetaPreview.metaLookupId].
    val metaLookupId: String? = null,
    val metaLookupType: String? = null,
    val savedAtEpochMs: Long,
)

data class LibrarySection(
    val type: String,
    val displayTitle: String,
    val items: List<LibraryItem>,
)

enum class LibrarySourceMode {
    LOCAL,
    TRAKT,
    SIMKL,
    MDBLIST,
    YAMTRACK,
}

data class LibraryUiState(
    val sourceMode: LibrarySourceMode = LibrarySourceMode.LOCAL,
    val items: List<LibraryItem> = emptyList(),
    val sections: List<LibrarySection> = emptyList(),
    val isLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

fun MetaDetails.toLibraryItem(savedAtEpochMs: Long): LibraryItem =
    LibraryItem(
        id = id,
        type = type,
        name = name,
        poster = poster,
        banner = background,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        runtime = runtime,
        imdbRating = imdbRating,
        genres = genres,
        posterShape = PosterShape.Poster,
        imdbId = id.takeIf { it.startsWith("tt") },
        savedAtEpochMs = savedAtEpochMs,
    )

fun MetaPreview.toLibraryItem(savedAtEpochMs: Long): LibraryItem =
    LibraryItem(
        id = id,
        type = type,
        name = name,
        poster = poster,
        banner = banner,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        runtime = runtime,
        imdbRating = imdbRating,
        genres = genres,
        posterShape = posterShape,
        imdbId = id.takeIf { it.startsWith("tt") },
        savedAtEpochMs = savedAtEpochMs,
    )

fun LocalMediaItem.toLibraryItem(): LibraryItem =
    LibraryItem(
        id = contentId,
        type = contentType,
        name = title,
        poster = poster,
        // Landscape cards and the TV hero read this; without it they stretch the portrait poster.
        banner = background,
        posterShape = PosterShape.Poster,
        imdbId = imdbId,
        tmdbId = tmdbId,
        posterRefreshToken = posterRefreshToken,
        savedAtEpochMs = 0L,
    )

fun LibraryItem.toMetaPreview(): MetaPreview {
    val resolvedPoster = resolveLibraryPosterUrl(id = id, type = type, fallback = poster, refreshToken = posterRefreshToken)
    val resolvedFallback = poster.withPosterRefreshToken(posterRefreshToken)
    return MetaPreview(
        id = id,
        type = type,
        name = name,
        poster = resolvedPoster,
        posterFallback = if (resolvedPoster != resolvedFallback) resolvedFallback else null,
        // Metahub is a guess — a URL built from the IMDb id that may or may not resolve — so art
        // the item actually carries from a metadata provider (the local library's TMDB backdrop)
        // outranks it. Anything else keeps metahub as the id-derived fallback.
        banner = banner.takeIfProviderArt()
            ?: imdbId?.let { "https://images.metahub.space/background/medium/$it/img" }
            ?: banner,
        logo = logo.takeIfProviderArt()
            ?: imdbId?.let { "https://images.metahub.space/logo/medium/$it/img" }
            ?: logo,
        posterShape = posterShape,
        description = description,
        releaseInfo = releaseInfo,
        runtime = runtime,
        imdbRating = imdbRating,
        genres = genres,
        metaLookupId = metaLookupId,
        metaLookupType = metaLookupType,
    )
}

/** Art fetched from TMDB/TVDB (or per-season anime art), as opposed to an id-derived guess. */
private fun String?.takeIfProviderArt(): String? = this?.takeIf { url ->
    url.contains("image.tmdb.org", ignoreCase = true) ||
        url.contains("artworks.thetvdb.com", ignoreCase = true)
}

/**
 * Routes a library item's poster through the user's custom poster service when configured.
 *
 * The library isn't catalog-backed, so its posters are plain TMDB images. When a poster
 * template is set (e.g. PostersPlus / RPDB / a self-hosted service), this substitutes the
 * item's ids and type into the template. Placeholders: {imdb_id}, {tmdb_id}, {type}.
 * Whichever id the item lacks is substituted as empty (the service template decides what it
 * needs); if the item has no usable IMDb/TMDB id at all, the original poster is kept.
 */
private fun resolveLibraryPosterUrl(id: String, type: String, fallback: String?, refreshToken: Long?): String? {
    val custom = customPosterUrl(
        settings = TmdbSettingsRepository.snapshot(),
        imdbId = id.takeIf { it.startsWith("tt") },
        tmdbId = if (id.startsWith("tmdb:")) id.removePrefix("tmdb:").substringBefore(":") else null,
        type = type,
    )
    return (custom ?: fallback).withPosterRefreshToken(refreshToken)
}

/**
 * Appends the poster refresh token as a URL fragment. The fragment changes the image-loader cache
 * key (forcing a fresh request) but is stripped by the HTTP client, so the server — including a
 * poster service like PostersPlus — still receives a valid URL.
 */
private fun String?.withPosterRefreshToken(refreshToken: Long?): String? {
    if (this.isNullOrBlank() || refreshToken == null || this.contains("#")) return this
    return "$this#v=$refreshToken"
}
