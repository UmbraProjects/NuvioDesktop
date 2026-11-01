package com.nuvio.app.features.preroll

import co.touchlab.kermit.Logger
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.discover.DiscoverAiClient
import com.nuvio.app.features.discover.DiscoverAiPromptText
import com.nuvio.app.features.discover.DiscoverAiSettings
import com.nuvio.app.features.discover.DiscoverAiSettingsRepository
import com.nuvio.app.features.discover.normalizeTitleForMatch
import com.nuvio.app.features.discover.pickBestTmdbMatch
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.tmdb.TmdbService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/*
 * Pre-show trailers the AI goes looking for. "More Like This" is TMDB's list, and for a film like The Dark Knight it
 * is mostly more Batman. With the user's own AI key on, one request per film asks for films a viewer of it would want
 * a trailer for, from OTHER franchises (other DC characters for Batman, say). Each suggestion is then looked up on
 * TMDB by the same matcher Discover's AI rows use, so a title the model invented matches nothing and is dropped; what
 * reaches the pre-show is always a real title that TMDB knows, with a trailer found the usual way.
 *
 * Kept per film for 90 days, so a film costs one request the first time it is played and none after. Failures and
 * empty answers are never kept.
 */

internal data class TrailerSuggestion(val title: String, val year: Int?)

/** Titles asked for, and the most that are looked up. */
private const val ASKED = 10
private const val MAX_SUGGESTIONS = 12

internal fun suggestionsPrompt(film: MetaDetails): DiscoverAiPromptText {
    val user = buildString {
        append("Film: ").append(film.name)
        yearOf(film.releaseInfo)?.let { append(" (").append(it).append(')') }
        append("\nType: ").append(if (film.type == "series") "TV series" else "film")
        if (film.genres.isNotEmpty()) append("\nGenres: ").append(film.genres.take(4).joinToString(", "))
        film.director.firstOrNull()?.let { append("\nDirector: ").append(it) }
    }
    return DiscoverAiPromptText(system = SUGGESTIONS_SYSTEM, user = user)
}

private const val SUGGESTIONS_SYSTEM =
    "You help put together a cinema pre-show: trailers that play before a film. Suggest $ASKED well-known " +
        "titles of the same kind whose trailers this film's audience would enjoy, from DIFFERENT franchises: never a " +
        "sequel, prequel, spin-off, reboot, remake or another version of the same film, series, character or " +
        "property. If the film belongs to a publisher's or studio's wider world (a comic-book film, say), prefer " +
        "other franchises from that same world, for example other DC characters for a Batman film, then films " +
        "similar in genre, tone and audience. Mix eras; real, released titles only; if you are not sure a title " +
        "exists, leave it out. Reply with JSON only, nothing else, in the form " +
        "{\"films\": [{\"title\": \"Title\", \"year\": 2013}]}."

/** The suggested titles in [raw], at most [MAX_SUGGESTIONS], repeats removed; null when the reply cannot be read. */
internal fun parseTrailerSuggestions(raw: String): List<TrailerSuggestion>? {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val root = runCatching { Json.parseToJsonElement(raw.substring(start, end + 1)) }.getOrNull() as? JsonObject ?: return null
    val films = root["films"] as? JsonArray ?: return null
    val seen = mutableSetOf<String>()
    return films.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val title = runCatching { obj["title"]?.jsonPrimitive?.content }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val year = runCatching { obj["year"]?.jsonPrimitive?.let { it.intOrNull ?: it.content.trim().toIntOrNull() } }.getOrNull()
        TrailerSuggestion(title, year?.takeIf { it in 1888..2100 })
    }.filter { seen.add(normalizeTitleForMatch(it.title)) }.take(MAX_SUGGESTIONS)
}

/**
 * The pre-show's list: [suggestions] first (they were chosen for this film), then what [named] still has left. Dropped
 * from the suggestions: the film itself, anything in its collection ([excludedIds]), anything that shares its franchise
 * by name ([baseTokens]), and any repeat of a title already in the list, whichever list it came from.
 */
