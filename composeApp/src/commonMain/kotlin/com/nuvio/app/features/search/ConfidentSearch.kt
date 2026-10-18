package com.nuvio.app.features.search

import co.touchlab.kermit.Logger
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.metadata.AnimeIdMapping
import com.nuvio.app.features.metadata.AnimeIdMappingRepository
import com.nuvio.app.features.metadata.AnimeIdPreference
import com.nuvio.app.features.metadata.AnimeIdPreferenceRepository
import com.nuvio.app.features.metadata.AnimeMappingCoordinateSystem
import com.nuvio.app.features.metadata.selectAnimeMappingByCoordinates
import com.nuvio.app.features.tmdb.TmdbSearchResult
import com.nuvio.app.features.tmdb.TmdbService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Confidence-based search, ported from Nuvio Tracker's search forward: submitting a search opens
 * the details page straight away when the query can only have meant one title.
 *
 * Only titles named exactly the query count (word for word, ignoring case, accents and
 * punctuation — "spiderman" is "Spider-Man"). The winner's confidence is its share of their
 * combined TMDB popularity: the only one is 100%, Death Note's anime at 5× its film is 83%,
 * US Shameless at 3× the UK one is 75%. Trailing qualifiers narrow the field when the name alone
 * can't: a year ("carrie 2013"), a type ("carrie tv", "carrie movie", "monster anime") or a season
 * ("sword art online s3", "season 3", which also means tv).
 */
@Serializable
data class ConfidentSearchSettings(
    val enabled: Boolean = true,
    /** Percent, one of [CONFIDENT_SEARCH_THRESHOLDS]. */
    val threshold: Int = DEFAULT_CONFIDENT_SEARCH_THRESHOLD,
)

/** 80% is roughly the tracker's "5× the next one" once a third title shares the name. */
const val DEFAULT_CONFIDENT_SEARCH_THRESHOLD = 80
val CONFIDENT_SEARCH_THRESHOLDS = listOf(60, 70, 80, 90, 100)

internal expect object ConfidentSearchStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}

/**
 * Read on every submit rather than cached across profiles: the store is profile-scoped and a
 * submit is rare enough that re-reading it is free next to the TMDB round trip it guards.
 */
object ConfidentSearchSettingsRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val _uiState = MutableStateFlow(ConfidentSearchSettings())
    val uiState: StateFlow<ConfidentSearchSettings> = _uiState.asStateFlow()

    fun snapshot(): ConfidentSearchSettings {
        val stored = ConfidentSearchStorage.loadPayload()?.takeIf(String::isNotBlank)
            ?.let { runCatching { json.decodeFromString<ConfidentSearchSettings>(it) }.getOrNull() }
            ?: ConfidentSearchSettings()
        _uiState.value = stored
        return stored
    }

    fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }

    fun setThreshold(threshold: Int) = update { it.copy(threshold = threshold) }

    private fun update(transform: (ConfidentSearchSettings) -> ConfidentSearchSettings) {
        val next = transform(snapshot())
        ConfidentSearchStorage.savePayload(json.encodeToString(ConfidentSearchSettings.serializer(), next))
        _uiState.value = next
    }
}

enum class ConfidentSearchKind { Movie, Tv, Anime }

/** A query split into the title and the qualifiers typed after it. */
data class ConfidentSearchQuery(
    val title: String,
    val year: Int? = null,
    val kind: ConfidentSearchKind? = null,
    val season: Int? = null,
    /** ISO 3166-1, from "us", "uk", "(US)", "british"… — for remakes like Shameless or The Office. */
    val country: String? = null,
) {
    val hasQualifiers: Boolean get() = year != null || kind != null || season != null || country != null
}

private val KIND_WORDS = mapOf(
    "movie" to ConfidentSearchKind.Movie,
    "movies" to ConfidentSearchKind.Movie,
    "film" to ConfidentSearchKind.Movie,
    "tv" to ConfidentSearchKind.Tv,
    "show" to ConfidentSearchKind.Tv,
    "series" to ConfidentSearchKind.Tv,
    "anime" to ConfidentSearchKind.Anime,
)
/**
 * Two-letter words that are also everyday English ("it", "in", "de") are left out: a query that
 * ends in one is far likelier to be part of the title.
 */
