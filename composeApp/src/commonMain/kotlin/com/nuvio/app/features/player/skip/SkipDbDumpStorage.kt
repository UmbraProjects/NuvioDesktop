package com.nuvio.app.features.player.skip

/**
 * Where the downloaded SkipDB export lives between runs.
 *
 * The payload is several megabytes of JSON, so it is kept as its own file rather than as a value in
 * one of the shared preference stores.
 */
internal expect object SkipDbDumpStorage {
    fun loadDump(): String?

    /** Replaces the stored export, along with the [etag] to revalidate it with next time. */
    fun saveDump(json: String, etag: String?)

    fun loadEtag(): String?

    fun loadLastSyncEpochMillis(): Long?

    fun saveLastSyncEpochMillis(epochMillis: Long)

    fun nowEpochMillis(): Long

    fun clear()
}
