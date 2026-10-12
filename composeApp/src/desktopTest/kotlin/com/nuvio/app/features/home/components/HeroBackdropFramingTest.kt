package com.nuvio.app.features.home.components

import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

class HeroBackdropFramingTest {

    private fun profile(
        mass: FloatArray,
        cut: FloatArray = FloatArray(mass.size),
        faces: List<HeroBackdropFace> = emptyList(),
    ): HeroBackdropFramingProfile {
        val total = mass.sum()
        return HeroBackdropFramingProfile(16f / 9f, FloatArray(mass.size) { mass[it] / total }, cut, faces)
    }

    @Test
    fun subjectAtTheBottomMovesTheCropDown() {
        val mass = FloatArray(160) { row -> if (row in 110..155) 1f else 0f }
        assertTrue(heroBackdropFramingBias(profile(mass), heroAspect = 3.55f, priorBias = -0.58f) > 0.3f)
    }

    @Test
    fun faceNearTheTopKeepsItsHeadroom() {
        val mass = FloatArray(160) { row -> if (row in 20..159) 1f else 0f }
        val face = HeroBackdropFace(left = 0.5f, top = 0.12f, right = 0.58f, bottom = 0.26f)
        val bias = heroBackdropFramingBias(profile(mass, faces = listOf(face)), heroAspect = 3.55f, priorBias = 0f)
        val visible = (16f / 9f) / 3.55f
        val windowTop = (1f + bias) / 2f * (1f - visible)
        assertTrue(windowTop <= face.top - 0.35f * (face.bottom - face.top), "window top $windowTop cuts the head")
    }

    @Test
    fun uncroppedBackdropKeepsThePrior() {
        val mass = FloatArray(160) { 1f }
        assertEquals(-0.58f, heroBackdropFramingBias(profile(mass), heroAspect = 1.5f, priorBias = -0.58f))
    }

    @Test
    fun evenlySpreadSubjectLeansToTheTop() {
        // Losing the top of a subject costs twice what losing the bottom does, so with nothing
        // else to go on the crop keeps the top.
        val mass = FloatArray(160) { 1f }
        val bias = heroBackdropFramingBias(profile(mass), heroAspect = 3.55f, priorBias = -0.58f)
        assertTrue(bias < -0.58f, "expected above the prior, got $bias")
    }

    /**
     * Parity with the Python bench the weights were tuned on. Opt-in: point
     * NUVIO_FRAMING_BENCH at a folder of `<name>.jpg` backdrops plus `expected.txt`
     * (`<name> <bias>` per line, hero aspect 3.55, prior -0.58).
     */
    @Test
    fun matchesTheReferenceBench() {
        val bench = System.getenv("NUVIO_FRAMING_BENCH")?.let(::File)?.takeIf(File::isDirectory) ?: return
        useTempOnnxNatives()
        val expected = bench.resolve("expected.txt").readLines()
            .filter(String::isNotBlank)
            .associate { line -> line.substringBefore(' ') to line.substringAfter(' ').trim().toFloat() }
        val misses = mutableListOf<String>()
        var worst = 0f
        for ((name, want) in expected) {
            val image = Image.makeFromEncoded(bench.resolve("$name.jpg").readBytes())
            val bitmap = Bitmap.makeFromImage(image)
            val rgba = assertNotNull(
                bitmap.readPixels(
                    ImageInfo(bitmap.width, bitmap.height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL),
                    bitmap.width * 4,
                    0,
                    0,
                ),
            )
            val profile = assertNotNull(analyzeHeroBackdropPixels(rgba, bitmap.width, bitmap.height), "models failed to load")
            val got = heroBackdropFramingBias(profile, heroAspect = 3.55f, priorBias = -0.58f)
            val diff = abs(got - want)
            worst = maxOf(worst, diff)
            println("$name kotlin=${"%+.2f".format(got)} ref=${"%+.2f".format(want)} faces=${profile.faces.size}")
            if (diff > 0.1f) misses += "$name: $got vs $want"
        }
        println("worst diff $worst")
        assertTrue(misses.isEmpty(), "Differs from the reference: $misses")
    }

    private fun useTempOnnxNatives() {
        if (System.getProperty("onnxruntime.native.path") != null) return
        val directory = Files.createTempDirectory("nuvio-ort-test")
        for (library in listOf("onnxruntime.dll", "onnxruntime4j_jni.dll", "onnxruntime_providers_shared.dll")) {
            javaClass.getResourceAsStream("/ai/onnxruntime/native/win-x64/$library")?.use { input ->
                directory.resolve(library).toFile().outputStream().use(input::copyTo)
            }
        }
        System.setProperty("onnxruntime.native.path", directory.toString())
    }
}