private val COUNTRY_WORDS = mapOf(
    "us" to "US", "usa" to "US", "american" to "US",
    "uk" to "GB", "gb" to "GB", "british" to "GB", "english" to "GB",
    "au" to "AU", "aus" to "AU", "australian" to "AU",
    "ca" to "CA", "canada" to "CA", "canadian" to "CA",
    "nz" to "NZ", "ie" to "IE", "irish" to "IE",
    "jp" to "JP", "japanese" to "JP", "kr" to "KR", "korean" to "KR",
    "french" to "FR", "german" to "DE", "spanish" to "ES", "italian" to "IT", "indian" to "IN",
    "swedish" to "SE", "danish" to "DK", "norwegian" to "NO", "mexican" to "MX", "brazilian" to "BR",
)
private val SEASON_TOKEN = Regex("^s(?:eason)?0*(\\d{1,3})$")
private val YEAR_TOKEN = Regex("^\\(?((?:18|19|20)\\d{2})\\)?$")

/**
 * Peels qualifiers off the end of [raw], each kind at most once and never the whole query —
 * "1917" stays a title. The raw query is always tried as a title first (see [ConfidentSearch]),
 * so "Blade Runner 2049" or "The Lego Movie" still match their own names.
 */
fun parseConfidentSearchQuery(raw: String): ConfidentSearchQuery {
    val words = raw.trim().split(Regex("\\s+")).filter(String::isNotBlank).toMutableList()
    var year: Int? = null
    var kind: ConfidentSearchKind? = null
    var season: Int? = null
    var country: String? = null
    while (words.size > 1) {
        val last = words.last().lowercase()
        val bare = last.removePrefix("(").removeSuffix(")")
        val beforeLast = words[words.size - 2].lowercase()
        when {
            season == null && beforeLast == "season" && last.toIntOrNull() != null && words.size > 2 -> {
                season = last.toInt()
                words.removeAt(words.lastIndex)
                words.removeAt(words.lastIndex)
            }
            season == null && SEASON_TOKEN.matches(last) -> {
                season = SEASON_TOKEN.find(last)!!.groupValues[1].toInt()
                words.removeAt(words.lastIndex)
            }
            year == null && YEAR_TOKEN.matches(last) -> {
                year = YEAR_TOKEN.find(last)!!.groupValues[1].toInt()
                words.removeAt(words.lastIndex)
            }
            country == null && bare in COUNTRY_WORDS -> {
                country = COUNTRY_WORDS.getValue(bare)
                words.removeAt(words.lastIndex)
            }
            kind == null && last in KIND_WORDS -> {
                kind = KIND_WORDS.getValue(last)
                words.removeAt(words.lastIndex)
                // "tv show", "tv series"
                if (kind == ConfidentSearchKind.Tv && words.size > 1 && words.last().equals("tv", ignoreCase = true)) {
                    words.removeAt(words.lastIndex)
                }
            }
            else -> break
        }
    }
    return ConfidentSearchQuery(title = words.joinToString(" "), year = year, kind = kind, season = season, country = country)
}

/** Letters and digits only, so case, accents, spacing and punctuation never decide a match. */
internal fun confidentSearchKey(name: String): String =
    java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .filter(Char::isLetterOrDigit)

private val ARTICLES = setOf("the", "a", "an")

/** Words for comparing names: accents, case and apostrophes dropped ("Grey's" → "greys"). */
private fun confidentSearchWords(name: String): List<String> =
    java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("['’]"), "")
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter(String::isNotEmpty)

/** [confidentSearchKey] without a leading "the"/"a"/"an": "dark knight" is The Dark Knight's name. */
private fun confidentSearchKeyWithoutArticle(name: String): String {
    val words = confidentSearchWords(name)
    return (if (words.size > 1 && words.first() in ARTICLES) words.drop(1) else words).joinToString("")
}

/** Whether [name] is exactly what [query] names, give or take punctuation and a leading article. */
internal fun confidentSearchNamesExactly(name: String, query: String): Boolean {
    val queryKey = confidentSearchKey(query)
    if (queryKey.isEmpty()) return false
    return confidentSearchKey(name) == queryKey ||
        confidentSearchKeyWithoutArticle(name) == confidentSearchKeyWithoutArticle(query)
}

/**
 * Whether [name] is what [query] names: exactly, or — for queries of two or more words besides
 * "the"/"a"/"an" — the end of it, since people search a title by its distinctive tail:
 * "chamber of secrets" (Harry Potter and the…), "welcome to derry" (IT: …).
 * One word is never enough ("secrets" ends a thousand titles).
 */
