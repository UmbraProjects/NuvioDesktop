package com.nuvio.app.features.simkl

import com.nuvio.app.features.metadata.AnimeIdMapping
import com.nuvio.app.features.metadata.AnimeIdMappingRepository
import com.nuvio.app.features.metadata.AnimeIdPreference
import com.nuvio.app.features.metadata.AnimeIdPreferenceRepository
import com.nuvio.app.features.metadata.franchiseContentId
import com.nuvio.app.features.metadata.hasNativeAnimePrefix
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class SimklAuthState(
    val accessToken: String? = null,
    val username: String? = null,
    val accountType: String? = null,
    val settingsActivitiesAt: String? = null,
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
    val accountType: String? = null,
    /** The 5-character PIN the user must enter at simkl.com/pin. */
    val pendingPin: String? = null,
    val errorMessage: String? = null,
)

val SimklAuthUiState.canUseRewatches: Boolean
    get() = accountType.equals("pro", ignoreCase = true) || accountType.equals("vip", ignoreCase = true)

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
    val account: SimklAccountDto? = null,
)

@Serializable
internal data class SimklUserDto(
    val name: String? = null,
)

@Serializable
internal data class SimklAccountDto(
    val type: String? = null,
)

// ── Sync activities ───────────────────────────────────────────────────────────

