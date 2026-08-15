package com.nuvio.app.features.home

import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.metadata.isAnimeNativeId
import com.nuvio.app.features.watching.application.WatchingState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.absoluteValue
import kotlin.random.Random

internal const val RANDOM_PLAY_SECTION_KEY = "nuvio:random-play"
private const val RANDOM_PLAY_ITEM_TYPE = "random_play"
private const val RANDOM_PLAY_ITEM_PREFIX = "random:"

@Serializable
enum class RandomPlayCategory {
    @SerialName("movie")
    Movie,

    @SerialName("series")
    Series,

    @SerialName("anime_movie")
    AnimeMovie,

    @SerialName("anime_series")
    AnimeSeries,
}

@Serializable
enum class RandomPlayAction {
    @SerialName("details")
    Details,

    @SerialName("play")
    Play,
}

/**
 * The genres every metadata provider agrees on — TMDB's set, which Cinemeta and the addons built on
 * it emit verbatim. Kept as its own list because a stored selection covering all of them means "any
 * genre"; see [expandLegacyRandomPlayGenres].
 */
private val RandomPlayStandardGenres: List<String> = listOf(
    "Action",
    "Adventure",
    "Animation",
    "Comedy",
    "Crime",
    "Documentary",
    "Drama",
    "Family",
    "Fantasy",
    "History",
    "Horror",
    "Music",
    "Mystery",
    "Romance",
    "Science Fiction",
    "Thriller",
    "War",
    "Western",
)

/**
 * Anime is tagged from a different vocabulary — kitsu/MAL/AniList demographics and genres that no
 * standard list contains. Without these, narrowing the allow-list at all drops anime whose only
 * genres are its own: a show tagged Shounen/Isekai/Mecha matches nothing above.
 *
 * "Anime" itself is in the list because catalogs do emit it as a genre, and because it is what a
 * user reaches for to keep (or exclude) anime wholesale.
 */
private val RandomPlayAnimeGenres: List<String> = listOf(
    "Anime",
    "Ecchi",
    "Harem",
    "Isekai",
    "Josei",
    "Magic",
    "Martial Arts",
    "Mecha",
    "Military",
    "Psychological",
    "School",
    "Seinen",
    "Shoujo",
    "Shounen",
    "Slice of Life",
    "Sports",
    "Supernatural",
)

/** Canonical genre allow-list used by Random Play settings and matching. */
val RandomPlayGenres: List<String> = RandomPlayStandardGenres + RandomPlayAnimeGenres

/**
 * One-shot migration for selections stored before the anime genres existed. A set that covered
 * every genre there was meant "any genre", and has to keep meaning that now the list is longer —
 * otherwise adding these silently switches every user who ever opened the settings from no
 * filtering to filtering, and the first casualty is every title whose catalog gives it no genres
 * at all, since those match no allow-list entry.
 *
 * A partial selection is left alone: it was a deliberate filter, and anime genres nobody has ever
 * seen in the UI are not something to opt them into. Guarded by a stored flag rather than by
 * "looks untouched", so a user who later turns *off* every anime genre is not re-expanded.
 */
internal fun expandLegacyRandomPlayGenres(stored: Set<String>): Set<String> {
    val normalized = stored.mapTo(linkedSetOf(), ::normalizeRandomPlayGenre)
    val coversEveryStandardGenre = RandomPlayStandardGenres
        .map(::normalizeRandomPlayGenre)
        .all(normalized::contains)
    return if (coversEveryStandardGenre) RandomPlayGenres.toSet() else stored
}

data class RandomPlayLabels(
    val sectionTitle: String,
    val sectionSubtitle: String,
    val movie: String,
    val series: String,
    val animeMovie: String,
    val animeSeries: String,
) {
    fun category(category: RandomPlayCategory): String = when (category) {
        RandomPlayCategory.Movie -> movie
        RandomPlayCategory.Series -> series
        RandomPlayCategory.AnimeMovie -> animeMovie
        RandomPlayCategory.AnimeSeries -> animeSeries
    }
}

fun MetaPreview.randomPlayCategoryOrNull(): RandomPlayCategory? =
    takeIf { type == RANDOM_PLAY_ITEM_TYPE && id.startsWith(RANDOM_PLAY_ITEM_PREFIX) }
        ?.id
        ?.removePrefix(RANDOM_PLAY_ITEM_PREFIX)
        ?.let { stored -> RandomPlayCategory.entries.firstOrNull { it.name == stored } }

