package com.nuvio.app.features.trakt

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

private const val BASE_URL = "https://api.trakt.tv"

/**
 * A single dated entry in the Trakt calendar (an episode airing or a movie release).
 * Public so [com.nuvio.app.features.calendar.CalendarScreen] can render it.
 */
data class TraktCalendarEntry(
    /** ISO date bucket, "YYYY-MM-DD" (UTC). */
    val dateKey: String,
    /** "series" or "movie", for navigation to the detail screen. */
    val type: String,
    /** Canonical content id (imdb / "tmdb:" / "trakt:"), for navigation. */
    val contentId: String,
    val title: String,
    val posterUrl: String?,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
)

data class TraktCalendarUiState(
    val isLoading: Boolean = false,
    val isAuthenticated: Boolean = false,
    val errorMessage: String? = null,
    /** Entries grouped by [TraktCalendarEntry.dateKey]. */
    val entriesByDate: Map<String, List<TraktCalendarEntry>> = emptyMap(),
    val hasLoaded: Boolean = false,
)

object TraktCalendarRepository {
    private val log = Logger.withTag("TraktCalendar")
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow(TraktCalendarUiState())
    val uiState: StateFlow<TraktCalendarUiState> = _uiState.asStateFlow()

    // Months are loaded on demand and accumulated, so the user can page arbitrarily far
    // forward/back rather than being limited to a fixed window.
    private val mutex = Mutex()
    private val entriesByDate = mutableMapOf<String, List<TraktCalendarEntry>>()
    private val loadedMonths = mutableSetOf<String>()
    private val inFlightMonths = mutableSetOf<String>()

    /** Loads the current month plus its neighbours so the initial view and paging feel instant. */
    fun ensureLoaded() {
        val today = epochMsToUtcDate(TraktPlatformClock.nowEpochMs())
        ensureMonthsAround(today.year, today.month)
    }

    /** Ensures [year]/[month] and its immediate neighbours are loaded. */
    fun ensureMonthsAround(year: Int, month: Int) {
        val prev = addMonth(year, month, -1)
        val next = addMonth(year, month, 1)
        ensureMonth(year, month)
        ensureMonth(prev.first, prev.second)
        ensureMonth(next.first, next.second)
    }

    fun ensureMonth(year: Int, month: Int) {
        scope.launch { loadMonth(year, month) }
    }

    fun refreshAsync() {
        scope.launch {
            mutex.withLock {
                entriesByDate.clear()
                loadedMonths.clear()
            }
            ensureLoaded()
        }
    }

    fun onProfileChanged() {
        scope.launch {
            mutex.withLock {
                entriesByDate.clear()
                loadedMonths.clear()
                inFlightMonths.clear()
            }
            _uiState.value = TraktCalendarUiState()
        }
    }

    fun clearLocalState() {
        onProfileChanged()
    }

