package com.nuvio.app.core.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import coil3.Image
import coil3.Extras
import coil3.ImageLoader
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.ImageRequest
import coil3.request.Options
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Image as SkiaImage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import kotlin.math.roundToInt

private val animatedImageLog = co.touchlab.kermit.Logger.withTag("AnimatedImage")

/**
 * Set on a request whose image must be shown as a still even if it is animated.
 *
 * The alternative would be to simply not request the animated URL, and where a separate still exists
 * that is what the caller does. This covers the case where it does not: a collection folder whose
 * only artwork is the GIF still has to show something, and "the first frame" is a better answer than
 * a blank card or an animation the surface asked not to have.
 *
 * Declining from the factory means Coil falls through to its ordinary decoder, which produces the
 * first frame as a plain bitmap - so this skips the whole frame-by-frame decode rather than decoding
 * an animation and then not playing it.
 */
internal val DisableAnimationKey = Extras.Key(default = false)

internal fun ImageRequest.Builder.disableAnimation(): ImageRequest.Builder = apply {
    extras[DisableAnimationKey] = true
}

/**
 * Enough bytes to reach a WebP's `VP8X` feature flags, which is the deepest this needs to look.
 *
 * Layout: `RIFF` (0-3), file size (4-7), `WEBP` (8-11), first chunk FourCC (12-15), chunk size
 * (16-19), and for a `VP8X` chunk the feature flags at 20. A GIF only needs its first four.
 */
private const val HeaderProbeBytes = 21L

private const val WebpAnimationFlag = 0x02

/**
 * Whether this could be an animation this decoder handles, judged from [HeaderProbeBytes] alone.
 *
 * A WebP answers definitively: only the extended `VP8X` form can animate, and then only with the
 * animation flag set, so plain `VP8`/`VP8L` are rejected without touching the body. That is the
 * overwhelming majority of what reaches here — of the GIF/WebP files in one real disk cache, 2,340
 * were static WebP against 99 animated WebP and 16 GIFs. Validated against that corpus: this agrees
 * with constructing a `Codec` and reading `frameCount` on 334 of 334 files, with no disagreements.
 *
 * A GIF cannot answer: frame count is only knowable by parsing, so a GIF is a *candidate* and the
 * body is read to settle it. There are few enough of them for that to be the right trade, and the
 * answer is remembered afterwards either way.
 */
internal fun ByteArray.isAnimatedCandidate(): Boolean {
    if (startsWith(0, "GIF8")) return true
    if (!startsWith(0, "RIFF") || !startsWith(8, "WEBP")) return false
    if (!startsWith(12, "VP8X")) return false
    return size > 20 && (this[20].toInt() and WebpAnimationFlag) != 0
}

private fun ByteArray.startsWith(offset: Int, ascii: String): Boolean {
    if (size < offset + ascii.length) return false
    return ascii.indices.all { this[offset + it].toInt() == ascii[it].code }
}

/** The first [HeaderProbeBytes] of the source, without consuming it. Null if it cannot be read. */
private fun ImageSource.peekHeader(): ByteArray? = try {
    source().peek().let { peeked ->
        if (peeked.request(HeaderProbeBytes)) peeked.readByteArray(HeaderProbeBytes) else peeked.readByteArray()
    }
} catch (e: Exception) {
    null
}

/**
 * An identity for this image that costs nothing to obtain, or null if there is not a trustworthy one.
 *
 * Prefers the request's own disk cache key, which for collection art is the image URL. Falls back to
 * the path of the file backing the source, which for anything Coil has cached on disk is derived
 * from that same URL and is equally stable across runs.
 *
 * Either way the file's length is folded in, and a size is *required* — a URL alone would name
 * whatever is at that URL today, so re-uploading different art to the same address could serve the
 * previous animation from cache. Without a length to check, this returns null and the caller falls
 * back to hashing the content, which cannot be fooled that way.
 */
private fun ImageSource.stableIdentity(options: Options): String? {
    val path = fileOrNull()
    val sizeBytes = path?.let { runCatching { fileSystem.metadataOrNull(it)?.size }.getOrNull() } ?: return null
    val name = options.diskCacheKey ?: path.toString()
    return "$name|$sizeBytes"
}

