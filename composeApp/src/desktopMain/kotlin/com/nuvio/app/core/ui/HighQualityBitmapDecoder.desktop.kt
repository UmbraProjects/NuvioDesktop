package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.maxBitmapSize
import coil3.size.Dimension
import coil3.size.Precision
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.EncodedOrigin
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Image as SkiaImage
import kotlin.math.roundToInt
import okio.FileSystem
import java.nio.file.Files

/**
 * Decodes still images, reducing them properly on the way rather than leaving that to the draw path.
 *
 * Coil's own non-Android decoder decodes at full resolution and then reduces to the requested size
 * with `SamplingMode.DEFAULT`, which is `FilterMipmap(NEAREST, NONE)` — nearest-neighbour. Past
 * about 2x minification a single-pass filter discards most of the source, which is why
 * [ScaledBitmapPainter] used to re-reduce every image from a deliberately oversized 1536 px request,
 * with repeated box-halving and a cubic, inside `onDraw`.
 *
 * This moves that reduction to where it belongs — the decode dispatcher — and removes the
 * nearest-neighbour step entirely, so a large source is no longer aliased before the careful pass
 * ever sees it. Requests are now sized from the destination, so what lands in Coil's memory cache is
 * already card-sized; the small headroom left on top covers focus enlargement. [ScaledBitmapPainter]
 * survives only as a fallback for fetchers that return their own oversized `BitmapImage`.
 */
internal class HighQualityBitmapDecoder(
    private val source: ImageSource,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult {
        val bytes = source.readAllBytes()
        val encoded = SkiaImage.makeFromEncoded(bytes)
        try {
            val multiplier = reductionMultiplier(encoded.width, encoded.height, options)
            // Rounded, not truncated: 500 * (414 / 500.0) can come out a hair under 414, and one
            // pixel short of the slot turns the draw's 1:1 blit back into a resample.
            val targetWidth = (encoded.width * multiplier).roundToInt().coerceAtLeast(1)
            val targetHeight = (encoded.height * multiplier).roundToInt().coerceAtLeast(1)

            val nothingToReduce = targetWidth >= encoded.width && targetHeight >= encoded.height
            val jpegScaled = if (nothingToReduce) null else decodeJpegEighths(bytes, targetWidth, targetHeight)
            val keepSourceSize = nothingToReduce ||
                keepsSourceSizeForGpu(encoded.width, encoded.height, targetWidth, targetHeight)
            val bitmap = if (jpegScaled != null) {
                if (keepsSourceSizeForGpu(jpegScaled.width, jpegScaled.height, targetWidth, targetHeight)) {
                    jpegScaled
                } else {
                    // Only a source 16x the slot gets here: 1/8 was still too large to hand the GPU.
                    jpegScaled.use { SkiaImage.makeFromBitmap(it).use { image -> image.reduceHighQuality(targetWidth, targetHeight) } }
                }
            } else if (keepSourceSize) {
                // Nothing to reduce, or a hero the GPU scales at draw. Rasterise exactly as Coil
                // would, so the common case (a source already smaller than the request) stays
                // identical to what shipped before.
                encoded.rasterize(encoded.width, encoded.height, SamplingMode.DEFAULT)
            } else {
                encoded.reduceHighQuality(targetWidth, targetHeight)
            }
            bitmap.setImmutable()
            DesktopArtworkTelemetry.recordDecode(
                sourceWidth = encoded.width,
                sourceHeight = encoded.height,
                outWidth = bitmap.width,
                outHeight = bitmap.height,
                destinationSized = options.hasDesktopArtworkDestinationSize(),
                sourceKey = options.diskCacheKey,
                requested = "${options.size.width}x${options.size.height}",
            )
            DesktopArtworkTelemetry.maybeLogSummary()
            // Unsampled lets Coil serve a full-size bitmap to a request of any size. Anything smaller
            // than its source is sampled, and Coil serves it only to requests it still covers.
            val sampled = bitmap.width < encoded.width || bitmap.height < encoded.height
            return DecodeResult(image = bitmap.asImage(), isSampled = sampled)
        } finally {
            encoded.close()
        }
    }

    /**
     * How far to reduce, following Coil's own rules so this cannot disagree with the rest of the
     * pipeline about what a request asked for.
     *
     * An [Dimension.Undefined] axis means "whatever the source is", which is what
     * `DecodeUtils.computeDstSize` does with it; its own return type is internal, so the two
     * dimensions are unpacked here instead. [Precision.INEXACT] is what
     * `AsyncImagePainter` sets whenever a caller has not chosen, and it is the clamp that stops a
     * small source being blown up to fill a large request.
     */
    private fun reductionMultiplier(srcWidth: Int, srcHeight: Int, options: Options): Double {
        val dstWidth = (options.size.width as? Dimension.Pixels)?.px ?: srcWidth
        val dstHeight = (options.size.height as? Dimension.Pixels)?.px ?: srcHeight
        val multiplier = DecodeUtils.computeSizeMultiplier(
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            dstWidth = dstWidth,
            dstHeight = dstHeight,
            scale = options.scale,
            maxSize = options.maxBitmapSize,
        )
        return if (options.precision == Precision.INEXACT) multiplier.coerceAtMost(1.0) else multiplier
    }

    class Factory : Decoder.Factory {
        /**
         * Accepts anything that reaches it, which is everything the SVG and animated factories
         * declined — so exactly the set Coil's own still-image decoder would have taken. A source it
         * cannot decode fails here the same way it would have failed there, since both go through
         * `Image.makeFromEncoded`.
         */
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder = HighQualityBitmapDecoder(result.source, options)
    }
}

