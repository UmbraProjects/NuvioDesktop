package com.nuvio.app.features.discover

import com.nuvio.app.features.home.MetaPreview
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The interchange format for a Discover row — plan §6.1.
 *
 * Self-describing on purpose: [format] and [version] are the first two fields so a file can be
 * identified without knowing where it came from, and every per-service exporter §6.2 eventually
 * adds is a pure transform of this shape rather than a second thing that has to be kept in step
 * with the rows.
 *
 * Deliberately **not** the same model as [DiscoverRowCacheEntry]. They look similar and are not:
 * the cache is a private, versioned, throw-away copy of internal state that may be discarded on any
 * shape change, while this is a document a user keeps, sends to a service, and re-imports months
 * later. Sharing one model would mean either the cache cannot change freely or the export cannot
 * stay stable, and the export is the one with an outside contract.
 */
@Serializable
data class DiscoverCatalogDocument(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val name: String,
    /** ISO-8601 UTC, for a human reading the file — never used to decide anything. */
    val generatedAt: String,
    val source: DiscoverCatalogSource,
    val items: List<DiscoverCatalogItem>,
) {
    companion object {
        const val FORMAT = "nuvio-discover-catalog"
        const val VERSION = 1
    }
}

/**
 * Where the row came from, so an importer can tell a frozen list from a reproducible query.
 *
 * [query] is populated only for a custom row, and carrying it is what makes §6.2's expected
 * outcome — "query export where the row has a query, static-list export where it does not" —
 * possible without a second export path. A recommendation row has no query and never will; its
 * provenance is the seed it was built from.
 */
@Serializable
data class DiscoverCatalogSource(
    val kind: String,
    /** Row-family entry id, or `custom:<id>` — what produced the row inside Nuvio. */
    val entryId: String,
    val seed: String? = null,
    val query: DiscoverCatalogQuery? = null,
)

/**
 * A custom row's TMDB `/discover` query, flattened.
 *
 * Field names follow [CustomDiscoverRow] rather than TMDB's parameter names: this describes what
 * the user asked for, and the mapping to any one service's parameters belongs in that service's
 * exporter, not baked into the document every service has to read.
 */
@Serializable
data class DiscoverCatalogQuery(
    val mediaType: String,
    val genres: List<String> = emptyList(),
    val matchAllGenres: Boolean = false,
    val sort: String? = null,
    val minRating: Int = 0,
    val minVotes: Int = 0,
    val fromYear: Int = 0,
    val toYear: Int = 0,
    val status: String? = null,
    val language: String? = null,
    val certification: String? = null,
    val minRuntime: Int = 0,
    val maxRuntime: Int = 0,
    val companies: List<DiscoverCatalogRef> = emptyList(),
    val cast: List<DiscoverCatalogRef> = emptyList(),
    val crew: List<DiscoverCatalogRef> = emptyList(),
)

/**
 * A TMDB id with the name it had when the row was saved.
 *
 * The name is display-only, exactly as [TmdbRef] treats it: a studio renamed at TMDB must not
 * break an exported row, so the id is the thing that queries and the name is what a human reads in
 * the file.
 */
@Serializable
data class DiscoverCatalogRef(val id: Int, val name: String)

/**
 * One title.
 *
 * [id] is written **as Nuvio addresses it** — `tmdb:1234` for a generated row — rather than
 * converted to IMDb on the way out. Converting would cost one request per item against a row of
 * fifty, and it would be the wrong place to spend it: whether a target service wants IMDb ids at
 * all is exactly what §6.2's research spike has to establish. A local round trip needs the id
 * unchanged, and any exporter that needs a different namespace can convert its own items once the
 * requirement is known.
 */
@Serializable
data class DiscoverCatalogItem(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    @SerialName("year") val releaseInfo: String? = null,
)

/** Why a file was rejected. Each maps to a message the import UI shows verbatim. */
sealed interface DiscoverCatalogImportError {
    /** Not JSON, or not an object with the fields this format requires. */
    data class Unreadable(val reason: String) : DiscoverCatalogImportError

    /** Valid JSON of some other kind — a Stremio manifest, a Trakt export, an addon config. */
    data class NotADiscoverCatalog(val foundFormat: String?) : DiscoverCatalogImportError

    /** This format, from a newer Nuvio. */
    data class UnsupportedVersion(val found: Int) : DiscoverCatalogImportError