internal fun confidentSearchNames(name: String, query: String): Boolean {
    if (confidentSearchNamesExactly(name, query)) return true
    val queryWords = confidentSearchWords(query)
    if (queryWords.count { it !in ARTICLES } < 2) return false
    val nameWords = confidentSearchWords(name)
    return nameWords.size > queryWords.size && nameWords.takeLast(queryWords.size) == queryWords
}

/** One title the query may have meant, whatever catalog it came from. */
internal data class ConfidentSearchCandidate<T>(
    val value: T,
    val name: String,
    val year: Int?,
    val isTv: Boolean,
    val isAnime: Boolean,
    val popularity: Double,
    /** Empty when the source doesn't say (TMDB movie results), which never rules a title out. */
    val countries: List<String> = emptyList(),
)

internal data class ConfidentSearchPick<T>(val value: T, val confidence: Double)

/**
 * The candidate [query] can only have meant, with its confidence, or null when no title is named
 * that (see [confidentSearchNames]). Titles with exactly that name are the field; titles that only
 * end in it count when there are none, else TV Animal Farm, a far more popular show than any
 * Animal Farm, would take "animal farm". Pure, so the rules are testable without TMDB.
 */
internal fun <T> pickConfidentCandidate(
    query: ConfidentSearchQuery,
    candidates: List<ConfidentSearchCandidate<T>>,
): ConfidentSearchPick<T>? {
    val eligible = candidates.filter { candidate ->
        (query.year == null || candidate.year == query.year) &&
            (query.season == null || candidate.isTv) &&
            (query.country == null || candidate.countries.isEmpty() || query.country in candidate.countries) &&
            when (query.kind) {
                ConfidentSearchKind.Movie -> !candidate.isTv
                ConfidentSearchKind.Tv -> candidate.isTv
                ConfidentSearchKind.Anime -> candidate.isAnime
                null -> true
            }
    }
    val named = eligible.filter { confidentSearchNamesExactly(it.name, query.title) }
        .ifEmpty { eligible.filter { confidentSearchNames(it.name, query.title) } }
    val top = named.maxByOrNull { it.popularity } ?: return null
    val total = named.sumOf { it.popularity.coerceAtLeast(0.0) }
    val confidence = if (total > 0) top.popularity.coerceAtLeast(0.0) / total else 1.0 / named.size
    return ConfidentSearchPick(top.value, confidence)
}

/**
 * The spelling among [names] that [title] is a typo of: whole names and their endings with as many
 * words as [title], within an edit budget of one per 7 letters (at least 1): "animal frm" may be
 * 1 off, "harr pottr an th chamber of secrets" 4. Generous on purpose — the addon already chose
 * these results for the query, this only checks the spelling taken from them is the one meant.
 * Null when nothing is that close or two different spellings tie.
 */
internal fun closestSpelling(title: String, names: List<String>): String? {
    val key = confidentSearchKey(title)
    if (key.length < 5) return null
    val budget = (key.length / 7).coerceAtLeast(1)
    val wordCount = confidentSearchWords(title).size
    val options = names.flatMap { name ->
        val words = confidentSearchWords(name)
        listOfNotNull(name, words.takeIf { it.size > wordCount }?.takeLast(wordCount)?.joinToString(" "))
    }
    val scored = options
        .map { it to editDistance(key, confidentSearchKey(it)) }
        .filter { (_, distance) -> distance <= budget }
    val best = scored.minOfOrNull { it.second } ?: return null
    val closest = scored.filter { it.second == best }.distinctBy { confidentSearchKey(it.first) }
    return closest.singleOrNull()?.first
}

/** Optimal string alignment distance: insertions, deletions, substitutions and adjacent swaps. */
internal fun editDistance(a: String, b: String): Int {
    val d = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) d[i][0] = i
    for (j in 0..b.length) d[0][j] = j
    for (i in 1..a.length) {
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
            }
        }
    }
    return d[a.length][b.length]
}

data class ConfidentSearchMatch(val type: String, val id: String, val confidence: Double)

object ConfidentSearch {
    private val log = Logger.withTag("ConfidentSearch")

    /**
     * The details page [rawQuery] should open, or null to stay on the results. Never throws and
     * never takes longer than [timeoutMs]: a slow answer would yank the user off results they're
     * already reading.
     */
    suspend fun resolve(rawQuery: String, timeoutMs: Long = RESOLVE_TIMEOUT_MS): ConfidentSearchMatch? {
        val settings = ConfidentSearchSettingsRepository.snapshot()
        if (!settings.enabled || rawQuery.isBlank()) return null
        return runCatching {
            withTimeoutOrNull(timeoutMs) { withContext(Dispatchers.Default) { resolveInternal(rawQuery.trim(), settings) } }
        }.onFailure { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            log.w { "Confident search failed for \"$rawQuery\": ${error.message}" }
        }.getOrNull()
    }

