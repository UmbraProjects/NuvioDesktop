package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Anime4kLumaOnlySourceTest {
    private fun shader(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/player-shaders/$name.glsl")) { name }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun cnnResidualsBecomeLumaOnly() {
        listOf("Anime4K_Restore_CNN_M", "Anime4K_Upscale_CNN_x2_M", "Anime4K_Upscale_Denoise_CNN_x2_VL").forEach { name ->
            val source = shader(name)
            val rewritten = checkNotNull(anime4kLumaOnlySource(source)) { name }
            assertFalse(Regex("""return .+ \+ MAIN_tex\(MAIN_pos\);""").containsMatchIn(rewritten), name)
            assertEquals(1, Regex("nuvio_residual = ").findAll(rewritten).count(), name)
            assertTrue("dot(nuvio_residual.rgb, vec3(0.2627, 0.6780, 0.0593))" in rewritten, name)
            // Every other pass is untouched.
            assertEquals(source.lines().size + 2, rewritten.lines().size, name)
        }
    }

    @Test
    fun colourNeutralShadersAreLeftAlone() {
        listOf("Anime4K_Clamp_Highlights", "Anime4K_Thin_Fast", "Anime4K_AutoDownscalePre_x2").forEach { name ->
            assertNull(anime4kLumaOnlySource(shader(name)), name)
        }
    }
}
