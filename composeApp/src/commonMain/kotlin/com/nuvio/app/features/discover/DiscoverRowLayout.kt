package com.nuvio.app.features.discover

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The row families the Discover tab can render, and the order they render in.
 *
 * A *family* is one entry in the row-management list, not one row on screen: "Because you watched"
 * and "Trending in <genre>" each produce as many rows as their count setting asks for, and all of
 * them travel together when the user drags the family. Ordering per-row instead would mean the list
 * changing shape whenever the taste profile picked a different genre, which is not a list anybody
 * can hold a mental model of.
 */
@Serializable
enum class DiscoverRowFamily(val entryId: String) {
    @SerialName("finish")
    Finish("finish"),

    @SerialName("because")
    Because("because"),

    @SerialName("favourites")
    Favourites("favourites"),

    @SerialName("gems")
    Gems("gems"),

    @SerialName("trending")
    Trending("trending"),
}

private const val CUSTOM_ENTRY_PREFIX = "custom:"
private const val IMPORTED_ENTRY_PREFIX = "imported:"
private const val AI_ENTRY_PREFIX = "ai:"

/** Entry id for a custom row. Namespaced so it can never collide with a family id. */
fun customDiscoverEntryId(rowId: String): String = "$CUSTOM_ENTRY_PREFIX$rowId"

/** Entry id for an imported list. Its own namespace, so the two kinds can never be confused. */
fun importedDiscoverEntryId(rowId: String): String = "$IMPORTED_ENTRY_PREFIX$rowId"

fun importedDiscoverRowId(entryId: String): String? =
    entryId.removePrefix(IMPORTED_ENTRY_PREFIX).takeIf { it != entryId && it.isNotBlank() }

/** Entry id for an AI row. Third namespace, same reasoning as the second. */
fun aiDiscoverEntryId(rowId: String): String = "$AI_ENTRY_PREFIX$rowId"

fun aiDiscoverRowId(entryId: String): String? =
    entryId.removePrefix(AI_ENTRY_PREFIX).takeIf { it != entryId && it.isNotBlank() }

/** The row id inside a custom entry id, or null if this is a built-in family. */
fun customDiscoverRowId(entryId: String): String? =
    entryId.removePrefix(CUSTOM_ENTRY_PREFIX).takeIf { it != entryId && it.isNotBlank() }

/**
 * The order families render in when the user has never touched the list. Also the reference the
 * normaliser inserts *into* when a family is missing from a saved order — see
 * [normalizeDiscoverRowOrder].
 *
 * "Finish what you started" leads because it is the only row that needs no network, no TMDB key and
 * no account: on a bare install it is the tab.
 */
val DefaultDiscoverRowOrder: List<String> = DiscoverRowFamily.entries.map { it.entryId }

/** Which TMDB namespaces a custom row queries. */
@Serializable
enum class CustomDiscoverMediaType {
    @SerialName("movie")
    Movies,

    @SerialName("tv")
    Shows,

    /** Both, queried separately and interleaved — see the genre note on [CustomDiscoverRow]. */
    @SerialName("both")
    Both,
    ;

    val tmdbMediaTypes: List<String>
        get() = when (this) {
            Movies -> listOf("movie")
            Shows -> listOf("tv")
            Both -> listOf("movie", "tv")
        }
}

/**
 * The vote floor a row actually sends, which is not always the one the user set.
 *
 * Sorting by rating with no floor does not return good titles, it returns the extreme tail — a film
 * rated 10 by four people outranks everything ever made. Applied on the user's behalf because the
 * result of not applying it looks like broken data rather than a missing filter. An explicit floor,
 * including a deliberately low one, is always honoured.
 *
 * Lives here beside the model rather than in the repository because the exporters need the same
 * answer: a query handed to a catalog service without this floor is the one that comes back wrong.
 */
fun CustomDiscoverRow.effectiveMinVotes(): Int? = when {
    minVotes > 0 -> minVotes
    sort == CustomDiscoverSort.Rating -> RATING_SORT_MIN_VOTES
    else -> null
}

/** Reference ids joined the way the row means them: `|` is OR, which is what all three lists are. */
fun List<TmdbRef>.orJoinedIds(): String? =
    takeIf { it.isNotEmpty() }?.joinToString("|") { it.id.toString() }

