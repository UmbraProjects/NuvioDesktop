package com.nuvio.app.core.ui

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ImageInfo
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The streaming resampler must produce exactly what the original whole-image version did. That
 * version is kept below, verbatim in its arithmetic, as the reference.
 */
class LanczosResamplerTest {

    private fun noiseBitmap(width: Int, height: Int, seed: Int): Bitmap {
        val random = Random(seed)
        val info = ImageInfo.makeN32Premul(width, height)
        val pixels = ByteArray(width * height * 4)
        for (i in pixels.indices step 4) {
            val alpha = if (random.nextInt(8) == 0) random.nextInt(256) else 255
            for (c in 0 until 3) pixels[i + c] = random.nextInt(alpha + 1).toByte()
            pixels[i + 3] = alpha.toByte()
        }
        return Bitmap().apply {
            allocPixels(info)
            installPixels(info, pixels, width * 4)
        }
    }

    private fun Bitmap.bytes(): ByteArray =
        readPixels(ImageInfo.makeN32Premul(width, height), width * 4, 0, 0)!!

    @Test
    fun `streaming output is byte-identical to the whole-image reference`() {
        for ((src, dst) in listOf(
            (1000 to 1500) to (414 to 621),
            (640 to 960) to (207 to 311),
            (300 to 450) to (299 to 449),
            (97 to 61) to (13 to 9),
        )) {
            val source = noiseBitmap(src.first, src.second, seed = src.first)
            val expected = referenceLanczos(source.bytes(), src.first, src.second, dst.first, dst.second)
            val actual = source.lanczosResampleTo(dst.first, dst.second)
            assertEquals(dst.first, actual.width)
            assertEquals(dst.second, actual.height)
            assertContentEquals(expected, actual.bytes(), "mismatch for $src -> $dst")
            actual.close()
            source.close()
        }
    }

    // ---- reference: the pre-streaming implementation ----

    private fun referenceLanczos(src: ByteArray, srcWidth: Int, srcHeight: Int, targetWidth: Int, targetHeight: Int): ByteArray {
        val xTaps = Taps(srcWidth, targetWidth)
        val rowWidth = targetWidth * 4
        val mid = FloatArray(rowWidth * srcHeight)
        val srcRow = FloatArray(srcWidth * 4)
        for (y in 0 until srcHeight) {
            val rowIn = y * srcWidth * 4
            for (k in srcRow.indices) srcRow[k] = (src[rowIn + k].toInt() and 0xFF).toFloat()
            val rowOut = y * rowWidth
            for (x in 0 until targetWidth) {
                var c0 = 0f; var c1 = 0f; var c2 = 0f; var c3 = 0f
                var i = xTaps.start[x] * 4
                var w = x * xTaps.maxTaps
                val end = w + xTaps.count[x]
                while (w < end) {
                    val weight = xTaps.weights[w]
                    c0 += weight * srcRow[i]; c1 += weight * srcRow[i + 1]
                    c2 += weight * srcRow[i + 2]; c3 += weight * srcRow[i + 3]
                    i += 4; w++
                }
                val o = rowOut + x * 4
                mid[o] = c0; mid[o + 1] = c1; mid[o + 2] = c2; mid[o + 3] = c3
            }
        }
        val yTaps = Taps(srcHeight, targetHeight)
        val out = ByteArray(rowWidth * targetHeight)
        val acc = FloatArray(rowWidth)
        for (y in 0 until targetHeight) {
            acc.fill(0f)
            val wBase = y * yTaps.maxTaps
            for (t in 0 until yTaps.count[y]) {
                val weight = yTaps.weights[wBase + t]
                val rowIn = (yTaps.start[y] + t) * rowWidth
                for (k in 0 until rowWidth) acc[k] += weight * mid[rowIn + k]
            }
            val rowOut = y * rowWidth
            var k = 0
            while (k < rowWidth) {
                val alpha = acc[k + 3].roundToInt().coerceIn(0, 255)
                for (c in 0 until 4) {
                    val value = if (c == 3) alpha else acc[k + c].roundToInt().coerceIn(0, alpha)
                    out[rowOut + k + c] = value.toByte()
                }
                k += 4
            }
        }
        return out
    }

    private class Taps(srcSize: Int, dstSize: Int) {
        val maxTaps: Int
        val start = IntArray(dstSize)
        val count = IntArray(dstSize)
        val weights: FloatArray

        init {
            val scale = srcSize.toDouble() / dstSize
            val filterScale = max(scale, 1.0)
            val support = 3.0 * filterScale
            maxTaps = ceil(support).toInt() * 2 + 1
            weights = FloatArray(dstSize * maxTaps)
            for (i in 0 until dstSize) {
                val center = (i + 0.5) * scale
                val first = max(floor(center - support + 0.5).toInt(), 0)
                val last = min(floor(center + support + 0.5).toInt(), srcSize)
                val n = min(last - first, maxTaps)
                var total = 0.0
                val base = i * maxTaps
                for (t in 0 until n) {
                    val w = lanczos((first + t - center + 0.5) / filterScale)
                    weights[base + t] = w.toFloat()
                    total += w
                }
                if (total != 0.0) for (t in 0 until n) weights[base + t] = (weights[base + t] / total).toFloat()
                start[i] = first
                count[i] = n
            }
        }

        private fun lanczos(x: Double): Double {
            val ax = abs(x)
            if (ax < 1e-7) return 1.0
            if (ax >= 3.0) return 0.0
            val px = PI * x
            return 3.0 * sin(px) * sin(px / 3.0) / (px * px)
        }
    }
}
