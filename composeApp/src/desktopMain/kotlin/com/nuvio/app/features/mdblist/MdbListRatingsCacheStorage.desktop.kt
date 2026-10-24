package com.nuvio.app.features.mdblist

import com.nuvio.app.core.storage.DesktopStorage

internal actual object MdbListRatingsCacheStorage {
    private const val cacheKey = "ratings_cache"
    private const val castCacheKey = "cast_cache"
    private val store = DesktopStorage.store("nuvio_mdblist_ratings_cache")

    actual fun load(): String? = store.getString(cacheKey)

    actual fun save(json: String) {
        store.putString(cacheKey, json)
    }

    actual fun loadCast(): String? = store.getString(castCacheKey)

    actual fun saveCast(json: String) {
        store.putString(castCacheKey, json)
    }
}
