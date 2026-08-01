package com.nuvio.app.features.mdblist

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.metadata.MediaIdResolver
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal data class MdbListLibraryState(
    val items: List<LibraryItem> = emptyList(),
    val hasLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

/** Authenticated MDBList watchlist (the combined movies + shows list shown by public watchlist URLs). */
internal object MdbListLibraryRepository {
    private const val CACHE_TTL_MS = 15 * 60 * 1000L
    private val log = Logger.withTag("MdbListLibrary")
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val refreshMutex = Mutex()
    private val _state = MutableStateFlow(MdbListLibraryState())
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var lastRefreshAtMs = 0L

    val state: StateFlow<MdbListLibraryState> = _state.asStateFlow()
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    fun ensureLoaded() = Unit

    fun clearLocalState() {
        lastRefreshAtMs = 0L
        _state.value = MdbListLibraryState()
        _changes.tryEmit(Unit)
    }

    suspend fun refresh(force: Boolean) = refreshMutex.withLock {
        val apiKey = MdbListSettingsRepository.trackingApiKey()
        if (apiKey == null) {
            _state.value = MdbListLibraryState(hasLoaded = true)
            _changes.tryEmit(Unit)
            return@withLock
        }
        val now = System.currentTimeMillis()
        if (!force && _state.value.hasLoaded && now - lastRefreshAtMs < CACHE_TTL_MS) return@withLock

        _state.value = _state.value.copy(isLoading = true, errorMessage = null)
        _changes.tryEmit(Unit)
        runCatching { fetchAll(apiKey) }.fold(
            onSuccess = { items ->
                lastRefreshAtMs = now
                _state.value = MdbListLibraryState(items = items, hasLoaded = true)
            },
            onFailure = { error ->
                log.w(error) { "MDBList watchlist fetch failed" }
                _state.value = _state.value.copy(
                    isLoading = false,
                    hasLoaded = true,
                    errorMessage = error.message,
                )
            },
        )
        _changes.tryEmit(Unit)
    }

    fun contains(contentId: String, contentType: String? = null): Boolean =
        _state.value.items.any { item ->
            idsMatch(item, contentId) && (contentType == null || item.type.equals(contentType, ignoreCase = true))
        }

    fun find(contentId: String): LibraryItem? = _state.value.items.firstOrNull { idsMatch(it, contentId) }

    suspend fun setWatchlistMembership(item: LibraryItem, desired: Boolean) {
        val apiKey = MdbListSettingsRepository.trackingApiKey()
            ?: error("MDBList tracking is not connected")
        val ids = resolveIds(item)
        if (!ids.hasAny()) error("MDBList could not identify ${item.name}")

        val body = encodeMutation(item.type, ids)
        val operation = if (desired) "add" else "remove"
        val url = "$MDBLIST_BASE_URL/watchlist/items/$operation?apikey=${apiKey.encodeURLParameter()}"
        val headers = mapOf("Accept" to "application/json", "Content-Type" to "application/json")
        val previous = _state.value
        val remaining = previous.items.filterNot { candidate -> idsMatch(candidate, item.id) }
        _state.value = previous.copy(
            items = if (desired) listOf(item.copy(savedAtEpochMs = System.currentTimeMillis())) + remaining else remaining,
            hasLoaded = true,
            errorMessage = null,
        )
        _changes.tryEmit(Unit)

        val response = runCatching {
            httpRequestRaw(method = "POST", url = url, headers = headers, body = body)
        }.getOrElse { error ->
            _state.value = previous
            _changes.tryEmit(Unit)
            throw error
        }
        if (response.status !in 200..299) {
            _state.value = previous
            _changes.tryEmit(Unit)
            error("MDBList watchlist update failed (${response.status}): ${response.body.take(200)}")
        }
        lastRefreshAtMs = 0L
    }

    private suspend fun fetchAll(apiKey: String): List<LibraryItem> {
        val collected = linkedMapOf<String, LibraryItem>()
        var cursor: String? = null
        do {
            val url = buildString {
                append("$MDBLIST_BASE_URL/watchlist/items?apikey=")
                append(apiKey.encodeURLParameter())
                append("&limit=1000&sort=added&order=desc&append_to_response=poster,description,genres")
                cursor?.let { append("&cursor=${it.encodeURLParameter()}") }
            }
            val response = httpRequestRaw(
                method = "GET",
                url = url,
                headers = mapOf("Accept" to "application/json"),
                body = "",
            )
            if (response.status !in 200..299) {
                error("MDBList /watchlist/items returned ${response.status}")
            }
            val root = json.parseToJsonElement(response.body) as? JsonObject
                ?: error("MDBList returned an invalid watchlist payload")
            decodeItems(root).forEach { item -> collected["${item.type}:${item.id}"] = item }
            cursor = root.objectValue("pagination")?.string("next_cursor")
                ?: root.string("next_cursor")
        } while (!cursor.isNullOrBlank())
        return collected.values.toList()
    }

    private fun decodeItems(root: JsonObject): List<LibraryItem> = buildList {
        root.array("movies").forEach { element ->
            (element as? JsonObject)?.toLibraryItem("movie")?.let(::add)
        }
        root.array("shows").forEach { element ->
            (element as? JsonObject)?.toLibraryItem("series")?.let(::add)
        }
    }

    internal fun decodeWatchlistForTest(payload: String): List<LibraryItem> =
        decodeItems(json.parseToJsonElement(payload) as JsonObject)

    private fun JsonObject.toLibraryItem(fallbackType: String): LibraryItem? {
        val ids = objectValue("ids")
        val imdb = ids?.string("imdb") ?: string("imdb_id")
        val tmdb = ids?.int("tmdb") ?: int("tmdb_id")
        val tvdb = ids?.int("tvdb") ?: int("tvdb_id")
        val mdblist = ids?.string("mdblist") ?: string("id")
        val id = imdb?.takeIf { it.isNotBlank() }
            ?: tmdb?.let { "tmdb:$it" }
            ?: tvdb?.let { "tvdb:$it" }
            ?: mdblist?.let { "mdblist:$it" }
            ?: return null
        val title = string("title")?.takeIf { it.isNotBlank() } ?: return null
        val apiType = string("mediatype")?.lowercase()
        val type = if (apiType in setOf("show", "series", "tv")) "series" else fallbackType
        return LibraryItem(
            id = id,
            type = type,
            name = title,
            poster = posterUrl(),
            description = string("description"),
            releaseInfo = (int("release_year") ?: int("year"))?.toString(),
            genres = array("genres").mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            imdbId = imdb,
            tmdbId = tmdb,
            savedAtEpochMs = long("added_at") ?: System.currentTimeMillis(),
        )
    }

    private fun JsonObject.posterUrl(): String? =
        string("poster") ?: objectValue("poster")?.string("url")

    private suspend fun resolveIds(item: LibraryItem): MdbListScrobbleRepository.MdbListIds {
        val resolved = MediaIdResolver.resolve(
            contentType = item.type,
            parentMetaId = item.id,
            videoId = null,
            title = item.name,
            isAnimeHint = item.type.equals("anime", ignoreCase = true),
        )
        return MdbListScrobbleRepository.MdbListIds(
            imdb = item.imdbId ?: resolved.imdb,
            tmdb = item.tmdbId ?: resolved.tmdb,
            tvdb = resolved.tvdb,
            trakt = item.traktId ?: resolved.trakt,
        )
    }

    private fun MdbListScrobbleRepository.MdbListIds.hasAny(): Boolean =
        !imdb.isNullOrBlank() || tmdb != null || tvdb != null || trakt != null || !mdblist.isNullOrBlank()

    private fun encodeMutation(type: String, ids: MdbListScrobbleRepository.MdbListIds): String {
        val key = if (type.equals("movie", ignoreCase = true)) "movies" else "shows"
        return buildJsonObject {
            put(key, buildJsonArray {
                add(buildJsonObject {
                    put("ids", buildJsonObject {
                        ids.imdb?.let { put("imdb", it) }
                        ids.tmdb?.let { put("tmdb", it) }
                        ids.tvdb?.let { put("tvdb", it) }
                        ids.trakt?.let { put("trakt", it) }
                        ids.mdblist?.let { put("mdblist", it) }
                    })
                })
            })
        }.toString()
    }

    internal fun encodeMutationForTest(type: String, ids: MdbListScrobbleRepository.MdbListIds): String =
        encodeMutation(type, ids)

    private fun idsMatch(item: LibraryItem, contentId: String): Boolean =
        item.id == contentId || item.imdbId == contentId ||
            item.tmdbId?.let { contentId == "tmdb:$it" } == true

    private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())
    private fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? {
        val value = this[key] as? JsonPrimitive ?: return null
        return value.intOrNull ?: value.contentOrNull?.toIntOrNull()
    }
    private fun JsonObject.long(key: String): Long? {
        val value = this[key] as? JsonPrimitive ?: return null
        return value.longOrNull ?: value.contentOrNull?.toLongOrNull()
    }
}
