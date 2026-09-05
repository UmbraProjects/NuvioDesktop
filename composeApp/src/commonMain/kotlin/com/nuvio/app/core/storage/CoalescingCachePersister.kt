package com.nuvio.app.core.storage

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Batches "rewrite the whole cache" work so a burst of changes costs one write instead of one each.
 *
 * The caches this exists for are single-blob stores: one JSON string per cache, rewritten in full
 * whenever any entry changes. That is fine when entries arrive occasionally and ruinous when they
 * arrive in a burst, which is exactly what hero enrichment does — it walks a row of titles and adds
 * one cast entry and one ratings entry per title. Measured on the real files, each of those entries
 * cost a ~3 ms re-encode of the whole map plus a ~20 ms rewrite of the whole 3.4 MB properties file,
 * and because neither service switches dispatcher, both ran on the Compose effect dispatcher — the
 * AWT event thread. Two caches, so up to ~46 ms of blocked UI thread per enriched title.
 *
 * So: mutate in memory now, persist later, on [Dispatchers.IO] and at most once per [writeDelayMs].
 *
 * The delay is deliberately a *fixed* window from the moment the cache first went dirty, not a
 * debounce that restarts on every change. A restarting timer would be starved by exactly the burst
 * this is here to survive, and could put off the write indefinitely; this way a steady stream of
 * changes still reaches disk on a predictable cadence.
 *
 * What this trades away is durability between windows: a hard crash loses up to [writeDelayMs] of
 * entries. That is the right trade for these two, whose contents are a re-fetchable cache rather
 * than user data — and the exit path calls [flush], so an ordinary quit loses nothing.
 */
internal class CoalescingCachePersister(
    private val tag: String,
    private val writeDelayMs: Long = DEFAULT_WRITE_DELAY_MS,
    /**
     * Writes the cache out. Runs off the caller's thread with no lock held, so it is free to take
     * the owning cache's mutex to snapshot, and to do the encode itself — the encode is half the
     * cost being batched here, so doing it inside this lambda rather than at the call site is the
     * point, not an implementation detail.
     */
    private val persist: suspend () -> Unit,
) {
    private val log = Logger.withTag("CachePersist")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()
    private var scheduled: Job? = null
    private var dirty = false

    /**
     * Records that the cache changed and makes sure a write is coming.
     *
     * Returns immediately and never touches the caller's lock, so it is safe to call from inside the
     * `withLock` block that just mutated the cache — which is where it belongs, since that is the
     * point at which the cache is known to be dirty.
     */
    fun schedule() {
        scope.launch {
            stateMutex.withLock {
                dirty = true
                if (scheduled != null) return@withLock
                scheduled = scope.launch {
                    delay(writeDelayMs)
                    writeIfDirty()
                }
            }
        }
    }

    /**
     * Writes any pending changes now and waits for them to land.
     *
     * For the exit path. Cancels a scheduled write first so the two cannot both run.
     */
    suspend fun flush() {
        stateMutex.withLock {
            scheduled?.cancel()
            scheduled = null
        }
        writeIfDirty()
    }

    private suspend fun writeIfDirty() {
        val shouldWrite = stateMutex.withLock {
            scheduled = null
            val wasDirty = dirty
            // Cleared before the write, not after: a change arriving mid-write must leave the cache
            // marked dirty so it schedules another window, rather than being swallowed by this one.
            dirty = false
            wasDirty
        }
        if (!shouldWrite) return
        runCatching { persist() }
            .onFailure { error ->
                // Put the flag back, so a transient failure retries on the next change instead of
                // silently dropping everything accumulated since the last successful write.
                stateMutex.withLock { dirty = true }
                log.w { "$tag: deferred cache write failed: ${error.message}" }
            }
    }

    private companion object {
        /**
         * Long enough that a hero prefetch walking a row collapses into a handful of writes, short
         * enough that the crash-loss window stays small for something a user might have spent real
         * API budget filling.
         */
        const val DEFAULT_WRITE_DELAY_MS = 3_000L
    }
}