/**
 * Metadata a catalog row did not carry, resolved afterwards from the metadata layer.
 *
 * Catalog responses are routinely thinner than what the app ends up showing: across the public
 * Kitsu addon's rows, 78% of metas arrive with an empty `genres` array (its Trending and Highest
 * Rated catalogs send none at all), and every film's `releaseInfo` is a bare start year. The app
 * fills that in later — the hero's enrichment pass, an opened details page — but Random Play judged
 * the raw row, so those titles failed the genre filter and had their film/show form guessed.
 *
 * [RandomPlayCandidatePool] fetches these for the items whose classification actually depends on
 * them, and they are folded back in by [withRandomPlayFacts].
 */
data class RandomPlayMetaFacts(
    val genres: List<String> = emptyList(),
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    /** The metadata response's own type, which may correct the thin catalog row's type. */
    val resolvedType: String? = null,
    /**
     * The resolved meta carried a MyAnimeList id, which only anime metadata addons supply — so it
     * says "anime" for a title whose catalog row reached the app under an imdb/tmdb/tvdb id and
     * said nothing.
     */
    val carriesAnimeCatalogueId: Boolean = false,
    /** Episodes in the resolved meta; see [MetaPreview.resolvedEpisodeCount]. */
    val episodeCount: Int = 0,
)

/**
 * What [RandomPlayCandidatePool] has learned on top of the loaded rows: extra catalog pages keyed
 * by section, and per-title facts keyed by [MetaPreview.stableKey].
 */
data class RandomPlayPoolState(
    val extraPages: Map<String, List<MetaPreview>> = emptyMap(),
    val facts: Map<String, RandomPlayMetaFacts> = emptyMap(),
) {
    val isEmpty: Boolean get() = extraPages.isEmpty() && facts.isEmpty()

    companion object {
        val Empty = RandomPlayPoolState()
    }
}

/**
 * Fills the gaps a catalog row left, without letting late metadata overwrite what the row already
 * said — except for [MetaPreview.releaseInfo], where a *range* is strictly more informative than
 * the bare start year catalogs hand out and is the signal [animeFormOrNull] reads.
 */
internal fun MetaPreview.withRandomPlayFacts(facts: Map<String, RandomPlayMetaFacts>): MetaPreview {
    val known = facts[stableKey()] ?: return this
    return copy(
        genres = genres.ifEmpty { known.genres },
        releaseInfo = bestRandomPlayReleaseInfo(own = releaseInfo, fetched = known.releaseInfo),
        imdbRating = imdbRating?.takeIf(String::isNotBlank) ?: known.imdbRating,
        carriesAnimeCatalogueId = carriesAnimeCatalogueId || known.carriesAnimeCatalogueId,
        resolvedMetadataType = resolvedMetadataType?.takeIf(String::isNotBlank) ?: known.resolvedType,
        resolvedEpisodeCount = maxOf(resolvedEpisodeCount, known.episodeCount),
    )
}

private fun bestRandomPlayReleaseInfo(own: String?, fetched: String?): String? = when {
    fetched != null && isRandomPlayYearRange(fetched) -> fetched
    else -> own ?: fetched
}

/**
 * The catalogs Random Play may draw from: the loaded Home rows, plus — when the user opted in —
 * the collection catalogs [RandomPlayCollectionPool] keeps warm, both widened by whatever
 * [RandomPlayCandidatePool] has since paged in or resolved. Duplicate titles across the sources
 * are collapsed later by [randomPlayCandidates]'s stable-key pass.
 */
fun randomPlaySourceSections(
    homeSections: List<HomeCatalogSection>,
    collectionSections: List<HomeCatalogSection>,
    settings: HomeCatalogSettingsUiState,
    pool: RandomPlayPoolState = RandomPlayPoolState.Empty,
): List<HomeCatalogSection> {
    val base = if (settings.randomPlayIncludeCollections) {
        homeSections + collectionSections
    } else {
        homeSections
    }
    return base.withRandomPlayPool(pool)
}

