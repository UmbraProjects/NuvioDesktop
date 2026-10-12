package com.nuvio.app.features.discover

/** Titles marked "Not interested" on Discover, per profile, as `type:id` keys. */
internal expect object DiscoverNotInterestedStorage {
    fun load(): Set<String>
    fun save(keys: Set<String>)
}
