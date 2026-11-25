package com.nuvio.app.features.metadata

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeToSequence
import java.io.InputStream

private const val ANIME_LIST_RESOURCE = "anime-list-mini.json"

private val animeMappingJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private val animeMappingLog = Logger.withTag("AnimeIdMapping")

internal expect object AnimeIdMappingStorage {
    fun loadAnimeListText(): String?
    fun openAnimeListStream(): InputStream?
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

internal enum class AnimeMappingCoordinateSystem {
    TMDB,
    TVDB,
    AUTO,
}

internal fun animeMappingCoordinateSystemFor(id: String?): AnimeMappingCoordinateSystem = when {
    id?.startsWith("tmdb:", ignoreCase = true) == true -> AnimeMappingCoordinateSystem.TMDB
    id?.startsWith("tvdb:", ignoreCase = true) == true -> AnimeMappingCoordinateSystem.TVDB
    else -> AnimeMappingCoordinateSystem.AUTO
}

/** Pure coordinate selection, kept separate from the resource index so edge cases are testable. */
internal fun selectAnimeMappingByCoordinates(
    candidates: List<AnimeIdMapping>,
    season: Int,
    episode: Int?,
    coordinateSystem: AnimeMappingCoordinateSystem,
    preferredOnTie: AnimeIdMapping? = null,
): AnimeIdMapping? {
    data class Coordinate(
        val mapping: AnimeIdMapping,
        val offset: Int,
    )

    fun AnimeIdMapping.coordinates(system: AnimeMappingCoordinateSystem): List<Pair<Int, Int>> {
        val hasNoExplicitSeason = tmdbSeason == null && tvdbSeason == null
        // anime-list rows routinely carry an offset for one provider only. A missing offset means
        // "unknown", not "zero": when both seasons are the same number the sibling's offset is
        // expressed in that same numbering, so it is the right answer, and 0 is a false claim that
        // collides with the season's genuine first cour. Pokémon season 15 is three entries at
        // tvdb offsets 0/24/38 with no tmdb offsets — without this they all advertise offset 0,
        // tie, and resolve to no entry at all. `preferredAnimeEpisodeOffset` already falls back
        // this way; the two must agree or selection and numbering disagree about the same entry.
        val sharedSeason = tmdbSeason != null && tmdbSeason == tvdbSeason
        val tmdbOffset = tmdbEpisodeOffset ?: tvdbEpisodeOffset.takeIf { sharedSeason }
        val tvdbOffset = tvdbEpisodeOffset ?: tmdbEpisodeOffset.takeIf { sharedSeason }
        val tmdb = tmdbSeason?.let { it to (tmdbOffset ?: 0) }
            ?: if (hasNoExplicitSeason) 1 to (tmdbEpisodeOffset ?: tvdbEpisodeOffset ?: 0) else null
        val tvdb = tvdbSeason?.let { it to (tvdbOffset ?: 0) }
            ?: if (hasNoExplicitSeason) 1 to (tvdbEpisodeOffset ?: tmdbEpisodeOffset ?: 0) else null
        return when (system) {
            AnimeMappingCoordinateSystem.TMDB -> listOfNotNull(tmdb)
            AnimeMappingCoordinateSystem.TVDB -> listOfNotNull(tvdb)
            AnimeMappingCoordinateSystem.AUTO -> listOfNotNull(tmdb, tvdb).distinct()
        }
    }

    val matching = candidates.flatMap { mapping ->
        mapping.coordinates(coordinateSystem)
            .filter { (candidateSeason, offset) ->
                candidateSeason == season && (episode == null || offset < episode)
            }
            .map { (_, offset) -> Coordinate(mapping, offset) }
    }
    if (matching.isEmpty()) return null

    // With an episode, the split whose offset most closely precedes it owns that episode.
    // Without one, choose the season's first split rather than a later cour.
    val targetOffset = if (episode != null) matching.maxOf { it.offset } else matching.minOf { it.offset }
    val finalists = matching.filter { it.offset == targetOffset }.map { it.mapping }.distinct()
    if (finalists.size == 1) return finalists.first()
    return preferredOnTie?.takeIf { it in finalists }
}

internal object AnimeIdMappingRepository {
    /**
     * Compact sorted-array lookup for primitive int IDs -> single [AnimeIdMapping].
     * Avoids `HashMap$Node` + boxed `Integer` overhead across ~150k index entries (~6-8 MB saved).
     */
    private class IntMappingTable(
        private val keys: IntArray,
        private val values: Array<AnimeIdMapping>,
    ) {
        operator fun get(key: Int): AnimeIdMapping? {
            val idx = keys.binarySearch(key)
            return if (idx >= 0) values[idx] else null
        }

        companion object {
            val EMPTY = IntMappingTable(IntArray(0), emptyArray())

            fun fromMap(map: Map<Int, AnimeIdMapping>): IntMappingTable {
                if (map.isEmpty()) return EMPTY
                val sortedKeys = IntArray(map.size)
                var i = 0
                for (k in map.keys) sortedKeys[i++] = k
                sortedKeys.sort()
                val sortedValues = Array(sortedKeys.size) { idx -> map.getValue(sortedKeys[idx]) }
                return IntMappingTable(sortedKeys, sortedValues)
            }
        }
    }

    /** Compact sorted-array lookup for primitive int IDs -> list of [AnimeIdMapping]. */
    private class IntMultiMappingTable(
        private val keys: IntArray,
        private val values: Array<List<AnimeIdMapping>>,
    ) {
        operator fun get(key: Int): List<AnimeIdMapping>? {
            val idx = keys.binarySearch(key)
            return if (idx >= 0) values[idx] else null
        }

        companion object {
            val EMPTY = IntMultiMappingTable(IntArray(0), emptyArray())

            fun fromMap(map: Map<Int, MutableList<AnimeIdMapping>>): IntMultiMappingTable {
                if (map.isEmpty()) return EMPTY
                val sortedKeys = IntArray(map.size)
                var i = 0
                for (k in map.keys) sortedKeys[i++] = k
                sortedKeys.sort()
                val sortedValues = Array<List<AnimeIdMapping>>(sortedKeys.size) { idx ->
                    val list = map.getValue(sortedKeys[idx])
                    if (list.size == 1) listOf(list[0]) else list
                }
                return IntMultiMappingTable(sortedKeys, sortedValues)
            }
        }
    }

    private class Index(
        val byAnidb: IntMappingTable,
        val byAnilist: IntMappingTable,
        val byKitsu: IntMappingTable,
        val byMal: IntMappingTable,
        val bySimkl: IntMappingTable,
        val byImdb: Map<String, List<AnimeIdMapping>>,
        val byTmdbTv: IntMultiMappingTable,
        val byTmdbMovie: IntMultiMappingTable,
        val byTvdb: IntMultiMappingTable,
    )

    @Volatile
    private var cachedIndex: Index? = null
    private val indexLock = Any()
    private val loadedListenersLock = Any()
    private val loadedListeners = mutableListOf<() -> Unit>()

    /** True once [ANIME_LIST_RESOURCE] has been parsed into memory. Does not trigger a load. */
    val isLoaded: Boolean
        get() = cachedIndex != null

    /**
     * Runs [listener] once the anime id index is loaded (immediately if already loaded, otherwise
     * right after the first [loadIndex] completes). Lets startup callers skip blocking on the
     * ~6 MB / 40k-entry parse while still picking up anime aliases as soon as [warmAsync] lands.
     */
    fun onIndexLoaded(listener: () -> Unit) {
        val alreadyLoaded = synchronized(loadedListenersLock) {
            if (cachedIndex != null) {
                true
            } else {
                loadedListeners += listener
                false
            }
        }
        if (alreadyLoaded) runCatching(listener)
    }

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
            tmdbCandidateTables(ids.contentType, index).forEach { table ->
                table[tmdb]?.selectBest(ids)?.let { mapping -> return mapping }
            }
        }
        ids.tvdb?.let { index.byTvdb[it]?.selectBest(ids)?.let { mapping -> return mapping } }
        return null
    }

    private fun tmdbCandidateTables(contentType: String, index: Index): List<IntMultiMappingTable> =
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
    fun franchiseEntryFor(
        base: AnimeIdMapping,
        season: Int,
        episode: Int,
        coordinateSystem: AnimeMappingCoordinateSystem = AnimeMappingCoordinateSystem.AUTO,
    ): AnimeIdMapping? {
        val index = loadIndex()
        val siblings = buildList {
            base.tvdbId?.let { id -> addAll(index.byTvdb[id].orEmpty()) }
            base.tmdbTvId?.let { id -> addAll(index.byTmdbTv[id].orEmpty()) }
        }.distinct()
        return selectAnimeMappingByCoordinates(
            candidates = siblings.filter { entry -> entry.hasNativeAnimeId() },
            season = season,
            episode = episode,
            coordinateSystem = coordinateSystem,
            preferredOnTie = base,
        )
    }

    /**
     * The anime-list entries for one TMDB title, or empty when the list doesn't know it as anime.
     * A tv show's entries are its per-season kitsu/mal splits (SAO is one TMDB show, many entries).
     */
    fun entriesForTmdb(tmdbId: Int, isTv: Boolean): List<AnimeIdMapping> {
        val index = loadIndex()
        return (if (isTv) index.byTmdbTv[tmdbId] else index.byTmdbMovie[tmdbId]).orEmpty()
    }

    /** The anime-list entries carrying [imdbId] (`tt…`), or empty when the list doesn't know it. */
    fun entriesForImdb(imdbId: String): List<AnimeIdMapping> =
        loadIndex().byImdb[imdbId.trim().lowercase()].orEmpty()

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

    private fun List<AnimeIdMapping>.selectBest(ids: ResolvedMediaIds): AnimeIdMapping? {
        if (isEmpty()) return null
        if (size == 1) return first()
        val titleSlug = ids.sourceTitle?.toSlug()
        val coordinateSystem = animeMappingCoordinateSystemFor(ids.sourceId)
        val coordinateMatch = ids.sourceSeasonNumber?.let { sourceSeason ->
            selectAnimeMappingByCoordinates(
                candidates = this,
                season = sourceSeason,
                episode = ids.sourceEpisodeNumber,
                coordinateSystem = coordinateSystem,
            )
        }
        if (ids.sourceEpisodeNumber != null) {
            coordinateMatch?.let { return it }
        }
        if ((ids.sourceSeasonNumber ?: 0) > 1) {
            coordinateMatch?.let { return it }
        }
        if (!titleSlug.isNullOrBlank()) {
            firstOrNull { mapping -> mapping.animePlanetId?.equals(titleSlug, ignoreCase = true) == true }?.let { return it }
        }
        coordinateMatch?.let { return it }
        if (!titleSlug.isNullOrBlank()) {
            filter { mapping ->
                val slug = mapping.animePlanetId.orEmpty()
                slug.isNotBlank() && (slug.contains(titleSlug, ignoreCase = true) || titleSlug.contains(slug, ignoreCase = true))
            }.singleOrNull()?.let { return it }
        }
        // A franchise-level IMDb/TMDB/TVDB id can legitimately map to dozens of anime entries.
        // Returning the JSON's first row when neither title nor coordinates disambiguate it is
        // data-order dependent and can silently turn episode 61 into entry/episode 1.
        return null
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
        var freshlyLoaded = false
        val index = synchronized(indexLock) {
            cachedIndex?.let { return@synchronized it }
            buildIndex().also {
                cachedIndex = it
                freshlyLoaded = true
            }
        }
        if (freshlyLoaded) {
            val pending = synchronized(loadedListenersLock) {
                if (loadedListeners.isEmpty()) emptyList()
                else loadedListeners.toList().also { loadedListeners.clear() }
            }
            pending.forEach { listener -> runCatching(listener) }
        }
        return index
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun buildIndex(): Index {
        val stream = AnimeIdMappingStorage.openAnimeListStream()
        val text = if (stream == null) AnimeIdMappingStorage.loadAnimeListText() else null
        if (stream == null && text.isNullOrBlank()) {
            animeMappingLog.w { "$ANIME_LIST_RESOURCE was not found; anime ID conversion will be limited." }
            return emptyIndex()
        }

        val byAnidb = HashMap<Int, AnimeIdMapping>(40_960)
        val byAnilist = HashMap<Int, AnimeIdMapping>(32_768)
        val byKitsu = HashMap<Int, AnimeIdMapping>(32_768)
        val byMal = HashMap<Int, AnimeIdMapping>(40_960)
        val bySimkl = HashMap<Int, AnimeIdMapping>(32_768)
        val byImdb = HashMap<String, MutableList<AnimeIdMapping>>(16_384)
        val byTmdbTv = HashMap<Int, MutableList<AnimeIdMapping>>(16_384)
        val byTmdbMovie = HashMap<Int, MutableList<AnimeIdMapping>>(8_192)
        val byTvdb = HashMap<Int, MutableList<AnimeIdMapping>>(16_384)
        // Deduplicate repeated `type` ("TV", "MOVIE", "OVA"...) and shared franchise `imdb` strings.
        val typePool = HashMap<String, String>(16)
        val imdbPool = HashMap<String, String>(16_384)

        fun ingest(entry: AnimeListMiniEntry) {
            val mapping = entry.toDomain(
                internType = { raw -> typePool.getOrPut(raw) { raw } },
                internImdb = { raw -> imdbPool.getOrPut(raw) { raw } },
            )
            mapping.anidbId?.let { byAnidb.putIfAbsent(it, mapping) }
            mapping.anilistId?.let { byAnilist.putIfAbsent(it, mapping) }
            mapping.kitsuId?.let { byKitsu.putIfAbsent(it, mapping) }
            mapping.malId?.let { byMal.putIfAbsent(it, mapping) }
            mapping.simklId?.let { bySimkl.putIfAbsent(it, mapping) }
            if (mapping.imdbIds.isNotEmpty()) {
                for (imdb in mapping.imdbIds) {
                    val key = imdbPool.getOrPut(imdb.lowercase()) { imdb.lowercase() }
                    byImdb.getOrPut(key) { ArrayList(2) } += mapping
                }
            }
            mapping.tmdbTvId?.let { byTmdbTv.getOrPut(it) { ArrayList(2) } += mapping }
            if (mapping.tmdbMovieIds.isNotEmpty()) {
                for (movieId in mapping.tmdbMovieIds) {
                    byTmdbMovie.getOrPut(movieId) { ArrayList(2) } += mapping
                }
            }
            mapping.tvdbId?.let { byTvdb.getOrPut(it) { ArrayList(2) } += mapping }
        }

        runCatching {
            if (stream != null) {
                stream.buffered().use { buffered ->
                    animeMappingJson
                        .decodeToSequence<AnimeListMiniEntry>(buffered, DecodeSequenceMode.ARRAY_WRAPPED)
                        .forEach(::ingest)
                }
            } else {
                animeMappingJson.decodeFromString<List<AnimeListMiniEntry>>(text!!).forEach(::ingest)
            }
        }.onFailure { error ->
            animeMappingLog.w(error) { "Failed to parse $ANIME_LIST_RESOURCE" }
        }

        val compactImdb = HashMap<String, List<AnimeIdMapping>>((byImdb.size * 4) / 3 + 16)
        byImdb.forEach { (k, v) -> compactImdb[k] = if (v.size == 1) listOf(v[0]) else v }

        return Index(
            byAnidb = IntMappingTable.fromMap(byAnidb),
            byAnilist = IntMappingTable.fromMap(byAnilist),
            byKitsu = IntMappingTable.fromMap(byKitsu),
            byMal = IntMappingTable.fromMap(byMal),
            bySimkl = IntMappingTable.fromMap(bySimkl),
            byImdb = compactImdb,
            byTmdbTv = IntMultiMappingTable.fromMap(byTmdbTv),
            byTmdbMovie = IntMultiMappingTable.fromMap(byTmdbMovie),
            byTvdb = IntMultiMappingTable.fromMap(byTvdb),
        )
    }

    private fun emptyIndex(): Index = Index(
        byAnidb = IntMappingTable.EMPTY,
        byAnilist = IntMappingTable.EMPTY,
        byKitsu = IntMappingTable.EMPTY,
        byMal = IntMappingTable.EMPTY,
        bySimkl = IntMappingTable.EMPTY,
        byImdb = emptyMap(),
        byTmdbTv = IntMultiMappingTable.EMPTY,
        byTmdbMovie = IntMultiMappingTable.EMPTY,
        byTvdb = IntMultiMappingTable.EMPTY,
    )
}

