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
    /**
     * A purpose-built 16:9 poster the addon supplied for landscape cards — non-standard Stremio,
     * written by AIOMetadata's **Landscape URL Pattern** and nothing else.
     *
     * Distinct from [banner] because it is not a backdrop: the art already has the title composited
     * into it, so a card showing it must not draw its own logo or title on top. Absent for every
     * title the pattern has no art for, which is why the landscape card still falls back to
     * [banner] rather than treating this as the only source.
     */
    val landscapePoster: String? = null,
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
    /**
     * `behaviorHints.defaultVideoId` from the catalog response: set when the meta is one playable
     * video rather than a collection of episodes. It is the only per-item signal that separates a
     * film from a show inside a catalog whose type says neither — an `anime` catalog carrying both.
     */
    val defaultVideoId: String? = null,
    /**
     * The anime-catalogue form — `TV`, `movie`, `OVA`, `ONA`, `special`, `music` — as emitted by
     * Kitsu/MAL/AniList-backed addons. Two things at once, and the only place either is stated:
     * that this is anime at all, and whether it is a film, for the addons that type every meta
     * `anime` and leave the form to be guessed.
     */
    val animeType: String? = null,
    /**
     * Whether the meta carried a `kitsu_id` / `mal_id` / `anilist_id` / `anidb_id` **beside** its
     * own id. Anime metadata reaches the app under whichever namespace the user's addons prefer —
     * imdb, tmdb, tvdb, simkl — and the primary id then says nothing about the content being
     * anime, while these side fields survive the translation.
     *
     * Classification evidence only. Never an identity: the id chain is [metadataId]'s business,
     * and picking a namespace here would undo [AnimeIdPreference].
     */
    val carriesAnimeCatalogueId: Boolean = false,
    /**
     * The content type returned by the metadata lookup Random Play used to verify this row.
     * Unlike the catalog's [type], this can correct an addon row that called a series `movie`, or
     * identify a zero-video anime film whose catalog type only said `anime`.
     */
    val resolvedMetadataType: String? = null,
    /**
     * Episodes in the meta this title resolved to — **not** catalog data; a catalog response has no
     * video list, so this is 0 until [RandomPlayCandidatePool] has looked the title up.
     *
     * The last word on whether something is a show, and the only one that survives an addon typing
     * a series `movie`: SIMKL-backed anime rows do exactly that, and no field in their catalog
     * payload contradicts it — not the type, not `animeType`, not a bare release year.
     */
    val resolvedEpisodeCount: Int = 0,
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
    /**
     * True while this copy is a pre-enrichment placeholder: its logo, genres, synopsis, ratings
     * and runtime are all still unknown and an enrichment for it is in flight.
     *
     * A raw Continue Watching row carries a backdrop, a title and an episode label and nothing
     * else, so the hero used to paint that much, then repaint a moment later with the logo and the
     * full metadata once enrichment landed. Both halves of that were visible as a flash.
     *
     * While this is set the hero renders its backdrop, its (fixed-height, empty) logo slot and its
     * action buttons only — the whole metadata column is withheld and revealed in one go. Whoever
     * sets it MUST clear it once the outcome is known: enrichment landed, or the bounded hold
     * expired and what the row already had is the final answer.
     */
    val heroMetadataPending: Boolean = false,
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
    // Null for inline-only catalogs supplied by a standalone Home destination. Those rows
    // paginate in place and never open the legacy CatalogScreen.
    val target: CatalogTarget? = null,
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
    val inlineOnly: Boolean = false,
)

fun HomeCatalogSection.canOpenCatalog(previewLimit: Int): Boolean =
    availableItemCount > previewLimit || hasMore

fun HomeCatalogSection.usesInfiniteHomeRow(catalogSeeMoreEnabled: Boolean): Boolean =
    inlineOnly || (paginates && !catalogSeeMoreEnabled)

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