/** The rows widened by what [RandomPlayCandidatePool] has paged in and resolved since. */
fun List<HomeCatalogSection>.withRandomPlayPool(pool: RandomPlayPoolState): List<HomeCatalogSection> {
    if (pool.isEmpty) return this
    return map { section ->
        val extra = pool.extraPages[section.key].orEmpty()
        if (extra.isEmpty() && pool.facts.isEmpty()) {
            section
        } else {
            section.copy(items = (section.items + extra).map { it.withRandomPlayFacts(pool.facts) })
        }
    }
}

/** Builds the four Random Play launch cards from the already-loaded catalog pool. */
fun buildRandomPlaySection(
    sourceSections: List<HomeCatalogSection>,
    settings: HomeCatalogSettingsUiState,
    labels: RandomPlayLabels,
    watchedKeys: Set<String> = emptySet(),
): HomeCatalogSection? {
    if (!settings.randomPlayEnabled || settings.randomPlayCategories.isEmpty()) return null

    val cards = settings.randomPlayCategories
        .sortedBy(RandomPlayCategory::ordinal)
        .map { category ->
            val candidates = randomPlayCandidates(
                sourceSections = sourceSections,
                category = category,
                allowedGenres = settings.randomPlayGenres,
                minimumImdbRating = settings.randomPlayMinimumImdbRating,
                watchedKeys = watchedKeys,
            )
            val sample = candidates.getOrNull(
                candidates.indices.randomIndexOrNull(seed = candidates.joinToString { it.stableKey() }.hashCode()),
            )
            val categoryPosters = candidates.mapNotNull(MetaPreview::preferredRandomPlayPoster)
            MetaPreview(
                id = RANDOM_PLAY_ITEM_PREFIX + category.name,
                type = RANDOM_PLAY_ITEM_TYPE,
                name = labels.category(category),
                banner = sample?.banner,
                posterShape = PosterShape.Poster,
                description = labels.sectionSubtitle,
                posterCollage = randomPlayPosterCollage(
                    category = category,
                    posters = categoryPosters,
                ),
            )
        }

    val fallbackTarget = sourceSections.firstOrNull()?.target ?: CatalogTarget.Library(
        contentType = RANDOM_PLAY_ITEM_TYPE,
        sectionType = RANDOM_PLAY_SECTION_KEY,
    )
    return HomeCatalogSection(
        key = RANDOM_PLAY_SECTION_KEY,
        title = labels.sectionTitle,
        subtitle = labels.sectionSubtitle,
        addonName = "Nuvio",
        target = fallbackTarget,
        items = cards,
    )
}

internal fun randomPlayPosterCollage(
    category: RandomPlayCategory,
    posters: List<String>,
): List<String> {
    return posters
        .distinct()
        .sortedBy { poster -> (category.name + poster).hashCode() }
        .take(RANDOM_PLAY_COLLAGE_POSTER_LIMIT)
}

/**
 * Normal Home rows are ordered from [HomeCatalogSettingsItem]s. Random Play is a virtual catalog,
 * so it needs a transient settings item to participate in the same standard, TV, and immersive
 * rendering pipelines without being persisted alongside addon catalogs.
 */
internal fun buildEnabledHomeItems(
    settingsItems: List<HomeCatalogSettingsItem>,
    effectiveSections: List<HomeCatalogSection>,
): List<HomeCatalogSettingsItem> = buildList {
    effectiveSections.firstOrNull { it.key == RANDOM_PLAY_SECTION_KEY }
        ?.takeIf { it.items.isNotEmpty() }
        ?.let { section ->
            add(
                HomeCatalogSettingsItem(
                    key = section.key,
                    defaultTitle = section.title,
                    addonName = section.addonName,
                    heroSourceEnabled = false,
                    order = Int.MIN_VALUE,
                ),
            )
        }
    addAll(settingsItems.filter { it.enabled && it.key != RANDOM_PLAY_SECTION_KEY })
}

fun pickRandomPlayItem(
    category: RandomPlayCategory,
    sourceSections: List<HomeCatalogSection>,
    settings: HomeCatalogSettingsUiState,
    watchedKeys: Set<String> = emptySet(),
    random: Random = Random.Default,
): MetaPreview? = randomPlayCandidates(
    sourceSections = sourceSections,
    category = category,
    allowedGenres = settings.randomPlayGenres,
    minimumImdbRating = settings.randomPlayMinimumImdbRating,
    watchedKeys = watchedKeys,
).randomOrNull(random)

