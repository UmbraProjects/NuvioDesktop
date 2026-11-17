package com.nuvio.app.features.catalog

import kotlinx.serialization.Serializable

sealed interface CatalogTarget {
    val contentType: String
    val supportsPagination: Boolean

    data class Addon(
        val manifestUrl: String,
        override val contentType: String,
        val catalogId: String,
        val genre: String? = null,
        // Set when the target came from a search result row, so opening the full catalog keeps
        // the query instead of dumping the addon's unfiltered catalog.
        val searchQuery: String? = null,
        override val supportsPagination: Boolean = false,
    ) : CatalogTarget

    data class Library(
        override val contentType: String,
        val sectionType: String,
    ) : CatalogTarget {
        override val supportsPagination: Boolean = false
    }

    /** A media server's library, resume row or collection (upstream's servers feature). */
    data class Server(
        val connectionId: String,
        val libraryId: String?,
        override val contentType: String,
        val collectionId: String? = null,
    ) : CatalogTarget {
        override val supportsPagination: Boolean = true
    }

    data class CollectionSource(
        val collectionId: String,
        val folderId: String,
        val sourceKey: String,
        override val contentType: String,
        override val supportsPagination: Boolean = false,
    ) : CatalogTarget

    /**
     * A generated Discover row ("Because you watched …", an AI row) opened in full. These rows are
     * built in memory rather than served by an addon, so "See all" reads the row back out of
     * [com.nuvio.app.features.discover.DiscoverRecommendationsRepository] by its key instead of
     * fetching a catalog.
     */
    data class DiscoverRow(
        val rowKey: String,
        override val contentType: String,
    ) : CatalogTarget {
        override val supportsPagination: Boolean = false
    }
}

@Serializable
enum class CatalogTargetKind {
    ADDON,
    LIBRARY,
    COLLECTION_SOURCE,
    DISCOVER_ROW,
    SERVER,
}
