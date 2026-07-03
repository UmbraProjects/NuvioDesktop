package com.nuvio.app.features.metadata

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val ANIME_LIST_RESOURCE = "anime-list-mini.json"

private val animeMappingJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private val animeMappingLog = Logger.withTag("AnimeIdMapping")

internal expect object AnimeIdMappingStorage {
    fun loadAnimeListText(): String?
}

internal data class AnimeIdMapping(
    val type: String? = null,
    val animePlanetId: String? = null,
    val anidbId: Int? = null,
    val anilistId: Int? = null,
    val kitsuId: Int? = null,
    val malId: Int? = null,
    val simklId: Int? = null,
    val imdbIds: List<String> = emptyList(),
    val tmdbMovieIds: List<Int> = emptyList(),
    val tmdbTvId: Int? = null,
    val tvdbId: Int? = null,
    val tvdbSeason: Int? = null,
    val tmdbSeason: Int? = null,
    val tvdbEpisodeOffset: Int? = null,
    val tmdbEpisodeOffset: Int? = null,
)

internal object AnimeIdMappingRepository {
    private data class Index(
        val byAnidb: Map<Int, AnimeIdMapping>,
        val byAnilist: Map<Int, AnimeIdMapping>,
        val byKitsu: Map<Int, AnimeIdMapping>,
        val byMal: Map<Int, AnimeIdMapping>,
        val bySimkl: Map<Int, AnimeIdMapping>,
        val byImdb: Map<String, List<AnimeIdMapping>>,
        val byTmdbTv: Map<Int, List<AnimeIdMapping>>,
        val byTmdbMovie: Map<Int, List<AnimeIdMapping>>,
        val byTvdb: Map<Int, List<AnimeIdMapping>>,
    )

    @Volatile
    private var cachedIndex: Index? = null
    private val indexLock = Any()

    fun lookup(ids: ResolvedMediaIds): AnimeIdMapping? {
        val index = loadIndex()
        ids.anidb?.let { index.byAnidb[it]?.let { mapping -> return mapping } }
        ids.anilist?.let { index.byAnilist[it]?.let { mapping -> return mapping } }
        ids.kitsu?.let { index.byKitsu[it]?.let { mapping -> return mapping } }
        ids.mal?.let { index.byMal[it]?.let { mapping -> return mapping } }
        ids.simkl?.let { index.bySimkl[it]?.let { mapping -> return mapping } }
        ids.imdb?.lowercase()?.let { index.byImdb[it]?.selectBest(ids)?.let { mapping -> return mapping } }
        // TMDB movie and TV ids are separate namespaces (movie 26209 ≠ tv 26209), so only
        // consult the map(s) matching the content type — a bare "movie" id must never match
        // an anime's tv mapping just because the numbers collide.
        ids.tmdb?.let { tmdb ->
            tmdbCandidateMaps(ids.contentType, index).forEach { map ->
                map[tmdb]?.selectBest(ids)?.let { mapping -> return mapping }
            }
        }
        ids.tvdb?.let { index.byTvdb[it]?.selectBest(ids)?.let { mapping -> return mapping } }
        return null
    }

    private fun tmdbCandidateMaps(contentType: String, index: Index): List<Map<Int, List<AnimeIdMapping>>> =
        when (contentType.trim().lowercase()) {
            "movie", "movies", "film" -> listOf(index.byTmdbMovie)
            "series", "tv", "show", "tvshow" -> listOf(index.byTmdbTv)
            else -> listOf(index.byTmdbTv, index.byTmdbMovie)
        }

    /**
     * The sibling entry of [base] that covers franchise [season]/[episode]. Anime franchises
     * are one TVDB/TMDB show but many per-season entries on kitsu/mal/etc (SAO season 3 is
     * its own kitsu id), and split-cour seasons additionally carve one franchise season into
     * several entries via episode_offset (an entry covers episodes offset+1 and up). Returns
     * null when no sibling with native anime ids matches — callers keep the base entry.
     */
    fun franchiseEntryFor(base: AnimeIdMapping, season: Int, episode: Int): AnimeIdMapping? {
        val index = loadIndex()
        val siblings = base.tvdbId?.let { index.byTvdb[it] }
            ?: base.tmdbTvId?.let { index.byTmdbTv[it] }
            ?: return null
        return siblings
            .filter { entry ->
                (entry.tvdbSeason == season || entry.tmdbSeason == season) &&
                    entry.hasNativeAnimeId() &&
                    entry.franchiseEpisodeOffset() < episode
            }
            .maxByOrNull { it.franchiseEpisodeOffset() }
    }

