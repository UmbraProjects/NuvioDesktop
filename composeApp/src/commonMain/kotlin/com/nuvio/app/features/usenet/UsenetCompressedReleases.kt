package com.nuvio.app.features.usenet

import com.nuvio.app.features.streams.StreamItem
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Usenet releases found to be a compressed 7z. They play, but only by unpacking from the start, so
 * a skip ahead waits for everything before it to download. Nothing in a search result says so: the
 * engine reports it when it opens the release, and it is remembered here per NZB link (a SHA-256 of
 * the link, never the link itself) so the next listing can score, mark or hide it. Another upload of
 * the same release is a different link and may well be stored. Kept beside the NZB cache, at most
 * [MAX] releases, the oldest dropped first.
 */
internal object UsenetCompressedReleases {
    private const val FILE = "usenet-compressed-7z.json"
    private const val MAX = 5_000
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(String.serializer())

    /** The folder the record lives in; tests point it elsewhere. */
    internal var directory: () -> File = { usenetNzbCacheDir().parentFile ?: usenetNzbCacheDir() }
    private var ids: LinkedHashSet<String>? = null

    fun record(nzbUrl: String?) {
        if (nzbUrl.isNullOrBlank()) return
        synchronized(this) {
            val set = load()
            if (!set.add(linkId(nzbUrl))) return
            while (set.size > MAX) set.remove(set.first())
            runCatching {
                val dir = directory().apply { mkdirs() }
                File(dir, FILE).writeText(json.encodeToString(serializer, set.toList()))
            }
        }
    }

    fun isCompressed(nzbUrl: String?): Boolean {
        if (nzbUrl.isNullOrBlank()) return false
        return synchronized(this) { linkId(nzbUrl) in load() }
    }

    fun isCompressed(stream: StreamItem): Boolean = isCompressed(stream.nzbUrl)

    /** Known to be a compressed 7z the setting skips: not offered and not tried again. */
    fun skipped(stream: StreamItem): Boolean =
        isCompressed(stream) && UsenetSettingsRepository.engine.skipsCompressed(stream.behaviorHints.videoSize)

    internal fun reset() = synchronized(this) { ids = null }

    private fun load(): LinkedHashSet<String> = ids ?: runCatching {
        val file = File(directory(), FILE)
        if (file.isFile) LinkedHashSet(json.decodeFromString(serializer, file.readText())) else LinkedHashSet()
    }.getOrElse { LinkedHashSet() }.also { ids = it }

    private fun linkId(nzbUrl: String): String =
        MessageDigest.getInstance("SHA-256").digest(nzbUrl.trim().toByteArray()).joinToString("") { "%02x".format(it) }
}
