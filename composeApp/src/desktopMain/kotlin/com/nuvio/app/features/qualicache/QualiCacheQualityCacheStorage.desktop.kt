package com.nuvio.app.features.qualicache

import com.nuvio.app.core.storage.DesktopStorage

internal actual object QualiCacheQualityCacheStorage {
    private const val cacheKey = "quality_cache"
    private val store = DesktopStorage.store("nuvio_qualicache_quality_cache")

    actual fun load(): String? = store.getString(cacheKey)

    actual fun save(json: String) {
        store.putString(cacheKey, json)
    }
}