    private fun AnimeIdMapping.hasNativeAnimeId(): Boolean =
        kitsuId != null || malId != null || anilistId != null || anidbId != null

    /**
     * Direct entry lookup by native ids. Used to translate sparse payloads (e.g. SIMKL
     * playback sessions that only carry a simkl id) into ids the rest of the pipeline —
     * meta addons, stream scrapers — actually understands.
     */
    fun entryForNativeIds(
        anidb: Int? = null,
        anilist: Int? = null,
        kitsu: Int? = null,
        mal: Int? = null,
        simkl: Int? = null,
    ): AnimeIdMapping? {
        val index = loadIndex()
        anidb?.let { index.byAnidb[it]?.let { mapping -> return mapping } }
        anilist?.let { index.byAnilist[it]?.let { mapping -> return mapping } }
        kitsu?.let { index.byKitsu[it]?.let { mapping -> return mapping } }
        mal?.let { index.byMal[it]?.let { mapping -> return mapping } }
        simkl?.let { index.bySimkl[it]?.let { mapping -> return mapping } }
        return null
    }

    private fun AnimeIdMapping.franchiseEpisodeOffset(): Int =
        tvdbEpisodeOffset ?: tmdbEpisodeOffset ?: 0

    private fun List<AnimeIdMapping>.selectBest(ids: ResolvedMediaIds): AnimeIdMapping? {
        if (isEmpty()) return null
        if (size == 1) return first()
        val titleSlug = ids.sourceTitle?.toSlug()
        if ((ids.sourceSeasonNumber ?: 0) > 1) {
            ids.sourceSeasonNumber?.let { sourceSeason ->
                firstOrNull { mapping ->
                    mapping.tmdbSeason == sourceSeason || mapping.tvdbSeason == sourceSeason
                }?.let { return it }
            }
        }
        if (!titleSlug.isNullOrBlank()) {
            firstOrNull { mapping -> mapping.animePlanetId?.equals(titleSlug, ignoreCase = true) == true }?.let { return it }
        }
        ids.sourceSeasonNumber?.let { sourceSeason ->
            firstOrNull { mapping ->
                mapping.tmdbSeason == sourceSeason || mapping.tvdbSeason == sourceSeason
            }?.let { return it }
        }
        if (!titleSlug.isNullOrBlank()) {
            firstOrNull { mapping ->
                val slug = mapping.animePlanetId.orEmpty()
                slug.isNotBlank() && (slug.contains(titleSlug, ignoreCase = true) || titleSlug.contains(slug, ignoreCase = true))
            }?.let { return it }
        }
        return first()
    }

    // anime-list-mini.json is ~6 MB / 42k entries; parsing it takes long enough to jank the
    // UI thread when the first lookup() happens inside composition (stream load, player
    // launch). Warming from App startup moves that cost to a background thread before any
    // user interaction needs it. Safe to call repeatedly; the double-checked loadIndex()
    // makes concurrent first-touch callers wait on the same parse instead of repeating it.
    fun warmAsync() {
        if (cachedIndex != null) return
        warmScope.launch { runCatching { loadIndex() } }
    }

