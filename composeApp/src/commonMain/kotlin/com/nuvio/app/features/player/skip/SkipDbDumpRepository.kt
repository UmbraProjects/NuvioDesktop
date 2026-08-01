package com.nuvio.app.features.player.skip

import com.nuvio.app.features.addons.RevalidatedFileResponse
import com.nuvio.app.features.addons.httpGetFileRevalidated
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Keeps a local copy of SkipDB's published export and answers skip lookups from it.
 *
 * SkipDB publishes every approved segment in one file and rebuilds it daily, which makes a local
 * copy authoritative: an episode missing from it is one SkipDB has nothing for, so a miss needs no
 * request to confirm. That is the point of holding the export at all — coverage is concentrated in
 * a few dozen shows, so most lookups miss, and a design that still called the API on a miss would
 * pay the round trip it was meant to avoid on almost every playback.
 *
 * The API is only consulted while no export is held at all (a first run still downloading, or a
 * sync that has never succeeded), where the alternative is answering nothing.
 */
object SkipDbDumpRepository {

    private const val DUMP_URL = "https://skipdb.tv/api/dump"
    private const val SYNC_INTERVAL_MILLIS = 24L * 60L * 60L * 1000L

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logger = Logger.withTag("SkipDbDump")

    private val mutex = Mutex()
    private var index: SkipDbDumpIndex? = null
    private var syncJob: Deferred<Unit>? = null

    /**
     * Loads whatever is on disk and refreshes it if it has gone stale. Safe to call repeatedly; the
     * work happens once and later callers join the run already in flight.
     */
    suspend fun ensureSynced() {
        val existing = mutex.withLock {
            syncJob ?: scope.async { runSync() }.also { syncJob = it }
        }
        runCatching { existing.await() }
        mutex.withLock { if (syncJob === existing) syncJob = null }
    }

    /** Kicks off [ensureSynced] without waiting for it, for callers on the startup path. */
    fun syncInBackground() {
        scope.launch { runCatching { ensureSynced() } }
    }

    /**
     * Best segment of each kind for one title.
     *
     * Null means no export is held and nothing can be said either way; the caller has to tell that
     * apart from an export that simply has nothing for this episode, which comes back as a present
     * but empty [SkipDbSegments] and is a final answer needing no request.
     */
    fun lookup(
        imdbId: String,
        season: Int?,
        episode: Int?,
        durationSeconds: Long?,
    ): SkipDbSegments? {
        val loaded = index ?: return null
        if (loaded.isEmpty) return null
        return loaded.lookup(imdbId, season, episode, durationSeconds) ?: SkipDbSegments()
    }

    private suspend fun runSync() {
        if (index == null) {
            loadFromDisk()?.let { index = it }
        }

        val lastSync = SkipDbDumpStorage.loadLastSyncEpochMillis()
        val now = SkipDbDumpStorage.nowEpochMillis()
        // A clock that has moved backwards would otherwise park the next sync a day into the future.
        val isStale = lastSync == null || now - lastSync >= SYNC_INTERVAL_MILLIS || now < lastSync
        if (!isStale && index != null) return

        when (val response = httpGetFileRevalidated(DUMP_URL, SkipDbDumpStorage.loadEtag())) {
            is RevalidatedFileResponse.NotModified -> {
                if (index == null) {
                    // Revalidated against an export that is no longer on disk — deleted underneath
                    // us, or a write that failed after the etag was kept. Dropping the etag makes
                    // the next sync a full download instead of a 304 that answers nothing forever.
                    logger.w { "Export revalidated but no local copy is held; forcing a full download" }
                    SkipDbDumpStorage.clear()
                    return
                }
                SkipDbDumpStorage.saveLastSyncEpochMillis(now)
                logger.d { "Export unchanged" }
            }

            is RevalidatedFileResponse.Downloaded -> {
                val parsed = parse(response.body)
                if (parsed == null) {
                    // Keep serving the copy already held rather than replacing it with something
                    // unparseable, and leave the timestamp alone so the next start retries.
                    logger.w { "Export could not be parsed; keeping the previous copy" }
                    return
                }
                SkipDbDumpStorage.saveDump(response.body, response.etag)
                SkipDbDumpStorage.saveLastSyncEpochMillis(now)
                index = parsed
                logger.i { "Export synced: ${parsed.segmentCount} segments (${parsed.generatedAt})" }
            }

            is RevalidatedFileResponse.Failed -> {
                // Whatever is already on disk keeps answering lookups; a failed refresh only means
                // data up to a day older, which is worth far more than falling back to nothing.
                logger.w { "Export sync failed (${response.status}): ${response.message}" }
            }
        }
    }

    private suspend fun loadFromDisk(): SkipDbDumpIndex? {
        val stored = withContext(Dispatchers.Default) { SkipDbDumpStorage.loadDump() } ?: return null
        return parse(stored) ?: run {
            // A copy that cannot be read is worse than none: it would answer every lookup empty.
            // Dropping it also clears the etag, so the next sync downloads in full.
            logger.w { "Stored export was unreadable; discarding it" }
            SkipDbDumpStorage.clear()
            null
        }
    }

    private suspend fun parse(payload: String): SkipDbDumpIndex? = withContext(Dispatchers.Default) {
        runCatching { SkipDbDumpIndex.from(json.decodeFromString<SkipDbDump>(payload)) }
            .getOrNull()
            ?.takeIf { !it.isEmpty }
    }
}
