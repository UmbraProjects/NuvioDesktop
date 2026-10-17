package com.nuvio.app.core.ui

import co.touchlab.kermit.Logger
import com.nuvio.app.features.home.HomeCatalogCacheStorage
import com.nuvio.app.features.home.HomeCatalogDiskCache
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.profiles.ProfileRepository
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The artwork prewarm's collection-folder title lists, kept across launches.
 *
 * The prewarm has to know a folder's titles before it can warm their art, and those lists were
 * only held in memory: every launch refetched the first page of every source of every warmed
 * folder. With a large community collection that was ~3,200 TMDB and Trakt requests per launch for
 * images that were already on disk. A list from the last launch is just as good for deciding what
 * to warm, and the folder screen itself always loads live, so nothing the user sees comes from here.
 *
 * Only the fields the prewarm reads are kept: artwork, and the ids the poster-service overlay keys
 * on. Stored beside Home's catalog rows and governed by the same Home Catalog Cache setting (Off
 * reads and writes nothing; turning it off deletes these too), capped at [MAX_AGE_MS] so newly
 * added titles still get warmed within the day.
 */
internal object PrewarmFolderListStore {
    private val log = Logger.withTag("PrewarmFolderList")
    private val json = Json { ignoreUnknownKeys = true }

    /** The stored list for [cacheKey] and when it was saved, if one is still fresh. */
    suspend fun read(cacheKey: String): Pair<Long, List<MetaPreview>>? {
        val lifetime = lifetimeMs()
        if (lifetime <= 0L) return null
        return withContext(Dispatchers.IO) {
            val stored = runCatching {
                HomeCatalogCacheStorage.read(ProfileRepository.activeProfileId, fileKey(cacheKey))
                    ?.let { json.decodeFromString(StoredPrewarmFolder.serializer(), it) }
            }.onFailure { log.w { "Unreadable stored folder list: ${it.message}" } }.getOrNull()
                ?: return@withContext null
            if (stored.cacheKey != cacheKey) return@withContext null
            if (System.currentTimeMillis() - stored.savedAtMs > lifetime) return@withContext null
            stored.savedAtMs to stored.items.map(StoredPrewarmItem::toMetaPreview)
        }
    }

    suspend fun write(cacheKey: String, savedAtMs: Long, items: List<MetaPreview>) {
        if (lifetimeMs() <= 0L || items.isEmpty()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val stored = StoredPrewarmFolder(
                    cacheKey = cacheKey,
                    savedAtMs = savedAtMs,
                    items = items.map(StoredPrewarmItem::from),
                )
                HomeCatalogCacheStorage.write(
                    ProfileRepository.activeProfileId,
                    fileKey(cacheKey),
                    json.encodeToString(StoredPrewarmFolder.serializer(), stored),
                )
            }.onFailure { log.w { "Could not store folder list: ${it.message}" } }
        }
    }

    private fun lifetimeMs(): Long = minOf(HomeCatalogDiskCache.lifetimeMs(), MAX_AGE_MS)

    private fun fileKey(cacheKey: String): String =
        "prewarm-folder-" + MessageDigest.getInstance("SHA-256")
            .digest(cacheKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }
            .take(32)

    private const val MAX_AGE_MS = 6L * 60L * 60L * 1000L
}

@Serializable
private data class StoredPrewarmFolder(
    val cacheKey: String,
    val savedAtMs: Long,
    val items: List<StoredPrewarmItem>,
)

@Serializable
private data class StoredPrewarmItem(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val posterFallback: String? = null,
    val banner: String? = null,
    val landscapePoster: String? = null,
    val logo: String? = null,
    val posterShape: String? = null,
    val metaLookupId: String? = null,
    val metaLookupType: String? = null,
    val addonTmdbId: Int? = null,
    val addonTvdbId: String? = null,
) {
    fun toMetaPreview(): MetaPreview = MetaPreview(
        id = id,
        type = type,
        name = name,
        poster = poster,
        posterFallback = posterFallback,
        banner = banner,
        landscapePoster = landscapePoster,
        logo = logo,
        posterShape = PosterShape.entries.firstOrNull { it.name == posterShape } ?: PosterShape.Poster,
        metaLookupId = metaLookupId,
        metaLookupType = metaLookupType,
        addonTmdbId = addonTmdbId,
        addonTvdbId = addonTvdbId,
    )

    companion object {
        fun from(item: MetaPreview) = StoredPrewarmItem(
            id = item.id,
            type = item.type,
            name = item.name,
            poster = item.poster,
            posterFallback = item.posterFallback,
            banner = item.banner,
            landscapePoster = item.landscapePoster,
            logo = item.logo,
            posterShape = item.posterShape.name,
            metaLookupId = item.metaLookupId,
            metaLookupType = item.metaLookupType,
            addonTmdbId = item.addonTmdbId,
            addonTvdbId = item.addonTvdbId,
        )
    }
}
