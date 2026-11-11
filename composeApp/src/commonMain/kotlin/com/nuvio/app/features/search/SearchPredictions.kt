package com.nuvio.app.features.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.tmdb.TmdbService
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/*
 * Predictive text for the on-screen keyboard (see core/ui/KeyboardSuggestions.kt), from outside the person's own history:
 * films and shows from TMDB's title search ("bat" gives Batman, The Batman...), YouTube searches from YouTube's own
 * autocomplete. Each answer is kept for the session, a few dozen at most, and nothing is asked before a short pause in
 * the typing or for fewer than two letters.
 */
internal object SearchPredictions {
    private val log = Logger.withTag("SearchPredictions")
    private val lock = Mutex()
    private val media = LinkedHashMap<String, List<String>>()
    private val youTube = LinkedHashMap<String, List<String>>()

    /** TMDB's films and shows for [query], most popular first, those that start with it ahead of the rest. */
    suspend fun mediaTitles(query: String): List<String> = cached(media, query) {
        val titles = TmdbService.searchTitles(query).mapNotNull { (it.title ?: it.name)?.trim()?.takeIf(String::isNotEmpty) }
        titles.distinctBy { it.lowercase() }.sortedBy { !it.startsWith(query.trim(), ignoreCase = true) }
    }

    /** YouTube's autocomplete for [query], as youtube.com's search box shows it. */
    suspend fun youTubeSearches(query: String): List<String> = cached(youTube, query) {
        val url = "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=${query.trim().encodeURLParameter()}"
        val body = httpGetTextWithHeaders(url, mapOf("Accept" to "application/json"))
        parseYouTubeSuggestions(body)
    }

    private suspend fun cached(store: LinkedHashMap<String, List<String>>, query: String, load: suspend () -> List<String>): List<String> {
        val key = query.trim().lowercase()
        if (key.length < MIN_LETTERS) return emptyList()
        lock.withLock { store[key] }?.let { return it }
        val found = try {
            load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            log.d { "Predictions failed: ${error.message}" }
            return emptyList()
        }
        lock.withLock {
            store[key] = found
            while (store.size > KEPT) store.remove(store.keys.first())
        }
        return found
    }

    private const val KEPT = 64
}

/** The suggestions in a `client=firefox` autocomplete answer: `["query", ["one", "two", ...]]`. */
internal fun parseYouTubeSuggestions(body: String): List<String> =
    runCatching {
        ((Json.parseToJsonElement(body) as? JsonArray)?.getOrNull(1) as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty) }
    }.getOrNull().orEmpty()

/**
 * Predictions for a films-and-shows search box holding [text]: the person's [recent] searches that start with it, then
 * [extra] (titles already on screen), then TMDB's titles; never the text itself.
 */
@Composable
internal fun rememberMediaPredictions(text: String, recent: List<String>, extra: List<String> = emptyList()): List<String> =
    produceState(emptyList<String>(), text, recent, extra) {
        value = predictions(text, recent, extra, remote = emptyList())
        if (text.trim().length < MIN_LETTERS) return@produceState
        delay(PAUSE_MS)
        value = predictions(text, recent, extra, SearchPredictions.mediaTitles(text))
    }.value

/** Predictions for a YouTube search box holding [text]: matching [recent] searches, then YouTube's autocomplete. */
@Composable
internal fun rememberYouTubePredictions(text: String, recent: List<String>): List<String> =
    produceState(emptyList<String>(), text, recent) {
        value = predictions(text, recent, emptyList(), remote = emptyList())
        if (text.trim().length < MIN_LETTERS) return@produceState
        delay(PAUSE_MS)
        value = predictions(text, recent, emptyList(), SearchPredictions.youTubeSearches(text))
    }.value

private fun predictions(text: String, recent: List<String>, extra: List<String>, remote: List<String>): List<String> {
    val line = text.trim()
    if (line.isEmpty()) return emptyList()
    val mine = recent.filter { it.startsWith(line, ignoreCase = true) }
    val onScreen = extra.filter { it.contains(line, ignoreCase = true) }
    return (mine + onScreen + remote)
        .filterNot { it.equals(line, ignoreCase = true) }
        .distinctBy { it.lowercase() }
}

private const val MIN_LETTERS = 2
private const val PAUSE_MS = 200L
