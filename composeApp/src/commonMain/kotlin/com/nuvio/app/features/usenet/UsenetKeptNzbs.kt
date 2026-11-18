package com.nuvio.app.features.usenet

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Which releases the engine already keeps on disk (Settings > Usenet > Cache NZB files), so their
 * rows can say so. The engine names each kept NZB by a digest of its request and reports that name
 * when a session opens; this remembers it per NZB link (by a SHA-256 of the link, never the link
 * itself, which carries the indexer key) beside the cache. A release counts as kept while the
 * engine's file is still there: the engine evicts and expires entries on its own.
 */
internal object UsenetKeptNzbs {
    private const val INDEX = "kept-index.json"
    private const val MAX = 2_000
    private val KEY = Regex("[0-9a-f]{64}")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    /** The engine's cache folder; tests point it elsewhere. */
    internal var directory: () -> File = { usenetNzbCacheDir() }
    private var index: MutableMap<String, String>? = null

    fun remember(nzbUrl: String, cacheKey: String?) {
        if (cacheKey == null || !cacheKey.matches(KEY)) return
        synchronized(this) {
            val map = load()
            val id = linkId(nzbUrl)
            if (map[id] == cacheKey) return
            map.remove(id)
            map[id] = cacheKey
            while (map.size > MAX) map.remove(map.keys.first())
            runCatching {
                val dir = directory().apply { mkdirs() }
                File(dir, INDEX).writeText(json.encodeToString(serializer, map))
            }
        }
    }

    fun isKept(nzbUrl: String?): Boolean {
        if (nzbUrl.isNullOrBlank()) return false
        val key = synchronized(this) { load()[linkId(nzbUrl)] } ?: return false
        return runCatching { File(directory(), "$key.nzb").isFile }.getOrDefault(false)
    }

    internal fun reset() = synchronized(this) { index = null }

    private fun load(): MutableMap<String, String> = index ?: runCatching {
        val file = File(directory(), INDEX)
        if (file.isFile) LinkedHashMap(json.decodeFromString(serializer, file.readText())) else LinkedHashMap()
    }.getOrElse { LinkedHashMap() }.also { index = it }

    private fun linkId(nzbUrl: String): String =
        MessageDigest.getInstance("SHA-256").digest(nzbUrl.trim().toByteArray()).joinToString("") { "%02x".format(it) }
}
