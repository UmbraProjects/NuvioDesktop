package com.nuvio.app.features.mdblist

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.metadata.MediaIdResolver
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal const val MDBLIST_WATCHLIST_KEY = "mdblist:watchlist"
internal const val MDBLIST_LIST_KEY_PREFIX = "mdblist:list:"
internal const val MDBLIST_DYNAMIC_LIST_KEY_PREFIX = "mdblist:dynamic:"
internal const val MDBLIST_LIKED_LIST_KEY_PREFIX = "mdblist:liked:"
internal const val MDBLIST_EXTERNAL_LIST_KEY_PREFIX = "mdblist:external:"

/**
 * What kind of MDBList list a Library section comes from. Only [STATIC] lists take add/remove: a
 * dynamic list is filled by its filters, a liked list belongs to someone else, and an external list
 * mirrors another service (IMDb, Trakt, Letterboxd...).
 */
internal enum class MdbListListKind(val keyPrefix: String) {
    STATIC(MDBLIST_LIST_KEY_PREFIX),
    DYNAMIC(MDBLIST_DYNAMIC_LIST_KEY_PREFIX),
    LIKED(MDBLIST_LIKED_LIST_KEY_PREFIX),
    EXTERNAL(MDBLIST_EXTERNAL_LIST_KEY_PREFIX),
}

internal data class MdbListLibraryList(
    val id: Long,
    val name: String,
    val kind: MdbListListKind,
    /** "movie" or "show" when the list holds only one kind; null for mixed lists. */
    val mediaType: String? = null,
    val updatedAt: String? = null,
    /** For external lists: the service it mirrors. */
    val source: String? = null,
) {
    val key: String get() = "${kind.keyPrefix}$id"
    val acceptsItems: Boolean get() = kind == MdbListListKind.STATIC

    /** The endpoint that lists (and for static lists, edits) its items. */
    val itemsPath: String
        get() = if (kind == MdbListListKind.EXTERNAL) "/external/lists/$id/items" else "/lists/$id/items"
}

internal data class MdbListLibraryState(
    /** Every title in the watchlist and the visible lists, once each; what "in library" checks read. */
    val items: List<LibraryItem> = emptyList(),
    val watchlist: List<LibraryItem> = emptyList(),
    /** Every list the account has, hidden ones included (the settings picker shows them all). */
    val lists: List<MdbListLibraryList> = emptyList(),
    /** Items per visible list key, in the order saved on mdblist.com. */
    val itemsByList: Map<String, List<LibraryItem>> = emptyMap(),
    val hiddenListKeys: Set<String> = emptySet(),
    val hasLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
) {
    val visibleLists: List<MdbListLibraryList> get() = lists.filterNot { it.key in hiddenListKeys }
}

/**
 * The MDBList library: the watchlist plus every list on the account (upstream `3913a140b`,
 * `a9797ff89`, `2cc2e6a61`, `d8ce72589`, rebuilt on this repository): the user's static and
 * dynamic lists (`/lists/user`), lists they liked from other users (`/lists/liked`) and lists
 * imported into MDBList from other services (`/external/lists/user`). Each becomes a Library
 * section; static lists also take add/remove.
 *
 * List items are requested without a sort, so they come in the order the owner saved on
 * mdblist.com; that order is kept as the item rank, which the Library's default sort follows.
 * A list is downloaded again only when its update time changes. Liked and external lists are
 * extras: if MDBList cannot serve them the cached copy stays and the rest still syncs. Lists the
 * user hid (settings) are not downloaded at all.
 */
internal object MdbListLibraryRepository {
    private const val CACHE_TTL_MS = 15 * 60 * 1000L
    private const val PAGE_LIMIT = 1000
    private const val MAX_PAGES = 20
    private val log = Logger.withTag("MdbListLibrary")
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val refreshMutex = Mutex()
    private val _state = MutableStateFlow(MdbListLibraryState())
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var lastRefreshAtMs = 0L

