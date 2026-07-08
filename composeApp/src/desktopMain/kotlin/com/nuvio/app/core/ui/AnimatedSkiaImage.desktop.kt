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
import coil3.ImageLoader
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
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

private val GifMagic1 = byteArrayOf(0x47, 0x49, 0x46, 0x38) // "GIF8"
private val RiffMagic = byteArrayOf(0x52, 0x49, 0x46, 0x46) // "RIFF"
private val WebpMagic = byteArrayOf(0x57, 0x45, 0x42, 0x50) // "WEBP"

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

private object DecodedImageCache {
    // Generous budget: ~six worst-case (64 MB) entries, far more than a typical viewport of tiles.
    private const val MAX_BYTES = 384L * 1024 * 1024

    // accessOrder=true makes iteration return least-recently-used first, so eviction is true LRU.
    private val map = object : LinkedHashMap<String, SkiaAnimatedImage>(16, 0.75f, true) {}
    private var currentBytes = 0L

    @Synchronized
    fun get(key: String): SkiaAnimatedImage? = map[key]

    @Synchronized
    fun put(key: String, image: SkiaAnimatedImage) {
        map.put(key, image)?.let { currentBytes -= it.size }
        currentBytes += image.size
        val iterator = map.entries.iterator()
        while (currentBytes > MAX_BYTES && map.size > 1 && iterator.hasNext()) {
            val eldest = iterator.next()
            iterator.remove()
            currentBytes -= eldest.value.size
        }
    }
}

internal class AnimatedSkiaImageDecoder(
    private val codec: Codec,
    private val cacheKey: String,
) : Decoder {
    override suspend fun decode(): DecodeResult {
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
        try {
            for (i in 0 until frameCount) {
                codec.readPixels(sharedBitmap, i)
                frames.add(
                    if (scale < 1f) {
                        sharedBitmap.scaleTo(scaledWidth, scaledHeight)
                    } else {
                        sharedBitmap.makeClone().asComposeImageBitmap()
                    },
                )
                durations[i] = framesInfo.getOrNull(i)?.duration?.coerceAtLeast(20) ?: 100
            }
        } finally {
            codec.close()
        }

        val image = SkiaAnimatedImage(
            width = scaledWidth,
            height = scaledHeight,
            frames = frames,
            frameDurationsMs = durations,
        )
        decodedImageCache.put(cacheKey, image)

        return DecodeResult(
            image = image,
            isSampled = scale < 1f,
        )
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val source = result.source
            val peeked = source.source().peek()
            val header = ByteArray(12)
            val read = try {
                peeked.readFully(header)
                12
            } catch (e: Exception) {
                0
            }
            val isGif = read >= 4 && header.copyOfRange(0, 4).contentEquals(GifMagic1)
            val isWebp = read >= 12 &&
                header.copyOfRange(0, 4).contentEquals(RiffMagic) &&
                header.copyOfRange(8, 12).contentEquals(WebpMagic)
            if (!isGif && !isWebp) return null

            val bytes = source.source().peek().readByteArray()
            val crc = CRC32().apply { update(bytes) }
            val cacheKey = "${bytes.size}:${crc.value}"

            decodedImageCache.get(cacheKey)?.let { cached ->
                return AnimatedSkiaImageDecoder.cachedHit(cached)
            }

            val codec = try {
                Codec.makeFromData(Data.makeFromBytes(bytes))
            } catch (e: Exception) {
                return null
            }
            return if (codec.frameCount > 1) {
                AnimatedSkiaImageDecoder(codec, cacheKey)
            } else {
                codec.close()
                null
            }
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

private fun Bitmap.scaleTo(width: Int, height: Int): ImageBitmap {
    val image = SkiaImage.makeFromBitmap(this)
    return try {
        val scaled = Bitmap()
        scaled.allocN32Pixels(width, height)
        image.scalePixels(
            scaled.peekPixels()!!,
            FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR),
            true,
        )
        scaled.asComposeImageBitmap()
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