// Halving is an exact 2x2 box average but a slightly soft pre-filter, while the Lanczos pass costs
// taps in proportion to the ratio it reduces by. Halving while at least 2x of the target remains
// leaves Lanczos a 2-4x step: simulated at 97% of a straight Lanczos from source, and it keeps a
// 2000px poster into a 4K TV Mode card at ~30 ms (decode thread) instead of ~65 ms for the full kernel.
private const val HalveWhileRatioAtLeast = 2

/**
 * Hero-size artwork that would shrink by under 2x (a 3840 px TMDB original into a 3264 px hero slot)
 * is kept at source size and scaled by the GPU at draw, where bilinear under 2x reads every texel.
 *
 * Measured per 4K backdrop (2026-10-16): JPEG decode ~140 ms, plus the Kotlin Lanczos pass 300–560
 * ms on the native build (with ~170 MB of arrays, which forced stop-the-world GCs while scrolling) or
 * Skia's CPU Catmull-Rom ~126 ms. Skipping the CPU resize leaves the decode alone. The cost is ~9 MB
 * more per cached hero (33 MB vs 24 MB). Posters keep the CPU reduction, since they shrink further and
 * are cheap. Hero-size means a target of ~2 MP, the same line as
 * [DesktopArtworkMemoryCache.LargeEntryBytes].
 */
internal fun keepsSourceSizeForGpu(srcWidth: Int, srcHeight: Int, targetWidth: Int, targetHeight: Int): Boolean =
    srcWidth < targetWidth * 2 && srcHeight < targetHeight * 2 &&
        targetWidth.toLong() * targetHeight >= GpuScaledMinTargetPixels

internal const val GpuScaledMinTargetPixels = 2_000_000L

/**
 * The encoded bytes in one exact-size array. A disk-cache hit is read straight from its file:
 * through okio it was read into a chain of 8 KB segments and then copied out, so every decode
 * allocated its file size twice (11% of all allocation while scrolling Home, JFR 2026-10-16).
 */
private fun ImageSource.readAllBytes(): ByteArray {
    val path = if (fileSystem == FileSystem.SYSTEM) fileOrNull() else null
    if (path != null) {
        runCatching { return Files.readAllBytes(path.toNioPath()) }
    }
    return source().readByteArray()
}

/**
 * The smallest k/8 JPEG decode scale whose output still covers the target on both axes, or 8 when
 * nothing smaller does. libjpeg-turbo sizes a k/8 decode as ceil(size * k / 8).
 */
