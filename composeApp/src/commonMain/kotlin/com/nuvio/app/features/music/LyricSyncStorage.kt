package com.nuvio.app.features.music

/** Where the lyric offsets the person set live on disk: one JSON payload (see [encodeSavedOffsets]). */
internal expect object LyricSyncStorage {
    fun load(): String?
    fun save(payload: String)
}