internal fun randomPlayCandidates(
    sourceSections: List<HomeCatalogSection>,
    category: RandomPlayCategory,
    allowedGenres: Set<String>,
    minimumImdbRating: Float,
    watchedKeys: Set<String> = emptySet(),
): List<MetaPreview> {
    val normalizedAllowedGenres = allowedGenres.mapTo(linkedSetOf(), ::normalizeRandomPlayGenre)
    val allowEveryGenre = randomPlayAllowsEveryGenre(allowedGenres)

    return sourceSections
        .asSequence()
        .filterNot { it.key == RANDOM_PLAY_SECTION_KEY }
        .flatMap { section ->
            val row = section.randomPlayRow()
            section.items.asSequence().map { item -> item to row }
        }
        .filter { (item, row) -> item.matchesRandomPlayCategory(category, row) }
        .filterNot { (item, _) -> WatchingState.isPosterWatched(watchedKeys, item) }
        .filter { (item, row) ->
            if (allowEveryGenre) return@filter true
            val candidateGenres = (item.genres + listOfNotNull(row.genre))
                .mapTo(linkedSetOf(), ::normalizeRandomPlayGenre)
            candidateGenres.any(normalizedAllowedGenres::contains)
        }
        .filter { (item, _) ->
            minimumImdbRating <= 0f ||
                (item.imdbRating.randomPlayRatingOrNull()?.let { it >= minimumImdbRating } == true)
        }
        .map { it.first }
        .distinctBy(MetaPreview::stableKey)
        .toList()
}

/**
 * What the row a candidate came from says about it. Anime catalogs routinely under-describe their
 * items: an AIOMetadata SIMKL anime row arrives typed `series`/`movie` with no "Anime" among its
 * genres, so the row's own catalog type has to take part in the classification — the item's type
 * alone puts every one of those titles in the plain Movie/Series buckets and leaves both anime
 * cards empty.
 */
internal data class RandomPlayRow(
    val genre: String? = null,
    val contentType: String? = null,
)

internal fun HomeCatalogSection.randomPlayRow(): RandomPlayRow = RandomPlayRow(
    genre = (target as? CatalogTarget.Addon)?.genre,
    contentType = target?.contentType,
)

/** Whether a candidate is a film or a show, once the type strings have been agreed on. */
private enum class RandomPlayKind { Movie, Series }

internal fun MetaPreview.matchesRandomPlayCategory(
    category: RandomPlayCategory,
    row: RandomPlayRow = RandomPlayRow(),
): Boolean = randomPlayCategoryIn(row) == category

/** The single bucket a candidate belongs to, or null when it is not something Random Play launches. */
internal fun MetaPreview.randomPlayCategoryIn(row: RandomPlayRow = RandomPlayRow()): RandomPlayCategory? {
    val normalizedType = normalizeRandomPlayType(type)
    if (normalizedType == RANDOM_PLAY_ITEM_TYPE) return null
    val rowType = row.contentType?.let(::normalizeRandomPlayType)
    val resolvedType = resolvedMetadataType?.let(::normalizeRandomPlayType)

    // Independent signals, because no single one survives every addon and nobody sticks to one id
    // namespace. An anime title may declare an anime *type*, sit in a catalog the manifest declares
    // as anime, be addressed by an anime-native id (kitsu/MAL/AniList/AniDB — see [isAnimeNativeId]),
    // carry an anime-catalogue id in a side field while its own id is an imdb/tmdb/tvdb/simkl one,
    // state an `animeType`, or say so in its genres — and plenty of rows carry exactly one of them.
    // "Animation" is deliberately not among them: it covers every western cartoon too.
    val isAnime = normalizedType.isRandomPlayAnimeType() ||
        rowType.isRandomPlayAnimeType() ||
        resolvedType.isRandomPlayAnimeType() ||
        id.isAnimeNativeId() ||
        carriesAnimeCatalogueId ||
        !animeType.isNullOrBlank() ||
        genres.any { genre -> normalizeRandomPlayGenre(genre) == "anime" }

    // Most specific statement of the form wins. An episode list is not a statement but the content
    // itself, so it outranks everything — an addon may type a six-episode show `movie`, and none of
    // its other fields will admit it. Then the metadata response's resolved type, followed by
    // `animeType`, the anime catalogue naming the form outright; then the raw catalog type; then
    // inference for metas whose type only says "anime".
    val kind = seriesFromResolvedEpisodes()
        ?: resolvedType?.randomPlayKindOrNull()
        ?: animeType?.randomPlayAnimeFormOrNull()
        ?: normalizedType.randomPlayKindOrNull()
        ?: when {
            normalizedType.isRandomPlayAnimeType() -> inferredAnimeForm(rowType)
            // Any other unrecognised type — a cloud-library `library` row, a `channel` — stays out
            // of every category, as before. The row's type must not speak for an item that isn't
            // one of the forms Random Play launches.
            else -> return null
        }

    return when {
        kind == RandomPlayKind.Movie && !isAnime -> RandomPlayCategory.Movie
        kind == RandomPlayKind.Series && !isAnime -> RandomPlayCategory.Series
        kind == RandomPlayKind.Movie -> RandomPlayCategory.AnimeMovie
        else -> RandomPlayCategory.AnimeSeries
    }
}