    private suspend fun loadMonth(year: Int, month: Int) {
        val monthKey = monthKey(year, month)
        val shouldFetch = mutex.withLock {
            if (loadedMonths.contains(monthKey) || inFlightMonths.contains(monthKey)) {
                false
            } else {
                inFlightMonths.add(monthKey)
                true
            }
        }
        if (!shouldFetch) return

        try {
            TraktAuthRepository.ensureLoaded()
            val headers = TraktAuthRepository.authorizedHeaders()
            if (headers == null) {
                _uiState.value = _uiState.value.copy(
                    isAuthenticated = false,
                    isLoading = false,
                    hasLoaded = true,
                )
                return
            }

            val firstLoad = mutex.withLock { entriesByDate.isEmpty() }
            if (firstLoad) {
                _uiState.value = _uiState.value.copy(isLoading = true, isAuthenticated = true, errorMessage = null)
            }

            val window = monthWindow(year, month)
            val result = runCatching {
                coroutineScope {
                    val shows = async { fetchShowEntries(headers, window) }
                    val movies = async { fetchMovieEntries(headers, window) }
                    awaitAll(shows, movies).flatten()
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                log.w { "Failed to load Trakt calendar month $monthKey: ${error.message}" }
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
                .groupBy { it.dateKey }
                .mapValues { (_, entries) ->
                    entries
                        .distinctBy { "${it.type}:${it.contentId}:${it.seasonNumber}:${it.episodeNumber}" }
                        .sortedBy { it.title.lowercase() }
                }

            mutex.withLock {
                loadedMonths.add(monthKey)
                // Replace this month's days so a reload never leaves stale entries.
                val monthPrefix = "$monthKey-"
                entriesByDate.keys
                    .filter { it.startsWith(monthPrefix) }
                    .toList()
                    .forEach { entriesByDate.remove(it) }
                entriesByDate.putAll(grouped)
                _uiState.value = TraktCalendarUiState(
                    isLoading = false,
                    isAuthenticated = true,
                    errorMessage = null,
                    entriesByDate = entriesByDate.toMap(),
                    hasLoaded = true,
                )
            }
        } finally {
            mutex.withLock { inFlightMonths.remove(monthKey) }
        }
    }

    private fun monthKey(year: Int, month: Int): String = "${year.pad4()}-${month.pad2()}"

    private suspend fun fetchShowEntries(
        headers: Map<String, String>,
        window: CalendarWindow,
    ): List<TraktCalendarEntry> {
        val url = "$BASE_URL/calendars/my/shows/${window.startDate}/${window.days}?extended=full,images"
        val response = httpRequestRaw(method = "GET", url = url, headers = headers, body = "")
        if (response.status !in 200..299) return emptyList()
        val items = runCatching {
            json.decodeFromString<List<TraktCalendarShowItem>>(response.body)
        }.getOrDefault(emptyList())

        return items.mapNotNull { item ->
            val show = item.show ?: return@mapNotNull null
            val dateKey = item.firstAired?.toDateKey() ?: return@mapNotNull null
            val contentId = normalizeTraktContentId(show.ids?.toExternalIds(), fallback = show.title)
                .takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TraktCalendarEntry(
                dateKey = dateKey,
                type = "series",
                contentId = contentId,
                title = show.title ?: contentId,
                posterUrl = show.images.traktBestPosterUrl(),
                seasonNumber = item.episode?.season,
                episodeNumber = item.episode?.number,
                episodeTitle = item.episode?.title,
            )
        }
    }

    private suspend fun fetchMovieEntries(
        headers: Map<String, String>,
        window: CalendarWindow,
    ): List<TraktCalendarEntry> {
        val url = "$BASE_URL/calendars/my/movies/${window.startDate}/${window.days}?extended=full,images"
        val response = httpRequestRaw(method = "GET", url = url, headers = headers, body = "")
        if (response.status !in 200..299) return emptyList()
        val items = runCatching {
            json.decodeFromString<List<TraktCalendarMovieItem>>(response.body)
        }.getOrDefault(emptyList())

        return items.mapNotNull { item ->
            val movie = item.movie ?: return@mapNotNull null
            val dateKey = item.released?.toDateKey() ?: return@mapNotNull null
            val contentId = normalizeTraktContentId(movie.ids?.toExternalIds(), fallback = movie.title)
                .takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TraktCalendarEntry(
                dateKey = dateKey,
                type = "movie",
                contentId = contentId,
                title = movie.title ?: contentId,
                posterUrl = movie.images.traktBestPosterUrl(),
            )
        }
    }

    private fun TraktCalendarIdsDto.toExternalIds(): TraktExternalIds =
        TraktExternalIds(trakt = trakt, imdb = imdb, tmdb = tmdb, tvdb = tvdb)

    /**
     * Trakt timestamps for episodes are full ISO datetimes ("2026-06-21T01:00:00.000Z");
     * movie `released` is already a plain date ("2026-06-21"). In both cases the leading
     * 10 chars are the UTC date we bucket on. Bucketing on the raw UTC date (rather than
     * converting to the device's local date) keeps grouping consistent with the UTC-based
     * month window and today highlight without pulling in a timezone library.
     */
    private fun String.toDateKey(): String? {
        val trimmed = trim()
        if (trimmed.length < 10) return null
        val candidate = trimmed.substring(0, 10)
        // Validate "YYYY-MM-DD" shape.
        if (candidate[4] != '-' || candidate[7] != '-') return null
        return candidate
    }
}

internal data class CalendarWindow(val startDate: String, val days: Int)

/** A single calendar month window (1st → last day), in UTC to match how entries are bucketed. */
private fun monthWindow(year: Int, month: Int): CalendarWindow =
    CalendarWindow(startDate = "${year.pad4()}-${month.pad2()}-01", days = calendarDaysInMonth(year, month))

/** month is 1-12; returns (year, month). */
internal fun addMonth(year: Int, month: Int, delta: Int): Pair<Int, Int> {
    val zeroBased = (month - 1) + delta
    val newYear = year + floorDiv(zeroBased, 12)
    val newMonth = floorMod(zeroBased, 12) + 1
    return newYear to newMonth
}

// Multiplatform-safe floor division/modulo (java.lang.Math is JVM-only and would not
// resolve on the iOS target where commonMain is also compiled).
private fun floorDiv(a: Int, b: Int): Int {
    var q = a / b
    if ((a xor b) < 0 && q * b != a) q -= 1
    return q
}

private fun floorMod(a: Int, b: Int): Int = a - floorDiv(a, b) * b

internal fun calendarIsLeapYear(year: Int): Boolean =
    (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)

/** month is 1-12. */
internal fun calendarDaysInMonth(year: Int, month: Int): Int = when (month) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    2 -> if (calendarIsLeapYear(year)) 29 else 28
    else -> 30
}

internal data class UtcDate(val year: Int, val month: Int, val day: Int)

internal fun epochMsToUtcDate(epochMs: Long): UtcDate {
    // epochMs for any supported date is >= 0, so integer division floors correctly.
    var days = (epochMs.coerceAtLeast(0L) / 86_400_000L).toInt()
    var year = 1970
    while (true) {
        val daysInYear = if (calendarIsLeapYear(year)) 366 else 365
        if (days < daysInYear) break
        days -= daysInYear
        year += 1
    }
    var month = 1
    while (true) {
        val dim = calendarDaysInMonth(year, month)
        if (days < dim) break
        days -= dim
        month += 1
    }
    return UtcDate(year = year, month = month, day = days + 1)
}

/** Sakamoto's algorithm; 0 = Sunday … 6 = Saturday. month is 1-12. */
internal fun dayOfWeekSundayZero(year: Int, month: Int, day: Int): Int {
    val t = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)
    val y = if (month < 3) year - 1 else year
    return (y + y / 4 - y / 100 + y / 400 + t[month - 1] + day) % 7
}