@Serializable
private data class AnimeListTmdbIds(
    val movie: List<Int> = emptyList(),
    val tv: Int? = null,
)

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
    @SerialName("themoviedb_id") val tmdbIds: AnimeListTmdbIds? = null,
    @SerialName("tvdb_id") val tvdbId: Int? = null,
    val season: AnimeListSeason? = null,
    @SerialName("episode_offset") val episodeOffset: AnimeListSeason? = null,
) {
    fun toDomain(
        internType: (String) -> String = { it },
        internImdb: (String) -> String = { it },
    ): AnimeIdMapping {
        val nonBlankImdb = when {
            imdbIds.isEmpty() -> emptyList()
            imdbIds.size == 1 -> if (imdbIds[0].isNotBlank()) listOf(internImdb(imdbIds[0])) else emptyList()
            else -> imdbIds.mapNotNull { id -> id.takeIf(String::isNotBlank)?.let(internImdb) }
        }
        val movieIds = tmdbIds?.movie.orEmpty()
        val compactMovies = when (movieIds.size) {
            0 -> emptyList()
            1 -> listOf(movieIds[0])
            else -> movieIds
        }
        return AnimeIdMapping(
            type = type?.let(internType),
            animePlanetId = animePlanetId,
            anidbId = anidbId,
            anilistId = anilistId,
            kitsuId = kitsuId,
            malId = malId,
            simklId = simklId,
            imdbIds = nonBlankImdb,
            tmdbMovieIds = compactMovies,
            tmdbTvId = tmdbIds?.tv,
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