    /** This format, correct version, but nothing usable in it. */
    data object Empty : DiscoverCatalogImportError
}

private val exportJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

private val importJson = Json {
    ignoreUnknownKeys = true
    // A file written by a newer Nuvio may carry fields this build has never heard of. Ignoring them
    // is what lets the version check below be the thing that decides compatibility, rather than the
    // parser failing first and reporting "unreadable" for a file that is merely newer.
    isLenient = true
}

/** Builds the document for [row]; [customRow] is its query when the row is a custom one. */
fun DiscoverRecommendationRow.toCatalogDocument(
    generatedAt: String,
    customRow: CustomDiscoverRow? = null,
    seed: String? = null,
): DiscoverCatalogDocument = DiscoverCatalogDocument(
    name = title,
    generatedAt = generatedAt,
    source = DiscoverCatalogSource(
        kind = catalogSourceKind(entryId),
        entryId = entryId,
        seed = seed,
        query = customRow?.toCatalogQuery(),
    ),
    items = items.map { item ->
        DiscoverCatalogItem(
            id = item.id,
            type = item.type,
            name = item.name,
            poster = item.poster,
            background = item.banner,
            logo = item.logo,
            description = item.description,
            releaseInfo = item.releaseInfo,
        )
    },
)

/**
 * Stable, service-facing names for what produced a row.
 *
 * Kept separate from [DiscoverRowFamily.entryId] even though they currently agree for most
 * families: the entry ids are an internal key that renaming a family would change freely, and these
 * appear in files people keep.
 */
private fun catalogSourceKind(entryId: String): String = when {
    customDiscoverRowId(entryId) != null -> "custom-query"
    entryId == DiscoverRowFamily.Finish.entryId -> "local-progress"
    entryId == DiscoverRowFamily.Because.entryId -> "tmdb-recommendations"
    entryId == DiscoverRowFamily.Favourites.entryId -> "tmdb-discover-genres"
    entryId == DiscoverRowFamily.Gems.entryId -> "tmdb-discover-hidden-gems"
    entryId == DiscoverRowFamily.Trending.entryId -> "tmdb-trending-genre"
    else -> "unknown"
}

fun CustomDiscoverRow.toCatalogQuery(): DiscoverCatalogQuery = DiscoverCatalogQuery(
    mediaType = mediaType.name,
    genres = genres.sorted(),
    matchAllGenres = matchAllGenres,
    sort = sort.name,
    minRating = minRating,
    minVotes = minVotes,
    fromYear = fromYear,
    toYear = toYear,
    status = status.name,
    language = language.takeIf { it.isNotBlank() },
    certification = certification.takeIf { it.isNotBlank() },
    minRuntime = minRuntime,
    maxRuntime = maxRuntime,
    companies = companies.map { DiscoverCatalogRef(it.id, it.name) },
    cast = cast.map { DiscoverCatalogRef(it.id, it.name) },
    crew = crew.map { DiscoverCatalogRef(it.id, it.name) },
)

fun DiscoverCatalogDocument.encodeToString(): String = exportJson.encodeToString(this)

/**
 * Parses a file the user chose, reporting *why* it was rejected rather than returning null.
 *
 * The distinction matters more here than anywhere else in Discover: this is the one place the user
 * hands the app a file from outside it, and "that isn't a Discover catalog" and "that's from a
 * newer Nuvio" need different actions from them. A single failure result would make both read as
 * "the file is broken".
 */
fun parseDiscoverCatalog(raw: String): Result<DiscoverCatalogDocument> {
    val probe = runCatching { importJson.decodeFromString<DiscoverCatalogProbe>(raw) }
        .getOrElse { error ->
            return Result.failure(
                DiscoverCatalogImportException(
                    DiscoverCatalogImportError.Unreadable(error.message.orEmpty()),
                ),
            )
        }
    if (probe.format != DiscoverCatalogDocument.FORMAT) {
        return Result.failure(
            DiscoverCatalogImportException(DiscoverCatalogImportError.NotADiscoverCatalog(probe.format)),
        )
    }
    if (probe.version > DiscoverCatalogDocument.VERSION) {
        return Result.failure(
            DiscoverCatalogImportException(DiscoverCatalogImportError.UnsupportedVersion(probe.version)),
        )
    }
    val document = runCatching { importJson.decodeFromString<DiscoverCatalogDocument>(raw) }
        .getOrElse { error ->
            return Result.failure(
                DiscoverCatalogImportException(
                    DiscoverCatalogImportError.Unreadable(error.message.orEmpty()),
                ),
            )
        }
    // Blank ids and names are dropped rather than imported: a card with neither is unclickable and
    // unreadable, and a row of them looks like the import half-worked.
    val usable = document.items.filter { it.id.isNotBlank() && it.name.isNotBlank() }
    // **A query counts as content.** A file carrying one imports as a live custom row, and its
    // items are incidental to that — they were already stale when the file was written. Rejecting a
    // query-only file as "empty" would refuse the one kind of export that does not go out of date.
    if (usable.isEmpty() && document.source.query == null) {
        return Result.failure(DiscoverCatalogImportException(DiscoverCatalogImportError.Empty))
    }
    return Result.success(document.copy(items = usable))
}

