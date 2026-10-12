package com.nuvio.app.core.ui

import coil3.annotation.ExperimentalCoilApi
import coil3.fetch.FetchResult
import coil3.network.ConcurrentRequestStrategy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * One network fetch per image URL at a time.
 *
 * Coil runs every request on its own, so a poster that is on screen in two places at once — the
 * hero and a row, the same title in two rows, a visible card and the prewarm — is downloaded once
 * per place, all at the same moment. On 2026-09-27, 111 poster URLs were fetched two to four
 * times within the same few milliseconds, each paying a ~2.5 s server render. Coil ships
 * `DeDupeConcurrentRequestStrategy` for this, but its followers simply run the fetch again once
 * the first finishes, and for a `no-store` poster (PostersPlus while a badge is still being looked
 * up) the disk strategy refuses the copy just written, so every follower downloads it anyway.
 *
 * Here the first request for a key runs; later ones wait for it. If it succeeded they fetch inside
 * [CoalescedFollower], which tells [ImageDiskCacheStrategy] to serve the entry the first request
 * just wrote, whatever its headers say. If it failed or was cancelled, each waiter fetches on its
 * own, exactly as before.
 *
 * Keyed by Coil's disk-cache key (the URL unless a request overrides it), which is what
 * `NetworkFetcher` passes in. Disk hits pass through here too; they finish in milliseconds, so a
 * waiter behind one loses nothing.
 */
@OptIn(ExperimentalCoilApi::class)
internal class CoalescingRequestStrategy : ConcurrentRequestStrategy {

    private val lock = Any()
    private val inFlight = HashMap<String, CompletableDeferred<Boolean>>()

    override suspend fun apply(key: String, block: suspend () -> FetchResult): FetchResult {
        // A follower's own fetch must not queue behind itself.
        if (currentCoroutineContext()[CoalescedFollower] != null) return block()

        var leading: CompletableDeferred<Boolean>? = null
        val waitingOn = synchronized(lock) {
            inFlight[key] ?: run {
                leading = CompletableDeferred<Boolean>().also { inFlight[key] = it }
                null
            }
        }

        if (waitingOn != null) {
            return if (waitingOn.await()) withContext(CoalescedFollower.Instance) { block() } else block()
        }

        val done = leading!!
        var succeeded = false
        try {
            return block().also { succeeded = true }
        } finally {
            synchronized(lock) { if (inFlight[key] === done) inFlight.remove(key) }
            done.complete(succeeded)
        }
    }

    internal fun inFlightCount(): Int = synchronized(lock) { inFlight.size }
}

/**
 * Marks a fetch that waited behind an identical one which has just succeeded, so the disk entry it
 * reads was written moments ago by that request.
 */
internal class CoalescedFollower private constructor() : AbstractCoroutineContextElement(Key) {
    // A separate companion key: an object passing itself as its own key would hand the
    // superclass null while it is still being constructed.
    companion object Key : CoroutineContext.Key<CoalescedFollower> {
        val Instance = CoalescedFollower()
    }
}

/** The one instance every fetcher shares; coalescing only works across a single map. */
internal val SharedImageRequestCoalescing = CoalescingRequestStrategy()
