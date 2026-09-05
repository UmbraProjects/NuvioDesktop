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

/**
 * Decodes still images, reducing them properly on the way rather than leaving that to the draw path.
 *
 * Coil's own non-Android decoder decodes at full resolution and then reduces to the requested size
 * with `SamplingMode.DEFAULT`, which is `FilterMipmap(NEAREST, NONE)` — nearest-neighbour. That is
 * the reason [ScaledBitmapPainter] exists: past about 2x minification a single-pass filter discards
 * most of the source, so the app re-reduces from a deliberately oversized 1536 px request using
 * repeated box-halving and a Mitchell cubic, and does it inside `onDraw`.
 *
 * This moves that reduction to where it belongs — the decode dispatcher — and removes the
 * nearest-neighbour step entirely, so a large source is no longer aliased before the careful pass
 * ever sees it.
 *
 * **Registered behind the animated and SVG factories, and it changes no sizing yet.** Requests are
 * still pinned at 1536 px by `NuvioAsyncImage`, so for a typical `w500` poster the multiplier is 1.0,
 * this performs the same 1:1 blit into an N32 bitmap that Coil does, and [ScaledBitmapPainter] still
 * reduces to the card size exactly as before — byte for byte. Only sources larger than 1536 px change
 * at all, and only by losing an aliasing step.
 *
 * Sizing the request from the destination is the separate half of that change, and it is the one that
 * makes this decoder pay off: the reduction then lands here instead of on the draw thread, and the
 * memory cache holds card-sized bitmaps. That half needs `rememberConstraintsSizeResolver`, whose
 * `size()` suspends until layout constraints arrive, so it is deliberately not bundled in here.
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
            val targetWidth = (encoded.width * multiplier).toInt().coerceAtLeast(1)
            val targetHeight = (encoded.height * multiplier).toInt().coerceAtLeast(1)

            val bitmap = if (targetWidth >= encoded.width && targetHeight >= encoded.height) {
                // Nothing to reduce. Rasterise exactly as Coil would, so the common case (a source
                // already smaller than the request) stays identical to what shipped before.
                encoded.rasterize(encoded.width, encoded.height, SamplingMode.DEFAULT)
            } else {
                encoded.reduceHighQuality(targetWidth, targetHeight)
            }
            bitmap.setImmutable()
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

/**
 * Box-halves until the remaining reduction is under 2x, then one Mitchell pass.
 *
 * The same technique and the same samplers [ScaledBitmapPainter] uses, for the same reason: a cubic
 * reads a fixed 4x4 neighbourhood however far it is reducing, so past 2x it simply discards most of
 * the source. Each halving is an exact 2x2 box average, so nothing is thrown away before the final,
 * well-conditioned step.
 */
private fun SkiaImage.reduceHighQuality(targetWidth: Int, targetHeight: Int): Bitmap {
    var intermediate: Bitmap? = null
    try {
        var currentWidth = width
        var currentHeight = height
        while (true) {
            val halfWidth = currentWidth / 2
            val halfHeight = currentHeight / 2
            // Stop before either axis would undershoot; the cubic handles the remainder.
            if (halfWidth < targetWidth || halfHeight < targetHeight) break
            val halved = intermediate
                ?.resampleTo(halfWidth, halfHeight, BoxHalvingSampling)
                ?: rasterize(halfWidth, halfHeight, BoxHalvingSampling)
            intermediate?.close()
            intermediate = halved
            currentWidth = halfWidth
            currentHeight = halfHeight
        }

        if (currentWidth == targetWidth && currentHeight == targetHeight) {
            // Halving landed exactly on the target; a further cubic pass would only soften it.
            intermediate?.let { exact ->
                intermediate = null
                return exact
            }
            return rasterize(targetWidth, targetHeight, SamplingMode.DEFAULT)
        }
        return intermediate
            ?.resampleTo(targetWidth, targetHeight, HighQualityDesktopResampler)
            ?: rasterize(targetWidth, targetHeight, HighQualityDesktopResampler)
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
