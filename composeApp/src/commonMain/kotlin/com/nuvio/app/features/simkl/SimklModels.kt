package com.nuvio.app.features.simkl

import com.nuvio.app.features.metadata.AnimeIdMapping
import com.nuvio.app.features.metadata.AnimeIdMappingRepository
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class SimklAuthState(
    val accessToken: String? = null,
    val username: String? = null,
) {
    val isAuthenticated: Boolean get() = !accessToken.isNullOrBlank()
}

enum class SimklConnectionMode {
    DISCONNECTED,
    AWAITING_PIN,
    CONNECTED,
}

data class SimklAuthUiState(
    val mode: SimklConnectionMode = SimklConnectionMode.DISCONNECTED,
    val isLoading: Boolean = false,
    val username: String? = null,
    /** The 5-character PIN the user must enter at simkl.com/pin. */
    val pendingPin: String? = null,
    val errorMessage: String? = null,
)

@Serializable
internal data class SimklPinResponse(
    @SerialName("user_code") val userCode: String,
    @SerialName("device_code") val deviceCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("expires_in") val expiresIn: Int,
    val interval: Int,
)

@Serializable
internal data class SimklPinPollResponse(
    val result: String,
    @SerialName("access_token") val accessToken: String? = null,
    // Present when the old code expires and the server issues a new one.
    @SerialName("device_code") val deviceCode: String? = null,
    val message: String? = null,
)

@Serializable
internal data class SimklUserSettingsResponse(
    val user: SimklUserDto? = null,
)

@Serializable
internal data class SimklUserDto(
    val name: String? = null,
)

// ── Sync activities ───────────────────────────────────────────────────────────

@Serializable
internal data class SimklActivities(
    @SerialName("tv_shows") val tvShows: SimklCategoryActivity? = null,
    val movies: SimklCategoryActivity? = null,
    val anime: SimklCategoryActivity? = null,
)

@Serializable
internal data class SimklCategoryActivity(
    val all: String? = null,
    @SerialName("plantowatch") val planToWatch: String? = null,
    val watching: String? = null,
    val playback: String? = null,
    val completed: String? = null,
)

// ── Library / all-items ───────────────────────────────────────────────────────

@Serializable
internal data class SimklMediaIds(
    val simkl: Int? = null,
    val slug: String? = null,
    val imdb: String? = null,
    val tmdb: String? = null,
    val tvdb: Int? = null,
    val mal: String? = null,
    val anidb: String? = null,
    val kitsu: String? = null,
    @SerialName("al") val anilist: String? = null,
)

/**
 * SIMKL hands this imdb id back for anime it has no real imdb id for, so it appears on entries
 * that have nothing to do with each other. Taking it at face value collapses them onto one title.
 */
private const val PLACEHOLDER_IMDB_ID = "tt2250192"

/** Converts SIMKL ids to the app's preferred content id (imdb > tmdb > simkl). */
internal fun SimklMediaIds.toBestContentId(): String? =
    imdb?.takeIf { it.isNotBlank() && it != PLACEHOLDER_IMDB_ID }
        ?: tmdb?.takeIf { it.isNotBlank() }?.let { "tmdb:$it" }
        ?: tvdb?.let { "tvdb:$it" }
        ?: simkl?.let { "simkl:$it" }

internal fun SimklMediaIds.toBestAnimeContentId(): String? = toFranchiseFirstAnimeContentId("series")

internal fun SimklMediaIds.toBestAnimeMovieContentId(): String? = toFranchiseFirstAnimeContentId("movie")

/**
 * Anime content id, franchise-first — mirroring upstream's default `SimklAnimeIdPreference.IMDB`.
 *
 * A franchise id (imdb, then TMDB, then TVDB) is the only kind that ordinary meta addons, MDBList
 * and TMDB can actually resolve. The fork previously put kitsu/mal first, which is upstream's
 * opt-in mode: it gives each season its own identity, but leaves anime unresolvable on any addon
 * that does not advertise a `kitsu` id prefix — which is most of them, including TMDB-backed ones.
 * Native anime ids are kept as a genuine last resort for entries with no franchise id at all.
 *
 * The anime-list mapping is consulted before SIMKL's own ids because SIMKL playback payloads are
 * routinely sparse (often nothing but a simkl id), and because the mapping's ids are namespace-
 * correct where SIMKL's single `tmdb` field does not say whether it is a movie or a tv id.
 */
private fun SimklMediaIds.toFranchiseFirstAnimeContentId(contentType: String): String? {
    animeListEntry()?.franchiseContentId(contentType)?.let { return it }
    val isMovie = contentType.equals("movie", ignoreCase = true)
    return imdb?.takeIf { it.isNotBlank() && it != PLACEHOLDER_IMDB_ID }
        ?: tmdb?.takeIf { it.isNotBlank() }?.let { "tmdb:$it" }
        ?: tvdb?.takeIf { !isMovie }?.let { "tvdb:$it" }
        ?: kitsu?.takeIf { it.isNotBlank() }?.let { "kitsu:$it" }
        ?: mal?.takeIf { it.isNotBlank() }?.let { "mal:$it" }
        ?: anilist?.takeIf { it.isNotBlank() }?.let { "anilist:$it" }
        ?: anidb?.takeIf { it.isNotBlank() }?.let { "anidb:$it" }
        ?: simkl?.let { "simkl:$it" }
}

