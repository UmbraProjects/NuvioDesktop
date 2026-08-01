package com.nuvio.app.features.search

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.HomeCatalogSection

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
)

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
    val emptyStateReason: DiscoverEmptyStateReason? = null,
    val errorMessage: String? = null,
) {
    val selectedCatalog: DiscoverCatalogOption?
        get() = catalogOptions.firstOrNull { it.key == selectedCatalogKey }

    val genreOptions: List<String>
        get() = selectedCatalog?.genreOptions.orEmpty()

    val canLoadMore: Boolean
        get() = nextSkip != null
}