internal fun mergeTrailerLineup(
    excludedIds: Set<String>,
    filmTitleKey: String,
    baseTokens: List<String>,
    suggestions: List<MetaPreview>,
    named: List<MetaPreview>,
): List<MetaPreview> {
    val usable = suggestions
        .filter { it.id !in excludedIds }
        .filter { normalizeTitleForMatch(it.name) != filmTitleKey }
        .filterNot { sharesFranchise(baseTokens, it.name) }
    val seenIds = mutableSetOf<String>()
    val seenTitles = mutableSetOf<String>()
    return (usable + named).filter { seenIds.add("${it.type}:${it.id}") && seenTitles.add(normalizeTitleForMatch(it.name) + "|" + (yearOf(it.releaseInfo) ?: "")) }
}

/** A resolved suggestion, as kept on disk. Every field needs a default, for the same reason as [TriviaCacheFile]. */
@Serializable
internal data class TrailerSuggestionEntry(
    val id: String = "",
    val type: String = "movie",
    val name: String = "",
    val poster: String? = null,
    val banner: String? = null,
    val year: String? = null,
)

@Serializable
internal data class TrailerSuggestionsFile(
    val version: Int = VERSION,
    val films: Map<String, List<TrailerSuggestionEntry>> = emptyMap(),
    val builtAt: Map<String, Long> = emptyMap(),
) {
    companion object {
        /** Bump on any change to this file's shape or to what [SUGGESTIONS_SYSTEM] asks for. */
        const val VERSION = 1
    }
}

private const val MAX_FILMS = 200
private const val KEEP_MS = 90L * 24 * 60 * 60 * 1000

internal fun TrailerSuggestionEntry.toPreview() = MetaPreview(id = id, type = type, name = name, poster = poster, banner = banner, releaseInfo = year)

internal object TrailerSuggestions {
    private val log = Logger.withTag("TrailerSuggestions")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var file: TrailerSuggestionsFile? = null
    private val inFlight = mutableMapOf<String, Deferred<List<MetaPreview>?>>()
    private val inFlightLock = Mutex()

    private fun loaded(): TrailerSuggestionsFile {
        file?.let { return it }
        val restored = runCatching {
            TrailerSuggestionsStorage.load()
                ?.let { json.decodeFromString<TrailerSuggestionsFile>(it) }
                ?.takeIf { it.version == TrailerSuggestionsFile.VERSION }
        }.onFailure { log.w(it) { "trailer suggestions unreadable; starting empty" } }.getOrNull()
        return (restored ?: TrailerSuggestionsFile()).also { file = it }
    }

    private fun kept(filmKey: String): List<MetaPreview>? = synchronized(lock) {
        val current = loaded()
        val at = current.builtAt[filmKey] ?: return null
        if (System.currentTimeMillis() - at > KEEP_MS) return null
        current.films[filmKey]?.map { it.toPreview() }?.takeIf { it.isNotEmpty() }
    }

    private fun store(filmKey: String, entries: List<TrailerSuggestionEntry>) = synchronized(lock) {
        val current = loaded()
        val films = LinkedHashMap(current.films)
        val builtAt = LinkedHashMap(current.builtAt)
        films[filmKey] = entries
        builtAt[filmKey] = System.currentTimeMillis()
        if (films.size > MAX_FILMS) {
            builtAt.entries.sortedBy { it.value }.take(films.size - MAX_FILMS).map { it.key }.forEach { films.remove(it); builtAt.remove(it) }
        }
        val next = TrailerSuggestionsFile(films = films, builtAt = builtAt)
        file = next
        runCatching { TrailerSuggestionsStorage.save(json.encodeToString(next)) }
            .onFailure { log.w(it) { "could not persist trailer suggestions" } }
    }

