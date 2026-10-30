package com.nuvio.app.features.preroll

import co.touchlab.kermit.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Trivia already paid for, kept across launches.
 *
 * A pack is deterministic enough for its inputs that re-opening a film must never bill a second
 * request. The key carries everything an answer depends on, so a hit is a hit for the right
 * reasons. Not scoped to a profile: nothing here is derived from a person.
 */
@Serializable
internal data class TriviaCacheEntry(
    val pack: TriviaPack = TriviaPack(),
    val builtAtEpochMs: Long = 0L,
    val model: String = "",
    val mode: String = TriviaMode.ModelKnowledge.name,
)

/**
 * **Every field needs a default.** A required field missing from an older file fails
 * deserialisation before [VERSION] is compared, and the file is discarded as corrupt rather than
 * superseded. The recap and Discover caches learned this already.
 */
@Serializable
internal data class TriviaCacheFile(
    val version: Int = VERSION,
    val entries: Map<String, TriviaCacheEntry> = emptyMap(),
) {
    companion object {
        /** Bump on any change to this file's shape or to how [triviaCacheKey] is composed. */
        const val VERSION = 1
    }
}

/** A pack is a few kilobytes; the working set is "films I might watch soon". */
private const val MAX_ENTRIES = 150

internal fun triviaCacheKey(
    metaId: String,
    model: String,
    languageTag: String,
    fingerprint: String,
    mode: TriviaMode,
): String = listOf(
    "trivia",
    "p$TRIVIA_PROMPT_VERSION",
    metaId,
    model,
    languageTag,
    fingerprint,
    mode.name,
).joinToString("|")

internal object TriviaCache {
    private val log = Logger.withTag("Trivia")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val lock = Any()
    private var entries: MutableMap<String, TriviaCacheEntry>? = null

    private fun loaded(): MutableMap<String, TriviaCacheEntry> {
        entries?.let { return it }
        val restored = runCatching {
            TriviaCacheStorage.load()
                ?.let { json.decodeFromString<TriviaCacheFile>(it) }
                ?.takeIf { it.version == TriviaCacheFile.VERSION }
                ?.entries
        }.onFailure { log.w(it) { "trivia cache unreadable; starting empty" } }
            .getOrNull()
            .orEmpty()
        return LinkedHashMap(restored).also { entries = it }
    }

    fun get(key: String): TriviaCacheEntry? = synchronized(lock) { loaded()[key] }

    fun put(key: String, entry: TriviaCacheEntry) = synchronized(lock) {
        val map = loaded()
        map[key] = entry
        if (map.size > MAX_ENTRIES) {
            // Keys copied out before removing: entry objects a LinkedHashMap hands back are views
            // into the map itself, and reading one after its removal is undefined.
            map.entries
                .sortedBy { it.value.builtAtEpochMs }
                .take(map.size - MAX_ENTRIES)
                .map { it.key }
                .forEach { map.remove(it) }
        }
        persist(map)
    }

    /** Forgets one entry, e.g. a pack the viewer reported as wrong. A missing key is fine. */
    fun remove(key: String) = synchronized(lock) {
        val map = loaded()
        if (map.remove(key) != null) persist(map)
    }

    fun clear() = synchronized(lock) {
        entries = LinkedHashMap()
        TriviaCacheStorage.save(null)
    }

    private fun persist(map: Map<String, TriviaCacheEntry>) {
        runCatching {
            TriviaCacheStorage.save(json.encodeToString(TriviaCacheFile(entries = map)))
        }.onFailure { log.w(it) { "could not persist trivia cache" } }
    }
}

/** Persists trivia across launches. */
internal expect object TriviaCacheStorage {
    fun load(): String?
    fun save(json: String?)
}