const val RATING_SORT_MIN_VOTES = 200

/** Sort orders offered for a custom row. A subset of TMDB's, kept to the ones that mean something. */
@Serializable
enum class CustomDiscoverSort(private val movieSortBy: String) {
    @SerialName("popularity")
    Popularity("popularity.desc"),

    @SerialName("rating")
    Rating("vote_average.desc"),

    @SerialName("newest")
    Newest("primary_release_date.desc"),

    @SerialName("oldest")
    Oldest("primary_release_date.asc"),
    ;

    /**
     * TMDB names the release-date sort differently per namespace — `primary_release_date` exists
     * only for movies and `first_air_date` only for tv — and sending the wrong one is a 400, not a
     * quiet fallback to the default sort.
     */
    fun tmdbSortBy(mediaType: String): String = when {
        mediaType != "tv" -> movieSortBy
        this == Newest -> "first_air_date.desc"
        this == Oldest -> "first_air_date.asc"
        else -> movieSortBy
    }
}

/**
 * Where a title is in its life, as TMDB can actually answer it.
 *
 * Films and shows answer this with different parameters — `with_release_type` against a film's
 * release history, `with_status` against a show's production state — and neither namespace can
 * answer the other's question. An option that does not apply to a namespace therefore returns
 * **nothing** from it rather than everything, the same rule genres follow: asking for what is in
 * cinemas on a films-and-shows row is a request for films, and quietly widening it back out would
 * answer a question the user did not ask.
 */
@Serializable
enum class CustomDiscoverStatus(
    /** TMDB `with_release_type` values, film side. Null where the question does not apply. */
    val movieReleaseTypes: String?,
    /** TMDB `with_status` values, television side. */
    val tvStatuses: String?,
) {
    @SerialName("any")
    Any(movieReleaseTypes = null, tvStatuses = null),

    /** Premiere, limited or wide theatrical. */
    @SerialName("in_cinemas")
    InCinemas(movieReleaseTypes = "1|2|3", tvStatuses = null),

    /** Digital, physical, or a TV premiere — the ways a film becomes watchable at home. */
    @SerialName("home_release")
    HomeRelease(movieReleaseTypes = "4|5|6", tvStatuses = null),

    @SerialName("returning")
    Returning(movieReleaseTypes = null, tvStatuses = "0"),

    /** Ended or cancelled. Both mean "no more of it is coming", which is the question being asked. */
    @SerialName("ended")
    Ended(movieReleaseTypes = null, tvStatuses = "3|4"),
    ;

    /** False when this namespace cannot answer at all — see the note above. */
    fun appliesTo(mediaType: String): Boolean = when (this) {
        Any -> true
        else -> if (mediaType == "tv") tvStatuses != null else movieReleaseTypes != null
    }
}

/**
 * Original languages offered for a custom row, as ISO 639-1 codes.
 *
 * A curated list rather than TMDB's full set: the full list is ~180 entries including languages with
 * a handful of records, and this is a dropdown, not a search field. The selection covers the
 * languages this app's catalogs actually carry.
 */
val CustomDiscoverLanguages: List<Pair<String, String>> = listOf(
    "en" to "English",
    "ja" to "Japanese",
    "ko" to "Korean",
    "zh" to "Chinese",
    "hi" to "Hindi",
    "fr" to "French",
    "de" to "German",
    "es" to "Spanish",
    "it" to "Italian",
    "pt" to "Portuguese",
    "ru" to "Russian",
    "sv" to "Swedish",
    "da" to "Danish",
    "no" to "Norwegian",
    "fi" to "Finnish",
    "nl" to "Dutch",
    "pl" to "Polish",
    "tr" to "Turkish",
    "th" to "Thai",
    "ar" to "Arabic",
)

/**
 * US film certifications, the only certification vocabulary this offers.
 *
 * TMDB's `certification` filter needs a `certification_country` alongside it and the vocabularies
 * are per country — the UK's BBFC set is a different list of different strings. One country is
 * offered rather than a country picker plus a dependent rating picker, and it is the US because
 * that is the certification TMDB's records are most completely populated with. **Films only:** the
 * television discover endpoint has no certification parameter at all.
 */
