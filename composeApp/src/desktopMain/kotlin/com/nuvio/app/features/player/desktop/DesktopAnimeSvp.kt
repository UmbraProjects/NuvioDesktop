package com.nuvio.app.features.player.desktop

import java.io.File

/**
 * Exports the bundled SVP VapourSynth script and config to a temp directory.
 */
internal object DesktopAnimeSvp {
    private const val resourcePrefix = "/player-scripts/"

    private val exportRoot: File by lazy {
        File(System.getProperty("java.io.tmpdir"), "nuvio-scripts").apply { mkdirs() }
    }

    private val exportedPaths = mutableMapOf<String, String?>()

    /**
     * Builds the mpv `vf` vapoursynth argument string, exporting the script if necessary.
     * Returns null if the script could not be exported.
     */
    fun vapoursynthArgument(): String? {
        // We also need to export the svp.conf config file because svp_main.vpy reads it from its own directory
        exportedPath("svp.conf")
        val vpyPath = exportedPath("svp_main.vpy") ?: return null
        return "vapoursynth=$vpyPath"
    }

    private fun exportedPath(name: String): String? = exportedPaths.getOrPut(name) {
        runCatching {
            val resource = "$resourcePrefix$name"
            val bytes = DesktopAnimeSvp::class.java.getResourceAsStream(resource)
                ?.use { it.readBytes() }
                ?: return@runCatching null
            val target = File(exportRoot, name)
            if (!target.exists() || !target.readBytes().contentEquals(bytes)) {
                target.writeBytes(bytes)
            }
            target.absolutePath
        }.getOrNull()
    }
}