/** Content hash, for sources with no stable identity. ~1.5 ms on a 16.6 MB file. */
private fun ByteArray.contentKey(): String = "$size:${CRC32().apply { update(this@contentKey) }.value}"

/**
 * Files already proven to be single-frame, so this decoder never reads one twice to re-learn it.
 *
 * Bounded because it is keyed by URL and a long session browses many. Keys only, no payload, so the
 * cap can be generous — the whole set is a few hundred kilobytes at most.
 */
private object NonAnimatedKeys {
    private const val MaxKeys = 4096
    private val keys = object : LinkedHashSet<String>() {}

    @Synchronized
    fun contains(key: String): Boolean = keys.contains(key)

    @Synchronized
    fun add(key: String) {
        keys.add(key)
        while (keys.size > MaxKeys) {
            val iterator = keys.iterator()
            iterator.next()
            iterator.remove()
        }
    }
}

// Catalog tiles only render these animations at small sizes, so decoded frames are downscaled
// to this max dimension before being cached - this keeps memory bounded without truncating the
// frame list (which previously caused the animation to loop before reaching its natural end).
private const val MaxFrameDimension = 320

// Safety cap so a pathological GIF can't blow up memory by decoding hundreds of frames upfront.
private const val MaxDecodedBytes = 64L * 1024 * 1024

// Decoding every frame of a GIF is the expensive part (not the network/disk fetch, which Coil
// already caches). Coil's memory cache can evict the large decoded SkiaAnimatedImage as soon as
// a tile scrolls offscreen, which made re-decoding happen on every scroll back. Keep decoded
// animations around, keyed by the source bytes, so scrolling back to a tile just replays the
// already-decoded frames.
//
// This used to be an unbounded ConcurrentHashMap held for the app's lifetime. Each entry can be
// up to 64 MB of decoded frames, so a long browsing session over lots of animated art grew heap
// without bound. Bound it to a byte budget with LRU eviction instead; the worst that eviction
// costs is a re-decode of a tile the user scrolls back to after a long absence.
private val decodedImageCache = DecodedImageCache

/**
 * How many bytes of decoded animation frames to keep, as a fraction of *physical* RAM.
 *
 * These frames are Skia bitmaps, so their pixels are native memory, not Java heap — the collector
 * cannot see them and heap pressure will never evict them. Sizing them at 35% of
 * `Runtime.maxMemory()` measured the wrong resource, and only landed anywhere sensible by
 * coincidence: the default max heap is exactly a quarter of physical RAM. Nothing sets `-Xmx`
 * today, but the day something does, a knob for the Java heap would have quietly moved a
 * native-memory budget with it. Hence a fraction of the machine, not of the heap.
 *
 * The fraction itself used to be 8.75%, clamped to 128 MB-3 GB. That was sized for a miss costing
 * 2.5-5.5 s, which was the right call at the time and is no longer the situation: [readFrameInto]
 * took a 73-frame 512x512 GIF from 3,573 ms to 155 ms, so a miss now costs about a sixth of a
 * second and re-decoding a tile the user scrolled back to is no longer worth gigabytes to avoid.
 *
 * It was also genuinely expensive. On a 32 GB machine 8.75% is 2,861 MB, and a measured session
 * reached 2,193 MB across 89 entries — seven times what all the G1 tuning in `build.gradle.kts`
 * reclaims, in memory that tuning cannot even see.
 *
 * 2.5% lands at the 768 MB ceiling on a 30 GB+ machine, 410 MB on a 16 GB one and 205 MB on an
 * 8 GB one: roughly 8-30 animated tiles. Past that the still underneath carries the card for the
 * ~150 ms a re-decode now takes. The floor stays low enough to only ever act as a floor.
 *
 * **This is a ceiling, never an allocation.** The map below only ever holds tiles that were
 * actually decoded, so a user whose collections contain five animated folders holds five tiles
 * (~125 MB) whether this number says 327 MB or 768 MB. Raising it costs nothing to anyone who does
 * not have the art to fill it, which is why it can afford to be generous. What it must not do is
 * let someone who *does* have that much art take memory the rest of the machine needs — that is
 * [DecodedImageCache.budgetBytes], which reconsiders on every insert.
 */
