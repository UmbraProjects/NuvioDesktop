package com.nuvio.app.core.ui

import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.annotation.ExperimentalCoilApi
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.nuvio.app.core.storage.DesktopStorage
import okio.Path.Companion.toOkioPath

/**
 * Coil creates a default disk cache on Android only. On the JVM there is none unless one is built
 * here, so `diskCachePolicy(ENABLED)` alone enabled nothing — every image was re-fetched over the
 * network on a cold start, and animated collection art re-downloaded every time a card left the
 * viewport and came back (animated frames are not held in the memory cache the way a static bitmap
 * is, so scrolling away drops them entirely).
 */
internal actual fun ImageLoader.Builder.configurePlatformImageLoader(): ImageLoader.Builder =
    memoryCache {
        MemoryCache.Builder()
            .maxSizeBytes(memoryCacheBudgetBytes())
            .build()
            .let(::DesktopArtworkMemoryCache)
            .also(DesktopArtworkCaches::register)
            .also { HeroArtworkMemory.cache = it }
    }.diskCache {
        val cacheDir = DesktopStorage.rootDir.resolve(IMAGE_CACHE_DIR_NAME)
        // Before the cache is opened, not after: the purge edits the journal directly, which is
        // only safe while Coil is not holding it.
        ImageDiskCachePurge.purgeCachedFailures(cacheDir)
        // Read once: Coil sizes a disk cache when it opens it, so a change applies on next launch.
        ImageCacheSettingsRepository.ensureLoaded()
        val totalBytes = ImageCacheSettingsRepository.settings.value.diskCacheGb * BytesPerGb
        val animatedBytes = (totalBytes * AnimatedImageCacheFraction).toLong()
        PartitionedImageDiskCache(
            stills = DiskCache.Builder()
                .directory(cacheDir.toOkioPath())
                .maxSizeBytes(totalBytes - animatedBytes)
                .build(),
            animated = DiskCache.Builder()
                .directory(DesktopStorage.rootDir.resolve(ANIMATED_IMAGE_CACHE_DIR_NAME).toOkioPath())
                .maxSizeBytes(animatedBytes)
                .build(),
        )
    }.components { addDesktopArtworkComponents() }
        // Per-image wait/fetch/decode timing for every load that leaves the memory cache; see the
        // listener for the log format and why it exists.
        .eventListenerFactory(ImageFetchTimingListener.Factory())

/**
 * The desktop fetch/decode chain, shared with the integration test that drives it against a real
 * HTTP server so the registration order below is what gets tested.
 */
@OptIn(ExperimentalCoilApi::class)
internal fun ComponentRegistry.Builder.addDesktopArtworkComponents() {
    // Order matters: factories are tried in registration order and the first non-null wins, and
    // Coil appends its own defaults after these. Animation is offered the source first; whatever
    // it declines falls to the high-quality still decoder, which would otherwise have gone to
    // Coil's own - the one that reduces with nearest-neighbour.
    add(HeroBackdropPrefetchJoinInterceptor())
    add(DesktopArtworkSizeInterceptor())
    // After the size interceptor, so a stale-copy retry keeps the memory key it was sized under.
    add(ImageStaleIfErrorInterceptor())
    add(AnimatedSkiaImageDecoder.Factory())
    add(HighQualityBitmapDecoder.Factory())
    // Coil finds a Ktor fetcher through the JVM ServiceLoader on its own, but only ever with
    // `CacheStrategy.DEFAULT`. Registering one here — ahead of the service-loader entry, which
    // is appended after everything added explicitly — is the only way to choose the strategy.
    //
    // The concurrent-request strategy makes identical in-flight requests share one download; see
    // CoalescingRequestStrategy.
    add(
        KtorNetworkFetcherFactory(
            cacheStrategy = { ImageDiskCacheStrategy },
            concurrentRequestStrategy = { SharedImageRequestCoalescing },
        ),
    )
}

/**
 * How many bytes of *decoded* artwork to keep in memory.
 *
 * Coil only sizes this sensibly on Android. Its non-Android default is 15% of
 * `totalAvailableMemoryBytes()`, and on this platform that function returns a hardcoded 512 MB
 * literal rather than anything about the machine — so leaving it unset gave every Windows user
 * exactly **76.8 MB**, whether they had 8 GB of RAM or 128 GB.
 *
 * Artwork is decoded close to its physical display size. Keep the established browsing budget
 * for smooth back-navigation; DesktopArtworkCaches temporarily lowers it while playback or a
 * hidden window makes most of the artwork inactive. The animation cache owns its frames itself.
 *
 * This is separate from, and much smaller than, the disk cache — that one holds compressed
 * bytes on disk, which is not the resource that runs out here.
 */
private fun memoryCacheBudgetBytes(): Long {
    val physicalRamBytes = physicalMemoryBytes() ?: (Runtime.getRuntime().maxMemory() * 4)
    return (physicalRamBytes * MemoryCacheRamFraction).toLong()
        .coerceIn(MinMemoryCacheBytes, MaxMemoryCacheBytes)
}

/**
 * 1% of RAM, capped at 384 MB.
 *
 * Raised to 3%/1.5 GB on 2026-09-07 to test whether cache eviction caused the card flashing on
 * collection rows. It did not: with the bigger budget the cache ran at 411/980 MB — real headroom,
 * no pressure — and `reloads` fell from 13-157 to 15 per window while the flashing looked exactly
 * the same. Put back, because a bigger number that buys nothing is RAM taken from everything else.
 *
 * The residual reloads are not eviction at all: animated images are deliberately kept out of this
 * cache (see DesktopArtworkMemoryCache), so they reload however much room there is.
 */
private const val MemoryCacheRamFraction = 0.01
private const val MinMemoryCacheBytes = 96L * 1024 * 1024
private const val MaxMemoryCacheBytes = 384L * 1024 * 1024

private const val IMAGE_CACHE_DIR_NAME = "image_cache"
private const val ANIMATED_IMAGE_CACHE_DIR_NAME = "image_cache_animated"

/**
 * The total is the user's (Settings → Advanced → Cache, 5 GB unless changed). Posters and backdrops
 * are small, but animated collection art is not — one browsing session filled 452 MB of the
 * original 512 MB budget — which is why that art gets its own partition of the total; see
 * PartitionedImageDiskCache.
 */
private const val BytesPerGb = 1024L * 1024 * 1024
