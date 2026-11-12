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
 * Predictive text for the on-screen keyboard (see core/ui/KeyboardSuggestions.kt) and the top bar's search panel, from outside the person's own history:
 * films and shows from TMDB's title search ("bat" gives Batman, The Batman...), YouTube searches from YouTube's own
 * autocomplete. Each answer is kept for the session, a few dozen at most, and nothing is asked before a short pause in
 * the typing or for fewer than two letters.
 */
internal object SearchPredictions {
    private val log = Logger.withTag("SearchPredictions")
    private val lock = Mutex()
    private val media = LinkedHashMap<String, List<String>>()
    private val youTube = LinkedHashMap<String, List<String>>()
    private val music = LinkedHashMap<String, List<String>>()

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

    /** YouTube Music's completions for [query], as music.youtube.com's search box shows them. */
    suspend fun musicSearches(query: String): List<String> = cached(music, query) {
        com.nuvio.app.features.music.defaultMusicCatalog().searchSuggestions(query.trim())
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

private fun predictions(text: String, recent: List<String>, extra: List<String>, remote: List<String>): List<String> =
    predictionItems(text, recent, extra, remote, PredictionSource.Title).map { it.text }

/** Where a prediction came from, so a list can mark it (a clock for the person's own searches, and so on). */
internal enum class PredictionSource { History, Title, YouTube, Music, Channel }

internal data class Prediction(val text: String, val source: PredictionSource)

/**
 * The list behind every prediction: [recent] searches that start with [text], then [extra] (titles on screen) that hold
 * it, then [remote]; never the text itself, each line once.
 */
internal fun predictionItems(
    text: String,
    recent: List<String>,
    extra: List<String>,
    remote: List<String>,
    remoteSource: PredictionSource,
): List<Prediction> {
    val line = text.trim()
    if (line.isEmpty()) return emptyList()
    val mine = recent.filter { it.startsWith(line, ignoreCase = true) }.map { Prediction(it, PredictionSource.History) }
    val onScreen = extra.filter { it.contains(line, ignoreCase = true) }.map { Prediction(it, remoteSource) }
    return (mine + onScreen + remote.map { Prediction(it, remoteSource) })
        .filterNot { it.text.equals(line, ignoreCase = true) }
        .distinctBy { it.text.lowercase() }
}

/** The [names] (channel names) that hold [text], those that start with it first. */
internal fun channelPredictions(text: String, names: Sequence<String>): List<String> {
    val typed = text.trim()
    if (typed.isEmpty()) return emptyList()
    return names.map { it.trim() }
        .filter { it.contains(typed, ignoreCase = true) && !it.equals(typed, ignoreCase = true) }
        .distinctBy { it.lowercase() }
        .sortedBy { !it.startsWith(typed, ignoreCase = true) }
        .take(MAX_BAR_PREDICTIONS)
        .toList()
}

/** Predictions for a music search box holding [text]: matching [recent] searches, then YouTube Music's completions. */
@Composable
internal fun rememberMusicPredictions(text: String, recent: List<String>): List<String> =
    produceState(emptyList<String>(), text, recent) {
        value = predictions(text, recent, emptyList(), remote = emptyList())
        if (text.trim().length < MIN_LETTERS) return@produceState
        delay(PAUSE_MS)
        value = predictions(text, recent, emptyList(), SearchPredictions.musicSearches(text))
    }.value

/**
 * fork:navbar-search - what the top bar's search panel lists for [scope] while the box holds [text]: nothing for an
 * empty box (the panel shows the recent searches then), else the same predictions the on-screen keyboard offers for
 * that scope, tagged by where they came from. [recent] is the scope's search history, [channels] Live TV's channel names.
 */
@Composable
internal fun rememberBarPredictions(
    scope: com.nuvio.app.BarSearchScope,
    text: String,
    recent: List<String>,
    channels: () -> Sequence<String> = { emptySequence() },
): List<Prediction> {
    val source = when (scope) {
        com.nuvio.app.BarSearchScope.Media -> PredictionSource.Title
        com.nuvio.app.BarSearchScope.Music -> PredictionSource.Music
        com.nuvio.app.BarSearchScope.YouTube -> PredictionSource.YouTube
        com.nuvio.app.BarSearchScope.LiveTv -> PredictionSource.Channel
    }
    return produceState(emptyList<Prediction>(), scope, text, recent) {
        if (scope == com.nuvio.app.BarSearchScope.LiveTv) {
            value = channelPredictions(text, channels()).map { Prediction(it, source) }
            return@produceState
        }
        value = predictionItems(text, recent, emptyList(), emptyList(), source).take(MAX_BAR_PREDICTIONS)
        if (text.trim().length < MIN_LETTERS) return@produceState
        delay(PAUSE_MS)
        val remote = when (scope) {
            com.nuvio.app.BarSearchScope.Media -> SearchPredictions.mediaTitles(text)
            com.nuvio.app.BarSearchScope.Music -> SearchPredictions.musicSearches(text)
            else -> SearchPredictions.youTubeSearches(text)
        }
        value = predictionItems(text, recent, emptyList(), remote, source).take(MAX_BAR_PREDICTIONS)
    }.value
}

/** The bar's panel lists at most this many. */
internal const val MAX_BAR_PREDICTIONS = 8

private const val MIN_LETTERS = 2
private const val PAUSE_MS = 200L