private fun animatedImageCacheCeilingBytes(): Long {
    val physicalRamBytes = physicalMemoryBytes() ?: (Runtime.getRuntime().maxMemory() * 4)
    return (physicalRamBytes * PhysicalRamFraction).toLong()
        .coerceIn(MinCacheBudgetBytes, MaxCacheBudgetBytes)
}

private const val PhysicalRamFraction = 0.025
private const val MinCacheBudgetBytes = 96L * 1024 * 1024
private const val MaxCacheBudgetBytes = 768L * 1024 * 1024

/**
 * How much physical memory to leave available to everything else before this cache stops growing.
 *
 * Flat rather than a fraction, because it is describing the machine's working room and not this
 * app's appetite: Windows begins paging well before available memory reaches zero, and a gigabyte
 * of headroom means Nuvio is never the process that pushes it there. The ceiling above already
 * scales with total RAM, so this only ever binds on a machine that is genuinely under pressure
 * right now — a 32 GB machine with a game running gets the same protection as an 8 GB one.
 */
private const val AvailableMemoryHeadroomBytes = 1024L * 1024 * 1024

/**
 * Null if the runtime image was built without the management module, in which case the caller falls
 * back to four times the max heap — the same number on a JVM that has not been given an `-Xmx`.
 * `jdk.management` is listed in the packaging modules precisely so that this does not happen, but a
 * missing module surfaces as a [NoClassDefFoundError] at first call rather than at build time, and
 * an animated poster is not worth crashing over.
 *
 * Internal rather than private because both decoded-image budgets are a fraction of the machine and
 * so both need it: this file's animation frames, and the Coil memory cache in
 * `PlatformImageLoader.desktop.kt`.
 */
internal fun physicalMemoryBytes(): Long? = runCatching {
    val osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
    (osBean as com.sun.management.OperatingSystemMXBean).totalMemorySize
}.getOrNull()?.takeIf { it > 0L }

/**
 * Physical memory the OS currently reports as available, or null if it cannot be read.
 *
 * On Windows this is `GlobalMemoryStatusEx.ullAvailPhys`, which counts the standby list as
 * available — the same figure Task Manager calls "Available", not the much smaller "Free". That is
 * the right measure here: standby pages are reclaimable, so treating them as unavailable would make
 * the cache refuse to grow on a perfectly healthy machine.
 *
 * Measured at 0.9 us per call, so callers do not need to cache it.
 */
private fun availablePhysicalMemoryBytes(): Long? = runCatching {
    val osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
    (osBean as com.sun.management.OperatingSystemMXBean).freeMemorySize
}.getOrNull()?.takeIf { it > 0L }

private object DecodedImageCache {
    /**
     * The most this cache may hold on this machine, decided once from total RAM.
     *
     * One 73-frame 320x320 animation is ~25 MB of raw ARGB, so this is really sized in tiles rather
     * than megabytes: see [animatedImageCacheCeilingBytes] for the fraction and which machine number
     * it is a fraction of.
     *
     * A low hit rate is no longer the problem it was. It used to be measured at 3% over a real
     * scroll (18 hits, 522 misses) with every miss costing 2.5-5.5 s; a miss now costs ~150 ms and
     * the still image underneath covers it.
     */
    private val CEILING_BYTES: Long = animatedImageCacheCeilingBytes()

    // accessOrder=true makes iteration return least-recently-used first, so eviction is true LRU.
    private val map = object : LinkedHashMap<String, SkiaAnimatedImage>(16, 0.75f, true) {}
    private var currentBytes = 0L

    @Synchronized
    fun get(key: String): SkiaAnimatedImage? = map[key]

    @Synchronized
    fun stats(): String = "entries=${map.size} bytes=${currentBytes / (1024 * 1024)}MB"

