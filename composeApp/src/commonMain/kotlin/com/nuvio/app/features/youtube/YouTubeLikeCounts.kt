package com.nuvio.app.features.youtube

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/*
 * fork:youtube-mode
 *
 * A video's like count for the YouTube tab's cards, which YouTube's feeds leave out. Read from Return
 * YouTube Dislike (https://returnyoutubedislike.com), whose `likes` is YouTube's own count: one small
 * request per card, where YouTube's watch page (`next`) takes about a second and counts against us.
 */

private val log = Logger.withTag("YouTubeLikeCounts")
private val json = Json { ignoreUnknownKeys = true }

private const val API = "https://returnyoutubedislikeapi.com/votes?videoId="
private const val CACHE_SIZE = 600

internal object YouTubeLikeCounts {
    private val mutex = Mutex()

    /** A video's count, or null when it could not be had (asked again next time it shows). */
    private val cache = LinkedHashMap<String, Long>()

    /** Like DeArrow: a grid page shows a dozen cards at once; a few lookups at a time is plenty. */
    private val gate = Semaphore(4)

    fun cached(videoId: String): Long? = cache[videoId]

    /** [videoId]'s like count, or null when it is hidden or Return YouTube Dislike cannot be reached. */
    suspend fun likes(videoId: String): Long? {
        mutex.withLock { cache[videoId] }?.let { return it }
        val found = gate.withPermit {
            mutex.withLock { cache[videoId] }?.let { return@withPermit it }
            try {
                parseYouTubeLikeCount(httpGetTextWithHeaders("$API$videoId", emptyMap()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.d { "no like count for $videoId: ${e.message}" }
                null
            }
        } ?: return null
        mutex.withLock {
            cache[videoId] = found
            if (cache.size > CACHE_SIZE) cache.remove(cache.keys.first())
        }
        return found
    }
}

/** `likes` from a Return YouTube Dislike answer (`{"id": ..., "likes": 1234, "dislikes": ..., ...}`). */
internal fun parseYouTubeLikeCount(body: String): Long? =
    runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        ?.let { (it["likes"] as? JsonPrimitive)?.longOrNull }
        ?.takeIf { it >= 0 }

/** A count as YouTube words it, rounded down: 999, 1.3K, 13K, 130K, 1.3M, 13M, 1.3B. */
internal fun formatYouTubeCount(count: Long): String {
    fun scaled(unit: Long, suffix: String): String {
        val tenths = count * 10 / unit
        return if (tenths < 100 && tenths % 10 != 0L) "${tenths / 10}.${tenths % 10}$suffix" else "${count / unit}$suffix"
    }
    return when {
        count < 1_000 -> count.toString()
        count < 1_000_000 -> scaled(1_000, "K")
        count < 1_000_000_000 -> scaled(1_000_000, "M")
        else -> scaled(1_000_000_000, "B")
    }
}

/** A card's details line ("1.2M views • 3 years ago") with the likes after the views, before the age. */
internal fun youTubeDetailsWithLikes(details: String?, likes: Long?): String? {
    if (likes == null) return details
    val label = "${formatYouTubeCount(likes)} likes"
    val parts = details?.split(" • ")?.filter { it.isNotBlank() }.orEmpty()
    val views = parts.indexOfFirst { it.contains("view", ignoreCase = true) }
    return (parts.take(views + 1) + label + parts.drop(views + 1)).joinToString(" • ")
}