@Serializable
internal data class SimklActivities(
    val all: String? = null,
    @SerialName("tv_shows") val tvShows: SimklCategoryActivity? = null,
    val movies: SimklCategoryActivity? = null,
    val anime: SimklCategoryActivity? = null,
    val settings: SimklCategoryActivity? = null,
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
internal const val PLACEHOLDER_IMDB_ID = "tt2250192"

/** Converts SIMKL ids to the app's preferred content id (imdb > tmdb > simkl). */
internal fun SimklMediaIds.toBestContentId(): String? =
    imdb?.takeIf { it.isNotBlank() && it != PLACEHOLDER_IMDB_ID }
        ?: tmdb?.takeIf { it.isNotBlank() }?.let { "tmdb:$it" }
        ?: tvdb?.let { "tvdb:$it" }
        ?: simkl?.let { "simkl:$it" }

// The preference is a parameter with a live default rather than a global read, so tests can state
// the policy they mean instead of mutating a process-wide singleton that persists to the user's
// own profile.
internal fun SimklMediaIds.toBestAnimeContentId(
    preference: AnimeIdPreference = AnimeIdPreferenceRepository.current(),
): String? = toFranchiseFirstAnimeContentId("series", preference)

internal fun SimklMediaIds.toBestAnimeMovieContentId(
    preference: AnimeIdPreference = AnimeIdPreferenceRepository.current(),
): String? = toFranchiseFirstAnimeContentId("movie", preference)

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
private fun SimklMediaIds.toFranchiseFirstAnimeContentId(
    contentType: String,
    preference: AnimeIdPreference,
): String? {
    // A MAL/KITSU preference asks for each entry to keep its own identity, so the native id wins
    // outright and the franchise chain below is never reached. IMDB (the default) falls through.
    preferredNativeAnimeContentId(preference)?.let { return it }
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

// franchiseContentId lives in the metadata package so this chain and the migration of ids persisted
// under the old kitsu-first policy cannot drift apart.

/**
 * The per-entry id the user asked anime to be addressed by, or null under [AnimeIdPreference.IMDB].
 * Preference order matches upstream exactly (MAL: mal→kitsu→anidb, KITSU: kitsu→mal→anidb).
 *
 * Unlike upstream this also consults the anime-list, because SIMKL playback payloads are routinely
 * sparse: a session carrying nothing but a simkl id would otherwise fall through to the franchise
 * chain and silently ignore the preference for exactly the rows Continue Watching is made of.
 */
private fun SimklMediaIds.preferredNativeAnimeContentId(
    preference: AnimeIdPreference,
): String? {
    if (preference == AnimeIdPreference.IMDB) return null
    val entry = animeListEntry()
    val kitsuId = kitsu?.takeIf { it.isNotBlank() } ?: entry?.kitsuId?.toString()
    val malId = mal?.takeIf { it.isNotBlank() } ?: entry?.malId?.toString()
    val anidbId = anidb?.takeIf { it.isNotBlank() } ?: entry?.anidbId?.toString()
    return when (preference) {
        AnimeIdPreference.MAL ->
            malId?.let { "mal:$it" } ?: kitsuId?.let { "kitsu:$it" } ?: anidbId?.let { "anidb:$it" }
        AnimeIdPreference.KITSU ->
            kitsuId?.let { "kitsu:$it" } ?: malId?.let { "mal:$it" } ?: anidbId?.let { "anidb:$it" }
        AnimeIdPreference.IMDB -> null
    }
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
 * The season/episode to store next to [contentId] for one SIMKL episode.
 *
 * Coordinates belong to whichever space the content id names, and SIMKL supplies both: its own
 * per-entry numbering ([entrySeason]/[entryEpisode], where an entry's season is always 1) and the
 * franchise (TVDB) coordinates it resolves for that same episode ([franchiseSeason]/
 * [franchiseEpisode], absent from payloads that do not state them).
 *
 * A per-entry id — `kitsu:`/`mal:`/… under the MAL/KITSU [AnimeIdPreference], or `simkl:` for an
 * entry with no franchise id at all — addresses a single anime-list entry, which has no season 2
 * and no cour offset. Franchise coordinates against one of those describe an episode that cannot
 * exist: Mushoku Tensei III is `kitsu:49002`, whose only season is 1, so `kitsu:49002:3:1` matches
 * nothing its own details page shows. SIMKL's entry-local numbers are already correct there, and
 * converting them anyway is exactly the "convert back" the preference exists to avoid. It only
 * bites on entries that map somewhere other than franchise season 1 — a one-season anime agrees
 * in both spaces, which is why this stayed invisible until a split/continuing series hit it.
 *
 * A franchise id is the mirror image: entry-local numbers against it claim the franchise's first
 * season, so the franchise coordinates are used, falling back to [toCanonicalAnimeEpisode] for
 * payloads that omit them.
 */
internal fun SimklMediaIds.episodeCoordinatesFor(
    contentId: String,
    isAnime: Boolean,
    entrySeason: Int,
    entryEpisode: Int,
    franchiseSeason: Int? = null,
    franchiseEpisode: Int? = null,
): Pair<Int, Int> = when {
    !isAnime -> entrySeason to entryEpisode
    contentId.hasNativeAnimePrefix() -> entrySeason to entryEpisode
    franchiseSeason != null && franchiseEpisode != null -> franchiseSeason to franchiseEpisode
    else -> toCanonicalAnimeEpisode(entrySeason, entryEpisode)
}

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
    //
    // Only when that entry actually carries a season, though. The anime-list holds stub rows that
    // record a simkl id and nothing else (Mushoku Tensei's third season is `{"type":"TV",
    // "animecountdown_id":2832226,"simkl_id":2832226}`), and preferring one shadows the complete
    // kitsu-keyed entry that does have the season — leaving the episode at an uncorrected S1E1,
    // which against a franchise id means the first season's first episode.
    val mapping = simkl
        ?.let { simklId -> AnimeIdMappingRepository.entryForNativeIds(simkl = simklId) }
        ?.takeIf { it.tvdbSeason != null || it.tmdbSeason != null }
        ?: animeListEntry() ?: return season to episode
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
    @SerialName("is_rewatch") val isRewatch: Boolean = false,
    @SerialName("rewatch_id") val rewatchId: Int? = null,
    @SerialName("rewatch_status") val rewatchStatus: String? = null,
    @SerialName("watched_episodes_count") val watchedEpisodesCount: Int? = null,
    @SerialName("total_episodes_count") val totalEpisodesCount: Int? = null,
    @SerialName("not_aired_episodes_count") val notAiredEpisodesCount: Int? = null,
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
    /** See [SimklEpisodeTvdbMapping]. */
    val tvdb: SimklEpisodeTvdbMapping? = null,
)

/**
 * The franchise (TVDB) coordinates SIMKL states for an episode alongside its own numbering.
 *
 * Anime entries are numbered per entry — Mushoku Tensei's third season is its own SIMKL entry whose
 * episode 1 is season 1 episode 1 — so the entry-local numbers are only meaningful next to the
 * entry's id. SIMKL resolves that itself and reports the TVDB season/episode here, which is what
 * makes the numbers usable against a franchise id. Taking them is strictly better than deriving
 * them from the offline anime-list: it needs no mapping entry, so it still works for entries the
 * anime-list records thinly or not at all.
 */
@Serializable
internal data class SimklEpisodeTvdbMapping(
    val season: Int? = null,
    val episode: Int? = null,
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
    // The playback endpoint spells the same franchise coordinates as flat fields rather than a
    // nested object — see [SimklEpisodeTvdbMapping] for why they are preferred.
    @SerialName("tvdb_season") val tvdbSeason: Int? = null,
    @SerialName("tvdb_number") val tvdbNumber: Int? = null,
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