/**
 * True when the film/show half of this item's bucket was a fallback rather than something the item
 * stated: a bare `anime` type with no `animeType`, year range or `defaultVideoId` to read. Those
 * land in Anime Series by default, and a fetched `releaseInfo` can still move them — which is the
 * whole reason [RandomPlayCandidatePool] spends a request on them.
 */
internal fun MetaPreview.randomPlayFormIsGuessed(row: RandomPlayRow = RandomPlayRow()): Boolean {
    if (resolvedMetadataType?.let(::normalizeRandomPlayType)?.randomPlayKindOrNull() != null) return false
    if (animeType?.randomPlayAnimeFormOrNull() != null) return false
    val normalizedType = normalizeRandomPlayType(type)
    if (normalizedType.randomPlayKindOrNull() != null) return false
    if (!normalizedType.isRandomPlayAnimeType()) return false
    return releaseInfo?.let(::isRandomPlayYearRange) != true &&
        row.contentType?.let(::normalizeRandomPlayType)?.randomPlayKindOrNull() == null &&
        defaultVideoFormOrNull() == null
}

/** Whether neither the item nor its row says anything the genre allow-list could match. */
internal fun MetaPreview.randomPlayGenresUnknown(row: RandomPlayRow = RandomPlayRow()): Boolean =
    genres.isEmpty() && row.genre.isNullOrBlank()

/**
 * Film or show for a meta whose type only says "anime" and which named no [MetaPreview.animeType],
 * weighing what such a meta still carries — strongest evidence first, never a coin toss.
 *
 * A run of years ("2013-2015", "2013-") is a show and nothing else. The row comes next: a catalog
 * declared `anime.series` or `series` names one form for everything in it, and only a catalog
 * declared bare `anime` is the mixed bag that has to be split per item. `defaultVideoId` is last
 * and now the weakest — see [defaultVideoFormOrNull].
 */
private fun MetaPreview.inferredAnimeForm(rowType: String?): RandomPlayKind =
    releaseInfo?.takeIf(::isRandomPlayYearRange)?.let { RandomPlayKind.Series }
        ?: rowType?.randomPlayKindOrNull()
        ?: defaultVideoFormOrNull()
        // Nothing to go on: an anime catalog is a show catalog far more often than a film one,
        // which is also what a bare `anime` type meant before any of this existed.
        ?: RandomPlayKind.Series

/**
 * Series, when the resolved meta listed more than one episode. Demotes only — a *missing* episode
 * list is no evidence of a film, since the lightweight fetch does not require one, and a film's
 * meta legitimately carries a single video for itself.
 */
private fun MetaPreview.seriesFromResolvedEpisodes(): RandomPlayKind? =
    RandomPlayKind.Series.takeIf { resolvedEpisodeCount > 1 }

/**
 * A title in the Anime Movies card on nothing better than a coarse `movie` type — the shape of the
 * SIMKL-backed anime rows, where whole shows arrive typed that way with no `animeType`, no
 * `defaultVideoId` and a bare release year. Worth one metadata request to settle, whether or not
 * the card is short: a full card of the wrong titles is not a card that needs more titles.
 *
 * Titles that stated their form — an `animeType`, or a `defaultVideoId` addressing the meta itself
 * — are taken at their word and cost nothing.
 */
internal fun MetaPreview.randomPlayAnimeMovieNeedsVerifying(row: RandomPlayRow = RandomPlayRow()): Boolean =
    randomPlayCategoryIn(row) == RandomPlayCategory.AnimeMovie &&
        resolvedMetadataType?.let(::normalizeRandomPlayType)?.randomPlayKindOrNull() == null &&
        resolvedEpisodeCount == 0 &&
        animeType?.randomPlayAnimeFormOrNull() == null &&
        defaultVideoFormOrNull() == null

