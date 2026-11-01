package com.nuvio.app.features.music

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Where the timed lyric cache (LRCLIB and the word-timing sources) lives on disk: one text value per key. */
internal expect object LyricCacheStorage {
    fun keys(): Set<String>
    fun get(key: String): String?

    /** Stores [value]; null removes the key. */
    fun put(key: String, value: String?)
}

/** What was remembered about one source and one track: [lyrics] null is a remembered "this source has none". */
internal data class CachedLyric(val atEpochMs: Long, val lyrics: Lyrics.Synced?)

private const val DAY_MS = 24L * 60L * 60L * 1_000L

/** How long a found lyric is trusted (a community lyric can be corrected) and how long a miss is (a lyric can be added). */
internal const val LYRIC_CACHE_HIT_TTL_MS = 30 * DAY_MS
internal const val LYRIC_CACHE_MISS_TTL_MS = 7 * DAY_MS

/**
 * Entries kept per cache; past it the oldest go. A found lyric is a few KB, a miss a few bytes. A track can hold up to
 * five entries (LRCLIB and the four word-timing sources), so this is a couple of hundred tracks.
 */
internal const val LYRIC_CACHE_MAX_ENTRIES = 1_000
internal const val LYRIC_CACHE_SLACK = 50

/**
 * Written in front of every key. Bump it when the layout below changes, or when a source's rules change so that an
 * old "none here" may now be wrong, and the old entries are simply never read. They are left on disk, untouched (the
 * cache never deletes another version's entries), which is at most a few hundred KB.
 * v2: LRCMux line-timed answers are no longer turned away, so a v1 miss for a song it only had by line was false.
 */
private const val CACHE_VERSION = "v2"

private val AT_PREFIX = Regex("""^\{"at":(\d+)""")
private val cacheJson = Json { ignoreUnknownKeys = true }

/**
 * One cache entry as text. `at` comes first so the age of an entry can be read without parsing the rest. A lyric is
 * `s` (who it came from), `d` (the length it was timed to, seconds) and `l`, its lines: `[timeMs, text, [[start, end,
 * word], ...]]`, the word list left out for a line timed only as a whole.
 */
internal fun encodeCachedLyric(atEpochMs: Long, lyrics: Lyrics.Synced?): String = buildJsonObject {
    put("at", JsonPrimitive(atEpochMs))
    if (lyrics != null) {
        lyrics.source?.let { put("s", JsonPrimitive(it)) }
        put("d", JsonPrimitive(lyrics.durationSeconds))
        put("l", buildJsonArray {
            for (line in lyrics.lines) {
                add(buildJsonArray {
                    add(JsonPrimitive(line.timeMs))
                    add(JsonPrimitive(line.text))
                    if (line.words.isNotEmpty()) {
                        add(buildJsonArray {
                            for (word in line.words) {
                                add(buildJsonArray {
                                    add(JsonPrimitive(word.startMs))
                                    add(JsonPrimitive(word.endMs))
                                    add(JsonPrimitive(word.text))
                                })
                            }
                        })
                    }
                })
            }
        })
    }
}.toString()

/** The entry [encodeCachedLyric] wrote, or null when [raw] is damaged or not an entry. */
internal fun decodeCachedLyric(raw: String): CachedLyric? {
    val obj = runCatching { cacheJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
    val at = (obj["at"] as? JsonPrimitive)?.longOrNull ?: return null
    val rawLines = obj["l"] as? JsonArray ?: return CachedLyric(at, null)
    val lines = rawLines.map { element ->
        val parts = element as? JsonArray ?: return null
        val time = (parts.getOrNull(0) as? JsonPrimitive)?.longOrNull ?: return null
        val text = (parts.getOrNull(1) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val words = (parts.getOrNull(2) as? JsonArray)?.map { w ->
            val triple = w as? JsonArray ?: return null
            val start = (triple.getOrNull(0) as? JsonPrimitive)?.longOrNull ?: return null
            val end = (triple.getOrNull(1) as? JsonPrimitive)?.longOrNull ?: return null
            val word = (triple.getOrNull(2) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            TimedWord(start, end, word)
        }.orEmpty()
        LyricLine(time, text, words)
    }
    if (lines.isEmpty()) return CachedLyric(at, null)
    val duration = (obj["d"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
    val source = (obj["s"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    return CachedLyric(at, Lyrics.Synced(lines, duration, source))
}

/** The few storage calls the cache needs, so its rules can be tested without the disk. */
internal interface LyricCacheBackend {
    fun keys(): Set<String>
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/**
 * Word-timed lyrics (and "none found") remembered, per source and track. Without it every session asks every source
 * again for every track it plays, and a source that has nothing is asked again and again.
 */
internal class LyricCacheStore(
    private val backend: LyricCacheBackend,
    private val maxEntries: Int = LYRIC_CACHE_MAX_ENTRIES,
    private val slack: Int = LYRIC_CACHE_SLACK,
) {
    private fun key(source: String, videoId: String) = "$CACHE_VERSION:$source:$videoId"

    /** The remembered answer of [source] for [videoId], or null when there is none or it has gone stale (a stale one is dropped). */
    fun get(source: String, videoId: String, nowMs: Long): CachedLyric? {
        val key = key(source, videoId)
        val raw = backend.get(key) ?: return null
        val entry = decodeCachedLyric(raw)
        val age = entry?.let { nowMs - it.atEpochMs }
        val ttl = if (entry?.lyrics != null) LYRIC_CACHE_HIT_TTL_MS else LYRIC_CACHE_MISS_TTL_MS
        if (entry == null || age == null || age < 0L || age > ttl) {
            backend.put(key, null)
            return null
        }
        return entry
    }

    fun put(source: String, videoId: String, nowMs: Long, lyrics: Lyrics.Synced?) {
        backend.put(key(source, videoId), encodeCachedLyric(nowMs, lyrics))
        trim()
    }

    /** Drops the oldest entries once there are more than [maxEntries] plus [slack] (so it is not done on every write). */
    private fun trim() {
        val keys = backend.keys().filter { it.startsWith("$CACHE_VERSION:") }
        if (keys.size <= maxEntries + slack) return
        val byAge = keys.map { k ->
            val at = backend.get(k)?.let { AT_PREFIX.find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: 0L
            k to at
        }.sortedBy { it.second }
        byAge.take(keys.size - maxEntries).forEach { (k, _) -> backend.put(k, null) }
    }
}

/** The cache the app uses, kept on disk. */
internal val LyricCache = LyricCacheStore(object : LyricCacheBackend {
    override fun keys(): Set<String> = LyricCacheStorage.keys()
    override fun get(key: String): String? = LyricCacheStorage.get(key)
    override fun put(key: String, value: String?) = LyricCacheStorage.put(key, value)
})
