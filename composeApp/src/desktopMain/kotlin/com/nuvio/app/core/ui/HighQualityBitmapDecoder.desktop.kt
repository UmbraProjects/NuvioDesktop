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
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Image as SkiaImage
import kotlin.math.roundToInt

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
        val bytes = source.source().readByteArray()
        val encoded = SkiaImage.makeFromEncoded(bytes)
        try {
            val multiplier = reductionMultiplier(encoded.width, encoded.height, options)
            // Rounded, not truncated: 500 * (414 / 500.0) can come out a hair under 414, and one
            // pixel short of the slot turns the draw's 1:1 blit back into a resample.
            val targetWidth = (encoded.width * multiplier).roundToInt().coerceAtLeast(1)
            val targetHeight = (encoded.height * multiplier).roundToInt().coerceAtLeast(1)

            val bitmap = if (targetWidth >= encoded.width && targetHeight >= encoded.height) {
                // Nothing to reduce. Rasterise exactly as Coil would, so the common case (a source
                // already smaller than the request) stays identical to what shipped before.
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
            )
            DesktopArtworkTelemetry.maybeLogSummary()
            return DecodeResult(image = bitmap.asImage(), isSampled = multiplier < 1.0)
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
