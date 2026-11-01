package com.nuvio.app.core.ui

import java.awt.Dimension
import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage
import java.lang.ref.WeakReference
import java.net.URI
import java.net.URL
import javax.imageio.ImageIO

// The app icon is a 1080px master with roughly a quarter of its width as transparent margin. Left
// alone it lands in the tray as a small glyph adrift in an empty box, so the artwork is cropped to
// what is actually drawn and given a margin of its own.
private const val TrayIconContentMargin = 0.06f

// Variants Windows can ask for as the shell scales: 100% through 300% of a 16px tray slot.
private val TrayIconVariantSizes = intArrayOf(16, 20, 24, 32, 40, 48)

// What a window gets asked for: ICON_SMALL (16px) and ICON_BIG (32px) at 100-300%, which is what
// the taskbar and Alt-Tab draw from, plus the larger ones Task View and the shell's jump lists use.
private val WindowIconVariantSizes = intArrayOf(16, 20, 24, 30, 32, 36, 40, 48, 60, 64, 72, 80, 96, 128, 256)

/**
 * Loads the app icon as a tray image.
 *
 * `TrayIcon.setImageAutoSize(true)` scales with the AWT toolkit's nearest-neighbour path, which is
 * what makes the shipped icon look chewed at 16px. This supplies the variants itself (see
 * [iconVariant]) and hands them over as a
 * [BaseMultiResolutionImage] so a HiDPI shell can pick a sharper one.
 *
 * Returns null when the resource cannot be decoded; the caller keeps whatever fallback it had.
 */
internal fun loadDesktopTrayIconImage(url: URL, trayIconSize: Dimension): Image? {
    val baseSize = trayIconSize.width.takeIf { it > 0 }?.coerceIn(16, 64) ?: 16
    val sizes = (intArrayOf(baseSize) + TrayIconVariantSizes)
        .filter { it >= baseSize }
        .distinct()
        .sorted()
    val variants = sizes.mapNotNull { size -> iconVariant(url, size) }.toTypedArray<Image>()
    if (variants.isEmpty()) return null
    return runCatching { BaseMultiResolutionImage(0, *variants) }
        .getOrElse { variants.firstOrNull() }
}

/**
 * Loads the app icon as the per-size list for `Window.setIconImages`, smallest first.
 *
 * Compose's `Window(icon = painter)` renders the painter once at 192dp and hands AWT that single
 * image; Windows then shrinks it — transparent margin included — to the 32px `ICON_BIG` the
 * taskbar shows, so the glyph came out a size smaller than every neighbour's. With an exact
 * variant for each size AWT asks for, nothing is rescaled by the shell at all.
 *
 * Empty when the resource cannot be decoded; the caller then keeps Compose's icon.
 */
internal fun loadDesktopWindowIconImages(url: URL): List<Image> =
    WindowIconVariantSizes.asList().mapNotNull { size -> iconVariant(url, size) }

/**
 * The icon at [size]: the copy drawn for that size when the resources have one, else the master
 * cropped and stepped down here. Java2D's bicubic steps work on straight (not premultiplied)
 * alpha and stair-step the glyph's round corners, and the glass rim, under a pixel wide at 32px,
 * breaks into specks; the drawn copies are area-averaged with the rim one pixel wide at their
 * size. They sit beside the master as `sizes/<name>-<size>.png`.
 */
private fun iconVariant(url: URL, size: Int): BufferedImage? =
    loadPrerenderedVariant(url, size) ?: loadCroppedIconMaster(url)?.let { resampleTo(it, size) }

private fun loadPrerenderedVariant(url: URL, size: Int): BufferedImage? {
    val name = url.path.substringAfterLast('/').substringBeforeLast('.')
    // ImageIO.read throws for a missing resource; a size without its own copy is resampled instead.
    val sibling = url.toString().substringBeforeLast('/') + "/sizes/$name-$size.png"
    return runCatching { ImageIO.read(URI.create(sibling).toURL()) }.getOrNull()
}

// The tray and the window both start from the same decode + crop, which is the expensive part;
// the second caller gets it for free. Weakly held: the ~1,000 px master is 3.65 MB, and a strong
// reference kept it for the whole session after both callers were done (heap dump, 2026-10-16).
private val croppedMasterLock = Any()
private var croppedMaster: Pair<URL, WeakReference<BufferedImage>>? = null

private fun loadCroppedIconMaster(url: URL): BufferedImage? {
    synchronized(croppedMasterLock) {
        croppedMaster?.takeIf { it.first == url }?.second?.get()?.let { return it }
        val source = runCatching { ImageIO.read(url) }.getOrNull() ?: return null
        val cropped = cropToContent(source)
        croppedMaster = url to WeakReference(cropped)
        return cropped
    }
}

/** The square crop around every non-transparent pixel, plus [TrayIconContentMargin] of breathing room. */
private fun cropToContent(source: BufferedImage): BufferedImage {
    var minX = source.width
    var minY = source.height
    var maxX = -1
    var maxY = -1
    // One bulk read: per-pixel getRGB on a 1080px master is a million calls through the raster.
    val row = IntArray(source.width)
    for (y in 0 until source.height) {
        source.getRGB(0, y, source.width, 1, row, 0, source.width)
        for (x in row.indices) {
            if ((row[x] ushr 24) == 0) continue
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