    @Synchronized
    fun put(key: String, image: SkiaAnimatedImage) {
        map.put(key, image)?.let { currentBytes -= it.size }
        currentBytes += image.size
        evictDownTo(budgetBytes())
    }

    /**
     * Drops back to the floor. Called when the window is hidden or minimised, where holding several
     * hundred megabytes of frames for a window nobody is looking at is the clearest case of memory
     * this cache does not currently need. Restoring re-decodes only the tiles actually on screen, in
     * the background, behind the stills that already cover a cold tile.
     */
    @Synchronized
    fun trimToFloor() {
        evictDownTo(MinCacheBudgetBytes)
    }

    /**
     * What the cache may hold *right now*, which is the smaller of the static ceiling and what the
     * machine can currently spare.
     *
     * The pressure term is `currentBytes + available - headroom`: the frames already held are not
     * counted in `available` because they are allocated, so releasing all of them would raise
     * `available` by exactly `currentBytes`. That makes the expression the largest size at which
     * available memory still clears [AvailableMemoryHeadroomBytes], and it is self-correcting —
     * when something else on the machine takes memory, the next insert evicts rather than competing.
     *
     * Never goes below [MinCacheBudgetBytes]: a couple of tiles is small enough not to matter to
     * anyone, and dropping under that would re-decode the visible row continuously, which costs CPU
     * on a machine already short of memory.
     */
    private fun budgetBytes(): Long {
        val available = availablePhysicalMemoryBytes() ?: return CEILING_BYTES
        val spareCeiling = currentBytes + available - AvailableMemoryHeadroomBytes
        return minOf(CEILING_BYTES, spareCeiling).coerceAtLeast(MinCacheBudgetBytes)
    }

    /** Caller must hold the monitor. */
    private fun evictDownTo(budget: Long) {
        val iterator = map.entries.iterator()
        while (currentBytes > budget && map.size > 1 && iterator.hasNext()) {
            val eldest = iterator.next()
            iterator.remove()
            currentBytes -= eldest.value.size
        }
    }
}

/**
 * Releases decoded animation frames the app is not currently showing.
 *
 * Exposed for `DesktopIdleHeapTrim`, which already knows the one moment this is free: the window is
 * hidden or minimised, so nothing on screen is animating and no re-decode can be seen.
 */
internal fun trimDecodedAnimationCache() {
    DecodedImageCache.trimToFloor()
}

