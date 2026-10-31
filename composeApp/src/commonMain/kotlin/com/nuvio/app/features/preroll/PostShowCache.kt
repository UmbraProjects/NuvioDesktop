package com.nuvio.app.features.preroll

import co.touchlab.kermit.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Post-show cards already paid for, kept across launches. Mirrors [TriviaCache], in its own file. */
@Serializable
internal data class PostShowCacheEntry(
    val pack: PostShowPack = PostShowPack(),
    val builtAtEpochMs: Long = 0L,
    val model: String = "",
)

/** Every field needs a default, for the same reason as [TriviaCacheFile]. */
@Serializable
internal data class PostShowCacheFile(
    val version: Int = VERSION,
    val entries: Map<String, PostShowCacheEntry> = emptyMap(),
) {
    companion object {
        /** Bump on any change to this file's shape or to how [postShowCacheKey] is composed. */
        const val VERSION = 1
    }
}

private const val MAX_ENTRIES = 100

internal fun postShowCacheKey(
    metaId: String,
    scope: PostShowScope,
    model: String,
    languageTag: String,
    fingerprint: String,
): String = listOf("postshow", "p$POST_SHOW_PROMPT_VERSION", metaId, scope.cacheKey, model, languageTag, fingerprint)
    .joinToString("|")

internal object PostShowCache {
    private val log = Logger.withTag("PostShow")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val lock = Any()
    private var entries: MutableMap<String, PostShowCacheEntry>? = null

    private fun loaded(): MutableMap<String, PostShowCacheEntry> {
        entries?.let { return it }
        val restored = runCatching {
            PostShowCacheStorage.load()
                ?.let { json.decodeFromString<PostShowCacheFile>(it) }
                ?.takeIf { it.version == PostShowCacheFile.VERSION }
                ?.entries
        }.onFailure { log.w(it) { "post-show cache unreadable; starting empty" } }
            .getOrNull()
            .orEmpty()
        return LinkedHashMap(restored).also { entries = it }
    }

    fun get(key: String): PostShowCacheEntry? = synchronized(lock) { loaded()[key] }

    fun put(key: String, entry: PostShowCacheEntry) = synchronized(lock) {
        val map = loaded()
        map[key] = entry
        if (map.size > MAX_ENTRIES) {
            map.entries
                .sortedBy { it.value.builtAtEpochMs }
                .take(map.size - MAX_ENTRIES)
                .map { it.key }
                .forEach { map.remove(it) }
        }
        persist(map)
    }

    fun remove(key: String) = synchronized(lock) {
        val map = loaded()
        if (map.remove(key) != null) persist(map)
    }

    private fun persist(map: Map<String, PostShowCacheEntry>) {
        runCatching {
            PostShowCacheStorage.save(json.encodeToString(PostShowCacheFile(entries = map)))
        }.onFailure { log.w(it) { "could not persist post-show cache" } }
    }

    internal fun encodeForTest(file: PostShowCacheFile): String = json.encodeToString(file)
    internal fun decodeForTest(raw: String): PostShowCacheFile = json.decodeFromString(raw)
}

internal expect object PostShowCacheStorage {
    fun load(): String?
    fun save(json: String?)
}
