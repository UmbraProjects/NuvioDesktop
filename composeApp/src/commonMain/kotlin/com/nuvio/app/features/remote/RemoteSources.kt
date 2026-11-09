package com.nuvio.app.features.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * fork:phone-remote sources — the source list on screen (the picker, or the player's sources panel), as the phone shows
 * it, in the order drawn. The phone picks one by its place in that order, which plays it exactly as a click on it would
 * (through the same hook voice control uses, `VoiceScreenTargets.sources`).
 */
data class RemoteSource(
    /** "Torrentio 4K": the row's name, as on the card. */
    val name: String,
    /** The card's description: the file name, size, seeders and the like, one line per line. */
    val detail: String?,
    /** The addon or the source it came from. */
    val addon: String,
    val sizeBytes: Long?,
    /** Cached on the debrid service, or already resolved by it: plays at once. */
    val instant: Boolean,
    /** False for a row the TV shows greyed out (a torrent with no debrid and no P2P, say). */
    val playable: Boolean,
)

data class RemoteSourceList(
    /** What the sources are for: "Blade Runner 2049", "Severance · S2 E5". */
    val title: String,
    val loading: Boolean,
    val sources: List<RemoteSource>,
)

object RemoteSources {
    private val _current = MutableStateFlow<RemoteSourceList?>(null)

    /** The list while a source list is on screen; null otherwise. */
    val current: StateFlow<RemoteSourceList?> = _current.asStateFlow()

    /** The [StreamList] showing it, so a list that went away does not clear its successor. */
    private var owner: Any? = null

    fun publish(owner: Any, list: RemoteSourceList) {
        this.owner = owner
        _current.value = list
    }

    fun clear(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        _current.value = null
    }
}
