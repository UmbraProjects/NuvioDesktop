package com.nuvio.app.features.home

import co.touchlab.kermit.Logger
import com.nuvio.app.core.ui.ImageCacheSettingsRepository
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One file per catalog per profile; see the desktop actual for where they live. */
internal expect object HomeCatalogCacheStorage {
    fun read(profileId: Int, fileKey: String): String?
    fun write(profileId: Int, fileKey: String, contents: String)
    fun clearAll()
}

/**
 * Home's catalog rows kept across launches, so a launch can show last time's rows at once and
 * refetch them behind the scenes instead of opening on an empty Home.
 *
 * What is kept is the catalog's raw first-page response, not the parsed row: a restored row is the
 * same response run through the same parser ([com.nuvio.app.features.catalog.catalogPageFromPayload])
 * a live fetch uses, so it can never drift from what a fresh launch would build. Only addon catalog
 * rows go through here. Continue Watching, Next Up and Upcoming come from the watch history and are
 * never part of it.
 *
 * Scoped by profile (different profiles, different addons) and checked against a hash of the
 * request URL, so a changed addon configuration is a miss rather than a wrong row. The lifetime is
 * the Home Catalog Cache setting in Advanced; Off reads and writes nothing.
 *
 * Beside the rows sits the list of row definitions they belong to ([readDefinitions]). Addon
 * manifests are fetched over the network at startup, so without it nothing knows which rows Home
 * has until each addon answers - too late to restore before Home opens. Those definitions carry
 * the manifest URLs, which can embed account tokens; they live in the same account folder as the
 * installed-addon list that already holds the same URLs.
 */
internal object HomeCatalogDiskCache {
    private val log = Logger.withTag("HomeCatalogDisk")
    private val json = Json { ignoreUnknownKeys = true }
    private const val HOUR_MS = 60L * 60L * 1000L

    fun lifetimeMs(): Long {
        ImageCacheSettingsRepository.ensureLoaded()
        return ImageCacheSettingsRepository.settings.value.homeCatalogCacheHours * HOUR_MS
    }

    /** The stored response for [sectionKey] if it was made for [url] and is still within the lifetime. */
    suspend fun read(profileId: Int, sectionKey: String, url: String): StoredHomeCatalog? {
        val lifetime = lifetimeMs()
        if (lifetime <= 0L) return null
        return withContext(Dispatchers.IO) {
            val stored = runCatching {
                HomeCatalogCacheStorage.read(profileId, fileKey(sectionKey))
                    ?.let { json.decodeFromString<StoredHomeCatalog>(it) }
            }.onFailure { log.w { "Unreadable cached row $sectionKey: ${it.message}" } }.getOrNull()
                ?: return@withContext null
            val fresh = System.currentTimeMillis() - stored.savedAtMs <= lifetime
            stored.takeIf { fresh && it.urlHash == hash(url) }
        }
    }

    suspend fun write(profileId: Int, sectionKey: String, url: String, payload: String) {
        if (lifetimeMs() <= 0L) return
        withContext(Dispatchers.IO) {
            runCatching {
                val stored = StoredHomeCatalog(
                    urlHash = hash(url),
                    savedAtMs = System.currentTimeMillis(),
                    payload = payload,
                )
                HomeCatalogCacheStorage.write(profileId, fileKey(sectionKey), json.encodeToString(StoredHomeCatalog.serializer(), stored))
            }.onFailure { log.w { "Could not cache row $sectionKey: ${it.message}" } }
        }
    }

    /** The rows Home had when every addon had last loaded, in no particular order; null when unknown. */
    suspend fun readDefinitions(profileId: Int): List<HomeCatalogDefinition>? {
        if (lifetimeMs() <= 0L) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                HomeCatalogCacheStorage.read(profileId, DEFINITIONS_FILE_KEY)
                    ?.let { json.decodeFromString<List<StoredHomeCatalogDefinition>>(it) }
                    ?.map(StoredHomeCatalogDefinition::toDefinition)
            }.onFailure { log.w { "Unreadable cached row list: ${it.message}" } }.getOrNull()
        }
    }

    suspend fun writeDefinitions(profileId: Int, definitions: List<HomeCatalogDefinition>) {
        if (lifetimeMs() <= 0L) return
        withContext(Dispatchers.IO) {
            runCatching {
                HomeCatalogCacheStorage.write(
                    profileId,
                    DEFINITIONS_FILE_KEY,
                    json.encodeToString(definitions.map(HomeCatalogDefinition::toStored)),
                )
            }.onFailure { log.w { "Could not cache the row list: ${it.message}" } }
        }
    }

    /** Drops every profile's rows. Called when the setting is turned off. */
    suspend fun clear() {
        withContext(Dispatchers.IO) { runCatching { HomeCatalogCacheStorage.clearAll() } }
    }

    private const val DEFINITIONS_FILE_KEY = "definitions"

    private fun fileKey(sectionKey: String): String = hash(sectionKey).take(32)

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

@Serializable
internal data class StoredHomeCatalog(
    val urlHash: String,
    val savedAtMs: Long,
    val payload: String,
)

@Serializable
internal data class StoredHomeCatalogDefinition(
    val key: String,
    val defaultTitle: String,
    val addonName: String,
    val manifestUrl: String,
    val type: String,
    val catalogId: String,
    val genre: String? = null,
    val supportsPagination: Boolean = false,
) {
    fun toDefinition() = HomeCatalogDefinition(
        key = key,
        defaultTitle = defaultTitle,
        addonName = addonName,
        manifestUrl = manifestUrl,
        type = type,
        catalogId = catalogId,
        genre = genre,
        supportsPagination = supportsPagination,
    )
}

internal fun HomeCatalogDefinition.toStored() = StoredHomeCatalogDefinition(
    key = key,
    defaultTitle = defaultTitle,
    addonName = addonName,
    manifestUrl = manifestUrl,
    type = type,
    catalogId = catalogId,
    genre = genre,
    supportsPagination = supportsPagination,
)