val CustomDiscoverCertifications: List<String> = listOf("G", "PG", "PG-13", "R", "NC-17")

const val CUSTOM_DISCOVER_CERTIFICATION_COUNTRY = "US"

/** Runtime bounds in minutes. 0 means unbounded at that end. */
val CUSTOM_DISCOVER_RUNTIME_RANGE: IntRange = 0..240
const val CUSTOM_DISCOVER_RUNTIME_STEP = 5

/**
 * A person or company the user picked, as **id plus the name they saw**.
 *
 * TMDB's `with_cast`, `with_crew` and `with_companies` take numeric ids and nothing else, which is
 * why this exists at all: unlike genres there is no name the API will accept, so the picker has to
 * resolve a name to an id once and the row has to remember both halves. The [name] is display only
 * — it is what the settings page shows and never what the query sends, so a studio being renamed at
 * TMDB cannot break a saved row.
 */
@Serializable
data class TmdbRef(val id: Int, val name: String)

/** How many people or companies one row may name. A limit on query length, not on ambition. */
const val CUSTOM_DISCOVER_REF_LIMIT = 8

/**
 * A user-defined Discover row: a saved TMDB `/discover` query, rendered as a row like any other.
 *
 * **This is deliberately not a Collection.** Collections are curated lists of specific titles, they
 * sync, and they appear on Home; a custom row is a *query*, it is Discover-local, and its contents
 * change as TMDB's do. "Save as Collection" is the promotion path between the two and belongs to
 * the export phase, not here.
 *
 * [genres] are stored as **names, not ids**, because TMDB's movie and tv genre vocabularies are
 * different — movies have "Action" (28) and "Adventure" (12) where tv has a single
 * "Action & Adventure" (10759). A row set to [CustomDiscoverMediaType.Both] resolves names to ids
 * separately per namespace at query time; storing an id would silently mean a different genre, or
 * no genre at all, in the other half of the query. The names come from [DiscoverGenreNames], the
 * union of both vocabularies, so a name belonging to only one namespace simply contributes nothing
 * to the other.
 *
 * The rating and vote floors are stored rather than defaulted because the useful value depends
 * entirely on the query: a floor that makes a mainstream-genre row respectable empties a
 * documentary one.
 */
@Serializable
data class CustomDiscoverRow(
    val id: String,
    val title: String = "",
    val mediaType: CustomDiscoverMediaType = CustomDiscoverMediaType.Both,
    val genres: Set<String> = emptySet(),
    /**
     * True ANDs the genres (TMDB's comma form), false ORs them (its `|` form).
     *
     * Defaults to OR, which is the opposite of what the built-in generators use and is the right
     * default here: those pick one or two genres deliberately, whereas a person ticking four boxes
     * means "any of these". ANDing four genres returns almost nothing, and an empty row that looks
     * like a bug is a worse first experience than an over-broad one.
     */
    val matchAllGenres: Boolean = false,
    val sort: CustomDiscoverSort = CustomDiscoverSort.Popularity,
    /** TMDB `vote_average.gte`, in whole points. 0 disables it. */
    val minRating: Int = 0,
    /** TMDB `vote_count.gte`. 0 disables it. */
    val minVotes: Int = 0,
    /** Release-year bounds, inclusive. 0 means unbounded at that end. */
    val fromYear: Int = 0,
    val toYear: Int = 0,
    val status: CustomDiscoverStatus = CustomDiscoverStatus.Any,
    /** ISO 639-1 original-language code, or blank for any. */
    val language: String = "",
    /** US film certification. Blank for any; ignored for television, which has no equivalent. */
    val certification: String = "",
    /** Runtime bounds in minutes. 0 means unbounded at that end. */
    val minRuntime: Int = 0,
    val maxRuntime: Int = 0,
    /**
     * `with_companies`. Applies to films and shows alike, unlike the two below.
     *
     * All three ref lists are **ORed** (TMDB's `|`), for the same reason genres default to OR: a
     * person naming three studios means "any of these", and ANDing them asks for a title all three
     * produced, which is a real query but almost never the one intended and returns nothing.
     */
    val companies: List<TmdbRef> = emptyList(),
    /** `with_cast`. **Films only** — the television discover endpoint has no cast filter. */
    val cast: List<TmdbRef> = emptyList(),
    /**
     * `with_crew`. Films only, and deliberately not called "director": TMDB cannot filter crew by
     * job, so this matches anyone who worked on the title in any role. The UI says so.
     */
    val crew: List<TmdbRef> = emptyList(),
    val enabled: Boolean = true,
) {
    val entryId: String get() = customDiscoverEntryId(id)
}

