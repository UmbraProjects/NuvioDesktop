package com.nuvio.app.features.mdblist

import android.content.Context
import android.content.SharedPreferences

internal actual object MdbListRatingsCacheStorage {
    private const val preferencesName = "nuvio_mdblist_ratings_cache"
    private const val cacheKey = "ratings_cache"
    private const val castCacheKey = "cast_cache"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun load(): String? = preferences?.getString(cacheKey, null)

    actual fun save(json: String) {
        preferences?.edit()?.putString(cacheKey, json)?.apply()
    }

    actual fun loadCast(): String? = preferences?.getString(castCacheKey, null)

    actual fun saveCast(json: String) {
        preferences?.edit()?.putString(castCacheKey, json)?.apply()
    }
}
