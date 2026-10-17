package com.nuvio.app.core.ui

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import com.nuvio.app.features.home.components.HeroBackdropPrefetcher

/**
 * A request for an image the hero prefetcher is decoding right now waits for that decode, then
 * proceeds and finds the result in the memory cache. Without it, focusing a card whose backdrop was
 * half-decoded started a second ~140 ms decode of the same file. Coil does not merge identical
 * in-flight requests itself.
 */
internal class HeroBackdropPrefetchJoinInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        if (chain.request.extras[HeroBackdropPrefetcher.PrefetchExtra] != true) {
            HeroBackdropPrefetcher.awaitInFlight(chain.request.data)
        }
        return chain.proceed()
    }
}