private fun Int.pad2(): String = if (this < 10) "0$this" else "$this"
private fun Int.pad4(): String = toString().padStart(4, '0')

@Serializable
private data class TraktCalendarShowItem(
    @SerialName("first_aired") val firstAired: String? = null,
    @SerialName("episode") val episode: TraktCalendarEpisodeDto? = null,
    @SerialName("show") val show: TraktCalendarMediaDto? = null,
)

@Serializable
private data class TraktCalendarMovieItem(
    @SerialName("released") val released: String? = null,
    @SerialName("movie") val movie: TraktCalendarMediaDto? = null,
)

@Serializable
private data class TraktCalendarEpisodeDto(
    @SerialName("season") val season: Int? = null,
    @SerialName("number") val number: Int? = null,
    @SerialName("title") val title: String? = null,
)

@Serializable
private data class TraktCalendarMediaDto(
    @SerialName("title") val title: String? = null,
    @SerialName("year") val year: Int? = null,
    @SerialName("ids") val ids: TraktCalendarIdsDto? = null,
    @SerialName("images") val images: TraktImagesDto? = null,
)

@Serializable
private data class TraktCalendarIdsDto(
    @SerialName("trakt") val trakt: Int? = null,
    @SerialName("imdb") val imdb: String? = null,
    @SerialName("tmdb") val tmdb: Int? = null,
    @SerialName("tvdb") val tvdb: Int? = null,
)