    /** Downloaded list items by key, with the update time they were downloaded at. */
    private val listCache = mutableMapOf<String, Pair<String?, List<LibraryItem>>>()

    val state: StateFlow<MdbListLibraryState> = _state.asStateFlow()
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    fun ensureLoaded() = Unit

    fun clearLocalState() {
        lastRefreshAtMs = 0L
        listCache.clear()
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
        runCatching { fetchLibrary(apiKey, reloadItems = force) }.fold(
            onSuccess = { next ->
                lastRefreshAtMs = now
                _state.value = next
            },
            onFailure = { error ->
                if (error is CancellationException) throw error
                log.w(error) { "MDBList library fetch failed" }
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

    fun isInWatchlist(contentId: String, contentType: String?): Boolean =
        _state.value.watchlist.any { idsMatch(it, contentId) && (contentType == null || it.type.equals(contentType, ignoreCase = true)) }

    fun isInList(key: String, contentId: String): Boolean =
        _state.value.itemsByList[key].orEmpty().any { idsMatch(it, contentId) }

    // --- hidden lists (settings) -------------------------------------------------------------

    fun hiddenListKeys(): Set<String> =
        MdbListSettingsStorage.loadHiddenLibraryLists().orEmpty().lines().map(String::trim).filter(String::isNotEmpty).toSet()

    /** Shows or hides a list in the Library. A list shown again is downloaded on the next refresh. */
    suspend fun setListVisible(key: String, visible: Boolean) {
        val hidden = hiddenListKeys().let { if (visible) it - key else it + key }
        MdbListSettingsStorage.saveHiddenLibraryLists(hidden.sorted().joinToString("\n"))
        val current = _state.value
        _state.value = current.withHidden(hidden)
        _changes.tryEmit(Unit)
        if (visible && key !in current.itemsByList) {
            lastRefreshAtMs = 0L
            refresh(force = false)
        }
    }

    private fun MdbListLibraryState.withHidden(hidden: Set<String>): MdbListLibraryState {
        val visibleItems = itemsByList.filterKeys { it !in hidden }
        return copy(hiddenListKeys = hidden, itemsByList = visibleItems, items = union(watchlist, visibleItems))
    }

    // --- membership --------------------------------------------------------------------------

    suspend fun setWatchlistMembership(item: LibraryItem, desired: Boolean) {
        mutate(item, desired, path = "/watchlist/items", update = { state, saved ->
            val remaining = state.watchlist.filterNot { idsMatch(it, item.id) }
            val watchlist = if (saved != null) listOf(saved) + remaining else remaining
            state.copy(watchlist = watchlist, items = union(watchlist, state.itemsByList))
        })
    }

    /** Adds to or removes from one of the user's static lists. */
    suspend fun setListMembership(item: LibraryItem, key: String, desired: Boolean) {
        val list = _state.value.lists.firstOrNull { it.key == key }
            ?: error("This MDBList list is no longer available")
        require(list.acceptsItems) { "${list.name} can't be edited from here" }
        mutate(item, desired, path = list.itemsPath, update = { state, saved ->
            val current = state.itemsByList[key].orEmpty()
            val remaining = current.filterNot { idsMatch(it, item.id) }
            val next = if (saved != null) {
                remaining + saved.copy(traktRank = (current.mapNotNull { it.traktRank }.maxOrNull() ?: 0) + 1)
            } else {
                remaining
            }
            val byList = state.itemsByList + (key to next)
            listCache.remove(key)
            state.copy(itemsByList = byList, items = union(state.watchlist, byList))
        })
    }

    /** One add/remove against [path], applied optimistically and rolled back on failure. */
    private suspend fun mutate(
        item: LibraryItem,
        desired: Boolean,
        path: String,
        update: (MdbListLibraryState, LibraryItem?) -> MdbListLibraryState,
    ) {
        val apiKey = MdbListSettingsRepository.trackingApiKey()
            ?: error("MDBList tracking is not connected")
        val ids = resolveIds(item)
        if (!ids.hasAny()) error("MDBList could not identify ${item.name}")

        val body = encodeMutation(item.type, ids)
        val operation = if (desired) "add" else "remove"
        val url = "$MDBLIST_BASE_URL$path/$operation?apikey=${apiKey.encodeURLParameter()}"
        val headers = mapOf("Accept" to "application/json", "Content-Type" to "application/json")
        val previous = _state.value
        _state.value = update(previous, item.copy(savedAtEpochMs = System.currentTimeMillis()).takeIf { desired })
            .copy(hasLoaded = true, errorMessage = null)
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
            error("MDBList update failed (${response.status}): ${response.body.take(200)}")
        }
        lastRefreshAtMs = 0L
    }

    // --- sync --------------------------------------------------------------------------------

    private suspend fun fetchLibrary(apiKey: String, reloadItems: Boolean): MdbListLibraryState {
        val previous = _state.value
        val hidden = hiddenListKeys()
        val watchlist = fetchWatchlist(apiKey)

        val ownRows = getJson(apiKey, "/lists/user", emptyMap()) as? JsonArray ?: error("MDBList returned no lists")
        val ownLists = ownRows.mapNotNull { (it as? JsonObject)?.toOwnList() }
        val ownIds = ownLists.mapTo(HashSet()) { it.id }
        val likedLists = optionalLists("liked", previous, MdbListListKind.LIKED) { fetchLikedLists(apiKey, ownIds) }
        val externalLists = optionalLists("external", previous, MdbListListKind.EXTERNAL) {
            (getJson(apiKey, "/external/lists/user", emptyMap()) as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.toList(MdbListListKind.EXTERNAL) }
        }
        val lists = (ownLists + likedLists + externalLists).distinctBy { it.key }

        val itemsByList = linkedMapOf<String, List<LibraryItem>>()
        for (list in lists) {
            if (list.key in hidden) continue
            val cached = listCache[list.key]
            val unchanged = !reloadItems && cached != null && list.updatedAt != null && cached.first == list.updatedAt
            val items = if (unchanged) {
                cached!!.second
            } else {
                runCatching { fetchListItems(apiKey, list) }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        log.w(error) { "MDBList list ${list.id} (${list.kind}) failed; keeping the cached copy" }
                    }
                    .getOrNull()
                    ?: cached?.second
                    ?: previous.itemsByList[list.key]
                    ?: continue
            }
            listCache[list.key] = list.updatedAt to items
            itemsByList[list.key] = items
        }
        listCache.keys.retainAll(lists.mapTo(HashSet()) { it.key })
        return MdbListLibraryState(
            items = union(watchlist, itemsByList),
            watchlist = watchlist,
            lists = lists,
            itemsByList = itemsByList,
            // Keys of lists deleted on MDBList are dropped from the stored set on the next change.
            hiddenListKeys = hidden,
            hasLoaded = true,
        )
    }

    /** Liked and external lists: a failure keeps what was there instead of failing the library. */
    private suspend fun optionalLists(
        label: String,
        previous: MdbListLibraryState,
        kind: MdbListListKind,
        fetch: suspend () -> List<MdbListLibraryList>,
    ): List<MdbListLibraryList> = try {
        fetch()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(error) { "MDBList $label lists failed; keeping the cached ones" }
        previous.lists.filter { it.kind == kind }
    }

    private suspend fun fetchLikedLists(apiKey: String, ownIds: Set<Long>): List<MdbListLibraryList> {
        val lists = mutableListOf<MdbListLibraryList>()
        var offset = 0
        repeat(MAX_PAGES) {
            val root = getJson(apiKey, "/lists/liked", mapOf("limit" to "100", "offset" to offset.toString()))
            val rows = (root as? JsonObject)?.array("lists") ?: (root as? JsonArray) ?: return lists
            val page = rows.mapNotNull { (it as? JsonObject)?.toList(MdbListListKind.LIKED) }
            // The account's own lists come from /lists/user already.
            lists += page.filterNot { it.id in ownIds }
            if (rows.size < 100) return lists.distinctBy { it.id }
            offset += rows.size
        }
        return lists.distinctBy { it.id }
    }

    private suspend fun fetchListItems(apiKey: String, list: MdbListLibraryList): List<LibraryItem> {
        val collected = linkedMapOf<String, LibraryItem>()
        var cursor: String? = null
        var offset = 0
        repeat(MAX_PAGES) {
            val query = buildMap {
                put("limit", PAGE_LIMIT.toString())
                put("unified", "true")
                put("append_to_response", "poster,description,genres")
                val next = cursor
                if (next != null) put("cursor", next) else if (offset > 0) put("offset", offset.toString())
            }
            val response = get(apiKey, list.itemsPath, query)
            val root = json.parseToJsonElement(response.body)
            val page = decodeListPage(root)
            page.forEach { item ->
                val key = "${item.type}:${item.id}"
                if (key !in collected) collected[key] = item.copy(traktRank = collected.size + 1)
            }
            val pagination = (root as? JsonObject)?.objectValue("pagination")
            cursor = pagination?.string("next_cursor")
                ?: (root as? JsonObject)?.string("next_cursor")
                ?: response.headers["x-next-cursor"]?.takeIf { it.isNotBlank() }
            val hasMore = pagination?.bool("has_more") == true ||
                response.headers["x-has-more"].equals("true", ignoreCase = true)
            when {
                cursor != null -> Unit
                hasMore && page.isNotEmpty() -> offset += page.size
                else -> return collected.values.toList()
            }
        }
        return collected.values.toList()
    }

    private suspend fun fetchWatchlist(apiKey: String): List<LibraryItem> {
        val collected = linkedMapOf<String, LibraryItem>()
        var cursor: String? = null
        do {
            val query = buildMap {
                put("limit", PAGE_LIMIT.toString())
                put("sort", "added")
                put("order", "desc")
                put("append_to_response", "poster,description,genres")
                cursor?.let { put("cursor", it) }
            }
            val root = json.parseToJsonElement(get(apiKey, "/watchlist/items", query).body) as? JsonObject
                ?: error("MDBList returned an invalid watchlist payload")
            decodeItems(root).forEach { item -> collected["${item.type}:${item.id}"] = item }
            cursor = root.objectValue("pagination")?.string("next_cursor")
                ?: root.string("next_cursor")
        } while (!cursor.isNullOrBlank())
        return collected.values.toList()
    }

    private suspend fun get(apiKey: String, path: String, query: Map<String, String>) =
        httpRequestRaw(
            method = "GET",
            url = buildString {
                append(MDBLIST_BASE_URL).append(path).append("?apikey=").append(apiKey.encodeURLParameter())
                query.forEach { (key, value) -> append('&').append(key).append('=').append(value.encodeURLParameter()) }
            },
            headers = mapOf("Accept" to "application/json"),
            body = "",
        ).also { response ->
            if (response.status !in 200..299) error("MDBList $path returned ${response.status}")
        }

    private suspend fun getJson(apiKey: String, path: String, query: Map<String, String>): JsonElement =
        json.parseToJsonElement(get(apiKey, path, query).body)

    // --- decoding ----------------------------------------------------------------------------

    private fun JsonObject.toOwnList(): MdbListLibraryList? {
        val dynamic = bool("dynamic") == true || string("type").equals("dynamic", ignoreCase = true)
        return toList(if (dynamic) MdbListListKind.DYNAMIC else MdbListListKind.STATIC)
    }

    private fun JsonObject.toList(kind: MdbListListKind): MdbListLibraryList? {
        val id = long("id")?.takeIf { it > 0 } ?: return null
        val name = string("name")?.takeIf { it.isNotBlank() } ?: return null
        return MdbListLibraryList(
            id = id,
            name = name,
            kind = kind,
            mediaType = string("mediatype")?.lowercase()?.let { if (it == "movie") "movie" else if (it.isBlank()) null else "show" },
            updatedAt = string("last_updated_at") ?: string("updated_at") ?: string("updated"),
            source = string("source"),
        )
    }

    /** A page of list items: a bare array, `{items: [...]}`, or `{movies: [...], shows: [...]}`. */
    private fun decodeListPage(root: JsonElement): List<LibraryItem> = when (root) {
        is JsonArray -> root.mapNotNull { (it as? JsonObject)?.toLibraryItem(null, rowIdIsTmdb = true) }
        is JsonObject -> if (root["items"] is JsonArray) {
            root.array("items").mapNotNull { (it as? JsonObject)?.toLibraryItem(null, rowIdIsTmdb = true) }
        } else {
            root.array("movies").mapNotNull { (it as? JsonObject)?.toLibraryItem("movie", rowIdIsTmdb = true) } +
                root.array("shows").mapNotNull { (it as? JsonObject)?.toLibraryItem("series", rowIdIsTmdb = true) }
        }
        else -> emptyList()
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

    internal fun decodeListPageForTest(payload: String): List<LibraryItem> =
        decodeListPage(json.parseToJsonElement(payload))

    internal fun decodeOwnListsForTest(payload: String): List<MdbListLibraryList> =
        (json.parseToJsonElement(payload) as JsonArray).mapNotNull { (it as? JsonObject)?.toOwnList() }

    /**
     * [fallbackType] null means the row must say itself (`mediatype`); rows of a season or episode
     * are skipped. [rowIdIsTmdb]: in list item rows a bare `id` is the TMDB id, in watchlist rows
     * it is MDBList's own.
     */
    private fun JsonObject.toLibraryItem(fallbackType: String?, rowIdIsTmdb: Boolean = false): LibraryItem? {
        val apiType = string("mediatype")?.lowercase() ?: string("type")?.lowercase()
        if (apiType == "season" || apiType == "episode") return null
        val type = when {
            apiType in setOf("show", "series", "tv", "anime") -> "series"
            apiType == "movie" -> "movie"
            else -> fallbackType ?: return null
        }
        val ids = objectValue("ids")
        val imdb = ids?.string("imdb") ?: string("imdb_id")
        val tmdb = ids?.int("tmdb") ?: int("tmdb_id") ?: if (rowIdIsTmdb) int("id") else null
        val tvdb = ids?.int("tvdb") ?: int("tvdb_id")
        val mdblist = ids?.string("mdblist") ?: if (rowIdIsTmdb) null else string("id")
        val id = imdb?.takeIf { it.isNotBlank() }
            ?: tmdb?.let { "tmdb:$it" }
            ?: tvdb?.let { "tvdb:$it" }
            ?: mdblist?.let { "mdblist:$it" }
            ?: return null
        val title = (string("title") ?: string("name"))?.takeIf { it.isNotBlank() } ?: return null
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

    private fun union(watchlist: List<LibraryItem>, byList: Map<String, List<LibraryItem>>): List<LibraryItem> {
        val seen = LinkedHashMap<String, LibraryItem>()
        (watchlist + byList.values.flatten()).forEach { item -> seen.putIfAbsent("${item.type}:${item.id}", item) }
        return seen.values.toList()
    }

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
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.int(key: String): Int? {
        val value = this[key] as? JsonPrimitive ?: return null
        return value.intOrNull ?: value.contentOrNull?.toIntOrNull()
    }
    private fun JsonObject.long(key: String): Long? {
        val value = this[key] as? JsonPrimitive ?: return null
        return value.longOrNull ?: value.contentOrNull?.toLongOrNull()
    }
}
