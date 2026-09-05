package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
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
    }.diskCache {
        DiskCache.Builder()
            .directory(DesktopStorage.rootDir.resolve(IMAGE_CACHE_DIR_NAME).toOkioPath())
            .maxSizeBytes(IMAGE_CACHE_MAX_BYTES)
            .build()
    }.components {
        // Order matters: factories are tried in registration order and the first non-null wins, and
        // Coil appends its own defaults after these. Animation is offered the source first; whatever
        // it declines falls to the high-quality still decoder, which would otherwise have gone to
        // Coil's own - the one that reduces with nearest-neighbour.
        add(AnimatedSkiaImageDecoder.Factory())
        add(HighQualityBitmapDecoder.Factory())
    }

/**
 * How many bytes of *decoded* artwork to keep in memory.
 *
 * Coil only sizes this sensibly on Android. Its non-Android default is 15% of
 * `totalAvailableMemoryBytes()`, and on this platform that function returns a hardcoded 512 MB
 * literal rather than anything about the machine — so leaving it unset gave every Windows user
 * exactly **76.8 MB**, whether they had 8 GB of RAM or 128 GB.
 *
 * That is far too little for what an entry costs here. `NuvioAsyncImage` requests artwork at up to
 * 1536 px, so a TMDB `w500` poster is held at 500x750 (1.4 MB) and a landscape card
 * cut from an `original` backdrop at 1536x864 (5.3 MB) — roughly 55 posters, or 15 landscape cards.
 * A single 1440p Search or Library screen shows more than that, so the cache was being evicted
 * within one row and every scroll back paid a fresh decode (measured at 2.4-7.5 ms per poster)
 * followed by a fresh resample in the draw path.
 *
 * A fraction of the machine rather than a constant, for the same reason as the animation cache next
 * door in `AnimatedSkiaImage.desktop.kt`: these are decoded bitmaps whose real constraint is
 * physical RAM. 1% gives 327 MB on a 32 GB machine (~230 posters) and 164 MB on a 16 GB one, and
 * the floor keeps a small machine above the 76.8 MB it would otherwise have had anyway.
 *
 * This is separate from, and much smaller than, [IMAGE_CACHE_MAX_BYTES] — that one holds compressed
 * bytes on disk, which is not the resource that runs out here.
 */
private fun memoryCacheBudgetBytes(): Long {
    val physicalRamBytes = physicalMemoryBytes() ?: (Runtime.getRuntime().maxMemory() * 4)
    return (physicalRamBytes * MemoryCacheRamFraction).toLong()
        .coerceIn(MinMemoryCacheBytes, MaxMemoryCacheBytes)
}

private const val MemoryCacheRamFraction = 0.01
private const val MinMemoryCacheBytes = 96L * 1024 * 1024
private const val MaxMemoryCacheBytes = 384L * 1024 * 1024

private const val IMAGE_CACHE_DIR_NAME = "image_cache"

/**
 * Posters and backdrops are small, but animated collection art is not — one browsing session filled
 * 452 MB of the original 512 MB budget, which would have had the LRU thrashing almost immediately.
 * This is a desktop app with a real disk, so the budget is generous.
 */
private const val IMAGE_CACHE_MAX_BYTES = 5L * 1024 * 1024 * 1024
