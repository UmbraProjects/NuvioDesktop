package com.nuvio.app.features.youtube

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.profiles.ProfilePinCrypto
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/*
 * fork:youtube-mode
 *
 * DeArrow (https://dearrow.ajay.app): titles and thumbnails its users wrote in place of a video's clickbait.
 * Used by the YouTube tab's cards (and the player's title) while Settings > YouTube account > DeArrow is on;
 * a video with nothing submitted keeps YouTube's own.
 *
 * Looked up by SHA-256 hash prefix like SponsorBlock, so the server never learns which video is shown.
 * Thumbnails are frames DeArrow's thumbnail service cuts at the voted timestamp.
 */

data class YouTubeBranding(val title: String?, val thumbnailUrl: String?)

private val log = Logger.withTag("YouTubeDeArrow")
private val json = Json { ignoreUnknownKeys = true }

private const val API = "https://sponsor.ajay.app/api/branding/"
private const val THUMBNAIL_API = "https://dearrow-thumb.ajay.app/api/v1/getThumbnail"
private const val HASH_PREFIX_LENGTH = 4
private const val CACHE_SIZE = 600

internal object YouTubeDeArrow {
    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, YouTubeBranding>()

    /** A grid page shows a dozen cards at once; a few lookups at a time is plenty. */
    private val gate = Semaphore(4)

    /** What is already known for [videoId] without asking. */
    fun cached(videoId: String): YouTubeBranding? = cache[videoId]

    /** The community title and thumbnail for [videoId]; both null when it has none or DeArrow cannot be reached. */
    suspend fun branding(videoId: String): YouTubeBranding {
        mutex.withLock { cache[videoId] }?.let { return it }
        val found = gate.withPermit {
            mutex.withLock { cache[videoId] }?.let { return@withPermit it }
            val prefix = ProfilePinCrypto.sha256Hex(videoId).take(HASH_PREFIX_LENGTH).lowercase()
            try {
                parseYouTubeBranding(httpGetTextWithHeaders("$API$prefix", emptyMap()), videoId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.d { "no branding for $videoId: ${e.message}" }
                YouTubeBranding(null, null)
            }
        }
        mutex.withLock {
            cache[videoId] = found
            if (cache.size > CACHE_SIZE) cache.remove(cache.keys.first())
        }
        return found
    }
}

/**
 * The title and thumbnail DeArrow would show for [videoId] in a hash-prefix answer
 * (`{"<videoID>": {"titles": [{title, original, votes, locked}], "thumbnails": [{timestamp, original, votes, locked}]}}`,
 * best first). The first entry that is locked or not voted down wins; when that is YouTube's own
 * (`original`), the community chose to keep it and so does this. A title's leading `>` (its "do not
 * auto-format" mark) is dropped.
 */
internal fun parseYouTubeBranding(body: String, videoId: String): YouTubeBranding {
    val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
    val video = root?.get(videoId) as? JsonObject ?: return YouTubeBranding(null, null)
    val title = (video["titles"] as? JsonArray).orEmpty()
        .mapNotNull { it as? JsonObject }
        .firstOrNull { it.isTrusted() }
        ?.takeUnless { it.isOriginal() }
        ?.get("title")?.text()
        ?.removePrefix(">")?.trim()
        ?.takeIf { it.isNotEmpty() }
    val thumbnail = (video["thumbnails"] as? JsonArray).orEmpty()
        .mapNotNull { it as? JsonObject }
        .firstOrNull { it.isTrusted() }
        ?.takeUnless { it.isOriginal() }
        ?.let { (it["timestamp"] as? JsonPrimitive)?.doubleOrNull }
        ?.let { time -> "$THUMBNAIL_API?videoID=${videoId.encodeURLParameter()}&time=$time" }
    return YouTubeBranding(title, thumbnail)
}

private fun JsonObject.isOriginal(): Boolean = (this["original"] as? JsonPrimitive)?.booleanOrNull == true

private fun JsonObject.isTrusted(): Boolean {
    val locked = (this["locked"] as? JsonPrimitive)?.booleanOrNull == true
    val votes = (this["votes"] as? JsonPrimitive)?.intOrNull ?: 0
    return locked || votes >= 0
}

private fun kotlinx.serialization.json.JsonElement.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
