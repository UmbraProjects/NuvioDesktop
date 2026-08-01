package com.nuvio.app.features.mdblist

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.trakt.TraktCalendarEntry
import com.nuvio.app.features.trakt.TraktCalendarUiState
import com.nuvio.app.features.trakt.addMonth
import com.nuvio.app.features.trakt.calendarDaysInMonth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Calendar data backed by MDBList's authenticated `/calendar/events` endpoint.
 *
 * MDBList limits a request to a 120-day range and 1,000 events. Loading one calendar month per
 * request stays comfortably inside both limits and mirrors the existing Trakt repository's
 * on-demand month paging.
 */
internal object MdbListCalendarRepository {
    private val log = Logger.withTag("MdbListCalendar")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow(TraktCalendarUiState())
    val uiState: StateFlow<TraktCalendarUiState> = _uiState.asStateFlow()

    private val mutex = Mutex()
    private val entriesByDate = mutableMapOf<String, List<TraktCalendarEntry>>()
    private val loadedMonths = mutableSetOf<String>()
    private val inFlightMonths = mutableSetOf<String>()

    fun ensureLoaded() {
        val today = com.nuvio.app.features.trakt.epochMsToUtcDate(
            com.nuvio.app.features.trakt.TraktPlatformClock.nowEpochMs(),
        )
        ensureMonthsAround(today.year, today.month)
    }

    fun ensureMonthsAround(year: Int, month: Int) {
        val previous = addMonth(year, month, -1)
        val next = addMonth(year, month, 1)
        ensureMonth(year, month)
        ensureMonth(previous.first, previous.second)
        ensureMonth(next.first, next.second)
    }

    fun ensureMonth(year: Int, month: Int) {
        scope.launch { loadMonth(year, month) }
    }

    fun refreshAsync() {
        scope.launch {
            resetState()
            ensureLoaded()
        }
    }

    fun onProfileChanged() {
        scope.launch {
            resetState()
            _uiState.value = TraktCalendarUiState()
        }
    }

    fun clearLocalState() = onProfileChanged()

    private suspend fun resetState() {
        mutex.withLock {
            entriesByDate.clear()
            loadedMonths.clear()
            inFlightMonths.clear()
        }
    }

    private suspend fun loadMonth(year: Int, month: Int) {
        val monthKey = calendarMonthKey(year, month)
        val shouldFetch = mutex.withLock {
            if (monthKey in loadedMonths || monthKey in inFlightMonths) {
                false
            } else {
                inFlightMonths.add(monthKey)
                true
            }
        }
        if (!shouldFetch) return

        try {
            val apiKey = MdbListSettingsRepository.trackingApiKey()
            if (apiKey == null) {
                _uiState.value = TraktCalendarUiState(
                    isAuthenticated = false,
                    hasLoaded = true,
                )
                return
            }

            val firstLoad = mutex.withLock { entriesByDate.isEmpty() }
            if (firstLoad) {
                _uiState.value = _uiState.value.copy(
                    isLoading = true,
                    isAuthenticated = true,
                    errorMessage = null,
                )
            }

            val result = runCatching {
                fetchMonth(apiKey = apiKey, year = year, month = month)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                log.w(error) { "Failed to load MDBList calendar month $monthKey" }
            }.getOrNull()

            if (result == null) {
                mutex.withLock {
                    if (entriesByDate.isEmpty()) {
                        _uiState.value = TraktCalendarUiState(
                            isLoading = false,
                            isAuthenticated = true,
                            errorMessage = "Failed to load calendar",
                            hasLoaded = true,
                        )
                    }
                }
                return
            }

            val grouped = result
                .groupBy(TraktCalendarEntry::dateKey)
                .mapValues { (_, entries) ->
                    entries
                        .distinctBy {
                            "${it.type}:${it.contentId}:${it.seasonNumber}:${it.episodeNumber}"
                        }
                        .sortedBy { it.title.lowercase() }
                }

            mutex.withLock {
                loadedMonths.add(monthKey)
                val monthPrefix = "$monthKey-"
                entriesByDate.keys
                    .filter { it.startsWith(monthPrefix) }
                    .toList()
                    .forEach(entriesByDate::remove)
                entriesByDate.putAll(grouped)
                _uiState.value = TraktCalendarUiState(
                    isLoading = false,
                    isAuthenticated = true,
                    entriesByDate = entriesByDate.toMap(),
                    hasLoaded = true,
                )
            }
        } finally {
            mutex.withLock { inFlightMonths.remove(monthKey) }
        }
    }

    private suspend fun fetchMonth(
        apiKey: String,
        year: Int,
        month: Int,
    ): List<TraktCalendarEntry> {
        val startDate = "${year.pad4()}-${month.pad2()}-01"
        val endDate = "${year.pad4()}-${month.pad2()}-${calendarDaysInMonth(year, month).pad2()}"
        val response = httpRequestRaw(
            method = "GET",
            url = "$MDBLIST_BASE_URL/calendar/events" +
                "?apikey=$apiKey&start=$startDate&end=$endDate&limit=1000",
            headers = mapOf("Accept" to "application/json"),
            body = "",
        )
        if (response.status !in 200..299) {
            error("MDBList /calendar/events returned ${response.status}")
        }
        return parseMdbListCalendar(response.body)
    }
}

