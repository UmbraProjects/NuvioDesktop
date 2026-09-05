package com.nuvio.app.core.ui

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Paints the tray menu offscreen. A tray menu is only ever seen on a real desktop, so the one
 * thing worth pinning down here is what the old Win32 menu got wrong and what a hand-painted one
 * can get wrong just as easily: a background that is not the app's dark surface, and labels that
 * are not readable on it.
 *
 * The rendered PNG is written to the build directory for eyeballing.
 */
class DesktopTrayMenuRenderTest {
    private val entries = listOf(
        DesktopTrayMenuEntry.Action("Open Nuvio") {},
        DesktopTrayMenuEntry.Separator,
        DesktopTrayMenuEntry.Info("Nuvio 1.13.0"),
        DesktopTrayMenuEntry.Separator,
        DesktopTrayMenuEntry.Action("Exit") {},
    )

    @Test
    fun paintsDarkPanelWithReadableLabels() {
        val image = renderMenu(drawShadow = false)

        val background = image.getRGB(image.width / 2, image.height / 2)
        assertTrue(
            luminance(background) < 40,
            "menu body should keep the app's dark surface, was ${Integer.toHexString(background)}",
        )

        val labelPixels = countPixels(image) { luminance(it) > 140 }
        assertTrue(labelPixels > 100, "expected light label text on the dark panel, found $labelPixels pixels")
    }

    @Test
    fun cornersAreRoundedAwayWhenTheShadowMarginIsDrawn() {
        val image = renderMenu(drawShadow = true)

        // The very corner of the image is outside both the shadow falloff and the rounded body.
        val corner = image.getRGB(0, 0)
        assertTrue((corner ushr 24) == 0, "menu corner should be fully transparent, was ${Integer.toHexString(corner)}")
    }

    private fun renderMenu(drawShadow: Boolean): BufferedImage {
        val panel = TrayMenuPanel(entries = entries, drawShadow = drawShadow, onSelected = {})
        val size = panel.preferredSize
        panel.setSize(size)
        panel.doLayout()
        // Lay the children out too: BoxLayout only reaches them once the panel has a size.
        panel.validate()

        val image = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            panel.paint(graphics)
        } finally {
            graphics.dispose()
        }

        val output = File("build/test-artifacts/tray-menu-${if (drawShadow) "shadow" else "flat"}.png")
        output.parentFile?.mkdirs()
        ImageIO.write(image, "png", output)
        return image
    }

    private fun luminance(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    private fun countPixels(image: BufferedImage, predicate: (Int) -> Boolean): Int {
        var count = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (predicate(image.getRGB(x, y))) count++
            }
        }
        return count
    }
}