    /**
     * Real titles the AI suggests for [film], or an empty list: no AI key, no TMDB key, a failed or slow request (a
     * late answer is still kept for next time), or nothing that matched. Waits at most [waitMs].
     */
    suspend fun forFilm(film: MetaDetails, waitMs: Long = 7_000L): List<MetaPreview> {
        val settings = DiscoverAiSettingsRepository.snapshot()
        if (!settings.isTriviaReady) return emptyList()
        val filmKey = trailerHistoryKey(film.type, film.id)
        kept(filmKey)?.let { return it }
        val request = inFlightLock.withLock {
            inFlight[filmKey]?.takeIf { it.isActive } ?: scope.async {
                try {
                    ask(film, filmKey, settings)
                } finally {
                    inFlightLock.withLock { inFlight.remove(filmKey) }
                }
            }.also { inFlight[filmKey] = it }
        }
        return withTimeoutOrNull(waitMs) { request.await() }.orEmpty()
    }

    private suspend fun ask(film: MetaDetails, filmKey: String, settings: DiscoverAiSettings): List<MetaPreview>? {
        val reply = DiscoverAiClient.complete(settings, suggestionsPrompt(film)).getOrElse {
            log.w { "suggestions for ${film.name} failed: $it" }
            return null
        }
        val asked = parseTrailerSuggestions(reply) ?: run {
            log.w { "suggestions for ${film.name}: reply unreadable" }
            return null
        }
        val mediaType = if (film.type == "series") "tv" else "movie"
        val resolved = coroutineScope { asked.map { s -> async { resolveOne(s, mediaType, film.type) } }.awaitAll() }
            .filterNotNull()
            .distinctBy { it.id }
        log.i { "suggestions for ${film.name}: asked ${asked.size}, matched ${resolved.size}: ${resolved.map { it.name }}" }
        if (resolved.isEmpty()) return null
        store(
            filmKey,
            resolved.map { TrailerSuggestionEntry(it.id, it.type, it.name, it.poster, it.banner, it.releaseInfo) },
        )
        return resolved
    }

    private suspend fun resolveOne(s: TrailerSuggestion, mediaType: String, type: String): MetaPreview? {
        val found = runCatching { TmdbService.searchTitles(s.title, mediaType, s.year) }.getOrNull().orEmpty()
            .ifEmpty { if (s.year == null) emptyList() else runCatching { TmdbService.searchTitles(s.title, mediaType) }.getOrNull().orEmpty() }
        // The same matcher Discover's AI rows use: an invented title matches nothing and is dropped here.
        val match = pickBestTmdbMatch(s.title, s.year, found) ?: return null
        return MetaPreview(
            id = "tmdb:${match.id}",
            type = type,
            name = match.displayTitle,
            poster = TmdbService.tmdbImageUrl(match.posterPath),
            banner = TmdbService.tmdbImageUrl(match.backdropPath, size = "w1280"),
            releaseInfo = match.year?.toString(),
        )
    }
}

/**
 * The pre-show's whole trailer list for [film]: the AI's suggestions first, then "More Like This" minus the same
 * franchise, both asked at once so neither waits on the other. With no AI key this is just [named].
 */
internal suspend fun buildTrailerLineup(film: MetaDetails, named: List<MetaPreview>): List<MetaPreview> = coroutineScope {
    val filtered = async { runCatching { TrailerFranchiseFilter.filter(film, named) }.getOrDefault(named) }
    val suggested = async { runCatching { TrailerSuggestions.forFilm(film) }.getOrDefault(emptyList()) }
    val excluded = buildSet {
        add(film.id)
        film.tmdbId?.let { add("tmdb:$it") }
        film.collectionItems.forEach { add(it.id) }
    }
    mergeTrailerLineup(
        excludedIds = excluded,
        filmTitleKey = normalizeTitleForMatch(film.name),
        baseTokens = franchiseTokens(film.name),
        suggestions = suggested.await(),
        named = filtered.await(),
    )
}

internal expect object TrailerSuggestionsStorage {
    fun load(): String?
    fun save(json: String?)
}