internal fun parseMdbListCalendarForTest(body: String): List<TraktCalendarEntry> =
    parseMdbListCalendar(body)

private fun parseMdbListCalendar(body: String): List<TraktCalendarEntry> {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }
    return json.decodeFromString<MdbListCalendarResponse>(body).events.mapNotNull { event ->
        event.toCalendarEntry()
    }
}

private fun MdbListCalendarEvent.toCalendarEntry(): TraktCalendarEntry? {
    val dateKey = start.toCalendarDateKey() ?: return null
    val normalizedType = type?.trim()?.lowercase()
    val isEpisode = normalizedType == "episode" ||
        (seasonNumber != null && episodeNumber != null)
    val isSeries = isEpisode ||
        normalizedType == "show" ||
        normalizedType == "series" ||
        showTmdb != null ||
        !showImdb.isNullOrBlank()
    val contentId = when {
        isSeries -> showImdb.normalizedImdbId()
            ?: showTmdb?.let { "tmdb:$it" }
            ?: tmdb?.let { "tmdb:$it" }
        else -> movieImdb.normalizedImdbId()
            ?: imdb.normalizedImdbId()
            ?: movieTmdb?.let { "tmdb:$it" }
            ?: tmdb?.let { "tmdb:$it" }
    } ?: id.normalizedCalendarContentId()
    if (contentId.isNullOrBlank()) return null

    return TraktCalendarEntry(
        dateKey = dateKey,
        type = if (isSeries) "series" else "movie",
        contentId = contentId,
        title = title?.trim()?.takeIf(String::isNotBlank) ?: contentId,
        posterUrl = listOf(poster, image, backdrop)
            .firstNotNullOfOrNull { it.normalizedCalendarImageUrl() },
        seasonNumber = seasonNumber.takeIf { isEpisode },
        episodeNumber = episodeNumber.takeIf { isEpisode },
        episodeTitle = episodeTitle?.trim()?.takeIf(String::isNotBlank).takeIf { isEpisode },
    )
}

private fun String?.toCalendarDateKey(): String? {
    val value = this?.trim().orEmpty()
    if (value.length < 10) return null
    val candidate = value.substring(0, 10)
    if (candidate[4] != '-' || candidate[7] != '-') return null
    if (candidate.substring(0, 4).toIntOrNull() == null) return null
    if (candidate.substring(5, 7).toIntOrNull() !in 1..12) return null
    if (candidate.substring(8, 10).toIntOrNull() !in 1..31) return null
    return candidate
}

private fun String?.normalizedImdbId(): String? =
    this?.trim()?.takeIf { it.startsWith("tt") && it.drop(2).all(Char::isDigit) }

private fun String?.normalizedCalendarContentId(): String? {
    val value = this?.trim()?.takeIf(String::isNotBlank) ?: return null
    return when {
        value.startsWith("tt") && value.drop(2).all(Char::isDigit) -> value
        value.startsWith("tmdb:") && value.removePrefix("tmdb:").toIntOrNull() != null -> value
        else -> null
    }
}

private fun String?.normalizedCalendarImageUrl(): String? {
    val value = this?.trim()?.takeIf(String::isNotBlank) ?: return null
    return when {
        value.startsWith("https://") || value.startsWith("http://") -> value
        value.startsWith("/") -> "https://image.tmdb.org/t/p/w500$value"
        else -> "https://image.tmdb.org/t/p/w500/$value"
    }
}

private fun calendarMonthKey(year: Int, month: Int): String = "${year.pad4()}-${month.pad2()}"
private fun Int.pad2(): String = toString().padStart(2, '0')
private fun Int.pad4(): String = toString().padStart(4, '0')

@Serializable
private data class MdbListCalendarResponse(
    val events: List<MdbListCalendarEvent> = emptyList(),
)

@Serializable
private data class MdbListCalendarEvent(
    val id: String? = null,
    val type: String? = null,
    @SerialName("release_type") val releaseType: String? = null,
    val start: String? = null,
    val title: String? = null,
    @SerialName("episode_title") val episodeTitle: String? = null,
    val description: String? = null,
    @SerialName("show_tmdb") val showTmdb: Int? = null,
    @SerialName("show_imdb") val showImdb: String? = null,
    @SerialName("movie_tmdb") val movieTmdb: Int? = null,
    @SerialName("movie_imdb") val movieImdb: String? = null,
    val tmdb: Int? = null,
    val imdb: String? = null,
    @SerialName("episode_tmdb") val episodeTmdb: Int? = null,
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    val poster: String? = null,
    val image: String? = null,
    val backdrop: String? = null,
    @SerialName("is_watchlist") val isWatchlist: Boolean? = null,
    @SerialName("is_watched") val isWatched: Boolean? = null,
)
