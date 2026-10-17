package com.nuvio.app.core.ui

import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.nuvio.app.core.storage.DesktopStorage

internal actual object ImageCacheSettingsStorage {
    private val store = DesktopStorage.store("nuvio_image_cache_settings")

    actual fun loadDiskCacheGb(): Int? = store.getInt("disk_cache_gb")
    actual fun saveDiskCacheGb(value: Int) = store.putInt("disk_cache_gb", value)
    actual fun loadPrewarmEnabled(): Boolean? = store.getBoolean("prewarm_enabled")
    actual fun savePrewarmEnabled(value: Boolean) = store.putBoolean("prewarm_enabled", value)
    actual fun loadPrewarmPosters(): Boolean? = store.getBoolean("prewarm_posters")
    actual fun savePrewarmPosters(value: Boolean) = store.putBoolean("prewarm_posters", value)
    actual fun loadPrewarmLogos(): Boolean? = store.getBoolean("prewarm_logos")
    actual fun savePrewarmLogos(value: Boolean) = store.putBoolean("prewarm_logos", value)
    actual fun loadPrewarmBackdrops(): Boolean? = store.getBoolean("prewarm_backdrops")
    actual fun savePrewarmBackdrops(value: Boolean) = store.putBoolean("prewarm_backdrops", value)
    actual fun loadPrewarmCollectionContents(): Boolean? = store.getBoolean("prewarm_collection_contents")
    actual fun savePrewarmCollectionContents(value: Boolean) = store.putBoolean("prewarm_collection_contents", value)
    actual fun loadMetadataCacheHours(): Int? = store.getInt("metadata_cache_hours")
    actual fun saveMetadataCacheHours(value: Int) = store.putInt("metadata_cache_hours", value)
    actual fun loadHomeCatalogCacheHours(): Int? = store.getInt("home_catalog_cache_hours")
    actual fun saveHomeCatalogCacheHours(value: Int) = store.putInt("home_catalog_cache_hours", value)
    actual fun loadMemoryUse(): String? = store.getString("memory_use")
    actual fun saveMemoryUse(value: String) = store.putString("memory_use", value)

    actual fun usedBytes(): Long? =
        runCatching { SingletonImageLoader.get(PlatformContext.INSTANCE).diskCache?.size }.getOrNull()
}
