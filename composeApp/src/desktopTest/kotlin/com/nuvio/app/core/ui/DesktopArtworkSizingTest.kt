package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.DataSource
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Dimension
import coil3.size.Scale
import coil3.size.Size
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesktopArtworkSizingTest {
    @Test
    fun `layout uses physical pixels and updates the request when density or card size changes`() = runComposeUiTest {
        val resolver = DesktopArtworkSizeResolver()
        val density = mutableStateOf(1f)
        val cardWidth = mutableStateOf(210)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(density.value)) {
                // requiredSize, not size: the test scene's own 1024x768 constraints would
                // otherwise clamp the tall card and the resolver would read the clamp, not the card.
                Box(Modifier.requiredSize(cardWidth.value.dp, (cardWidth.value * 1.5f).dp).then(resolver))
            }
        }
        waitForIdle()
        assertEquals(Size(210, 315), resolver.requestSize)
        runOnIdle { density.value = 2f }
        waitForIdle()
        assertEquals(Size(420, 630), resolver.requestSize)
        runOnIdle { cardWidth.value = 300 }
        waitForIdle()
        assertEquals(Size(600, 900), resolver.requestSize)
    }

    @Test
    fun `an unbounded axis stays intrinsic and bounded axes decode at the exact slot size`() {
        assertEquals(Dimension.Undefined, desktopArtworkSize(Size(Dimension.Pixels(210), Dimension.Undefined)).height)
        assertEquals(Dimension.Pixels(210), desktopArtworkSize(Size(Dimension.Pixels(210), Dimension.Undefined)).width)
        // Neither axis bounded would otherwise reach the decoder as Size.ORIGINAL.
        assertEquals(Size(1536, 1536), desktopArtworkSize(Size.ORIGINAL))
        for (pixels in listOf(34, 52, 210, 420, 630, 1920, 3840)) {
            assertEquals(pixels, desktopArtworkDimension(pixels))
        }
    }

    @Test
    fun `a one pixel drift keeps the request but a new bucket does not`() {
        assertTrue(Size(414, 621).sameArtworkBucketAs(Size(410, 617)))
        assertTrue(!Size(414, 621).sameArtworkBucketAs(Size(420, 621)))
        assertTrue(!Size(414, 621).sameArtworkBucketAs(null))
    }

    @Test
    fun `lanczos reduction keeps flat colour flat and lands on the exact size`() {
        val source = org.jetbrains.skia.Bitmap().apply { allocN32Pixels(500, 750) }
        org.jetbrains.skia.Canvas(source).clear(0xFF3366CC.toInt())
        val image = org.jetbrains.skia.Image.makeFromBitmap(source)
        try {
            for ((w, h) in listOf(414 to 621, 207 to 311, 100 to 150)) {
                val out = image.reduceHighQuality(w, h)
                assertEquals(w, out.width)
                assertEquals(h, out.height)
                for ((x, y) in listOf(0 to 0, w / 2 to h / 2, w - 1 to h - 1)) {
                    assertEquals(0xFF3366CC.toInt(), out.getColor(x, y))
                }
                out.close()
            }
        } finally {
            image.close()
            source.close()
        }
    }

    @Test
    fun `only hero-size artwork shrinking by under 2x is left for the GPU to scale`() {
        // A 4K TMDB original into a 3264 px hero slot.
        assertTrue(keepsSourceSizeForGpu(3840, 2160, 3264, 1836))
        // 2x or more still reduces on the CPU: the GPU's bilinear would skip source pixels.
        assertTrue(!keepsSourceSizeForGpu(9504, 6336, 3264, 2176))
        // A poster-size target always reduces, however mild.
        assertTrue(!keepsSourceSizeForGpu(500, 750, 414, 621))
    }

    @Test
    fun `the JPEG decode scale is the smallest eighth that still covers the slot`() {
        // 4K into a 3264x1836 slot: 7/8 is 3360x1890.
        assertEquals(7, jpegEighthsScale(3840, 2160, 3264, 1836))
        // A 9504 px original: 3/8 is 3564x2376.
        assertEquals(3, jpegEighthsScale(9504, 6336, 3264, 1836))
        // Nothing smaller covers an exact fit.
        assertEquals(8, jpegEighthsScale(3264, 1836, 3264, 1836))
        // Rounds up the way libjpeg-turbo does: 7/8 of 3731 is 3264.6, so 3265 covers 3265.
        assertEquals(7, jpegEighthsScale(3731, 2099, 3265, 1836))
    }

    @Test
    fun `a hero-size JPEG decodes at the scale that covers its slot`() = runBlocking {
        val file = Files.createTempFile("nuvio-hero-jpeg", ".jpg").toFile()
        ImageIO.write(BufferedImage(3840, 2160, BufferedImage.TYPE_INT_RGB), "jpg", file)
        val loader = ImageLoader.Builder(PlatformContext.INSTANCE)
            .components { add(HighQualityBitmapDecoder.Factory()) }
            .build()
        try {
            val result = loader.execute(
                ImageRequest.Builder(PlatformContext.INSTANCE)
                    .data(file)
                    .size(3264, 1836)
                    .precision(coil3.size.Precision.INEXACT)
                    .scale(coil3.size.Scale.FILL)
                    .build(),
            )
            val image = (result as SuccessResult).image
            assertEquals(3360, image.width)
            assertEquals(1890, image.height)
        } finally {
            loader.shutdown()
            file.delete()
        }
    }

    @Test
    fun `hero-size requests share one entry whatever their size or tag, and concurrent ones decode once`() = runBlocking {
        val file = Files.createTempFile("nuvio-hero-share", ".jpg").toFile()
        ImageIO.write(BufferedImage(3840, 2160, BufferedImage.TYPE_INT_RGB), "jpg", file)
        val context = PlatformContext.INSTANCE
        val loader = ImageLoader.Builder(context)
            .memoryCache { MemoryCache.Builder().maxSizeBytes(256L * 1024 * 1024).build() }
            .components {
                add(DesktopArtworkSizeInterceptor())
                add(HighQualityBitmapDecoder.Factory())
            }.build()
        fun homeHero() = ImageRequest.Builder(context).data(file).size(3264, 1836)
            .precision(coil3.size.Precision.INEXACT).scale(Scale.FILL).build()
        fun detailsHero() = ImageRequest.Builder(context).data(file)
            .scale(Scale.FILL).nuvioArtworkRequestSize(3840, 2160).build()
        try {
            val home = loader.execute(homeHero()) as SuccessResult
            assertEquals(3360, home.image.width)
            // The Details hero is size-tagged and wider than the 7/8 decode Home holds: it decodes
            // the full image under the same key, which then serves Home without another decode.
            val details = loader.execute(detailsHero()) as SuccessResult
            assertEquals(3840, details.image.width)
            assertEquals(home.memoryCacheKey, details.memoryCacheKey)
            val homeAgain = loader.execute(homeHero()) as SuccessResult
            assertEquals(DataSource.MEMORY_CACHE, homeAgain.dataSource)
            assertEquals(3840, homeAgain.image.width)

            loader.memoryCache!!.clear()
            val pair = kotlinx.coroutines.coroutineScope {
                listOf(async { loader.execute(detailsHero()) }, async { loader.execute(detailsHero()) })
                    .map { (it.await() as SuccessResult).dataSource }
            }
            assertEquals(1, pair.count { it == DataSource.MEMORY_CACHE }, "sources: $pair")
        } finally {
            loader.shutdown()
            file.delete()
        }
    }

    @Test
    fun `a nearly full-size hero in memory serves the Details hero without a second decode`() = runBlocking {
        val file = Files.createTempFile("nuvio-hero-near", ".jpg").toFile()
        ImageIO.write(BufferedImage(3840, 2160, BufferedImage.TYPE_INT_RGB), "jpg", file)
        val context = PlatformContext.INSTANCE
        lateinit var loader: ImageLoader
        loader = ImageLoader.Builder(context)
            .memoryCache { MemoryCache.Builder().maxSizeBytes(256L * 1024 * 1024).build() }
            .components {
                add(DesktopArtworkSizeInterceptor(memoryCache = { loader.memoryCache }))
                add(HighQualityBitmapDecoder.Factory())
            }.build()
        fun homeHero() = ImageRequest.Builder(context).data(file).size(3264, 1836)
            .precision(coil3.size.Precision.INEXACT).scale(Scale.FILL).build()
        fun detailsHero() = ImageRequest.Builder(context).data(file)
            .scale(Scale.FILL).nuvioArtworkRequestSize(3840, 2160).build()
        try {
            val home = loader.execute(homeHero()) as SuccessResult
            assertEquals(3360, home.image.width)
            // 3360 of 3840 is 87.5%: Details takes Home's bitmap (and its texture) as it is.
            val details = loader.execute(detailsHero()) as SuccessResult
            assertEquals(DataSource.MEMORY_CACHE, details.dataSource)
            assertEquals(3360, details.image.width)

            // Cold: Details decodes at full size, and that bitmap then serves Home.
            loader.memoryCache!!.clear()
            val cold = loader.execute(detailsHero()) as SuccessResult
            assertEquals(3840, cold.image.width)
            assertNotEquals(DataSource.MEMORY_CACHE, cold.dataSource)
            assertEquals(DataSource.MEMORY_CACHE, (loader.execute(homeHero()) as SuccessResult).dataSource)
        } finally {
            loader.shutdown()
            file.delete()
        }
    }

    @Test
    fun `small artwork decodes separately from a large cached source and reuses its own entry`() = runBlocking {
        val file = Files.createTempFile("nuvio-artwork-test", ".png").toFile()
        ImageIO.write(BufferedImage(800, 1200, BufferedImage.TYPE_INT_RGB), "png", file)
        val context = PlatformContext.INSTANCE
        val loader = ImageLoader.Builder(context)
            .memoryCache { MemoryCache.Builder().maxSizeBytes(32L * 1024 * 1024).build() }
            .components {
                add(DesktopArtworkSizeInterceptor())
                add(HighQualityBitmapDecoder.Factory())
            }.build()
        try {
            val large = loader.execute(ImageRequest.Builder(context).data(file)
                .memoryCacheKey("same-artwork").size(800, 1200).build()) as SuccessResult
            val request = ImageRequest.Builder(context).data(file).memoryCacheKey("same-artwork")
                .scale(Scale.FILL).nuvioArtworkRequestSize(250, 375).build()
            val small = loader.execute(request) as SuccessResult
            assertEquals(250, small.image.width)
            assertEquals(375, small.image.height)
            assertTrue(small.image.size < large.image.size / 4)
            assertNotEquals(large.memoryCacheKey, small.memoryCacheKey)
            assertEquals(DataSource.MEMORY_CACHE, (loader.execute(request) as SuccessResult).dataSource)
            // Neither size variant invalidates the other, including explicit caller cache keys.
            assertEquals(800, large.image.width)
            val before = loader.memoryCache!!.size
            val prefetch = loader.execute(ImageRequest.Builder(context).data(file)
                .nuvioArtworkRequestSize().build()) as SuccessResult
            assertEquals(before, loader.memoryCache!!.size)
            assertTrue(prefetch.image.size < 1024)
        } finally {
            loader.shutdown()
            file.delete()
        }
    }
}
