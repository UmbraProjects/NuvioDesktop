package com.nuvio.app.features.home

import androidx.compose.ui.graphics.Color
import coil3.BitmapImage
import coil3.Image
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private const val HueBucketCount = 24
private const val SampleColumns = 32
private const val SampleRows = 18

internal actual fun extractHeroAccentColor(image: Image): Color? {
    val bitmap = (image as? BitmapImage)?.bitmap ?: return null
    if (bitmap.width <= 0 || bitmap.height <= 0) return null

    val buckets = Array(HueBucketCount) { ColorBucket() }
    repeat(SampleRows) { row ->
        val y = ((row + 0.5f) * bitmap.height / SampleRows)
            .toInt()
            .coerceIn(0, bitmap.height - 1)
        repeat(SampleColumns) { column ->
            val x = ((column + 0.5f) * bitmap.width / SampleColumns)
                .toInt()
                .coerceIn(0, bitmap.width - 1)
            val color = bitmap.getColor(x, y)
            val red = ((color shr 16) and 0xFF) / 255f
            val green = ((color shr 8) and 0xFF) / 255f
            val blue = (color and 0xFF) / 255f
            val hsv = rgbToHsv(red, green, blue)
            if (hsv.saturation < 0.16f || hsv.value < 0.16f || hsv.value > 0.98f) {
                return@repeat
            }

            val bucketIndex = floor(hsv.hue * HueBucketCount)
                .toInt()
                .coerceIn(0, HueBucketCount - 1)
            val weight = hsv.saturation * hsv.saturation * (0.35f + hsv.value)
            buckets[bucketIndex].add(red, green, blue, weight)
        }
    }

    val dominant = buckets.maxByOrNull(ColorBucket::score)
        ?.takeIf { it.weight > 0f }
        ?: return null
    return dominant.color()
}

internal actual fun extractHeroPaletteColors(image: Image, count: Int): List<Color> {
    val bitmap = (image as? BitmapImage)?.bitmap ?: return emptyList()
    if (bitmap.width <= 0 || bitmap.height <= 0) return emptyList()
    val buckets = Array(HueBucketCount) { ColorBucket() }
    var greyRed = 0f
    var greyGreen = 0f
    var greyBlue = 0f
    var samples = 0
    repeat(SampleRows) { row ->
        val y = ((row + 0.5f) * bitmap.height / SampleRows).toInt().coerceIn(0, bitmap.height - 1)
        repeat(SampleColumns) { column ->
            val x = ((column + 0.5f) * bitmap.width / SampleColumns).toInt().coerceIn(0, bitmap.width - 1)
            val color = bitmap.getColor(x, y)
            val red = ((color shr 16) and 0xFF) / 255f
            val green = ((color shr 8) and 0xFF) / 255f
            val blue = (color and 0xFF) / 255f
            greyRed += red; greyGreen += green; greyBlue += blue; samples++
            val hsv = rgbToHsv(red, green, blue)
            if (hsv.saturation < 0.12f || hsv.value < 0.10f) return@repeat
            val bucketIndex = floor(hsv.hue * HueBucketCount).toInt().coerceIn(0, HueBucketCount - 1)
            // Presence counts more than vividness here: the backdrop should look like the cover overall.
            buckets[bucketIndex].add(red, green, blue, 0.4f + hsv.saturation * (0.35f + hsv.value))
        }
    }
    // The strongest hues, each at least three buckets (45 degrees) from the ones already taken.
    val picked = mutableListOf<Int>()
    buckets.indices.sortedByDescending { buckets[it].score }.forEach { i ->
        if (picked.size >= count || buckets[i].weight <= 0f) return@forEach
        val far = picked.all { j -> val d = kotlin.math.abs(i - j); minOf(d, HueBucketCount - d) >= 3 }
        // A hue far weaker than the first adds noise, not colour.
        val strongEnough = picked.isEmpty() || buckets[i].score >= buckets[picked.first()].score * 0.12f
        if (far && strongEnough) picked += i
    }
    if (picked.isNotEmpty()) return picked.map { buckets[it].color() }
    if (samples == 0) return emptyList()
    return listOf(Color(red = greyRed / samples, green = greyGreen / samples, blue = greyBlue / samples))
}

private data class Hsv(
    val hue: Float,
    val saturation: Float,
    val value: Float,
)

private class ColorBucket {
    var weightedRed = 0f
    var weightedGreen = 0f
    var weightedBlue = 0f
    var weight = 0f
    var score = 0f

    fun add(red: Float, green: Float, blue: Float, sampleWeight: Float) {
        weightedRed += red * sampleWeight
        weightedGreen += green * sampleWeight
        weightedBlue += blue * sampleWeight
        weight += sampleWeight
        score += sampleWeight
    }

    fun color(): Color {
        val red = (weightedRed / weight).coerceIn(0f, 1f)
        val green = (weightedGreen / weight).coerceIn(0f, 1f)
        val blue = (weightedBlue / weight).coerceIn(0f, 1f)
        return Color(red = red, green = green, blue = blue)
    }
}

private fun rgbToHsv(red: Float, green: Float, blue: Float): Hsv {
    val maximum = max(red, max(green, blue))
    val minimum = min(red, min(green, blue))
    val delta = maximum - minimum
    val hue = when {
        delta == 0f -> 0f
        maximum == red -> ((green - blue) / delta).mod(6f) / 6f
        maximum == green -> (((blue - red) / delta) + 2f) / 6f
        else -> (((red - green) / delta) + 4f) / 6f
    }
    return Hsv(
        hue = hue,
        saturation = if (maximum == 0f) 0f else delta / maximum,
        value = maximum,
    )
}
