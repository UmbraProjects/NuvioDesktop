package com.nuvio.app.features.qualicache

internal expect object QualiCacheQualityCacheStorage {
    fun load(): String?
    fun save(json: String)
}
