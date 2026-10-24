package com.nuvio.app.features.search

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.discover.TmdbBrowseOptions
import com.nuvio.app.features.discover.TmdbBrowseQuery

enum class SearchEmptyStateReason {
    NoActiveAddons,
    NoSearchCatalogs,
    NoResults,
    RequestFailed,
}

data class SearchUiState(
    val query: String = "",
    val isLoading: Boolean = false,
    val sections: List<HomeCatalogSection> = emptyList(),
    val emptyStateReason: SearchEmptyStateReason? = null,
    val errorMessage: String? = null,
)

enum class DiscoverEmptyStateReason {
    NoActiveAddons,
    NoDiscoverCatalogs,
    NoResults,
    RequestFailed,
}

data class DiscoverCatalogOption(
    val key: String,
    val addonName: String,
    val manifestUrl: String,
    val type: String,
    val catalogId: String,
    val catalogName: String,
    val genreOptions: List<String> = emptyList(),
    val genreRequired: Boolean = false,
    val supportsPagination: Boolean = false,
) {
    /** Which Type entry the catalog sits under — see [discoverTypeGroup]. Requests keep [type]. */
    val typeGroup: String
        get() = discoverTypeGroup(type)
}

/**
 * Groups catalog types that mean the same thing under one Type entry. Addons may relabel types —
 * AIOMetadata registers its catalogs as "Films" and "TV" — while their items are ordinary films and
 * shows, so those join "movie" and "series". Lowercase `tv` is left alone: in the Stremio addon
 * protocol that is the live-TV channel type, not television series.
 */
internal fun discoverTypeGroup(type: String): String {
    val lower = type.trim().lowercase()
    return when {
        lower in setOf("movie", "movies", "film", "films") -> "movie"
        lower in setOf("series", "show", "shows") -> "series"
        lower == "tv" && type.trim() != "tv" -> "series"
        else -> type
    }
}

internal fun discoverCatalogDisplayLabels(catalogs: List<DiscoverCatalogOption>): List<String> =
    catalogs.mapIndexed { index, catalog ->
        val sameAsPrevious = catalogs.getOrNull(index - 1)
            ?.catalogName
            ?.equals(catalog.catalogName, ignoreCase = true) == true
        val sameAsNext = catalogs.getOrNull(index + 1)
            ?.catalogName
            ?.equals(catalog.catalogName, ignoreCase = true) == true
        if (sameAsPrevious || sameAsNext) {
            val typeLabel = when (catalog.type.lowercase()) {
                "movie" -> "Movie"
                "series", "show", "tv" -> "Show"
                else -> catalog.type.replaceFirstChar { it.titlecase() }
            }
            "${catalog.catalogName} ($typeLabel)"
        } else {
            catalog.catalogName
        }
    }

data class DiscoverUiState(
    val availableCatalogs: List<DiscoverCatalogOption> = emptyList(),
    val typeOptions: List<String> = emptyList(),
    val selectedType: String? = null,
    val catalogOptions: List<DiscoverCatalogOption> = emptyList(),
    val selectedCatalogKey: String? = null,
    val selectedGenre: String? = null,
    val items: List<MetaPreview> = emptyList(),
    val isLoading: Boolean = false,
    val nextSkip: Int? = null,
    val consecutiveDuplicatePages: Int = 0,
    // Whether the selected catalog pages, as learned from its first response: a full page means
    // it does even when the manifest never advertised `skip`. The browser row keys its
    // infinite-scroll mode off this, so it agrees with the feed that actually fetches the pages.
    val paginates: Boolean = false,
    val emptyStateReason: DiscoverEmptyStateReason? = null,
    val errorMessage: String? = null,
    // The built-in TMDB browser (ported from Nuvio Tracker) in place of an addon catalog. While it
    // is selected, [nextSkip] is the next TMDB page rather than an addon skip.
    val tmdbSelected: Boolean = false,
    val tmdbAvailable: Boolean = false,
    val tmdbQuery: TmdbBrowseQuery = TmdbBrowseQuery(),
    val tmdbTotal: Int = 0,
    val tmdbOptions: TmdbBrowseOptions = TmdbBrowseOptions(),
) {
    val selectedCatalog: DiscoverCatalogOption?
        get() = if (tmdbSelected) null else catalogOptions.firstOrNull { it.key == selectedCatalogKey }

    val genreOptions: List<String>
        get() = selectedCatalog?.genreOptions.orEmpty()

    val canLoadMore: Boolean
        get() = nextSkip != null
}
