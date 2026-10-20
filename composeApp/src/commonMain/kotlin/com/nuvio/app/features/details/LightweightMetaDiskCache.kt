package com.nuvio.app.features.details

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.CoalescingCachePersister
import com.nuvio.app.core.storage.decodeJsonMap
import com.nuvio.app.core.storage.encodeJsonMap
import com.nuvio.app.core.ui.ImageCacheSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/** Streamed in and out (see encodeJsonMap): the file is ~8 MB on a well-used install. */
internal expect object LightweightMetaCacheStorage {
    fun <T> read(decode: (InputStream) -> T): T?
    fun write(encode: (OutputStream) -> Unit)
    fun clear()
}

/**
 * The lightweight meta cache ([MetaDetailsRepository.fetchLightweightMeta]) kept across launches.
 *
 * In memory only, every launch paid for every title again: the artwork prewarm walks ~1,100
 * focused-hero titles per pass, and each one is an addon meta request, a TMDB request and, for
 * shows in TVDB mode, a TVDB request - measured at ~1,300 addon and ~320 TVDB requests in a
 * 3-minute session (2026-10-14), nearly all of them the same titles as the launch before.
 *
 * Only what the lightweight consumers read is kept - artwork, text, rating, runtime, genres,
 * country, language, ids - plus a slim episode list (id, title, date, coordinates), because Random
 * Play counts episodes and the player's episode resolver reads ids and coordinates. No cast,
 * trailers, thumbnails or streams: the lightweight path never had them to begin with.
 *
 * Every entry carries a fingerprint of what shaped it (TMDB settings, hero image source, the TVDB
 * key, the enabled addons). An entry made under different settings is a miss, so changing the
 * metadata language or an addon takes effect at once instead of when the entries expire. The
 * lifetime is the Metadata Cache setting in Advanced; Off reads and writes nothing.
 */
internal object LightweightMetaDiskCache {
    private val log = Logger.withTag("LightweightMetaDisk")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val mutex = Mutex()
    private var entries: MutableMap<String, StoredLightweightMeta>? = null

    /** Generous next to the ~1,100 titles a full prewarm pass touches; oldest go first. */
    private const val MAX_ENTRIES = 5_000
    private const val WRITE_DELAY_MS = 30_000L
    private const val HOUR_MS = 60L * 60L * 1000L

    private val persister = CoalescingCachePersister(tag = "lightweightMeta", writeDelayMs = WRITE_DELAY_MS) {
        val snapshot = mutex.withLock { entries?.toMap() } ?: return@CoalescingCachePersister
        LightweightMetaCacheStorage.write { output ->
            json.encodeJsonMap(output, snapshot, StoredLightweightMeta.serializer()) { it }
        }
    }

    private fun lifetimeMs(): Long {
        ImageCacheSettingsRepository.ensureLoaded()
        return ImageCacheSettingsRepository.settings.value.metadataCacheHours * HOUR_MS
    }

    suspend fun get(requestKey: String, context: String): MetaDetails? {
        val lifetime = lifetimeMs()
        if (lifetime <= 0L) return null
        val now = System.currentTimeMillis()
        return withContext(Dispatchers.Default) {
            mutex.withLock {
                val stored = ensureLoaded()[requestKey] ?: return@withLock null
                if (stored.context != context || now - stored.savedAtMs > lifetime) null else stored.toMetaDetails()
            }
        }
    }

    suspend fun put(requestKey: String, context: String, meta: MetaDetails) {
        if (lifetimeMs() <= 0L) return
        val stored = meta.toStored(context = context, savedAtMs = System.currentTimeMillis())
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val map = ensureLoaded()
                map.remove(requestKey)
                map[requestKey] = stored
                if (map.size > MAX_ENTRIES) {
                    map.entries.sortedBy { it.value.savedAtMs }
                        .take(map.size - MAX_ENTRIES)
                        .forEach { map.remove(it.key) }
                }
            }
        }
        persister.schedule()
    }

    /** Drops everything, in memory and on disk. Called when the setting is turned off. */
    suspend fun clear() {
        mutex.withLock { entries = mutableMapOf() }
        withContext(Dispatchers.Default) { LightweightMetaCacheStorage.clear() }
    }

    /** Writes any pending changes now. For the exit path. */
    suspend fun flushPendingWrites() {
        persister.flush()
    }

    private fun ensureLoaded(): MutableMap<String, StoredLightweightMeta> {
        entries?.let { return it }
        val lifetime = lifetimeMs()
        val now = System.currentTimeMillis()
        val loaded = runCatching {
            LightweightMetaCacheStorage.read { input ->
                json.decodeJsonMap(input, StoredLightweightMeta.serializer())
            }.orEmpty()
        }.onFailure { error ->
            log.w { "Discarding unreadable lightweight meta cache: ${error.message}" }
        }.getOrDefault(emptyMap())
        // Expired entries are dropped on load; the next write leaves them out of the file too.
        val live = loaded.filterValues { now - it.savedAtMs <= lifetime }
        log.i { "Loaded ${live.size} of ${loaded.size} cached lightweight meta entries" }
        return LinkedHashMap(live).also { entries = it }
    }
}

@Serializable
internal data class StoredLightweightMeta(
    val context: String,
    val savedAtMs: Long,
    val id: String,
    val type: String,
    val name: String,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val malId: String? = null,
    val tvdbId: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val lastAirDate: String? = null,
    val status: String? = null,
    val imdbRating: String? = null,
    val ageRating: String? = null,
    val runtime: String? = null,
    val genres: List<String> = emptyList(),
    val country: String? = null,
    val language: String? = null,
    val hasScheduledVideos: Boolean = false,
    val identityTrusted: Boolean = true,
    val videos: List<StoredLightweightVideo> = emptyList(),
)

@Serializable
internal data class StoredLightweightVideo(
    val id: String,
    val title: String = "",
    val released: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

internal fun MetaDetails.toStored(context: String, savedAtMs: Long) = StoredLightweightMeta(
    context = context,
    savedAtMs = savedAtMs,
    id = id,
    type = type,
    name = name,
    tmdbId = tmdbId,
    imdbId = imdbId,
    malId = malId,
    tvdbId = tvdbId,
    poster = poster,
    background = background,
    logo = logo,
    description = description,
    releaseInfo = releaseInfo,
    lastAirDate = lastAirDate,
    status = status,
    imdbRating = imdbRating,
    ageRating = ageRating,
    runtime = runtime,
    genres = genres,
    country = country,
    language = language,
    hasScheduledVideos = hasScheduledVideos,
    identityTrusted = imdbTmdbIdentityTrusted,
    videos = videos.map { video ->
        StoredLightweightVideo(
            id = video.id,
            title = video.title,
            released = video.released,
            season = video.season,
            episode = video.episode,
        )
    },
)

internal fun StoredLightweightMeta.toMetaDetails() = MetaDetails(
    id = id,
    type = type,
    name = name,
    tmdbId = tmdbId,
    imdbId = imdbId,
    malId = malId,
    tvdbId = tvdbId,
    poster = poster,
    background = background,
    logo = logo,
    description = description,
    releaseInfo = releaseInfo,
    lastAirDate = lastAirDate,
    status = status,
    imdbRating = imdbRating,
    ageRating = ageRating,
    runtime = runtime,
    genres = genres,
    country = country,
    language = language,
    hasScheduledVideos = hasScheduledVideos,
    imdbTmdbIdentityTrusted = identityTrusted,
    videos = videos.map { video ->
        MetaVideo(
            id = video.id,
            title = video.title,
            released = video.released,
            season = video.season,
            episode = video.episode,
        )
    },
)
