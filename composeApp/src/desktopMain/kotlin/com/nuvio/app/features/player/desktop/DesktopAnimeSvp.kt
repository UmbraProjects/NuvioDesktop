package com.nuvio.app.features.player.desktop

import co.touchlab.kermit.Logger
import java.io.File

private val log = Logger.withTag("DesktopAnimeSvp")

/**
 * Exports the bundled SVP VapourSynth script and config to a temp directory.
 */
internal object DesktopAnimeSvp {
    private const val resourcePrefix = "/player-scripts/"

    private val exportRoot: File by lazy {
        File(System.getProperty("java.io.tmpdir"), "nuvio-scripts").apply { mkdirs() }
    }

    private val exportedPaths = mutableMapOf<String, String?>()

    // The svpflow*.dll plugins users drop into the app folder are VapourSynth *plugins* — they
    // still need VapourSynth's own core scripting engine (vsscript.dll, which itself needs a
    // Python install) to actually be present and discoverable on the DLL search path. Without
    // it, mpv's vf_vapoursynth filter fails to load at file-open time — and mpv responds to a vf
    // chain failure by dropping the video track entirely (audio-only, black screen), not by
    // skipping the filter. So this must be checked BEFORE handing the filter string to mpv.
    private val vapourSynthCoreAvailable: Boolean by lazy {
        val candidateNames = listOf("vsscript.dll", "VSScript.dll")
        val searchDirs = buildList {
            NativePlayerBridge.runtimeDllDir()?.let(::add)
        }
        val found = searchDirs.any { dir -> candidateNames.any { name -> File(dir, name).isFile } }
        if (!found) {
            log.w { "Bundled VapourSynth core (vsscript.dll) not found in Nuvio runtime directory; SVP will be skipped" }
        }
        found
    }



    /**
     * Builds the mpv `vf` vapoursynth argument string, exporting the script if necessary.
     * Returns null if the script could not be exported, or
     * VapourSynth's own core engine isn't installed (see [vapourSynthCoreAvailable]).
     */
    fun vapoursynthArgument(): String? {
        if (!vapourSynthCoreAvailable) return null
        // We also need to export the svp.conf config file because svp_main.vpy reads it from its own directory
        exportedPath("svp.conf")
        val vpyPath = exportedPath("svp_main.vpy") ?: return null
        // mpv's vf/af suboption parser treats \ as an escape character, so a raw Windows path
        // (backslash-separated) silently corrupts and the whole vf= property set fails with
        // MPV_ERROR_INVALID_PARAMETER — SVP was never actually applying. mpv's own %n% verbatim
        // quoting (n = UTF-8 byte length) passes the path through untouched, exactly as
        // documented for this case: https://mpv.io/manual/master/#quoting-and-escaping
        val byteLength = vpyPath.toByteArray(Charsets.UTF_8).size
        // Match Stremio-Kai's SVP filter scheduling. The old single-frame setting serialized
        // VapourSynth work and could starve mpv's GPU shader chain on lower-end cards.
        return "vapoursynth=file=%$byteLength%$vpyPath:buffered-frames=8:concurrent-frames=16"
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
