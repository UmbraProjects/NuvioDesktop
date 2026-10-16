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
    fun shaderChain(preset: DesktopAnimeMode): String {
        val names = presetShaderNames[preset] ?: return ""
        return names
            .mapNotNull { exportedPath(it) }
            .joinToString(";")
    }

    /**
     * The bundled CAS sharpen shader behind the player's Sharpen menu (mpv's own `sharpen` is
     * vo=gpu only). Strength is its `nuvio_cas_sharpness` param, set through `glsl-shader-opts`.
     */
    fun casSharpenPath(): String? = exportedPath("Nuvio_CAS_Sharpen")

    private fun exportedPath(name: String): String? = exportedPaths.getOrPut(name) {
        runCatching {
            val resource = "$resourcePrefix$name.glsl"
            val bytes = DesktopAnimeShaders::class.java.getResourceAsStream(resource)
                ?.use { it.readBytes() }
                ?: return@runCatching null
            val target = File(exportRoot, "$name.glsl")
            if (!target.exists() || !target.readBytes().contentEquals(bytes)) {
                target.writeBytes(bytes)
            }
            target.absolutePath
        }.getOrNull()
    }
}
