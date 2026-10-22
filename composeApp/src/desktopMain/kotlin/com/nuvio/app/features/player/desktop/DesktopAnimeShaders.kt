package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.DesktopAnimeMode
import java.io.File

/**
 * Exports the bundled Anime4K GLSL shaders (resources under `/player-shaders/`) to a temp directory
 * and builds the `glsl-shaders` chain string mpv expects for a given anime preset.
 *
 * The preset chains mirror Stremio-Kai's `SHADER_PRESETS` (Optimized / Fast / HQ), minus Kai's
 * optional leading denoise pass (denoise1 / nlmeans), which is not part of the MIT-licensed Anime4K
 * set we bundle. Any shader file that fails to export is dropped from the chain so a missing asset
 * degrades gracefully instead of breaking playback.
 *
 * Windows-only: mpv's list path separator is `;` here, which also keeps drive letters (`C:\`) intact.
 */
/**
 * CAS strength for the saved Sharpen choice ("1.0" Low, "2.0" High — the old mpv `sharpen`
 * values), or null when sharpening is off.
 */
internal fun desktopCasSharpness(value: String?): String? {
    val level = value?.toDoubleOrNull() ?: return null
    return when {
        level <= 0.0 -> null
        level < 1.5 -> "0.4"
        else -> "0.8"
    }
}

/**
 * [chain] with the CAS sharpen shader appended when [sharpness] is set, plus the matching
 * `glsl-shader-opts` value. CAS hooks LUMA, so appending it never reorders a MAIN-stage chain.
 */
internal fun desktopShaderChainWithSharpen(chain: String, sharpness: String?): Pair<String, String> {
    val casPath = sharpness?.let { DesktopAnimeShaders.casSharpenPath() }
        ?: return chain to ""
    val combined = listOf(chain, casPath).filter { it.isNotEmpty() }.joinToString(";")
    return combined to "nuvio_cas_sharpness=$sharpness"
}

internal object DesktopAnimeShaders {
    private const val resourcePrefix = "/player-shaders/"

    // Shader filenames per preset, in apply order. Mirrors Stremio-Kai's profile-manager.lua chains.
    private val presetShaderNames: Map<DesktopAnimeMode, List<String>> = mapOf(
        DesktopAnimeMode.Optimized to listOf(
            "Anime4K_Clamp_Highlights",
            "Anime4K_Restore_CNN_M",
            "Anime4K_Upscale_CNN_x2_M",
            "Anime4K_AutoDownscalePre_x2",
            "Anime4K_AutoDownscalePre_x4",
            "Anime4K_Upscale_Denoise_CNN_x2_M",
            "Anime4K_Thin_Fast",
        ),
        DesktopAnimeMode.Fast to listOf(
            "Anime4K_Clamp_Highlights",
            "Anime4K_Restore_CNN_M",
            "Anime4K_Upscale_CNN_x2_M",
            "Anime4K_Restore_CNN_S",
            "Anime4K_AutoDownscalePre_x2",
            "Anime4K_AutoDownscalePre_x4",
            "Anime4K_Upscale_CNN_x2_S",
            "Anime4K_Thin_HQ",
            "Anime4K_Thin_Fast",
            "Anime4K_Thin_VeryFast",
        ),
        DesktopAnimeMode.Hq to listOf(
            "Anime4K_Clamp_Highlights",
            "Anime4K_Restore_CNN_VL",
            "Anime4K_Upscale_CNN_x2_VL",
            "Anime4K_Restore_CNN_M",
            "Anime4K_AutoDownscalePre_x2",
            "Anime4K_AutoDownscalePre_x4",
            "Anime4K_Upscale_CNN_x2_M",
            "Anime4K_Thin_HQ",
            "Anime4K_Thin_Fast",
            "Anime4K_Thin_VeryFast",
        ),
        // Standard Anime4K presets (bloc97/Anime4K v4.0.1 GLSL_Instructions chains).
        DesktopAnimeMode.ModeAFast to listOf(
            "Anime4K_Clamp_Highlights",
            "Anime4K_Restore_CNN_M",
            "Anime4K_Upscale_CNN_x2_M",
            "Anime4K_AutoDownscalePre_x2",
            "Anime4K_AutoDownscalePre_x4",
            "Anime4K_Upscale_CNN_x2_S",
        ),
        DesktopAnimeMode.ModeAHq to listOf(
            "Anime4K_Clamp_Highlights",
            "Anime4K_Restore_CNN_VL",
            "Anime4K_Upscale_CNN_x2_VL",
            "Anime4K_AutoDownscalePre_x2",
            "Anime4K_AutoDownscalePre_x4",
            "Anime4K_Upscale_CNN_x2_M",
        ),
        DesktopAnimeMode.ModeBFast to listOf(
            "Anime4K_Clamp_Highlights",
            "Anime4K_Restore_CNN_Soft_M",
            "Anime4K_Upscale_CNN_x2_M",
            "Anime4K_AutoDownscalePre_x2",
            "Anime4K_AutoDownscalePre_x4",
            "Anime4K_Upscale_CNN_x2_S",
        ),
        DesktopAnimeMode.ModeBHq to listOf(
            "Anime4K_Clamp_Highlights",
            "Anime4K_Restore_CNN_Soft_VL",
            "Anime4K_Upscale_CNN_x2_VL",
            "Anime4K_AutoDownscalePre_x2",
            "Anime4K_AutoDownscalePre_x4",
            "Anime4K_Upscale_CNN_x2_M",
        ),
        DesktopAnimeMode.ModeCFast to listOf(
            "Anime4K_Clamp_Highlights",
            "Anime4K_Upscale_Denoise_CNN_x2_M",
            "Anime4K_AutoDownscalePre_x2",
            "Anime4K_AutoDownscalePre_x4",
            "Anime4K_Upscale_CNN_x2_S",
        ),
        DesktopAnimeMode.ModeCHq to listOf(
            "Anime4K_Clamp_Highlights",
            "Anime4K_Upscale_Denoise_CNN_x2_VL",
            "Anime4K_AutoDownscalePre_x2",
            "Anime4K_AutoDownscalePre_x4",
            "Anime4K_Upscale_CNN_x2_M",
        ),
    )