/**
 * A list imported from a `nuvio-discover-catalog` file — plan §6.1.
 *
 * The counterpart to [CustomDiscoverRow], and deliberately a separate type rather than a mode of
 * it. A custom row is a *question* the app re-asks TMDB on every build; this is an *answer* somebody
 * else already computed. They differ in every way that matters — one refreshes and one cannot, one
 * has filters to edit and one has none, one costs requests and one costs nothing — so folding them
 * into one model would mean a row whose editable fields depend on a flag, which is the shape that
 * produces controls that silently do nothing.
 *
 * Files that carry a query are **not** imported as this: they become a real [CustomDiscoverRow], so
 * they stay live. This type is for the rows that have no query to recreate — recommendations,
 * trending, hidden gems, anything generated from history.
 */
@Serializable
data class ImportedDiscoverRow(
    val id: String,
    val title: String,
    /** Where the file said it came from, kept for display so the row can say it was imported. */
    val sourceKind: String = "",
    /** ISO-8601, from the file. Shown so a user can see how old a frozen list is. */
    val generatedAt: String = "",
    val enabled: Boolean = true,
    val items: List<ImportedDiscoverItem> = emptyList(),
)

/**
 * Drops one title from an imported list by position.
 *
 * **By index, not by id.** Nothing guarantees an imported file holds each title once — it is
 * somebody else's export, and a merged or hand-edited one can repeat an id — so removing by id
 * would take every copy on a click aimed at one of them. An index outside the list is returned
 * unchanged rather than throwing: the editor saves through the repository on every click, and a
 * stale index is a recomposition race, not a bug worth crashing settings for.
 */
fun ImportedDiscoverRow.withoutItemAt(index: Int): ImportedDiscoverRow =
    if (index !in items.indices) this else copy(items = items.filterIndexed { at, _ -> at != index })

@Serializable
data class ImportedDiscoverItem(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
)

/**
 * Cap on imported lists, mirroring [CUSTOM_DISCOVER_ROW_LIMIT].
 *
 * Lower than it looks like it should be because these carry their items with them: an imported row
 * is stored in full in the settings payload, so ten fifty-item lists is a meaningfully larger
 * settings file, where ten custom rows are ten small queries.
 */
const val IMPORTED_DISCOVER_ROW_LIMIT = 10

/** Items kept per imported list. Matches what a generated row holds, so they render alike. */
const val IMPORTED_DISCOVER_ITEM_LIMIT = 50

val CUSTOM_DISCOVER_MIN_RATING_RANGE: IntRange = 0..9
val CUSTOM_DISCOVER_MIN_VOTES_RANGE: IntRange = 0..2000
const val CUSTOM_DISCOVER_MIN_VOTES_STEP = 50

/**
 * Bounds for the year pickers.
 *
 * Narrower than the range of cinema on purpose: these are sliders, and a two-century sweep makes
 * every useful year unhittable. Stored values outside it are honoured, only unreachable by dragging.
 * The value one below the floor is the pickers' "any" position and is stored as 0.
 */
val CUSTOM_DISCOVER_YEAR_RANGE: IntRange = 1950..2035

/** How many custom rows one profile may define. A ceiling on TMDB requests per refresh, not taste. */
const val CUSTOM_DISCOVER_ROW_LIMIT = 10

/**
 * Reconciles a saved row order with the families and custom rows that actually exist now.
 *
 * Three things can be wrong with a saved order, and each has a different right answer:
 *
 * - **An entry that no longer exists** (a deleted custom row) is dropped.
 * - **A custom row the order has never seen** is appended, because a row the user just created
 *   should appear somewhere predictable rather than in the middle of the built-ins.
 * - **A *family* the order has never seen** — one added in a later version, whose saved order was
 *   written before it existed — is inserted at its [DefaultDiscoverRowOrder] position rather than
 *   appended. Appending would put a row that belongs at the top behind every custom row the user
 *   has, and nothing about the result tells them the app chose that rather than they did. This is
 *   the same failure mode the hero-badge slot migrations exist to avoid.
 *
 * The saved order always wins where it has an opinion; this only fills gaps.
 */