/**
 * The franchise id this anime-list entry is addressable by, or null when it has none.
 *
 * TMDB movie and tv ids are separate namespaces, so a movie must never borrow the entry's tv id
 * (or the reverse) merely because one exists. TVDB is series-only for the same reason: an anime
 * film maps onto season 0 of its parent series' TVDB record, not onto a record of its own.
 */
private fun AnimeIdMapping.franchiseContentId(contentType: String): String? {
    val isMovie = contentType.equals("movie", ignoreCase = true)
    return imdbIds.firstOrNull()?.takeIf { it.isNotBlank() }
        ?: (if (isMovie) tmdbMovieIds.firstOrNull() else tmdbTvId)?.let { "tmdb:$it" }
        ?: tvdbId?.takeIf { !isMovie }?.let { "tvdb:$it" }
}

private fun SimklMediaIds.animeListEntry(): AnimeIdMapping? =
    AnimeIdMappingRepository.entryForNativeIds(
        anidb = anidb?.toIntOrNull(),
        anilist = anilist?.toIntOrNull(),
        kitsu = kitsu?.toIntOrNull(),
        mal = mal?.toIntOrNull(),
        simkl = simkl,
    )

/**
 * Converts an episode coordinate scoped to a SIMKL anime entry back into the franchise numbering
 * used by details metadata. SIMKL anime entries report season 1 even when the corresponding entry
 * is TVDB/TMDB season 2+ (and split cours additionally carry an episode offset).
 */
internal fun SimklMediaIds.toCanonicalAnimeEpisode(
    season: Int,
    episode: Int,
): Pair<Int, Int> {
    if (season != 1) return season to episode
    // This is a SIMKL response, so its own entry id is the strongest discriminator. Some payloads
    // also contain a franchise-level Kitsu/MAL id that would otherwise select the wrong sibling.
    val mapping = simkl?.let { simklId ->
        AnimeIdMappingRepository.entryForNativeIds(simkl = simklId)
    } ?: animeListEntry() ?: return season to episode
    val mappedSeason = mapping.tvdbSeason ?: mapping.tmdbSeason ?: return season to episode
    val offset = when {
        mapping.tvdbSeason != null -> mapping.tvdbEpisodeOffset
        else -> mapping.tmdbEpisodeOffset
    } ?: 0
    return mappedSeason to (episode + offset)
}

/**
 * True when these ids belong to a known anime entry. SIMKL delivers anime movies under the
 * plain `movie` node in some payloads (no `anime` node), where the non-anime id preference
 * (imdb first) trusts SIMKL's imdb — which is unreliable for anime and can point at a
 * completely unrelated title. Anime-list membership proves anime regardless of the node.
 */
internal fun SimklMediaIds.isKnownAnime(): Boolean = animeListEntry() != null

@Serializable
internal data class SimklShowMedia(
    val title: String? = null,
    val year: Int? = null,
    val poster: String? = null,
    val ids: SimklMediaIds = SimklMediaIds(),
)

@Serializable
internal data class SimklMovieMedia(
    val title: String? = null,
    val year: Int? = null,
    val poster: String? = null,
    val ids: SimklMediaIds = SimklMediaIds(),
)

@Serializable
internal data class SimklAllItemsEntry(
    @SerialName("added_to_watchlist_at") val addedToWatchlistAt: String? = null,
    @SerialName("last_watched_at") val lastWatchedAt: String? = null,
    @SerialName("last_watched") val lastWatched: String? = null, // e.g. "S05E16"
    val status: String? = null,
    val show: SimklShowMedia? = null,
    val movie: SimklMovieMedia? = null,
    val anime: SimklShowMedia? = null,
    val seasons: List<SimklWatchedSeason> = emptyList(),
)

@Serializable
internal data class SimklWatchedSeason(
    val number: Int? = null,
    val episodes: List<SimklWatchedEpisode> = emptyList(),
)

@Serializable
internal data class SimklWatchedEpisode(
    val number: Int? = null,
    @SerialName("watched_at") val watchedAt: String? = null,
)

@Serializable
internal data class SimklAllItemsResponse(
    val shows: List<SimklAllItemsEntry> = emptyList(),
    val movies: List<SimklAllItemsEntry> = emptyList(),
    val anime: List<SimklAllItemsEntry> = emptyList(),
)

internal fun String.simklPosterUrl(): String = "https://simkl.net/posters/${this}_m.jpg"

/** Poster from SIMKL's numeric ID — used when only the id is available (e.g. playback endpoint). */
internal fun simklCdnPosterUrl(simklId: Int): String = "https://simkl.net/posters/${simklId}_m.jpg"

