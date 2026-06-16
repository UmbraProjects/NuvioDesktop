package com.nuvio.app.features.mdblist

import platform.Foundation.NSUserDefaults

internal actual object MdbListRatingsCacheStorage {
    private const val cacheKey = "mdblist_ratings_cache"
    private const val castCacheKey = "hero_cast_cache"

    actual fun load(): String? = NSUserDefaults.standardUserDefaults.stringForKey(cacheKey)

    actual fun save(json: String) {
        NSUserDefaults.standardUserDefaults.setObject(json, forKey = cacheKey)
    }

    actual fun loadCast(): String? = NSUserDefaults.standardUserDefaults.stringForKey(castCacheKey)

    actual fun saveCast(json: String) {
        NSUserDefaults.standardUserDefaults.setObject(json, forKey = castCacheKey)
    }
}
