package com.nuvio.app.core.ui

import coil3.memory.MemoryCache

/**
 * Coil's memory cache with two Nuvio rules on top.
 *
 * Animation frames have one cache owner, so trimming that owner actually releases its references;
 * they are never stored here.
 *
 * A still is served only while [ImageFreshnessRegistry] says the bytes it was decoded from are
 * still fresh, and only while they are the *current* bytes. Coil never expires a memory entry and
 * only consults the disk cache — where `Cache-Control` is honoured — on a memory miss, so this is
 * the point at which a poster provider's "keep this for a day" reaches a long-running session:
 * an expired entry is a miss, the request falls through to the disk cache and its strategy, and
 * whatever comes back (a 304, or a new poster) is stored here again with a new deadline.
 */
internal class DesktopArtworkMemoryCache(private val delegate: MemoryCache) : MemoryCache by delegate {

    override fun get(key: MemoryCache.Key): MemoryCache.Value? {
        val value = delegate[key] ?: return null
        // Values without a disk-cache key did not come through a fetcher we track (a painter
        // resource, a data URI); Coil's own rules apply to them unchanged.
        val diskCacheKey = value.extras[CoilDiskCacheKeyExtra] as? String ?: return value
        val freshness = ImageFreshnessRegistry.get(diskCacheKey) ?: return value
        val storedBodyVersion = value.extras[BodyVersionExtra] as? Int
        val superseded = storedBodyVersion != null && storedBodyVersion != freshness.bodyVersion
        if (superseded || ImageFreshnessRegistry.nowMillis() >= freshness.expiresAtMillis) {
            delegate.remove(key)
            return null
        }
        return value
    }

    override fun set(key: MemoryCache.Key, value: MemoryCache.Value) {
        if (value.image is SkiaAnimatedImage) return
        val diskCacheKey = value.extras[CoilDiskCacheKeyExtra] as? String
        val freshness = diskCacheKey?.let(ImageFreshnessRegistry::get)
        delegate[key] = if (freshness == null) {
            value
        } else {
            value.copy(extras = value.extras + (BodyVersionExtra to freshness.bodyVersion))
        }
    }

    private companion object {
        /** `MemoryCacheService.EXTRA_DISK_CACHE_KEY`: Coil records it on every value it writes. */
        const val CoilDiskCacheKeyExtra = "coil#disk_cache_key"
        const val BodyVersionExtra = "nuvio#body_version"
    }
}

internal const val PlaybackArtworkCacheBytes = 64L * 1024 * 1024
internal const val PlaybackScaledCacheBytes = 8L * 1024 * 1024

/**
 * Window visibility and full-player sessions are independent reasons to lower the caches. Leases
 * prevent an outgoing player from restoring budgets underneath a replacement. Late image requests
 * remain subject to the smaller budgets until every reason has ended. Pixels still owned by a
 * painter are never closed here; only cache references are dropped.
 */
internal class DesktopArtworkCacheCoordinator(
    private val limitAnimations: (Boolean) -> Unit,
    private val limitScaledBitmaps: (Boolean) -> Unit,
) {
    private var memoryCache: MemoryCache? = null
    private var browsingMaxBytes = 0L
    private var windowVisible = true
    private val playbackSessions = mutableSetOf<Any>()
    private var restricted = false

    @Synchronized
    fun register(cache: MemoryCache) {
        if (memoryCache === cache) return
        memoryCache = cache
        browsingMaxBytes = cache.maxSize
        applyMemoryBudget()
    }

    /** Null until Coil has actually built its cache, which is lazy. */
    @Synchronized
    fun memorySummary(): String? = memoryCache?.let { cache ->
        val megabytes = 1024 * 1024
        "cache=${cache.size / megabytes}/${cache.maxSize / megabytes}MB " +
            "entries=${cache.keys.size}${if (restricted) " restricted" else ""}"
    }

    @Synchronized
    fun beginPlayback(): Any = Any().also {
        playbackSessions.add(it)
        update()
    }

    @Synchronized
    fun endPlayback(session: Any) {
        playbackSessions.remove(session)
        update()
    }

    @Synchronized
    fun onWindowVisibilityChanged(visible: Boolean) {
        windowVisible = visible
        update()
    }

    private fun update() {
        val next = !windowVisible || playbackSessions.isNotEmpty()
        if (restricted == next) return
        restricted = next
        applyMemoryBudget()
        limitAnimations(restricted)
        limitScaledBitmaps(restricted)
    }

    private fun applyMemoryBudget() {
        memoryCache?.maxSize = if (restricted) {
            minOf(browsingMaxBytes, PlaybackArtworkCacheBytes)
        } else browsingMaxBytes
    }
}

internal val DesktopArtworkCaches = DesktopArtworkCacheCoordinator(
    limitAnimations = ::limitDecodedAnimationCache,
    limitScaledBitmaps = ScaledBitmapCache::setBackgroundMode,
)