internal fun parseSimklTimestamp(iso: String): Long? = runCatching {
    val s = iso.trimEnd('Z').replace("T", " ")
    val parts = s.split(" ", "-", ":")
    if (parts.size < 6) return@runCatching null
    val year = parts[0].toInt(); val month = parts[1].toInt(); val day = parts[2].toInt()
    val hour = parts[3].toInt(); val min = parts[4].toInt(); val sec = parts[5].toIntOrNull() ?: 0
    val daysFromEpoch = simklEpochDays(year, month, day)
    (daysFromEpoch * 86400L + hour * 3600L + min * 60L + sec) * 1000L
}.getOrNull()

private fun simklEpochDays(year: Int, month: Int, day: Int): Long {
    val y = year.toLong(); val m = month.toLong(); val d = day.toLong()
    val a = (14 - m) / 12
    val ya = y + 4800 - a
    val ma = m + 12 * a - 3
    val jdn = d + (153 * ma + 2) / 5 + 365 * ya + ya / 4 - ya / 100 + ya / 400 - 32045
    return jdn - 2440588L
}

// ── Calendar ──────────────────────────────────────────────────────────────────

@Serializable
internal data class SimklCalendarItem(
    val title: String? = null,
    val poster: String? = null,
    /** Air/release timestamp with timezone offset, e.g. "2026-05-16T00:00:00-05:00". */
    val date: String? = null,
    val episode: SimklCalendarEpisode? = null,
    val ids: SimklCalendarIds? = null,
)

@Serializable
internal data class SimklCalendarEpisode(
    val season: Int? = null,
    val episode: Int? = null,
    val title: String? = null,
)

@Serializable
internal data class SimklCalendarIds(
    @SerialName("simkl_id") val simklId: Int? = null,
    val slug: String? = null,
    val imdb: String? = null,
    val tmdb: String? = null,
) {
    fun toBestContentId(): String? =
        imdb?.takeIf { it.isNotBlank() }
            ?: tmdb?.takeIf { it.isNotBlank() }?.let { "tmdb:$it" }
            ?: simklId?.let { "simkl:$it" }
}

// ── Movie release dates ───────────────────────────────────────────────────────

// Release type codes (TMDB convention used by SIMKL):
// 1=Premiere  2=Limited theatrical  3=Theatrical  4=Digital  5=Physical/Home  6=TV
internal const val SIMKL_RELEASE_TYPE_DIGITAL = 4
internal const val SIMKL_RELEASE_TYPE_THEATRICAL = 3

@Serializable
internal data class SimklMovieDetails(
    val title: String? = null,
    @SerialName("release_dates") val releaseDates: List<SimklMovieReleaseCountry>? = null,
)

@Serializable
internal data class SimklMovieReleaseCountry(
    @SerialName("iso_3166_1") val country: String? = null,
    val results: List<SimklMovieReleaseResult> = emptyList(),
)

@Serializable
internal data class SimklMovieReleaseResult(
    val type: Int? = null,
    @SerialName("release_date") val releaseDate: String? = null,
)

/**
 * Picks the best calendar date for a movie. Priority:
 * 1. US digital (type 4) — most common reference date for English-language apps
 * 2. GB digital (type 4)
 * 3. Any digital (type 4) — earliest
 * 4. US theatrical (type 3) — fallback if no digital date available
 * 5. Any theatrical (type 3) — earliest
 */
internal fun List<SimklMovieReleaseCountry>.bestCalendarDate(): String? {
    fun releasesOfType(type: Int) = flatMap { country ->
        country.results
            .filter { it.type == type }
            .mapNotNull { r -> r.releaseDate?.takeIf { it.isNotBlank() }?.let { country.country to it } }
    }

    val digital = releasesOfType(SIMKL_RELEASE_TYPE_DIGITAL)
    val theatrical = releasesOfType(SIMKL_RELEASE_TYPE_THEATRICAL)

    return digital.firstOrNull { it.first == "US" }?.second
        ?: digital.firstOrNull { it.first == "GB" }?.second
        ?: digital.minByOrNull { it.second }?.second
        ?: theatrical.firstOrNull { it.first == "US" }?.second
        ?: theatrical.minByOrNull { it.second }?.second
}

// ── Playback / continue watching ──────────────────────────────────────────────

@Serializable
internal data class SimklEpisodeRef(
    val season: Int? = null,
    val number: Int? = null,
    val title: String? = null,
)

/** Parses SIMKL's "S05E16" episode marker into (season, episode). */
internal fun parseSimklEpisodeMarker(marker: String): Pair<Int, Int>? {
    val m = Regex("""S(\d+)E(\d+)""", RegexOption.IGNORE_CASE).find(marker) ?: return null
    val s = m.groupValues[1].toIntOrNull() ?: return null
    val e = m.groupValues[2].toIntOrNull() ?: return null
    return s to e
}

@Serializable
internal data class SimklPlaybackSession(
    val id: Int? = null,
    val progress: Float? = null,
    @SerialName("watched_at") val watchedAt: String? = null,
    val type: String? = null,
    val movie: SimklMovieMedia? = null,
    val show: SimklShowMedia? = null,
    val anime: SimklShowMedia? = null,
    val episode: SimklEpisodeRef? = null,
)
