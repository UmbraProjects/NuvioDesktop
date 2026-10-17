package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coil3.Bitmap
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Size
import coil3.transform.Transformation
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface

@Composable
internal actual fun rememberHeroLogoModel(logoUrl: String): Any {
    val context = LocalPlatformContext.current
    return remember(context, logoUrl) {
        ImageRequest.Builder(context)
            .data(logoUrl)
            .transformations(TrimTransparentEdgesTransformation)
            .build()
    }
}

/**
 * Crops a decoded logo to its visible pixels. [cacheKey] keeps the trimmed bitmap apart from the
 * untrimmed one in the memory cache; the disk cache still holds the original download.
 */
private object TrimTransparentEdgesTransformation : Transformation() {
    override val cacheKey: String = "nuvio:trim-transparent-edges"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap = trimTransparentEdges(input)
}

/**
 * [input] cropped to the smallest rectangle holding every pixel more opaque than
 * [LOGO_ALPHA_THRESHOLD]; [input] itself when there is nothing worth trimming, when the image is
 * fully transparent, or when its alpha cannot be read. Never throws: a logo that cannot be trimmed is
 * still a logo.
 */
internal fun trimTransparentEdges(input: Bitmap): Bitmap {
    val width = input.width
    val height = input.height
    if (width <= 0 || height <= 0 || input.imageInfo.colorInfo.alphaType == ColorAlphaType.OPAQUE) return input
    return runCatching {
        val alpha = input.readPixels(
            ImageInfo(width, height, ColorType.ALPHA_8, ColorAlphaType.PREMUL),
            width,
            0,
            0,
        ) ?: return input
        var top = -1
        var bottom = -1
        var left = width
        var right = -1
        for (y in 0 until height) {
            val row = y * width
            var rowLeft = -1
            var rowRight = -1
            for (x in 0 until width) {
                if ((alpha[row + x].toInt() and 0xFF) > LOGO_ALPHA_THRESHOLD) {
                    if (rowLeft < 0) rowLeft = x
                    rowRight = x
                }
            }
            if (rowLeft >= 0) {
                if (top < 0) top = y
                bottom = y
                if (rowLeft < left) left = rowLeft
                if (rowRight > right) right = rowRight
            }
        }
        if (top < 0) return input
        val cropWidth = right - left + 1
        val cropHeight = bottom - top + 1
        // A margin of a pixel or two is not worth a second bitmap.
        if (width - cropWidth <= MIN_TRIM_PX && height - cropHeight <= MIN_TRIM_PX) return input

        val surface = Surface.makeRaster(input.imageInfo.withWidthHeight(cropWidth, cropHeight))
        try {
            Image.makeFromBitmap(input).use { image ->
                surface.canvas.drawImage(image, -left.toFloat(), -top.toFloat())
            }
            val output = Bitmap().apply { allocPixels(input.imageInfo.withWidthHeight(cropWidth, cropHeight)) }
            if (surface.readPixels(output, 0, 0)) {
                output.setImmutable()
                output
            } else {
                input
            }
        } finally {
            surface.close()
        }
    }.getOrDefault(input)
}

/** Out of 255: faint enough to keep soft shadows and glows, firm enough to ignore stray noise. */
private const val LOGO_ALPHA_THRESHOLD = 8
private const val MIN_TRIM_PX = 2