internal class AnimatedSkiaImageDecoder(
    private val codec: Codec,
    private val cacheKey: String,
) : Decoder {
    override suspend fun decode(): DecodeResult {
        val decodeStartedAtMs = System.currentTimeMillis()
        decodedImageCache.get(cacheKey)?.let { cached ->
            codec.close()
            return DecodeResult(image = cached, isSampled = true)
        }

        val width = codec.width
        val height = codec.height
        val scale = (MaxFrameDimension.toFloat() / maxOf(width, height)).coerceAtMost(1f)
        val scaledWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val scaledHeight = (height * scale).roundToInt().coerceAtLeast(1)

        val bytesPerFrame = (scaledWidth.toLong() * scaledHeight.toLong() * 4).coerceAtLeast(1)
        val maxFrames = (MaxDecodedBytes / bytesPerFrame).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        val frameCount = codec.frameCount.coerceAtMost(maxFrames)
        val framesInfo = codec.framesInfo

        val frames = ArrayList<ImageBitmap>(frameCount)
        val durations = IntArray(frameCount)
        // Many animated GIF/WebP frames are deltas that only redraw a sub-region of the canvas
        // (relying on the previous frame's content for the rest). Decoding into a fresh,
        // uninitialized bitmap each time leaves those untouched regions as garbage memory, which
        // shows up as blocky noise. Decoding sequentially into one reused bitmap lets the codec
        // composite each delta frame on top of the previous frame's pixels correctly.
        val sharedBitmap = Bitmap().apply {
            allocPixels(codec.imageInfo)
            erase(0)
        }
        var readPixelsMs = 0L
        var scaleMs = 0L
        try {
            for (i in 0 until frameCount) {
                val readStartedAt = System.nanoTime()
                try {
                    codec.readFrameInto(sharedBitmap, i)
                } catch (error: Exception) {
                    // A frame Skia cannot produce into this bitmap used to fail the whole decode,
                    // and the tile then rendered as nothing at all. Measured case: a GIF using
                    // RESTORE_PREVIOUS disposal throws "Invalid conversion" partway through. Keep
                    // what has already been decoded and end the animation early instead.
                    animatedImageLog.w {
                        "frame $i of $frameCount failed to decode, truncating: ${error.message}"
                    }
                    if (frames.isEmpty()) throw error
                    break
                }
                readPixelsMs += (System.nanoTime() - readStartedAt) / 1_000_000
                val scaleStartedAt = System.nanoTime()
                frames.add(
                    if (scale < 1f) {
                        sharedBitmap.scaleTo(scaledWidth, scaledHeight)
                    } else {
                        sharedBitmap.makeClone().asComposeImageBitmap()
                    },
                )
                scaleMs += (System.nanoTime() - scaleStartedAt) / 1_000_000
                durations[i] = framesInfo.getOrNull(i)?.duration?.coerceAtLeast(20) ?: 100
            }
        } finally {
            codec.close()
        }

        val image = SkiaAnimatedImage(
            width = scaledWidth,
            height = scaledHeight,
            frames = frames,
            // Trimmed to what actually decoded. [SkiaAnimatedImage.currentFrame] walks the duration
            // array and indexes `frames` with its position, so a truncated decode above would
            // otherwise index past the end once the clock passed the last decoded frame.
            frameDurationsMs = durations.copyOf(frames.size),
        )
        decodedImageCache.put(cacheKey, image)
        animatedImageLog.i {
            "decoded $scaledWidth x $scaledHeight (source ${width}x$height) frames=$frameCount " +
                "in ${System.currentTimeMillis() - decodeStartedAtMs}ms " +
                "(readPixels=${readPixelsMs}ms scale=${scaleMs}ms) ${decodedImageCache.stats()}"
        }

        return DecodeResult(
            image = image,
            isSampled = scale < 1f,
        )
    }

    class Factory : Decoder.Factory {
        /**
         * Decides whether this decoder wants the image, doing as little reading as it can.
         *
         * The version this replaced answered that question by reading the *entire* source into a
         * byte array and CRC32ing it, purely to build a cache key — before it knew whether the image
         * was even animated. Measured over one session: 2,119 calls, 11.5 s, averaging 16.8 ms for
         * sources over 1 MB. Almost none of that was the CRC (1.5 ms on a 16.6 MB file); it was the
         * read. And the answer was thrown away every time: a single-frame image is never cached, so
         * the same static file paid again on every decode, forever — the log shows 1,736 misses
         * against 319 distinct keys.
         *
         * It is also the wrong shape for this cache. A cache hit had to read the whole file before
         * it could discover that it already had the decoded frames.
         *
         * So the order is inverted: classify from the header, look the image up by an identity that
         * costs nothing to obtain, and only read the body once it is known that the body is wanted.
         */
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            // Asked for a still: decline before reading anything, so Coil's ordinary decoder
            // produces the first frame and no animation is decoded at all.
            // `== true` rather than a bare read: Extras returns null for a key that was never set.
            if (options.extras[DisableAnimationKey] == true) return null

            val source = result.source
            val header = source.peekHeader() ?: return null
            if (!header.isAnimatedCandidate()) return null

            val identity = source.stableIdentity(options)
            if (identity != null) {
                if (NonAnimatedKeys.contains(identity)) return null
                decodedImageCache.get(identity)?.let { cached ->
                    animatedImageLog.d { "decode-cache HIT key=$identity (no read) ${decodedImageCache.stats()}" }
                    return cachedHit(cached)
                }
            }

            // Past here the body is genuinely needed: either to decode it, or (for a GIF, whose
            // header cannot say how many frames it has) to find out that it is not animated.
            val readStartedAtMs = System.currentTimeMillis()
            val bytes = try {
                source.source().peek().readByteArray()
            } catch (e: Exception) {
                return null
            }
            val cacheKey = identity ?: bytes.contentKey()
            val readElapsedMs = System.currentTimeMillis() - readStartedAtMs

            if (identity == null) {
                // No stable identity, so neither lookup could happen before the read. Both still
                // pay off: they skip building a Codec and parsing its frame count.
                if (NonAnimatedKeys.contains(cacheKey)) return null
                decodedImageCache.get(cacheKey)?.let { cached ->
                    animatedImageLog.d {
                        "decode-cache HIT key=$cacheKey ${bytes.size / 1024}KB readMs=$readElapsedMs " +
                            decodedImageCache.stats()
                    }
                    return cachedHit(cached)
                }
            }
            animatedImageLog.d {
                "decode-cache MISS key=$cacheKey ${bytes.size / 1024}KB readMs=$readElapsedMs " +
                    decodedImageCache.stats()
            }

            val codec = try {
                Codec.makeFromData(Data.makeFromBytes(bytes))
            } catch (e: Exception) {
                return null
            }
            if (codec.frameCount <= 1) {
                codec.close()
                // Remember, so this file is never read again to reach the same conclusion.
                NonAnimatedKeys.add(cacheKey)
                return null
            }
            return AnimatedSkiaImageDecoder(codec, cacheKey)
        }
    }

    companion object {
        // Pin the already-decoded image in the closure rather than re-looking it up by key: the
        // LRU cache can evict between Factory.create() and this decode, and the in-flight request
        // must still resolve to a valid frame set instead of throwing.
        fun cachedHit(cached: SkiaAnimatedImage): Decoder = Decoder {
            DecodeResult(image = cached, isSampled = true)
        }
    }
}

