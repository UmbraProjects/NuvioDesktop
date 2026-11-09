package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.DESKTOP_AUDIO_SWRESAMPLE_OVERRIDE_KEY
import com.nuvio.app.features.player.DESKTOP_HEADPHONE_SURROUND_ON
import com.nuvio.app.features.player.DESKTOP_HEADPHONE_SURROUND_OVERRIDE_KEY
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeadphoneSurroundTest {

    private val responses = HeadphoneHrirModel.generate()

    private fun response(speaker: String, rightEar: Boolean): DoubleArray {
        val index = HEADPHONE_SPEAKERS.indexOfFirst { it.first == speaker }
        return responses[index * 2 + if (rightEar) 1 else 0]
    }

    /** Index of the direct-sound peak (the reflections are all well below it). */
    private fun arrival(samples: DoubleArray): Int = samples.indices.maxByOrNull { abs(samples[it]) }!!

    /** Energy of the direct sound only, before the first reflection. */
    private fun directEnergy(samples: DoubleArray): Double = samples.take(200).sumOf { it * it }

    @Test
    fun `one left and right ear response per speaker`() {
        assertEquals(16, responses.size)
        assertTrue(responses.all { it.size == HeadphoneHrirModel.LENGTH })
    }

    @Test
    fun `a front-left speaker reaches the left ear first and louder`() {
        val left = response("FL", rightEar = false)
        val right = response("FL", rightEar = true)
        val lagSamples = arrival(right) - arrival(left)
        // About 0.25 ms at 30 degrees off centre.
        assertTrue(lagSamples in 8..16, "lag $lagSamples")
        assertTrue(directEnergy(left) > directEnergy(right))
    }

    @Test
    fun `a side speaker has a wider gap between the ears than a front one`() {
        val frontLag = arrival(response("FL", true)) - arrival(response("FL", false))
        val sideLag = arrival(response("SL", true)) - arrival(response("SL", false))
        assertTrue(sideLag > frontLag, "side $sideLag front $frontLag")
        // Under the head's own limit, about 0.7 ms.
        assertTrue(sideLag <= 36, "side $sideLag")
    }

    @Test
    fun `left and right speakers mirror each other and the centre is the same at both ears`() {
        val centreLeft = response("FC", false)
        val centreRight = response("FC", true)
        assertEquals(arrival(centreLeft), arrival(centreRight))
        assertEquals(directEnergy(centreLeft), directEnergy(centreRight), 1e-9)
        assertEquals(arrival(response("FL", false)), arrival(response("FR", true)))
        assertEquals(directEnergy(response("BL", false)), directEnergy(response("BR", true)), 1e-9)
    }

    /** Magnitude in dB around [hz], averaged over an octave so the room tail evens out. */
    private fun levelDb(samples: DoubleArray, hz: Double): Double {
        val points = 41
        val power = (0 until points).sumOf { step ->
            val f = hz * Math.pow(2.0, (step - points / 2).toDouble() / points)
            val w = 2 * Math.PI * f / HeadphoneHrirModel.SAMPLE_RATE
            var re = 0.0
            var im = 0.0
            for (n in samples.indices) {
                re += samples[n] * Math.cos(w * n)
                im -= samples[n] * Math.sin(w * n)
            }
            re * re + im * im
        } / points
        return 10 * Math.log10(power)
    }

    @Test
    fun `dialogue from the centre speaker keeps its treble`() {
        // Version 1 lost ~5 dB here, which made dialogue sound muffled.
        listOf(false, true).forEach { rightEar ->
            val centre = response("FC", rightEar)
            val mid = levelDb(centre, 500.0)
            listOf(2_000.0, 4_000.0, 8_000.0, 12_000.0).forEach { hz ->
                val difference = levelDb(centre, hz) - mid
                assertTrue(abs(difference) < 2.0, "FC at $hz Hz is $difference dB from 500 Hz")
            }
        }
    }

    @Test
    fun `a phantom centre from the front pair stays close to flat`() {
        val left = response("FL", false)
        val right = response("FR", false)
        val phantom = DoubleArray(left.size) { left[it] + right[it] }
        val low = levelDb(phantom, 250.0)
        listOf(4_000.0, 8_000.0, 12_000.0).forEach { hz ->
            val difference = levelDb(phantom, hz) - low
            assertTrue(abs(difference) < 3.0, "phantom centre at $hz Hz is $difference dB from 250 Hz")
        }
    }

    @Test
    fun `the generated responses are the same every time`() {
        val again = HeadphoneHrirModel.generate()
        assertTrue(responses.indices.all { responses[it].contentEquals(again[it]) })
    }

    @Test
    fun `wav header describes 16 float channels at 48 kHz`() {
        val bytes = HeadphoneHrirModel.wavBytes(responses)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals(44 + HeadphoneHrirModel.LENGTH * 16 * 4, bytes.size)
        assertEquals(16, (bytes[22].toInt() and 0xff) or ((bytes[23].toInt() and 0xff) shl 8))
        // Kept for a manual check with ffmpeg/mpv; the build dir is disposable.
        File("build/headphone-hrir-test.wav").writeBytes(bytes)
    }

    @Test
    fun `filter chain keeps the audio profile and appends headphone surround`() {
        val overrides = mapOf(
            "af" to "dynaudnorm=f=150:g=15",
            DESKTOP_HEADPHONE_SURROUND_OVERRIDE_KEY to DESKTOP_HEADPHONE_SURROUND_ON,
        )
        val chain = desktopAudioFilterChain(overrides) { "C:\\Users\\a b\\hrir.wav" }
        assertTrue(chain.startsWith("dynaudnorm=f=150:g=15,lavfi=graph=%"), chain)
        val graph = chain.substringAfter("lavfi=graph=%").substringAfter('%')
        val declared = chain.substringAfter("lavfi=graph=%").substringBefore('%').toInt()
        assertEquals(declared, graph.encodeToByteArray().size)
        assertTrue("amovie='C\\:/Users/a b/hrir.wav'" in graph, graph)
        assertTrue("map=FL|FR|FC|LFE|BL|BR|SL|SR" in graph)
        assertTrue("pan=7.1|FL=0.5*FL+0.5*FR|FR=0.5*FL-0.5*FR|FC=FC|" in graph, graph)
        assertTrue("equalizer=f=1300:t=q:w=1:g=6:c=FL,pan=7.1|FL=FL+FR|FR=FL-FR|FC=FC|" in graph, graph)
    }

    @Test
    fun `filter chain is only the profile when headphone surround is off or unavailable`() {
        assertEquals("", desktopAudioFilterChain(emptyMap()) { "x.wav" })
        assertEquals("dynaudnorm", desktopAudioFilterChain(mapOf("af" to "dynaudnorm")) { "x.wav" })
        assertEquals(
            "dynaudnorm",
            desktopAudioFilterChain(
                mapOf("af" to "dynaudnorm", DESKTOP_HEADPHONE_SURROUND_OVERRIDE_KEY to DESKTOP_HEADPHONE_SURROUND_ON),
            ) { null },
        )
    }

    @Test
    fun `dialogue boost becomes a centre gain ahead of the filter`() {
        assertEquals(1.0, dialogueCenterGain(null))
        assertEquals(2.0, dialogueCenterGain("center_mix_level=1.414"), 0.01)
        val chain = desktopAudioFilterChain(
            mapOf(
                DESKTOP_HEADPHONE_SURROUND_OVERRIDE_KEY to DESKTOP_HEADPHONE_SURROUND_ON,
                DESKTOP_AUDIO_SWRESAMPLE_OVERRIDE_KEY to "center_mix_level=1.414",
            ),
        ) { "x.wav" }
        assertTrue("|FC=2.000*FC|" in chain, chain)
    }

    @Test
    fun `quotes in a path are closed and reopened`() {
        assertEquals("'C\\:/it'\\''s/h.wav'", quoteLavfiValue("C:\\it's\\h.wav"))
    }
}
