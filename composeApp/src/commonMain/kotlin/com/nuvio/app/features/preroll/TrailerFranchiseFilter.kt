package com.nuvio.app.features.preroll

import co.touchlab.kermit.Logger
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.discover.DiscoverAiClient
import com.nuvio.app.features.discover.DiscoverAiPromptText
import com.nuvio.app.features.discover.DiscoverAiSettingsRepository
import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
 * The pre-show's franchise filter, second half. [prerollTrailerCandidates] drops what it can tell from names and
 * collections, but "The Dark Knight" and "The Batman" share neither, so Batman trailers slipped in before it. Only
 * knowing the films are the same property catches that, so when the user's own AI key is on, one request per film
 * asks which of the candidates are the same franchise.
 *
 * The model only ever picks from the numbered titles it is given, so it cannot invent a trailer: the worst a bad
 * answer does is keep or drop a title that was already on the list. Each answer is kept for good, per candidate, so
 * a film costs one request the first time it is played and none afterwards.
 */

internal fun franchisePrompt(film: MetaDetails, candidates: List<MetaPreview>): DiscoverAiPromptText {
    val user = buildString {
        append("Film: ").append(film.name)
        yearOf(film.releaseInfo)?.let { append(" (").append(it).append(')') }
        if (film.genres.isNotEmpty()) append("\nGenres: ").append(film.genres.take(4).joinToString(", "))
        append("\n\nOther titles:\n")
        candidates.forEachIndexed { i, c ->
            append(i + 1).append(". ").append(c.name)
            yearOf(c.releaseInfo)?.let { append(" (").append(it).append(')') }
            append('\n')
        }
    }
    return DiscoverAiPromptText(system = FRANCHISE_SYSTEM, user = user)
}

private const val FRANCHISE_SYSTEM =
    "You help put together a cinema pre-show: trailers that play before a film. You are given the film and a " +
        "numbered list of other titles. Pick every title that must NOT be shown before the film because it is part " +
        "of the same franchise or series: a sequel, prequel, spin-off, reboot or remake, another adaptation of the " +
        "same source or character (for example a different actor's version of the same hero), an entry in the same " +
        "shared universe, or the film itself under another title. Use only what you are sure of. If you are not sure " +
        "two titles are connected, leave the title out of your answer. Reply with JSON only, nothing else, in the " +
        "form {\"drop\": [3, 7]}, using the numbers from the list. An empty list is a valid answer."

/** The year at the front of a release string ("2008", "2008-2013"), or null. */
internal fun yearOf(releaseInfo: String?): String? =
    releaseInfo?.trim()?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }

/**
 * The 1-based numbers the model chose to drop, as zero-based indexes, or null when the reply cannot be read.
 * Numbers outside the list are ignored. A reply that drops most of the list is treated as unreadable: it would
 * empty the pre-show, and that is far more likely a muddled answer than the truth.
 */
internal fun parseFranchiseDrops(raw: String, candidateCount: Int): Set<Int>? {
    if (candidateCount <= 0) return emptySet()
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val root = runCatching { Json.parseToJsonElement(raw.substring(start, end + 1)) }.getOrNull() as? JsonObject ?: return null
    val array = root["drop"] as? JsonArray ?: return null
    val indexes = array.mapNotNull { element ->
        val number = runCatching { element.jsonPrimitive.intOrNull ?: element.jsonPrimitive.content.trim().toIntOrNull() }.getOrNull()
        number?.takeIf { it in 1..candidateCount }?.minus(1)
    }.toSet()
    if (candidateCount >= 4 && indexes.size * 10 > candidateCount * 7) return null
    return indexes
}

/** [candidates] without the ones [decisions] marks as the same franchise. A title with no decision stays. */
internal fun applyFranchiseDecisions(candidates: List<MetaPreview>, decisions: Map<String, Boolean>): List<MetaPreview> =
    candidates.filterNot { decisions[trailerHistoryKey(it.type, it.id)] == true }

/** Every field needs a default, for the same reason as [TriviaCacheFile]. */
@Serializable
internal data class FranchiseDecisionsFile(
    val version: Int = VERSION,
    /** film `type:id` -> candidate `type:id` -> true when it is the same franchise (dropped). */
    val films: Map<String, Map<String, Boolean>> = emptyMap(),
    val touched: Map<String, Long> = emptyMap(),
) {
    companion object {
        /** Bump on any change to this file's shape or to [FRANCHISE_SYSTEM]'s meaning. */
        const val VERSION = 1
    }
}