    private suspend fun resolveInternal(rawQuery: String, settings: ConfidentSearchSettings): ConfidentSearchMatch? {
        val threshold = settings.threshold.coerceIn(1, 100) / 100.0
        val parsed = parseConfidentSearchQuery(rawQuery)
        val asTyped = ConfidentSearchQuery(title = rawQuery)
        if (!com.nuvio.app.features.discover.TmdbBrowse.isAvailable) return resolveFromAddons(asTyped, parsed, threshold)

        // Both readings at once when there are qualifiers; the query as typed wins if anything is
        // named that, so a title that ends in a year or "movie" is never cut short.
        val (typedResults, parsedResults) = coroutineScope {
            val typed = async { TmdbService.searchTitles(rawQuery).toCandidates() }
            val stripped = if (parsed.hasQualifiers) async { TmdbService.searchTitles(parsed.title).toCandidates() } else null
            typed.await() to stripped?.await()
        }
        val (query, candidates) = when {
            typedResults.any { confidentSearchNames(it.name, rawQuery) } -> asTyped to typedResults
            parsedResults?.any { confidentSearchNames(it.name, parsed.title) } == true -> parsed to parsedResults
            else -> {
                // Nothing on TMDB by that name: maybe a typo. Addons like AIOMetadata (LumiereDB)
                // correct spelling in their search, so take the spelling from the results this
                // submit already loaded and ask TMDB again — confidence still comes from TMDB,
                // so "animal frm" opens exactly what "animal farm" would.
                val base = if (parsed.hasQualifiers) parsed else asTyped
                val spelled = addonSpelling(base.title, rawQuery)
                if (spelled == null) {
                    log.i { "\"$rawQuery\": no forward (no title named that${if (parsed.hasQualifiers) "" else ", no qualifiers"}, no close addon spelling)" }
                    return null
                }
                log.i { "\"$rawQuery\": addon results spell it \"$spelled\"" }
                base.copy(title = spelled) to TmdbService.searchTitles(spelled).toCandidates()
            }
        }
        val pick = pickConfidentCandidate(query, candidates)
        if (pick == null || pick.confidence < threshold) {
            log.i { "\"$rawQuery\": no forward (${pick?.let { "best ${(it.confidence * 100).toInt()}%" } ?: "no title named that"})" }
            return null
        }
        val result = pick.value
        val match = when (val anime = animeTarget(result, query.season)) {
            AnimeTarget.NotSplit -> addonMatchFor(result) ?: ConfidentSearchMatch(
                type = if (result.isTv) "series" else "movie",
                id = "tmdb:${result.id}",
                confidence = pick.confidence,
            )
            AnimeTarget.Ambiguous -> {
                log.i { "\"$rawQuery\": anime with season-split ids and ${query.season?.let { "no entry for season $it" } ?: "several seasons, none given"}" }
                return null
            }
            is AnimeTarget.Entry -> anime.match
        }
        log.i { "\"$rawQuery\" → ${match.type} ${match.id} at ${(pick.confidence * 100).toInt()}%" }
        return match.copy(confidence = pick.confidence)
    }

    private sealed interface AnimeTarget {
        /** Not anime, ids grouped by franchise, or no split entries known: open the TMDB title. */
        data object NotSplit : AnimeTarget
        /** Several season entries and no way to tell which one was meant. */
        data object Ambiguous : AnimeTarget
        data class Entry(val match: ConfidentSearchMatch) : AnimeTarget
    }

