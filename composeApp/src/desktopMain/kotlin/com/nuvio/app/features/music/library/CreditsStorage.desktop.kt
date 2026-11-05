package com.nuvio.app.features.music.library
// FORK UI (Spyder): Nick's album page lenses, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import com.nuvio.app.core.storage.DesktopStorage

/**
 * 16 files, a key in the one its hash picks, so saving one album's credits rewrites a sixteenth of them. They used to be one
 * 27 MB file that was written out whole after every change while the credits sweep ran.
 *
 * Whatever the old single `nuvio_music_credits` store still holds is moved into the shards the first time this is used.
 */
internal actual object CreditsStorage {
    private const val SHARDS = 16
    private val stores = List(SHARDS) { DesktopStorage.store("nuvio_music_credits_$it") }

    private fun shard(key: String) = stores[key.hashCode() and (SHARDS - 1)]

    init {
        val old = DesktopStorage.store("nuvio_music_credits")
        for (key in old.keys().toList()) {
            old.getString(key)?.let { value -> if (shard(key).getString(key) == null) shard(key).putString(key, value) }
            old.putString(key, null)
        }
    }

    actual fun get(key: String): String? = shard(key).getString(key)

    actual fun put(key: String, value: String?) {
        shard(key).putString(key, value)
    }

    actual fun keys(): Set<String> = stores.flatMapTo(HashSet()) { it.keys() }
}
