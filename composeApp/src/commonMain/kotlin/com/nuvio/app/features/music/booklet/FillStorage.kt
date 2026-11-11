package com.nuvio.app.features.music.booklet

/** Where the background filler keeps its switch, daily cap, request count and play counts (see FactsFiller). Nothing here expires. */
internal expect object FillStorage {
    fun get(key: String): String?
    fun put(key: String, value: String?)
    fun keys(): Set<String>
}
