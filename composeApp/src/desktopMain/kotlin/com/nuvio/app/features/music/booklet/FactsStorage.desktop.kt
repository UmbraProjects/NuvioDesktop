package com.nuvio.app.features.music.booklet

import com.nuvio.app.core.storage.DesktopStorage

/** 16 files, a key in the one its hash picks, so a flush rewrites a sixteenth of the facts, not all of them. */
internal actual object FactsStorage {
    private const val SHARDS = 16
    private val stores = List(SHARDS) { DesktopStorage.store("nuvio_music_facts_$it") }

    private fun shard(key: String) = stores[key.hashCode() and (SHARDS - 1)]

    actual fun keys(): Set<String> = stores.flatMapTo(HashSet()) { it.keys() }

    actual fun get(key: String): String? = shard(key).getString(key)

    actual fun put(key: String, value: String?) {
        shard(key).putString(key, value)
    }
}