/**
 * What `behaviorHints.defaultVideoId` says about the form — read by *what it points at*, not by
 * whether it exists.
 *
 * The hint marks a meta that is one playable video, and Cinemeta and the addons that follow it
 * point a film's at the film itself. Plenty of addons set it on shows too, at the first episode,
 * and taking mere presence as "film" is what put whole shows in the Anime Movies card. An id
 * *under* the meta's own — `tt123:1:1`, `kitsu:456:1` — is the addon naming an episode, which only
 * a show has, so it now argues the opposite. Anything unrelated to the meta's id is not understood
 * and gets no vote.
 */
private fun MetaPreview.defaultVideoFormOrNull(): RandomPlayKind? {
    val videoId = defaultVideoId?.trim()?.takeIf(String::isNotBlank) ?: return null
    return when {
        videoId.equals(id, ignoreCase = true) -> RandomPlayKind.Movie
        videoId.startsWith("$id:", ignoreCase = true) -> RandomPlayKind.Series
        else -> null
    }
}

/**
 * The Kitsu/MAL/AniList form vocabulary. Only `movie` is a film; `OVA` and `special` are episodic
 * entries in those catalogues — the Kitsu addon types both `series` in its own metas — so they
 * follow `TV` and `ONA` rather than being read as one-off videos.
 *
 * Null for a value none of them uses, so an unknown string falls through to the next signal instead
 * of inventing a form.
 */
private fun String.randomPlayAnimeFormOrNull(): RandomPlayKind? = when (trim().lowercase()) {
    "movie", "film" -> RandomPlayKind.Movie
    "tv", "ona", "ova", "special", "music" -> RandomPlayKind.Series
    else -> null
}

/**
 * A start year with a dash after it, open ("2013-") or closed ("2013-2015"). Anchored and restricted
 * to plausible years so an ISO release date ("2016-05-01"), which plenty of catalogs put in
 * `releaseInfo`, is not mistaken for a run.
 */
private val randomPlayYearRangeRegex = Regex("""^\s*(19|20)\d{2}\s*[-–—]\s*((19|20)\d{2})?\s*$""")

private fun isRandomPlayYearRange(value: String): Boolean = randomPlayYearRangeRegex.matches(value)

/**
 * Type strings are not standardised past `movie`/`series`. AIOMetadata alone declares `anime.movie`,
 * `anime.series`, `Films` and `TV`; others use `anime-movie`. Folding the separators to `_` lets one
 * set of suffix checks cover all of them.
 */
private fun normalizeRandomPlayType(value: String): String = value
    .trim()
    .lowercase()
    .replace('-', '_')
    .replace('.', '_')

private fun String?.isRandomPlayAnimeType(): Boolean = this != null && "anime" in this

private fun String.randomPlayKindOrNull(): RandomPlayKind? = when {
    this == "movie" || endsWith("_movie") || contains("film") -> RandomPlayKind.Movie
    this in setOf("series", "show", "tv", "tvshow") || endsWith("_series") -> RandomPlayKind.Series
    else -> null
}

/**
 * Whether the allow-list covers everything, in which case no genre check runs at all. Items whose
 * catalog gave them no genres only survive in this state, which is why the pool treats a narrowed
 * selection as a reason to go and resolve them.
 */
internal fun randomPlayAllowsEveryGenre(allowedGenres: Set<String>): Boolean =
    allowedGenres.mapTo(linkedSetOf(), ::normalizeRandomPlayGenre)
        .containsAll(RandomPlayGenres.map(::normalizeRandomPlayGenre))

/**
 * How many candidates each enabled card should be able to draw from before the pool stops working.
 * One page of one row is not a shuffle: a mixed anime catalog's first page is ~15% films, so an
 * anime-movie card backed by a single row was picking from about three titles — and from none at
 * all once the watched filter had taken its share.
 */
const val RANDOM_PLAY_TARGET_CANDIDATES = 15

/**
 * Every signal that decided a card's candidates, a few titles at a time.
 *
 * No two addons describe anime the same way and their payloads are not reproducible from here, so
 * when a title lands in the wrong card this is what says which field lied — the meta's own type,
 * its `animeType`, the episode-or-film id in `defaultVideoId`, its release info, or the row it came
 * from.
 */
