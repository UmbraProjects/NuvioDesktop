package com.nuvio.app.features.music.library

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.library.LibraryClock
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/*
 * fork:music
 *
 * Last.fm as the tag source (https://www.last.fm/api). It needs a free personal API key, set under
 * Settings > Music. With one, the Library's tags and the ladders' candidates come from here and
 * MusicBrainz is only asked when Last.fm cannot answer; with none, nothing changes.
 *
 * Last.fm's tag counts are relative (the artist's top tag is 100), where MusicBrainz's are raw
 * votes. The ladder scores tags as shares of an artist's own total, so either scale works.
 */

private val log = Logger.withTag("LastFm")

private val LASTFM_JSON = Json { ignoreUnknownKeys = true }

/** Tags weaker than this (out of 100) are noise: one listener's "seen live". */
private const val MIN_TAG_COUNT = 5

/** Most tags kept per artist, strongest first. */
private const val MAX_TAGS = 15

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toIntOrNull() }

/** Last.fm sends a list as an array, but a single item as a bare object. */
private fun JsonElement?.items(): List<JsonObject> = when (this) {
    is JsonArray -> mapNotNull { it.obj() }
    is JsonObject -> listOf(this)
    else -> emptyList()
}

/** An artist's tags from `artist.getTopTags`: the artist's name as Last.fm corrected it, and tag to weight (0 to 100). */
internal data class LastFmTags(val artist: String, val tags: Map<String, Int>)

/** Null for an error answer (unknown artist, bad key) or a body that does not parse. */
internal fun parseLastFmTopTags(body: String): LastFmTags? {
    val root = runCatching { LASTFM_JSON.parseToJsonElement(body) }.getOrNull().obj() ?: return null
    if (root["error"] != null) return null
    val top = root["toptags"].obj() ?: return null
    val artist = top["@attr"].obj()?.get("artist").str() ?: return null
    val tags = top["tag"].items()
        .mapNotNull { tag -> tag["name"].str()?.lowercase()?.let { name -> name to (tag["count"].int() ?: 0) } }
        .filter { (_, count) -> count >= MIN_TAG_COUNT }
        .distinctBy { it.first }
        .take(MAX_TAGS)
        .toMap()
    return LastFmTags(artist, tags)
}

/** The artists most tagged [tag], best first, from `tag.getTopArtists`. Empty for an error answer. */
internal fun parseLastFmTagArtists(body: String): List<String> {
    val root = runCatching { LASTFM_JSON.parseToJsonElement(body) }.getOrNull().obj() ?: return emptyList()
    if (root["error"] != null) return emptyList()
    return root["topartists"].obj()?.get("artist").items().mapNotNull { it["name"].str() }.distinct()
}

/** Last.fm's error code, or null when the body is not an error. 6 is "not found", 10 a bad key, 29 rate limited. */
internal fun lastFmErrorCode(body: String): Int? =
    runCatching { LASTFM_JSON.parseToJsonElement(body) }.getOrNull().obj()?.get("error").int()

internal object LastFmClient {
    private const val BASE = "https://ws.audioscrobbler.com/2.0/"

    /** Last.fm asks for at most five calls a second; one every 220 ms leaves room. */
    private const val GAP_MS = 220L

    /** How many of a genre's top artists get their own tags looked up for a ladder (one call each). */
    private const val CANDIDATE_LOOKUPS = 40

    private const val USER_AGENT = "NuvioDesktop/1.0 (https://github.com/UmbraProjects/NuvioDesktop)"

    private val gate = Mutex()
    private var lastCallMs = 0L

    /** Artist tags already fetched this session, by lower-case name; a ladder and the Library share them. */
    private val tagCache = HashMap<String, LastFmTags?>()
    private val cacheLock = Mutex()

    private val key: String
        get() = MusicLibraryRepository.state.value.settings.lastFmApiKey.trim()

    val isConfigured: Boolean get() = key.isNotEmpty()