/**
 * Decodes frame [index] into [target], telling Skia that [target] already holds frame `index - 1`.
 *
 * The two-argument `readPixels` overload passes `priorFrame = -1`, which tells Skia the destination
 * holds nothing it can build on — so for every frame it re-decodes the entire dependency chain back
 * to the last independent frame, and a 73-frame GIF pays roughly 2,700 frame decodes instead of 73.
 * Measured on the largest GIF in the disk cache (17.4 MB, 512x512, 73 frames), that is 3,394 ms of
 * `readPixels` against 93 ms once the prior frame is declared. Whole-decode: 3,573 ms to 155 ms.
 *
 * The caller already decodes sequentially into one shared bitmap so that delta frames composite
 * correctly, which is exactly the precondition this needs: the bitmap genuinely does hold the
 * previous frame.
 *
 * Skia validates the claim against the frame's disposal chain and rejects it (skiko turns that into
 * a throw) when the previous frame is not a usable base, so the fallback is precisely today's
 * behaviour rather than a corrupt frame. Verified byte-identical against the two-argument path for
 * every animated GIF in the disk cache and for synthesised KEEP and RESTORE_BG_COLOR files.
 */
private fun Codec.readFrameInto(target: Bitmap, index: Int) {
    if (index > 0) {
        try {
            readPixels(target, index, index - 1)
            return
        } catch (_: Exception) {
            // Not a usable base for this frame; fall through and let Skia rebuild the chain.
        }
    }
    readPixels(target, index)
}

/**
 * Box-halves while the remaining reduction is 2x or more, then one linear pass — the same technique
 * [ScaledBitmapPainter] uses for posters, for the same reason: a filter's footprint does not grow
 * with the ratio, so past ~2x a single pass discards most of the source and aliases.
 *
 * This used to sample with `MipmapMode.LINEAR`, which makes Skia build a full mipmap chain of the
 * source on *every frame* to serve a reduction of at most 2.2x. Measured per frame: 2.198 ms at
 * 512x512 and 1.249 ms at 704x400, against 0.620 ms and 0.933 ms here. Cheaper at both of the
 * source sizes collection art actually arrives in, and with no loss of quality at either — the
 * halving step is an exact 2x2 box average, so nothing is thrown away before the final pass.
 */
