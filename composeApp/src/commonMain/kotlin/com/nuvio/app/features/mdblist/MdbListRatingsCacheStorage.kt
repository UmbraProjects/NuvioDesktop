package com.nuvio.app.features.mdblist

internal expect object MdbListRatingsCacheStorage {
    fun load(): String?
    fun save(json: String)
    fun loadCast(): String?
    fun saveCast(json: String)
}