    /** The body for [method] with [params], or null when Last.fm could not be reached or refused the key. Paced. */
    private suspend fun call(method: String, params: Map<String, String>): String? {
        val apiKey = key.ifEmpty { return null }
        val query = (params + mapOf("method" to method, "api_key" to apiKey, "format" to "json"))
            .entries.joinToString("&") { (k, v) -> "$k=${v.encodeURLParameter()}" }
        val body = gate.withLock {
            val wait = lastCallMs + GAP_MS - LibraryClock.nowEpochMs()
            if (wait > 0) delay(wait)
            try {
                httpGetTextWithHeaders("$BASE?$query", mapOf("User-Agent" to USER_AGENT))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // A bad key is answered with HTTP 403, which the HTTP helper raises rather than returning the body.
                if ("403" in error.message.orEmpty()) {
                    log.w { "Last.fm refused the API key (check Settings > Music)" }
                } else {
                    log.d { "$method failed: ${error.message}" }
                }
                null
            } finally {
                lastCallMs = LibraryClock.nowEpochMs()
            }
        } ?: return null
        when (lastFmErrorCode(body)) {
            10, 26 -> {
                log.w { "Last.fm refused the API key (check Settings > Music)" }
                return null
            }
            29 -> {
                log.w { "Last.fm rate limit reached on $method" }
                return null
            }
        }
        return body
    }

    /**
     * An artist's tags, strongest first. Null when Last.fm could not be reached (try MusicBrainz);
     * a [LastFmTags] with no tags when the artist is unknown or untagged there.
     */
    suspend fun artistTags(name: String): LastFmTags? {
        val cacheKey = name.trim().lowercase()
        cacheLock.withLock { if (tagCache.containsKey(cacheKey)) return tagCache[cacheKey] }
        val body = call("artist.gettoptags", mapOf("artist" to name, "autocorrect" to "1")) ?: return null
        val found = parseLastFmTopTags(body) ?: LastFmTags(name, emptyMap())
        cacheLock.withLock { tagCache[cacheKey] = found }
        return found
    }

    /**
     * The artist called [name] with its tag votes, shaped like [MusicBrainzClient.searchArtistWithTags]: a one-item
     * list, empty when Last.fm has no such artist, null when it could not be reached.
     */
    suspend fun searchArtistWithTags(name: String): List<LadderCandidate>? {
        val tags = artistTags(name) ?: return null
        if (tags.tags.isEmpty()) return emptyList()
        return listOf(LadderCandidate(id = lastFmId(tags.artist), name = tags.artist, votes = tags.tags))
    }

    /**
     * The artists tagged [tag] with their own tag votes, shaped like [MusicBrainzClient.searchByTag]. Last.fm's tag
     * chart gives names only, so the first [CANDIDATE_LOOKUPS] are looked up (paced, a few seconds the first time,
     * cached after). Null when Last.fm could not be reached.
     */
    suspend fun searchByTag(tag: String): List<LadderCandidate>? = coroutineScope {
        val body = call("tag.gettopartists", mapOf("tag" to tag, "limit" to "100")) ?: return@coroutineScope null
        val names = parseLastFmTagArtists(body).take(CANDIDATE_LOOKUPS)
        names.map { name -> async { artistTags(name) } }.awaitAll()
            .filterNotNull()
            .filter { it.tags.isNotEmpty() }
            .map { LadderCandidate(id = lastFmId(it.artist), name = it.artist, votes = it.tags) }
    }

    /** A stable id for an artist Last.fm names (it has no ids of its own for most artists). */
    private fun lastFmId(name: String): String = "lastfm:" + name.trim().lowercase()
}

/**
 * The tag source the ladders and the Library use: Last.fm first when a key is set, MusicBrainz when
 * Last.fm is not set up or could not be reached.
 */
internal object MusicTagSource {
    suspend fun searchByTag(tag: String): List<LadderCandidate>? {
        if (LastFmClient.isConfigured) LastFmClient.searchByTag(tag)?.takeIf { it.isNotEmpty() }?.let { return it }
        return MusicBrainzClient.searchByTag(tag)
    }

    suspend fun searchArtistWithTags(name: String): List<LadderCandidate>? {
        if (LastFmClient.isConfigured) LastFmClient.searchArtistWithTags(name)?.takeIf { it.isNotEmpty() }?.let { return it }
        return MusicBrainzClient.searchArtistWithTags(name)
    }
}