private fun Bitmap.scaleTo(width: Int, height: Int): ImageBitmap {
    var intermediate: Bitmap? = null
    try {
        while (true) {
            val source = intermediate ?: this
            val halfWidth = source.width / 2
            val halfHeight = source.height / 2
            // Stop before either axis would undershoot; the final pass handles the remainder.
            if (halfWidth < width || halfHeight < height) break
            val halved = source.resampleTo(halfWidth, halfHeight)
            intermediate?.close()
            intermediate = halved
        }

        val source = intermediate ?: this
        if (source.width == width && source.height == height) {
            // Halving landed exactly on the target. Ownership of the intermediate transfers to the
            // returned ImageBitmap; `this` is the caller's shared bitmap, which the next frame
            // decodes over, so it has to be cloned instead of handed out.
            val halved = intermediate
            if (halved != null) {
                intermediate = null
                return halved.asComposeImageBitmap()
            }
            return source.makeClone().asComposeImageBitmap()
        }
        return source.resampleTo(width, height).asComposeImageBitmap()
    } finally {
        intermediate?.close()
    }
}

private fun Bitmap.resampleTo(width: Int, height: Int): Bitmap {
    val image = SkiaImage.makeFromBitmap(this)
    return try {
        val scaled = Bitmap()
        scaled.allocN32Pixels(width, height)
        image.scalePixels(
            scaled.peekPixels()!!,
            FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE),
            true,
        )
        scaled
    } finally {
        image.close()
    }
}

private object AnimatedImageClock {
    val tick = mutableStateOf(0)
    private val started = AtomicBoolean(false)

    fun ensureStarted() {
        if (started.compareAndSet(false, true)) {
            CoroutineScope(Dispatchers.Default).launch {
                while (true) {
                    delay(33)
                    tick.value = (tick.value + 1) and 0xFFFF
                }
            }
        }
    }
}

internal class SkiaAnimatedImage(
    override val width: Int,
    override val height: Int,
    private val frames: List<ImageBitmap>,
    private val frameDurationsMs: IntArray,
) : Image {
    override val shareable: Boolean = true
    override val size: Long = width.toLong() * height.toLong() * 4 * frames.size

    private val totalDurationMs: Int = frameDurationsMs.sum().coerceAtLeast(1)

    private fun currentFrame(): ImageBitmap {
        if (frames.size <= 1) return frames[0]
        val t = (System.currentTimeMillis() % totalDurationMs).toInt()
        var acc = 0
        for ((index, duration) in frameDurationsMs.withIndex()) {
            acc += duration
            if (t < acc) return frames[index]
        }
        return frames.last()
    }

    override fun draw(canvas: org.jetbrains.skia.Canvas) {
        val skiaImage = org.jetbrains.skia.Image.makeFromBitmap(currentFrame().asSkiaBitmap())
        try {
            canvas.drawImage(skiaImage, 0f, 0f)
        } finally {
            skiaImage.close()
        }
    }

    internal fun currentFrameForCompose(): ImageBitmap = currentFrame()

    internal val isAnimated: Boolean get() = frames.size > 1
}

internal class SkiaAnimatedPainter(
    private val image: SkiaAnimatedImage,
) : Painter() {
    init {
        if (image.isAnimated) {
            AnimatedImageClock.ensureStarted()
        }
    }

    override val intrinsicSize: Size = Size(image.width.toFloat(), image.height.toFloat())

    override fun DrawScope.onDraw() {
        if (image.isAnimated) {
            AnimatedImageClock.tick.value
        }
        val frame = image.currentFrameForCompose()
        drawIntoCanvas { canvas ->
            val skiaImage = SkiaImage.makeFromBitmap(frame.asSkiaBitmap())
            try {
                canvas.nativeCanvas.drawImageRect(
                    skiaImage,
                    Rect.makeWH(frame.width.toFloat(), frame.height.toFloat()),
                    Rect.makeWH(size.width, size.height),
                    SamplingMode.LINEAR,
                    Paint().apply { isDither = true },
                    true,
                )
            } finally {
                skiaImage.close()
            }
        }
    }
}