internal fun jpegEighthsScale(srcWidth: Int, srcHeight: Int, targetWidth: Int, targetHeight: Int): Int =
    (1..8).first { k ->
        (srcWidth * k + 7) / 8 >= targetWidth && (srcHeight * k + 7) / 8 >= targetHeight
    }

/**
 * Hero-size JPEGs decoded straight at the smallest k/8 scale that still covers the slot, for
 * example a 3840 px TMDB original at 7/8 (3360 px) for a 3264 px slot. libjpeg-turbo does the
 * reduction inside the decode, so it costs nothing extra, the GPU never has to upscale, and the
 * bitmap is 24 MB instead of 33 MB. Measured 2026-10-16: no faster than a full decode (~140 ms for
 * 4K, which is dominated by entropy decoding), so the gain is memory only.
 *
 * Null (the caller decodes as before) for other formats, for a rotated EXIF orientation (a codec
 * decode ignores it, unlike [SkiaImage.makeFromEncoded]), when 8/8 is the only scale that covers the
 * slot, and on any codec failure.
 */
private fun decodeJpegEighths(bytes: ByteArray, targetWidth: Int, targetHeight: Int): Bitmap? {
    if (targetWidth.toLong() * targetHeight < GpuScaledMinTargetPixels) return null
    return runCatching {
        Codec.makeFromData(Data.makeFromBytes(bytes)).use { codec ->
            if (codec.encodedImageFormat != EncodedImageFormat.JPEG) return null
            if (codec.encodedOrigin != EncodedOrigin.TOP_LEFT) return null
            val k = jpegEighthsScale(codec.width, codec.height, targetWidth, targetHeight)
            if (k == 8) return null
            val bitmap = Bitmap()
            bitmap.allocN32Pixels((codec.width * k + 7) / 8, (codec.height * k + 7) / 8)
            try {
                codec.readPixels(bitmap)
            } catch (error: Throwable) {
                bitmap.close()
                throw error
            }
            bitmap
        }
    }.getOrNull()
}

/**
 * Box-halves while the source is still several times the target, then one area-correct Lanczos pass.
 *
 * This used to halve until under 2x and finish with a Mitchell cubic. Skia's cubic reads a fixed 4x4
 * neighbourhood, so it only works under 2x, and Mitchell's blur (b = 1/3) is how it avoids aliasing
 * there: simulated on posters it reached ~80% of ideal sharpness. [lanczosResampleTo] widens its
 * kernel with the ratio and reaches ~98%.
 */
internal fun SkiaImage.reduceHighQuality(targetWidth: Int, targetHeight: Int): Bitmap {
    var intermediate: Bitmap? = null
    try {
        var currentWidth = width
        var currentHeight = height
        while (true) {
            val halfWidth = currentWidth / 2
            val halfHeight = currentHeight / 2
            if (halfWidth < targetWidth * HalveWhileRatioAtLeast ||
                halfHeight < targetHeight * HalveWhileRatioAtLeast
            ) break
            val halved = intermediate
                ?.resampleTo(halfWidth, halfHeight, BoxHalvingSampling)
                ?: rasterize(halfWidth, halfHeight, BoxHalvingSampling)
            intermediate?.close()
            intermediate = halved
            currentWidth = halfWidth
            currentHeight = halfHeight
        }

        val source = intermediate ?: rasterize(width, height, SamplingMode.DEFAULT)
        intermediate = source
        if (currentWidth == targetWidth && currentHeight == targetHeight) {
            // Already there; ownership of the bitmap transfers to the caller.
            intermediate = null
            return source
        }
        return source.lanczosResampleTo(targetWidth, targetHeight)
    } finally {
        intermediate?.close()
    }
}

private fun SkiaImage.rasterize(width: Int, height: Int, sampling: SamplingMode): Bitmap {
    val target = Bitmap()
    target.allocN32Pixels(width, height)
    scalePixels(target.peekPixels()!!, sampling, false)
    return target
}

private fun Bitmap.resampleTo(width: Int, height: Int, sampling: SamplingMode): Bitmap {
    val image = SkiaImage.makeFromBitmap(this)
    return try {
        image.rasterize(width, height, sampling)
    } finally {
        image.close()
    }
}
