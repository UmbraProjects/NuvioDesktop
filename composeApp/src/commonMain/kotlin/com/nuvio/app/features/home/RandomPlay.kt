package com.nuvio.app.features.home

import com.nuvio.app.features.catalog.CatalogTarget
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

/** Canonical genre allow-list used by Random Play settings and matching. */
val RandomPlayGenres: List<String> = listOf(
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
    val allowEveryGenre = normalizedAllowedGenres.containsAll(RandomPlayGenres.map(::normalizeRandomPlayGenre))

    return sourceSections
        .asSequence()
        .filterNot { it.key == RANDOM_PLAY_SECTION_KEY }
        .flatMap { section ->
            val sectionGenre = (section.target as? CatalogTarget.Addon)?.genre
            section.items.asSequence().map { item -> item to sectionGenre }
        }
        .filter { (item, _) -> item.matchesRandomPlayCategory(category) }
        .filterNot { (item, _) -> WatchingState.isPosterWatched(watchedKeys, item) }
        .filter { (item, sectionGenre) ->
            if (allowEveryGenre) return@filter true
            val candidateGenres = (item.genres + listOfNotNull(sectionGenre))
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

private fun MetaPreview.matchesRandomPlayCategory(category: RandomPlayCategory): Boolean {
    val normalizedType = type.trim().lowercase().replace('-', '_')
    if (normalizedType == RANDOM_PLAY_ITEM_TYPE) return false
    val normalizedGenres = genres.map(::normalizeRandomPlayGenre)
    val isAnime = "anime" in normalizedType || normalizedGenres.any { it == "anime" }
    val isMovie = normalizedType == "movie" || normalizedType.endsWith("_movie") ||
        normalizedType.contains("film")
    val isSeries = normalizedType in setOf("series", "show", "tv", "anime") ||
        normalizedType.endsWith("_series")

    return when (category) {
        RandomPlayCategory.Movie -> isMovie && !isAnime
        RandomPlayCategory.Series -> isSeries && !isAnime
        RandomPlayCategory.AnimeMovie -> isMovie && isAnime
        RandomPlayCategory.AnimeSeries -> isSeries && isAnime
    }
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