/** Read first, so the format and version can be checked before the whole document has to parse. */
@Serializable
private data class DiscoverCatalogProbe(
    val format: String? = null,
    val version: Int = 0,
)

class DiscoverCatalogImportException(
    val error: DiscoverCatalogImportError,
) : Exception(error.toString())

/**
 * A parsed document as a stored import.
 *
 * The `nuvio-discover-catalog` document and [ImportedDiscoverRow] are kept apart on purpose: the
 * document is an outside contract that must stay stable, the stored row is internal state free to
 * change. This is the one place they meet.
 */
fun DiscoverCatalogDocument.toImportedRow(id: String): ImportedDiscoverRow = ImportedDiscoverRow(
    id = id,
    title = name,
    sourceKind = source.kind,
    generatedAt = generatedAt,
    items = items.take(IMPORTED_DISCOVER_ITEM_LIMIT).map { item ->
        ImportedDiscoverItem(
            id = item.id,
            type = item.type,
            name = item.name,
            poster = item.poster,
            background = item.background,
            logo = item.logo,
            description = item.description,
            releaseInfo = item.releaseInfo,
        )
    },
)

/**
 * The query a document carries, as a live custom row — the "both" half of import.
 *
 * A file with a query is deliberately **not** imported as a frozen list: recreating the question
 * means the row refreshes like any other custom row, where the items in the file were already stale
 * when it was written. Returns null when the document has no query to recreate, which is the signal
 * to fall back to [toImportedRow].
 */
fun DiscoverCatalogDocument.toCustomRow(id: String): CustomDiscoverRow? {
    val query = source.query ?: return null
    return CustomDiscoverRow(
        id = id,
        title = name,
        mediaType = enumFrom(query.mediaType, CustomDiscoverMediaType.entries, CustomDiscoverMediaType.Both),
        genres = query.genres.toSet(),
        matchAllGenres = query.matchAllGenres,
        sort = enumFrom(query.sort, CustomDiscoverSort.entries, CustomDiscoverSort.Popularity),
        minRating = query.minRating,
        minVotes = query.minVotes,
        fromYear = query.fromYear,
        toYear = query.toYear,
        status = enumFrom(query.status, CustomDiscoverStatus.entries, CustomDiscoverStatus.Any),
        language = query.language.orEmpty(),
        certification = query.certification.orEmpty(),
        minRuntime = query.minRuntime,
        maxRuntime = query.maxRuntime,
        companies = query.companies.map { TmdbRef(it.id, it.name) },
        cast = query.cast.map { TmdbRef(it.id, it.name) },
        crew = query.crew.map { TmdbRef(it.id, it.name) },
    )
}

/**
 * Enum values arrive as strings from a file someone may have edited, and an unrecognised one falls
 * back rather than failing the import: a row with the wrong sort order is fixable in the editor,
 * where a rejected file tells the user nothing about which field was wrong.
 */
private fun <T : Enum<T>> enumFrom(name: String?, values: List<T>, fallback: T): T =
    values.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: fallback

/** Renders a stored import as a Discover row. No network, no filtering — it is already the answer. */
fun ImportedDiscoverRow.toRecommendationRow(): DiscoverRecommendationRow = DiscoverRecommendationRow(
    key = importedDiscoverEntryId(id),
    title = title,
    entryId = importedDiscoverEntryId(id),
    items = items.map { item ->
        MetaPreview(
            id = item.id,
            type = item.type,
            name = item.name,
            poster = item.poster,
            banner = item.background,
            logo = item.logo,
            description = item.description,
            releaseInfo = item.releaseInfo,
        )
    },
)
