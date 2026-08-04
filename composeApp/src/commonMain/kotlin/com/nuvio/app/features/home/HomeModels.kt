package com.nuvio.app.features.home

import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.catalog.CatalogTarget

data class MetaPreview(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val posterFallback: String? = null,
    val banner: String? = null,
    val logo: String? = null,
    val posterShape: PosterShape = PosterShape.Poster,
    val description: String? = null,
    val releaseInfo: String? = null,
    val rawReleaseDate: String? = null,
    val popularity: Double? = null,
    val voteCount: Int? = null,
    val imdbRating: String? = null,
    val ageRating: String? = null,
    val runtime: String? = null,
    val genres: List<String> = emptyList(),
    val cast: List<HeroCastMember> = emptyList(),
    // Used by virtual catalog cards that compose several loaded posters into one piece of art.
    val posterCollage: List<String> = emptyList(),
    // Navigation provenance, not content metadata. A title can appear in both a local-library
    // catalog and a remote watchlist, so the playback policy must follow the row that was opened.
    val preferLocalStreams: Boolean = false,
    // Metadata identity for rows whose own [id] addresses a *file* rather than a title. Cloud
    // library catalogs (AIOStreams, TorBox) list account contents, so a row arrives as
    // `aiostreams::library.…` / `type=library` and resolves to no metadata anywhere — no genres,
    // no synopsis, no ratings. FilenameMetaResolver fills these in from the release name it
    // matched, and every metadata lookup (hero enrichment, MDBList ratings, cast, quality badges)
    // uses them in place of [id]/[type].
    //
    // Deliberately NOT used for navigation, streams or playback: those must keep addressing the
    // provider's own row, which is the whole point of a cloud-library catalog.
    val metaLookupId: String? = null,
    val metaLookupType: String? = null,
) {
    /** The id metadata lookups should use — the resolved title's, when the row's own id is a file. */
    val metadataId: String
        get() = metaLookupId?.takeIf { it.isNotBlank() } ?: id

    /** The type metadata lookups should use; a cloud-library row's own type is `library`. */
    val metadataType: String
        get() = metaLookupType?.takeIf { it.isNotBlank() } ?: type
}

data class HeroCastMember(
    val name: String,
    val photo: String? = null,
    val role: String? = null,
    val tmdbId: Int? = null,
)

fun MetaPreview.stableKey(): String = "$type:$id"

/** [MetaPreview.type] used for a hero item that represents a whole Collection rather than a
 *  single piece of content (e.g. its own curated backdrop image, not a movie/show poster). */
internal const val COLLECTION_HERO_TYPE = "collection"

enum class PosterShape {
    Poster,
    Square,
    Landscape,
}

data class HomeCatalogSection(
    val key: String,
    val title: String,
    val subtitle: String,
    val addonName: String,
    val target: CatalogTarget,
    val items: List<MetaPreview>,
    val availableItemCount: Int = items.size,
    val hasMore: Boolean = false,
    // Stable: whether this catalog is a horizontal infinite-scroll row (the addon supports skip-based
    // paging). Decides whether the row renders all loaded items + paginates vs. a capped preview + pill.
    // Does NOT change when the catalog is exhausted, so a fully-loaded row never collapses back.
    val paginates: Boolean = false,
    // Skip offset for the next page; null once the catalog is exhausted or for non-paginating catalogs.
    val nextSkip: Int? = null,
    // True while the next page is being fetched (drives the trailing spinner).
    val isLoadingMore: Boolean = false,
)

fun HomeCatalogSection.canOpenCatalog(previewLimit: Int): Boolean =
    availableItemCount > previewLimit || hasMore

fun HomeCatalogSection.usesInfiniteHomeRow(catalogSeeMoreEnabled: Boolean): Boolean =
    paginates && !catalogSeeMoreEnabled

/**
 * Guarantees every [HomeCatalogSection.key] in the list is unique by suffixing collisions
 * (`key`, `key#1`, `key#2`, …). LazyColumn/LazyRow throw "Key … was already used" — which on
 * Desktop crashes the whole app (the error dialog's OK button closes it) — when two items in the
 * same list share a key, and two catalogs can legitimately resolve to the same section key: an
 * addon exposing a catalog twice, two installed addons reporting an identical manifest id, or two
 * collection tabs sharing a label. Applying this at each data source keeps every consumer safe,
 * including the TV-mode lists that key items directly rather than via [withDuplicateSafeLazyKeys].
 */
fun List<HomeCatalogSection>.ensureUniqueKeys(): List<HomeCatalogSection> {
    val occurrences = HashMap<String, Int>()
    return map { section ->
        val occurrence = occurrences.getOrElse(section.key) { 0 }
        occurrences[section.key] = occurrence + 1
        if (occurrence == 0) section else section.copy(key = "${section.key}#$occurrence")
    }
}

data class HomeUiState(
    val isLoading: Boolean = false,
    val heroItems: List<MetaPreview> = emptyList(),
    val sections: List<HomeCatalogSection> = emptyList(),
    val errorMessage: String? = null,
)

internal data class CatalogRequest(
    val addon: ManagedAddon,
    val catalogId: String,
    val catalogName: String,
    val type: String,
    val supportsPagination: Boolean,
)
