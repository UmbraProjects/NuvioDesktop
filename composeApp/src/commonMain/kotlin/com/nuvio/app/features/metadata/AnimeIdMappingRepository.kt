package com.nuvio.app.features.metadata

import co.touchlab.kermit.Logger
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
        val byTmdb: Map<Int, List<AnimeIdMapping>>,
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
        ids.tmdb?.let { index.byTmdb[it]?.selectBest(ids)?.let { mapping -> return mapping } }
        ids.tvdb?.let { index.byTvdb[it]?.selectBest(ids)?.let { mapping -> return mapping } }
        return null
    }

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
        val byTmdb = linkedMapOf<Int, MutableList<AnimeIdMapping>>()
        val byTvdb = linkedMapOf<Int, MutableList<AnimeIdMapping>>()

        mappings.forEach { mapping ->
            mapping.anidbId?.let { byAnidb.putIfAbsent(it, mapping) }
            mapping.anilistId?.let { byAnilist.putIfAbsent(it, mapping) }
            mapping.kitsuId?.let { byKitsu.putIfAbsent(it, mapping) }
            mapping.malId?.let { byMal.putIfAbsent(it, mapping) }
            mapping.simklId?.let { bySimkl.putIfAbsent(it, mapping) }
            mapping.imdbIds.forEach { byImdb.getOrPut(it.lowercase()) { mutableListOf() } += mapping }
            mapping.tmdbTvId?.let { byTmdb.getOrPut(it) { mutableListOf() } += mapping }
            mapping.tmdbMovieIds.forEach { byTmdb.getOrPut(it) { mutableListOf() } += mapping }
            mapping.tvdbId?.let { byTvdb.getOrPut(it) { mutableListOf() } += mapping }
        }

        return Index(
            byAnidb = byAnidb,
            byAnilist = byAnilist,
            byKitsu = byKitsu,
            byMal = byMal,
            bySimkl = bySimkl,
            byImdb = byImdb,
            byTmdb = byTmdb,
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
        byTmdb = emptyMap(),
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