    private val exportRoot: File by lazy {
        File(System.getProperty("java.io.tmpdir"), "nuvio-shaders").apply { mkdirs() }
    }

    // name -> exported absolute path (or null if the resource could not be written).
    private val exportedPaths = mutableMapOf<String, String?>()

    /**
     * Builds the mpv `glsl-shaders` value for [preset], exporting any not-yet-exported shaders.
     * Returns an empty string if the preset has no resolvable shaders.
     */
    fun shaderChain(preset: DesktopAnimeMode, lumaOnly: Boolean = false): String {
        val names = presetShaderNames[preset] ?: return ""
        return names
            .mapNotNull { if (lumaOnly) exportedLumaOnlyPath(it) else exportedPath(it) }
            .joinToString(";")
    }

    /**
     * The bundled CAS sharpen shader behind the player's Sharpen menu (mpv's own `sharpen` is
     * vo=gpu only). Strength is its `nuvio_cas_sharpness` param, set through `glsl-shader-opts`.
     */
    fun casSharpenPath(): String? = exportedPath("Nuvio_CAS_Sharpen")

    private fun exportedPath(name: String): String? = exportedPaths.getOrPut(name) {
        runCatching {
            val bytes = resourceBytes(name) ?: return@runCatching null
            writeIfChanged("$name.glsl", bytes)
        }.getOrNull()
    }

    /**
     * The chain used when RTX True HDR runs in front of the shaders. Shaders without a per-channel
     * residual are exported unchanged; see [anime4kLumaOnlySource].
     */
    private fun exportedLumaOnlyPath(name: String): String? = exportedPaths.getOrPut("$name#luma") {
        runCatching {
            val source = resourceBytes(name)?.toString(Charsets.UTF_8) ?: return@runCatching null
            val transformed = anime4kLumaOnlySource(source) ?: return@runCatching exportedPath(name)
            writeIfChanged("${name}_LumaOnly.glsl", transformed.toByteArray(Charsets.UTF_8))
        }.getOrNull()
    }

    private fun resourceBytes(name: String): ByteArray? =
        DesktopAnimeShaders::class.java.getResourceAsStream("$resourcePrefix$name.glsl")?.use { it.readBytes() }

    private fun writeIfChanged(fileName: String, bytes: ByteArray): String {
        val target = File(exportRoot, fileName)
        if (!target.exists() || !target.readBytes().contentEquals(bytes)) {
            target.writeBytes(bytes)
        }
        return target.absolutePath
    }
}

private val anime4kResidualReturn = Regex("""return (.+?) \+ MAIN_tex\(MAIN_pos\);""")

/**
 * Rewrites an Anime4K CNN's final `return residual + MAIN_tex(MAIN_pos);` so the residual moves
 * only brightness: its BT.2020 luma is added equally to R, G and B. Null when the shader has no
 * such line (Clamp_Highlights, Thin and AutoDownscale already leave colour alone).
 *
 * Restore/Upscale predict a separate correction per channel, trained on SDR BT.709 frames. Under
 * RTX True HDR the renderer hands them PQ-encoded BT.2020 instead, and the three corrections no
 * longer agree at hard edges: black text on a light background picked up a pink rim with cyan
 * inside. A luma-only residual keeps the sharpening and cannot tint anything.
 */
internal fun anime4kLumaOnlySource(source: String): String? {
    if (!anime4kResidualReturn.containsMatchIn(source)) return null
    return anime4kResidualReturn.replace(source) { match ->
        val residual = match.groupValues[1]
        "vec4 nuvio_residual = $residual;\n" +
            "    vec4 nuvio_base = MAIN_tex(MAIN_pos);\n" +
            "    return vec4(nuvio_base.rgb + dot(nuvio_residual.rgb, vec3(0.2627, 0.6780, 0.0593)), nuvio_base.a);"
    }
}
