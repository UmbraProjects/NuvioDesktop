package com.nuvio.app.features.home.components

import coil3.Extras
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.tmdb.fullSizeTmdbBackdrop
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the hero draws for [item]: its backdrop at full TMDB size, else its poster. */
internal fun heroBackdropModel(item: MetaPreview): String? =
    fullSizeTmdbBackdrop(item.banner?.takeIf(String::isNotBlank))
        ?: item.poster?.takeIf(String::isNotBlank)

/**
 * Decodes the backdrops of the cards either side of the focused one, so the hero finds them in the
 * memory cache when focus moves.
 *
 * Every hero backdrop is already on disk (the artwork prewarm), but the disk holds JPEG: showing one
 * still costs a ~140 ms decode of a 4K file, and it was that decode, not the disk, that made some
 * backdrops arrive late while scrolling (2026-10-16 log: fetch 0–2 ms, decode 160–560 ms).
 *
 * One prefetch at a time, so it never competes with the focused hero for more than one core. A new
 * focus replaces the queue; a decode already running finishes and lands in the cache, since that is
 * usually the card the user is moving onto. The hero's own request for a backdrop being prefetched
 * waits for it ([awaitInFlight], via the desktop loader's interceptor) instead of decoding it twice.
 * Requests match the hero's (same url, no transformations, untagged), which is what makes Coil's
 * memory-cache key the same: Coil keys these by url and checks size separately.
 */
internal object HeroBackdropPrefetcher {
    /** Marks a prefetch request, so the join interceptor does not make it wait on itself. */
    val PrefetchExtra = Extras.Key(default = false)

    /** The hero backdrop slot in pixels, from the last laid-out hero; null until there is one. */
    val slotSizePx = MutableStateFlow<Pair<Int, Int>?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val confined = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + confined)

    // Confined to [confined].
    private var queue: List<String> = emptyList()
    private var worker: Job? = null

    /** Read from any thread by [awaitInFlight]; written only on [confined]. */
    private val inFlight = MutableStateFlow<Map<String, CompletableDeferred<Unit>>>(emptyMap())

    /** Replaces the pending queue with [models], nearest first. */
    fun request(context: PlatformContext, loader: ImageLoader, models: List<String>) {
        val (width, height) = slotSizePx.value ?: return
        scope.launch {
            queue = models
            if (worker?.isActive == true) return@launch
            worker = scope.launch { drain(context, loader, width, height) }
        }
    }

    /** Suspends until a prefetch of [data] running right now has finished; returns at once otherwise. */
    suspend fun awaitInFlight(data: Any?) {
        val url = data as? String ?: return
        inFlight.value[url]?.await()
    }

    private suspend fun drain(context: PlatformContext, loader: ImageLoader, width: Int, height: Int) {
        while (true) {
            val next = queue.firstOrNull() ?: return
            queue = queue.drop(1)
            if (next in inFlight.value) continue
            val done = CompletableDeferred<Unit>()
            inFlight.update { it + (next to done) }
            try {
                loader.execute(
                    ImageRequest.Builder(context)
                        .data(next)
                        .size(width, height)
                        .precision(Precision.INEXACT)
                        // The hero draws ContentScale.Crop, which Coil turns into FILL. The decoder
                        // picks its JPEG scale from this, so FIT here would decode too small for it.
                        .scale(Scale.FILL)
                        .apply { extras[PrefetchExtra] = true }
                        .build(),
                )
            } finally {
                inFlight.update { it - next }
                done.complete(Unit)
            }
        }
    }
}
