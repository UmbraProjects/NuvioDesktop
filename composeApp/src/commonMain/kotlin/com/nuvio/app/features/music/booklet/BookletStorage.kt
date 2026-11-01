package com.nuvio.app.features.music.booklet

/** Where the album booklets live on disk: one text value per key (see [encodeBookletEntry]). */
internal expect object BookletStorage {
    fun keys(): Set<String>
    fun get(key: String): String?

    /** Stores [value]; null removes the key. */
    fun put(key: String, value: String?)
}
