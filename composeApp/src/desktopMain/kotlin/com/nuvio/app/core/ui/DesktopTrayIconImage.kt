package com.nuvio.app.core.ui

import java.awt.Dimension
import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage
import java.net.URL
import javax.imageio.ImageIO

// The app icon is a 1080px master with roughly a quarter of its width as transparent margin. Left
// alone it lands in the tray as a small glyph adrift in an empty box, so the artwork is cropped to
// what is actually drawn and given a margin of its own.
private const val TrayIconContentMargin = 0.06f

// Variants Windows can ask for as the shell scales: 100% through 300% of a 16px tray slot.
private val TrayIconVariantSizes = intArrayOf(16, 20, 24, 32, 40, 48)

/**
 * Loads the app icon as a tray image.
 *
 * `TrayIcon.setImageAutoSize(true)` scales with the AWT toolkit's nearest-neighbour path, which is
 * what makes the shipped icon look chewed at 16px. This renders the variants itself — cropped,
 * then stepped down by halves with bicubic resampling — and hands them over as a
 * [BaseMultiResolutionImage] so a HiDPI shell can pick a sharper one.
 *
 * Returns null when the resource cannot be decoded; the caller keeps whatever fallback it had.
 */
internal fun loadDesktopTrayIconImage(url: URL, trayIconSize: Dimension): Image? {
    val source = runCatching { ImageIO.read(url) }.getOrNull() ?: return null
    val cropped = cropToContent(source)
    val baseSize = trayIconSize.width.takeIf { it > 0 }?.coerceIn(16, 64) ?: 16
    val sizes = (intArrayOf(baseSize) + TrayIconVariantSizes)
        .filter { it >= baseSize }
        .distinct()
        .sorted()
    val variants = sizes.map { size -> resampleTo(cropped, size) }.toTypedArray<Image>()
    return runCatching { BaseMultiResolutionImage(0, *variants) }
        .getOrElse { variants.firstOrNull() }
}

/** The square crop around every non-transparent pixel, plus [TrayIconContentMargin] of breathing room. */
private fun cropToContent(source: BufferedImage): BufferedImage {
    var minX = source.width
    var minY = source.height
    var maxX = -1
    var maxY = -1
    for (y in 0 until source.height) {
        for (x in 0 until source.width) {
            if ((source.getRGB(x, y) ushr 24) == 0) continue
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
    }
    if (maxX < minX || maxY < minY) return source

    val contentWidth = maxX - minX + 1
    val contentHeight = maxY - minY + 1
    val side = (maxOf(contentWidth, contentHeight) * (1f + TrayIconContentMargin * 2f)).toInt()
    val centerX = minX + contentWidth / 2
    val centerY = minY + contentHeight / 2

    // The crop is allowed to run past the source edges - the icon is square and centred, so the
    // overhang is transparent - and the padded copy keeps the glyph centred either way.
    val target = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
    val graphics = target.createGraphics()
    try {
        graphics.drawImage(source, side / 2 - centerX, side / 2 - centerY, null)
    } finally {
        graphics.dispose()
    }
    return target
}

/** Steps the image down by halves before the final resize, which is what keeps small sizes clean. */
private fun resampleTo(source: BufferedImage, size: Int): BufferedImage {
    var current = source
    while (current.width / 2 > size) {
        current = resize(current, current.width / 2)
    }
    return resize(current, size)
}

private fun resize(source: BufferedImage, size: Int): BufferedImage {
    val target = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val graphics = target.createGraphics()
    try {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.drawImage(source, 0, 0, size, size, null)
    } finally {
        graphics.dispose()
    }
    return target
}