internal fun randomPlayClassificationTrace(
    sourceSections: List<HomeCatalogSection>,
    category: RandomPlayCategory,
    limit: Int = 6,
): String = sourceSections
    .asSequence()
    .filterNot { it.key == RANDOM_PLAY_SECTION_KEY }
    .flatMap { section ->
        val row = section.randomPlayRow()
        section.items.asSequence().map { item -> item to row }
    }
    .filter { (item, row) -> item.randomPlayCategoryIn(row) == category }
    .distinctBy { (item, _) -> item.stableKey() }
    .take(limit)
    .joinToString { (item, row) ->
        buildString {
            append(item.id)
            append("(type=")
            append(item.type)
            item.animeType?.let { append(",animeType=$it") }
            item.resolvedMetadataType?.let { append(",resolvedType=$it") }
            item.defaultVideoId?.let { append(",defaultVideo=$it") }
            item.releaseInfo?.let { append(",release=$it") }
            if (item.resolvedEpisodeCount > 0) append(",episodes=${item.resolvedEpisodeCount}")
            row.contentType?.let { append(",row=$it") }
            if (item.carriesAnimeCatalogueId) append(",animeId")
            append(')')
        }
    }

/**
 * The same signals for one picked title, plus the row it was drawn from. A card holding the wrong
 * kind of title is a report about a single click, so the click has to be able to explain itself.
 */
fun randomPlayPickTrace(sourceSections: List<HomeCatalogSection>, item: MetaPreview): String {
    val section = sourceSections.firstOrNull { candidate ->
        candidate.key != RANDOM_PLAY_SECTION_KEY &&
            candidate.items.any { it.stableKey() == item.stableKey() }
    }
    val row = section?.randomPlayRow() ?: RandomPlayRow()
    return buildString {
        append(item.name)
        append(' ')
        append(item.id)
        append("(type=")
        append(item.type)
        item.animeType?.let { append(",animeType=$it") }
        item.resolvedMetadataType?.let { append(",resolvedType=$it") }
        item.defaultVideoId?.let { append(",defaultVideo=$it") }
        item.releaseInfo?.let { append(",release=$it") }
        if (item.resolvedEpisodeCount > 0) append(",episodes=${item.resolvedEpisodeCount}")
        if (item.carriesAnimeCatalogueId) append(",animeId")
        if (item.genres.isNotEmpty()) append(",genres=${item.genres.joinToString("/")}")
        append(") from row ")
        append(section?.title ?: "unknown")
        append('[')
        append(row.contentType)
        append(']')
        append(" -> ")
        append(item.randomPlayCategoryIn(row))
    }
}

/** The enabled cards that cannot yet offer [RANDOM_PLAY_TARGET_CANDIDATES] distinct titles. */
internal fun randomPlayShortCategories(
    sourceSections: List<HomeCatalogSection>,
    settings: HomeCatalogSettingsUiState,
    watchedKeys: Set<String>,
): Set<RandomPlayCategory> = settings.randomPlayCategories.filterTo(linkedSetOf()) { category ->
    randomPlayCandidates(
        sourceSections = sourceSections,
        category = category,
        allowedGenres = settings.randomPlayGenres,
        minimumImdbRating = settings.randomPlayMinimumImdbRating,
        watchedKeys = watchedKeys,
    ).size < RANDOM_PLAY_TARGET_CANDIDATES
}

private fun normalizeRandomPlayGenre(value: String): String = value
    .trim()
    .lowercase()
    .replace("sci-fi", "science fiction")
    .replace("science-fiction", "science fiction")
    .replace("science_fiction", "science fiction")

private fun String?.randomPlayRatingOrNull(): Float? = this
    ?.let { rating -> Regex("\\d+(?:\\.\\d+)?").find(rating)?.value }
    ?.toFloatOrNull()

private fun MetaPreview.preferredRandomPlayPoster(): String? =
    poster?.takeIf(String::isNotBlank) ?: posterFallback?.takeIf(String::isNotBlank)

private fun IntRange.randomIndexOrNull(seed: Int): Int {
    if (isEmpty()) return -1
    return Random(seed.absoluteValue + 1).nextInt(first, last + 1)
}

private const val RANDOM_PLAY_COLLAGE_POSTER_LIMIT = 5
