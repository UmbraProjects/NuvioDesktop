package com.nuvio.app.features.library

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
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
    val imdbRating: String? = null,
    val genres: List<String> = emptyList(),
    val posterShape: PosterShape = PosterShape.Poster,
    val addonBaseUrl: String? = null,
    val listKeys: Set<String> = emptySet(),
    val traktRank: Int? = null,
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val traktId: Int? = null,
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
        imdbRating = imdbRating,
        genres = genres,
        posterShape = posterShape,
        imdbId = id.takeIf { it.startsWith("tt") },
        savedAtEpochMs = savedAtEpochMs,
    )

fun LibraryItem.toMetaPreview(): MetaPreview =
    MetaPreview(
        id = id,
        type = type,
        name = name,
        poster = resolveLibraryPosterUrl(id = id, type = type, fallback = poster),
        banner = banner,
        logo = logo,
        posterShape = posterShape,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        genres = genres,
    )

/**
 * Routes a library item's poster through the user's custom poster service when configured.
 *
 * The library isn't catalog-backed, so its posters are plain TMDB images. When a poster
 * template is set (e.g. PostersPlus / RPDB / a self-hosted service), this substitutes the
 * item's ids and type into the template. Placeholders: {imdb_id}, {tmdb_id}, {type}.
 * Whichever id the item lacks is substituted as empty (the service template decides what it
 * needs); if the item has no usable IMDb/TMDB id at all, the original poster is kept.
 */
private fun resolveLibraryPosterUrl(id: String, type: String, fallback: String?): String? {
    val settings = TmdbSettingsRepository.snapshot()
    if (!settings.libraryPosterEnabled) return fallback
    val template = settings.libraryPosterUrlTemplate
    if (template.isBlank()) return fallback

    val imdbId = id.takeIf { it.startsWith("tt") }.orEmpty()
    val tmdbId = if (id.startsWith("tmdb:")) id.removePrefix("tmdb:").substringBefore(":") else ""
    if (imdbId.isBlank() && tmdbId.isBlank()) return fallback

    return template
        .replace("{imdb_id}", imdbId)
        .replace("{tmdb_id}", tmdbId)
        .replace("{type}", type)
}
