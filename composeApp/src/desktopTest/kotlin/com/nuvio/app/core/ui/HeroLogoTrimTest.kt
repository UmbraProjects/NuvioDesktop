package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

class HeroLogoTrimTest {

    @Test
    fun `transparent margins are cropped to the visible artwork`() {
        val logo = bitmapWith(width = 100, height = 50, opaque = Rect.makeLTRB(10f, 5f, 40f, 25f))
        val trimmed = trimTransparentEdges(logo)
        assertEquals(30, trimmed.width)
        assertEquals(20, trimmed.height)
    }

    @Test
    fun `a logo cut tight is returned as it is`() {
        val logo = bitmapWith(width = 60, height = 20, opaque = Rect.makeLTRB(0f, 0f, 60f, 20f))
        assertSame(logo, trimTransparentEdges(logo))
    }

    @Test
    fun `a fully transparent image is left alone`() {
        val logo = bitmapWith(width = 40, height = 20, opaque = null)
        assertSame(logo, trimTransparentEdges(logo))
    }

    private fun bitmapWith(width: Int, height: Int, opaque: Rect?): Bitmap {
        val info = ImageInfo.makeN32(width, height, ColorAlphaType.PREMUL)
        val surface = Surface.makeRaster(info)
        try {
            surface.canvas.clear(Color.TRANSPARENT)
            if (opaque != null) surface.canvas.drawRect(opaque, Paint().apply { color = Color.WHITE })
            val bitmap = Bitmap().apply { allocPixels(info) }
            check(surface.readPixels(bitmap, 0, 0))
            return bitmap
        } finally {
            surface.close()
        }
    }
}
