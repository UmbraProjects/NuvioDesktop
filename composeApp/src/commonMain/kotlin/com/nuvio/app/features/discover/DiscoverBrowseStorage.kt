package com.nuvio.app.features.discover

/** Discover row 1's built-in TMDB browser: whether it is selected, and its filters. */
internal expect object DiscoverBrowseStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}