private const val MAX_FILMS = 300

internal object TrailerFranchiseFilter {
    private val log = Logger.withTag("TrailerFranchise")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var file: FranchiseDecisionsFile? = null
    private val inFlight = mutableMapOf<String, Deferred<Map<String, Boolean>?>>()
    private val inFlightLock = Mutex()

    private fun loaded(): FranchiseDecisionsFile {
        file?.let { return it }
        val restored = runCatching {
            FranchiseStorage.load()
                ?.let { json.decodeFromString<FranchiseDecisionsFile>(it) }
                ?.takeIf { it.version == FranchiseDecisionsFile.VERSION }
        }.onFailure { log.w(it) { "franchise decisions unreadable; starting empty" } }.getOrNull()
        return (restored ?: FranchiseDecisionsFile()).also { file = it }
    }

    private fun decisionsFor(filmKey: String): Map<String, Boolean> = synchronized(lock) { loaded().films[filmKey].orEmpty() }

    private fun store(filmKey: String, added: Map<String, Boolean>) = synchronized(lock) {
        val current = loaded()
        val films = LinkedHashMap(current.films)
        val touched = LinkedHashMap(current.touched)
        films[filmKey] = films[filmKey].orEmpty() + added
        touched[filmKey] = System.currentTimeMillis()
        if (films.size > MAX_FILMS) {
            touched.entries.sortedBy { it.value }.take(films.size - MAX_FILMS).map { it.key }.forEach { films.remove(it); touched.remove(it) }
        }
        val next = FranchiseDecisionsFile(films = films, touched = touched)
        file = next
        runCatching { FranchiseStorage.save(json.encodeToString(next)) }
            .onFailure { log.w(it) { "could not persist franchise decisions" } }
    }

    /**
     * [candidates] less the ones the model calls the same franchise as [film]. Answers already kept are used at
     * once; for titles not yet judged it asks, waiting at most [waitMs] so the pre-show never stalls on a slow
     * provider. A late answer still lands in the cache for the next play. With no AI key (or the trivia switch off)
     * the list comes back unchanged.
     */
    suspend fun filter(film: MetaDetails, candidates: List<MetaPreview>, waitMs: Long = 5_000L): List<MetaPreview> {
        if (candidates.isEmpty()) return candidates
        val settings = DiscoverAiSettingsRepository.snapshot()
        if (!settings.isTriviaReady) return candidates
        val filmKey = trailerHistoryKey(film.type, film.id)
        val decided = decisionsFor(filmKey)
        val undecided = candidates.filter { trailerHistoryKey(it.type, it.id) !in decided }
        if (undecided.isEmpty()) return applyFranchiseDecisions(candidates, decided)

        val request = inFlightLock.withLock {
            inFlight[filmKey]?.takeIf { it.isActive } ?: scope.async {
                try {
                    ask(film, undecided, settings)
                } finally {
                    inFlightLock.withLock { inFlight.remove(filmKey) }
                }
            }.also { inFlight[filmKey] = it }
        }
        val answered = withTimeoutOrNull(waitMs) { request.await() }
        if (answered == null) log.i { "franchise answer for ${film.name} not ready; using what is known" }
        return applyFranchiseDecisions(candidates, decided + answered.orEmpty())
    }

    private suspend fun ask(
        film: MetaDetails,
        undecided: List<MetaPreview>,
        settings: com.nuvio.app.features.discover.DiscoverAiSettings,
    ): Map<String, Boolean>? {
        val reply = DiscoverAiClient.complete(settings, franchisePrompt(film, undecided)).getOrElse {
            log.w { "franchise check for ${film.name} failed: $it" }
            return null
        }
        val drops = parseFranchiseDrops(reply, undecided.size) ?: run {
            log.w { "franchise check for ${film.name}: reply unreadable" }
            return null
        }
        val answers = undecided.mapIndexed { i, c -> trailerHistoryKey(c.type, c.id) to (i in drops) }.toMap()
        store(trailerHistoryKey(film.type, film.id), answers)
        log.i { "franchise check for ${film.name}: dropped ${drops.map { undecided[it].name }}" }
        return answers
    }
}

internal expect object FranchiseStorage {
    fun load(): String?
    fun save(json: String?)
}