fun normalizeDiscoverRowOrder(
    savedOrder: List<String>,
    customRowIds: List<String>,
    importedRowIds: List<String> = emptyList(),
    aiRowIds: List<String> = emptyList(),
): List<String> {
    val customEntryIds = customRowIds.map(::customDiscoverEntryId) +
        importedRowIds.map(::importedDiscoverEntryId) +
        aiRowIds.map(::aiDiscoverEntryId)
    val known = (DefaultDiscoverRowOrder + customEntryIds).toSet()
    val result = savedOrder.filter { it in known }.distinct().toMutableList()

    DefaultDiscoverRowOrder.forEachIndexed { defaultIndex, familyId ->
        if (familyId in result) return@forEachIndexed
        // Land it directly after whichever earlier default family is nearest in the current list,
        // so an inserted family keeps its neighbours even when the user has moved them around.
        val anchor = DefaultDiscoverRowOrder
            .take(defaultIndex)
            .asReversed()
            .firstNotNullOfOrNull { earlier -> result.indexOf(earlier).takeIf { it >= 0 } }
        result.add(if (anchor == null) 0 else anchor + 1, familyId)
    }

    customEntryIds.forEach { entryId -> if (entryId !in result) result.add(entryId) }
    return result
}

/**
 * Sorts built rows into the user's configured order.
 *
 * Rows within one family keep the order the generator produced them in — seed recency for "Because
 * you watched", genre affinity for "Trending in" — because that ordering is itself a ranking and
 * the row-management list has no way to express it.
 *
 * Anything whose entry is missing from [order] sorts last rather than being dropped: this function
 * decides presentation, and silently losing a row that was expensively built would be a far worse
 * failure than showing it in the wrong place.
 */
fun <T> orderDiscoverRows(rows: List<T>, order: List<String>, entryIdOf: (T) -> String): List<T> {
    val rank = order.withIndex().associate { (index, entryId) -> entryId to index }
    return rows.withIndex()
        .sortedWith(compareBy({ rank[entryIdOf(it.value)] ?: Int.MAX_VALUE }, { it.index }))
        .map { it.value }
}

/**
 * What produced a Discover row, as far as it is worth telling the user — plan §7, phase 7.
 *
 * **Deliberately about the row's *kind*, not about which tracking provider the history came from.**
 * §7 lists "Nuvio/Trakt/Simkl/AI", but the first three are one global setting: watch history follows
 * the Continue Watching source, so every history-derived row on the tab would carry the same badge
 * at once. A label that is identical on six consecutive rows is decoration, not information.
 *
 * What is genuinely per-row, and invisible without a badge, is the distinction between a shelf that
 * a model wrote, a list that was imported and will never change on its own, and a query the user
 * wrote themselves. The built-in generated families are the default and are left unbadged: badging
 * everything is the same as badging nothing.
 */
enum class DiscoverRowProvenance {
    Ai,
    Imported,
    Custom,
}

/**
 * The badge for a rendered row, keyed off the row key the renderer already has, or null for a
 * built-in family.
 *
 * Matches on prefix rather than equality because `ensureUniqueKeys` may append a disambiguating
 * suffix before the row reaches the renderer — it never touches the front of the key.
 */
fun discoverRowProvenance(rowKey: String): DiscoverRowProvenance? = when {
    rowKey.startsWith(AI_ENTRY_PREFIX) -> DiscoverRowProvenance.Ai
    rowKey.startsWith(IMPORTED_ENTRY_PREFIX) -> DiscoverRowProvenance.Imported
    // Note this is the *row key* prefix, `discover:custom:`, not the entry id's `custom:` — a
    // custom row carries both and they are deliberately different strings.
    rowKey.startsWith(DiscoverRecommendationsRepository.CUSTOM_ROW_KEY_PREFIX) ->
        DiscoverRowProvenance.Custom
    else -> null
}