    /**
     * With season-split anime ids (MAL/Kitsu), a franchise with several seasons is several titles:
     * forward only to the season asked for ("sword art online s3"), or when there is just the one.
     */
    private fun animeTarget(result: TmdbSearchResult, season: Int?): AnimeTarget {
        val preference = AnimeIdPreferenceRepository.current()
        if (preference == AnimeIdPreference.IMDB || !result.isTv) return AnimeTarget.NotSplit
        val entries = AnimeIdMappingRepository.entriesForTmdb(result.id, isTv = true)
            .filter { it.kitsuId != null || it.malId != null }
            .distinct()
        if (entries.isEmpty()) return AnimeTarget.NotSplit
        val entry = when {
            season != null -> selectAnimeMappingByCoordinates(entries, season, null, AnimeMappingCoordinateSystem.AUTO)
            entries.size == 1 -> entries.first()
            else -> null
        } ?: return AnimeTarget.Ambiguous
        val id = entry.nativeId(preference) ?: return AnimeTarget.Ambiguous
        return AnimeTarget.Entry(
            ConfidentSearchMatch(
                type = if (entry.type.equals("MOVIE", ignoreCase = true)) "movie" else "series",
                id = id,
                confidence = 0.0,
            ),
        )
    }

    /**
     * Prefer the user's own addon result for the winner when it's already on screen: its id is the
     * one their library and watched state use. Same name, type and year — else TMDB's id.
     */
    private fun addonMatchFor(result: TmdbSearchResult): ConfidentSearchMatch? {
        val key = confidentSearchKey(result.displayTitle)
        val year = result.year
        return SearchRepository.uiState.value.sections.asSequence()
            .flatMap { it.items.asSequence() }
            .firstOrNull { item ->
                confidentSearchKey(item.name) == key &&
                    item.isTvType() == result.isTv &&
                    item.releaseYear() == year
            }
            ?.let { ConfidentSearchMatch(type = it.type, id = it.id, confidence = 0.0) }
    }

    /**
     * The addon results' spelling of [title], or null when none is close enough to be a typo of it.
     * Compared against whole names and against name endings of the same length, so "chambr of
     * secrets" finds "chamber of secrets" in "Harry Potter and the Chamber of Secrets". Two different
     * spellings equally close mean the addon wasn't sure either.
     */
    private suspend fun addonSpelling(title: String, rawQuery: String): String? {
        val state = SearchRepository.uiState.first { it.query == rawQuery && !it.isLoading }
        val names = state.sections.flatMap { section -> section.items.map { it.name } }.distinct()
        return closestSpelling(title, names)
    }

    /**
     * Without a TMDB key there is no popularity, so every title named the query counts the same:
     * the confidence is 1 in however many there are. Waits for the addon search the submit started.
     */
    private suspend fun resolveFromAddons(
        asTyped: ConfidentSearchQuery,
        parsed: ConfidentSearchQuery,
        threshold: Double,
    ): ConfidentSearchMatch? {
        val state = SearchRepository.uiState.first { it.query == asTyped.title && !it.isLoading }
        val candidates = state.sections.flatMap { it.items }
            .distinctBy { it.id }
            .map { item ->
                ConfidentSearchCandidate(
                    value = item,
                    name = item.name,
                    year = item.releaseYear(),
                    isTv = item.isTvType(),
                    isAnime = item.type.equals("anime", ignoreCase = true),
                    popularity = 0.0,
                )
            }
        val pick = pickConfidentCandidate(asTyped, candidates)
            ?: parsed.takeIf { it.hasQualifiers }?.let { pickConfidentCandidate(it, candidates) }
            ?: return null
        if (pick.confidence < threshold) return null
        return ConfidentSearchMatch(type = pick.value.type, id = pick.value.id, confidence = pick.confidence)
    }

    private fun List<TmdbSearchResult>.toCandidates(): List<ConfidentSearchCandidate<TmdbSearchResult>> =
        map { result ->
            ConfidentSearchCandidate(
                value = result,
                name = result.displayTitle,
                year = result.year,
                isTv = result.isTv,
                isAnime = AnimeIdMappingRepository.entriesForTmdb(result.id, result.isTv).isNotEmpty(),
                popularity = result.popularity,
                countries = result.originCountry,
            )
        }

    private fun AnimeIdMapping.nativeId(preference: AnimeIdPreference): String? = when (preference) {
        AnimeIdPreference.MAL -> malId?.let { "mal:$it" } ?: kitsuId?.let { "kitsu:$it" }
        else -> kitsuId?.let { "kitsu:$it" } ?: malId?.let { "mal:$it" }
    }

    private fun MetaPreview.isTvType(): Boolean =
        type.equals("series", ignoreCase = true) || type.equals("tv", ignoreCase = true) ||
            type.equals("anime", ignoreCase = true)

    private fun MetaPreview.releaseYear(): Int? =
        releaseInfo?.let { Regex("(?:18|19|20)\\d{2}").find(it)?.value?.toIntOrNull() }
}

private const val RESOLVE_TIMEOUT_MS = 4_000L
