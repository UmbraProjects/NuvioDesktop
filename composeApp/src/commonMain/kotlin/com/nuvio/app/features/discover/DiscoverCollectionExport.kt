package com.nuvio.app.features.discover

import com.nuvio.app.features.collection.Collection
import com.nuvio.app.features.collection.CollectionFolder
import com.nuvio.app.features.collection.CollectionSource
import com.nuvio.app.features.collection.TmdbCollectionFilters
import com.nuvio.app.features.collection.TmdbCollectionMediaType
import com.nuvio.app.features.collection.TmdbCollectionSourceType
import kotlinx.serialization.json.Json

/**
 * A custom Discover row as TMDB-backed collection sources — plan §6.2 / §6.3 / §11.
 *
 * One conversion serves two features, which is the whole point of it existing:
 *
 * - **Save as Collection** (§6.3) promotes a good row onto Home as a normal collection.
 * - **The AIOMetadata export** (§6.2) writes the same collection to a file. Their importer reads
 *   `provider`/`tmdbSourceType`/`mediaType`/`sortBy`/`filters` off a native source and rebuilds the
 *   catalog on their side, so we emit the query and never an AIOMetadata catalog id — those are
 *   per-config and resolve to nothing on anyone else's instance.
 *
 * Genre ids are a parameter rather than resolved here because the same genre name has different
 * ids in TMDB's film and television namespaces, and resolving them is a network call. Keeping it
 * out leaves this a pure function over the row.
 */
data class DiscoverCollectionExport(
    val sources: List<CollectionSource>,
    /**
     * Filters the row carries that no collection source can express, named for the UI to report.
     *
     * Reported rather than silently applied-or-not because the exported query is *broader* than the
     * row without them: a user who filtered to "released, PG-13" and exports gets a catalog that is
     * neither, and nothing on the receiving side will ever say so.
     */
    val droppedFilters: List<DroppedDiscoverFilter>,
)

enum class DroppedDiscoverFilter {
    /** `with_release_type` / `with_status`. No `CollectionSource` field, no AIOMetadata equivalent. */
    Status,

    /** Film certification. Needs `certification_country` alongside it, which the model has nowhere to put. */
    Certification,

    /**
     * Cast and crew on a television source. TMDB's tv endpoint has no people filter, so the row's
     * own fetch already drops these — the export is not losing anything the row had.
     */
    PeopleOnSeries,
}

/**
 * Builds the sources for [this] row.
 *
 * [genreIdsByType] maps `"movie"` and `"tv"` to the ids of the row's genres in that namespace.
 * [movieSuffix] and [seriesSuffix] disambiguate the two halves of a `Both` row, mirroring what the
 * collection editor does when one picker choice becomes two sources.
 */
fun CustomDiscoverRow.toCollectionSources(
    genreIdsByType: Map<String, List<Int>>,
    movieSuffix: String? = null,
    seriesSuffix: String? = null,
): DiscoverCollectionExport {
    val mediaTypes = when (mediaType) {
        CustomDiscoverMediaType.Movies -> listOf(TmdbCollectionMediaType.MOVIE)
        CustomDiscoverMediaType.Shows -> listOf(TmdbCollectionMediaType.TV)
        // A source is one namespace or the other, so a Both row is two sources in one folder.
        CustomDiscoverMediaType.Both ->
            listOf(TmdbCollectionMediaType.MOVIE, TmdbCollectionMediaType.TV)
    }
    val dropped = buildList {
        if (status != CustomDiscoverStatus.Any) add(DroppedDiscoverFilter.Status)
        if (certification.isNotBlank()) add(DroppedDiscoverFilter.Certification)
        if ((cast.isNotEmpty() || crew.isNotEmpty()) && TmdbCollectionMediaType.TV in mediaTypes) {
            add(DroppedDiscoverFilter.PeopleOnSeries)
        }
    }

    val sources = mediaTypes.map { type ->
        val apiType = type.value
        val genreIds = genreIdsByType[apiType].orEmpty()
        CollectionSource(
            provider = "tmdb",
            tmdbSourceType = TmdbCollectionSourceType.DISCOVER.name,
            title = exportTitle(type, mediaTypes.size > 1, movieSuffix, seriesSuffix),
            mediaType = type.name,
            sortBy = sort.tmdbSortBy(apiType),
            filters = TmdbCollectionFilters(
                // `,` ANDs and `|` ORs, which is exactly what matchAllGenres means.
                withGenres = genreIds.takeIf { it.isNotEmpty() }
                    ?.joinToString(if (matchAllGenres) "," else "|") { it.toString() },
                releaseDateGte = fromYear.takeIf { it > 0 }?.let { "$it-01-01" },
                releaseDateLte = toYear.takeIf { it > 0 }?.let { "$it-12-31" },
                voteAverageGte = minRating.takeIf { it > 0 }?.toDouble(),
                // The rating-sort floor travels with the query. A catalog service handed this
                // without it returns the four-votes-and-a-ten tail, which reads as a broken row.
                voteCountGte = effectiveMinVotes(),
                withOriginalLanguage = language.takeIf { it.isNotBlank() },
                withRuntimeGte = minRuntime.takeIf { it > 0 },
                withRuntimeLte = maxRuntime.takeIf { it > 0 },
                withCompanies = companies.orJoinedIds(),
                // Films only, and merged: TMDB's tv endpoint has no people filter, and the one
                // field downstream consumers read is `with_people`, not the cast/crew pair.
                withPeople = if (type == TmdbCollectionMediaType.MOVIE) {
                    (cast + crew).orJoinedIds()
                } else {
                    null
                },
            ),
        )
    }

    return DiscoverCollectionExport(sources = sources, droppedFilters = dropped)
}

private fun CustomDiscoverRow.exportTitle(
    type: TmdbCollectionMediaType,
    split: Boolean,
    movieSuffix: String?,
    seriesSuffix: String?,
): String {
    val base = title.trim().ifBlank { return "" }
    if (!split) return base
    val suffix = if (type == TmdbCollectionMediaType.MOVIE) movieSuffix else seriesSuffix
    return suffix?.takeIf { it.isNotBlank() }?.let { "$base $it" } ?: base
}

/**
 * Wraps [sources] as a one-folder collection — the artefact both destinations use.
 *
 * Ids are parameters so this stays pure; the caller supplies them. One folder rather than one per
 * source because a `Both` row's two halves are two views of a single question, and AIOMetadata's
 * importer reads sources off the folder either way.
 */
fun discoverRowCollection(
    title: String,
    sources: List<CollectionSource>,
    collectionId: String,
    folderId: String,
): Collection = Collection(
    id = collectionId,
    title = title,
    folders = listOf(
        CollectionFolder(
            id = folderId,
            title = title,
            sources = sources,
        ),
    ),
)

/**
 * The collection as the file AIOMetadata imports.
 *
 * **A bare array, not an object.** Their `detectFormat` reads an array whose first element carries
 * `folders` as Nuvio's own format, and `CollectionRepository.importFromJson` decodes the same
 * shape — so one file round-trips locally and imports there without a second export path.
 *
 * `encodeDefaults` is on so the document states its own defaults rather than relying on the reader
 * agreeing with ours about what a missing `tileShape` or `hideTitle` means.
 */
fun encodeCollectionsExport(collection: Collection): String =
    collectionExportJson.encodeToString(listOf(collection))

private val collectionExportJson = Json {
    prettyPrint = true
    encodeDefaults = true
}

/** Same rules as [discoverCatalogFileName], with a name that says what the file is. */
fun discoverCollectionFileName(title: String): String =
    "nuvio-collection-" + discoverCatalogFileName(title)
