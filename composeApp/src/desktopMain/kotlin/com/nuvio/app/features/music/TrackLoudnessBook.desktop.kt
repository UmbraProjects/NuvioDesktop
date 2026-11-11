package com.nuvio.app.features.music

import com.nuvio.app.core.storage.DesktopStorage

/**
 * fork:loudness - the loudness measured for each track, kept on disk under the track's identity (`yt:<video id>`,
 * `addon:<track id>`), so the next play starts at the right level and is never measured twice. At most 3000 tracks;
 * the oldest measurements go first.
 */
internal object TrackLoudnessBook {
    private const val CAPACITY = 3000

    /** Evicts in batches, so a full book does not read every entry on every save. */
    private const val EVICT_SLACK = 100

    private val store = DesktopStorage.store("nuvio_music_loudness")

    /** The measured integrated loudness for [key] in LUFS, or null when the track has not been measured. */
    fun get(key: String): Double? = decodeMeasuredLoudness(store.getString(key))?.lufs

    fun put(key: String, lufs: Double) {
        store.putString(key, encodeMeasuredLoudness(MeasuredLoudness(lufs, System.currentTimeMillis())))
        val keys = store.keys()
        if (keys.size > CAPACITY + EVICT_SLACK) {
            val ages = keys.associateWith { decodeMeasuredLoudness(store.getString(it))?.atEpochMs ?: 0L }
            loudnessKeysToEvict(ages, CAPACITY).forEach { store.putString(it, null) }
        }
    }
}

internal actual fun hasKeptLoudness(loudnessKey: String): Boolean = TrackLoudnessBook.get(loudnessKey) != null
