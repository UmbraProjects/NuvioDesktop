package com.nuvio.app.core.ui

import java.awt.Dimension
import java.awt.image.BufferedImage
import java.awt.image.MultiResolutionImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The tray icon is 16px of a 1080px master, so both things that make it look bad are silent:
 * a glyph left swimming in the master's transparent margin, and a fast rescale that chews its
 * edges. Both are checked here against the real app icon.
 */
class DesktopTrayIconImageTest {
    private val iconUrl = checkNotNull(
        Thread.currentThread().contextClassLoader.getResource("icons/nuvio-app-icon.png"),
    ) { "app icon missing from the desktop resources" }

    @Test
    fun rendersVariantsForTheTraySizeAndAbove() {
        val image = assertNotNull(loadDesktopTrayIconImage(iconUrl, Dimension(16, 16)))
        val variants = (image as MultiResolutionImage).resolutionVariants
        val sizes = variants.map { it.getWidth(null) }

        assertEquals(16, sizes.first(), "the base variant must match the tray slot")
        assertEquals(sizes.sorted(), sizes, "resolution variants must be sorted by size")
        assertTrue(sizes.contains(32), "a HiDPI shell asks for larger variants: $sizes")
    }

    @Test
    fun cropsTheMasterSoTheGlyphFillsTheTraySlot() {
        val image = assertNotNull(loadDesktopTrayIconImage(iconUrl, Dimension(16, 16)))
        val base = (image as MultiResolutionImage).getResolutionVariant(16.0, 16.0) as BufferedImage

        val (minX, minY, maxX, maxY) = assertNotNull(
            opaqueBounds(base),
            "the rendered icon is entirely transparent",
        )
        val covered = maxOf(maxX - minX + 1, maxY - minY + 1)
        assertTrue(
            covered >= base.width - 2,
            "the glyph should fill the 16px slot, covered ${covered}px of ${base.width}",
        )

        val output = File("build/test-artifacts/tray-icon-16.png")
        output.parentFile?.mkdirs()
        ImageIO.write(base, "png", output)
        ImageIO.write(
            (image.getResolutionVariant(32.0, 32.0) as BufferedImage),
            "png",
            File("build/test-artifacts/tray-icon-32.png"),
        )
    }

    /**
     * Edges are resampled rather than dropped: a nearest-neighbour downscale of this artwork lands
     * almost every pixel on full or zero alpha, so a healthy spread of partial alpha is the signal
     * that the smooth path ran.
     */
    @Test
    fun resamplesEdgesInsteadOfClippingThem() {
        val image = assertNotNull(loadDesktopTrayIconImage(iconUrl, Dimension(16, 16)))
        val base = (image as MultiResolutionImage).getResolutionVariant(16.0, 16.0) as BufferedImage

        var partial = 0
        for (y in 0 until base.height) {
            for (x in 0 until base.width) {
                val alpha = base.getRGB(x, y) ushr 24
                if (alpha in 1..254) partial++
            }
        }
        assertTrue(partial >= 16, "expected antialiased edges, found $partial partially transparent pixels")
    }

    private data class OpaqueBounds(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int)

    private fun opaqueBounds(image: BufferedImage): OpaqueBounds? {
        var minX = image.width
        var minY = image.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if ((image.getRGB(x, y) ushr 24) < 16) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        return if (maxX < minX) null else OpaqueBounds(minX, minY, maxX, maxY)
    }
}