    private val warmScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Double-checked locking: cachedIndex is read from multiple coroutines (MediaIdResolver,
    // scrobble builds, hero prefetch) without a suspend context available here, so a plain
    // unsynchronized var risked two callers racing to parse anime-list-mini.json concurrently.
    private fun loadIndex(): Index {
        cachedIndex?.let { return it }
        return synchronized(indexLock) {
            cachedIndex?.let { return@synchronized it }
            buildIndex().also { cachedIndex = it }
        }
    }

    private fun buildIndex(): Index {
        val text = AnimeIdMappingStorage.loadAnimeListText()
        if (text.isNullOrBlank()) {
            animeMappingLog.w { "$ANIME_LIST_RESOURCE was not found; anime ID conversion will be limited." }
            return emptyIndex()
        }

        val mappings = runCatching {
            animeMappingJson.decodeFromString<List<AnimeListMiniEntry>>(text)
                .map(AnimeListMiniEntry::toDomain)
        }.onFailure { error ->
            animeMappingLog.w(error) { "Failed to parse $ANIME_LIST_RESOURCE" }
        }.getOrElse { emptyList() }

        val byAnidb = linkedMapOf<Int, AnimeIdMapping>()
        val byAnilist = linkedMapOf<Int, AnimeIdMapping>()
        val byKitsu = linkedMapOf<Int, AnimeIdMapping>()
        val byMal = linkedMapOf<Int, AnimeIdMapping>()
        val bySimkl = linkedMapOf<Int, AnimeIdMapping>()
        val byImdb = linkedMapOf<String, MutableList<AnimeIdMapping>>()
        val byTmdbTv = linkedMapOf<Int, MutableList<AnimeIdMapping>>()
        val byTmdbMovie = linkedMapOf<Int, MutableList<AnimeIdMapping>>()
        val byTvdb = linkedMapOf<Int, MutableList<AnimeIdMapping>>()

        mappings.forEach { mapping ->
            mapping.anidbId?.let { byAnidb.putIfAbsent(it, mapping) }
            mapping.anilistId?.let { byAnilist.putIfAbsent(it, mapping) }
            mapping.kitsuId?.let { byKitsu.putIfAbsent(it, mapping) }
            mapping.malId?.let { byMal.putIfAbsent(it, mapping) }
            mapping.simklId?.let { bySimkl.putIfAbsent(it, mapping) }
            mapping.imdbIds.forEach { byImdb.getOrPut(it.lowercase()) { mutableListOf() } += mapping }
            mapping.tmdbTvId?.let { byTmdbTv.getOrPut(it) { mutableListOf() } += mapping }
            mapping.tmdbMovieIds.forEach { byTmdbMovie.getOrPut(it) { mutableListOf() } += mapping }
            mapping.tvdbId?.let { byTvdb.getOrPut(it) { mutableListOf() } += mapping }
        }

        return Index(
            byAnidb = byAnidb,
            byAnilist = byAnilist,
            byKitsu = byKitsu,
            byMal = byMal,
            bySimkl = bySimkl,
            byImdb = byImdb,
            byTmdbTv = byTmdbTv,
            byTmdbMovie = byTmdbMovie,
            byTvdb = byTvdb,
        )
    }

    private fun emptyIndex(): Index = Index(
        byAnidb = emptyMap(),
        byAnilist = emptyMap(),
        byKitsu = emptyMap(),
        byMal = emptyMap(),
        bySimkl = emptyMap(),
        byImdb = emptyMap(),
        byTmdbTv = emptyMap(),
        byTmdbMovie = emptyMap(),
        byTvdb = emptyMap(),
    )
}

@Serializable
private data class AnimeListMiniEntry(
    val type: String? = null,
    @SerialName("anime-planet_id") val animePlanetId: String? = null,
    @SerialName("anidb_id") val anidbId: Int? = null,
    @SerialName("anilist_id") val anilistId: Int? = null,
    @SerialName("kitsu_id") val kitsuId: Int? = null,
    @SerialName("mal_id") val malId: Int? = null,
    @SerialName("simkl_id") val simklId: Int? = null,
    @SerialName("imdb_id") val imdbIds: List<String> = emptyList(),
    @SerialName("themoviedb_id") val tmdbIds: JsonElement? = null,
    @SerialName("tvdb_id") val tvdbId: Int? = null,
    val season: AnimeListSeason? = null,
    @SerialName("episode_offset") val episodeOffset: AnimeListSeason? = null,
) {
    fun toDomain(): AnimeIdMapping {
        val tmdbObject = tmdbIds as? JsonObject
        return AnimeIdMapping(
            type = type,
            animePlanetId = animePlanetId,
            anidbId = anidbId,
            anilistId = anilistId,
            kitsuId = kitsuId,
            malId = malId,
            simklId = simklId,
            imdbIds = imdbIds.filter(String::isNotBlank),
            tmdbMovieIds = tmdbObject?.get("movie").toIntList(),
            tmdbTvId = tmdbObject?.get("tv").toIntList().firstOrNull(),
            tvdbId = tvdbId,
            tvdbSeason = season?.tvdb,
            tmdbSeason = season?.tmdb,
            tvdbEpisodeOffset = episodeOffset?.tvdb,
            tmdbEpisodeOffset = episodeOffset?.tmdb,
        )
    }
}

private fun String.toSlug(): String =
    trim()
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')

@Serializable
private data class AnimeListSeason(
    val tvdb: Int? = null,
    val tmdb: Int? = null,
)

private fun JsonElement?.toIntList(): List<Int> =
    when (this) {
        null -> emptyList()
        is JsonPrimitive -> listOfNotNull(jsonPrimitive.intOrNull)
        else -> runCatching {
            jsonArray.mapNotNull { item -> item.jsonPrimitive.intOrNull }
        }.getOrElse {
            runCatching {
                animeMappingJson.decodeFromJsonElement<List<Int>>(this)
            }.getOrDefault(emptyList())
        }
    }
