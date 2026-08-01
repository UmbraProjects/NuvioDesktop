package com.nuvio.app.features.player.desktop

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.DesktopStorage
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

    // The script's own diagnostic log. Kept with nuvio.log / nuvio-mpv.log rather than in the
    // temp export directory, so "Open logs folder" surfaces it and log bundles pick it up.
    private val diagnosticLogPath: String by lazy {
        DesktopStorage.rootDir.resolve("logs").resolve("svp_diag.log").toAbsolutePath().toString()
    }

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
     *
     * [debugOverlay] is baked into the exported config as `DEBUG_OVERLAY`; the script re-reads the
     * config on every file load, so the next playback start picks up a change with no restart.
     */
    fun vapoursynthArgument(debugOverlay: Boolean): String? {
        if (!vapourSynthCoreAvailable) return null
        exportConfig(debugOverlay)
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

    /**
     * Exports `svp.conf` to the `script-opts/` sub-directory svp_main.vpy actually reads it from —
     * it was previously written next to the script, where `load_config()` never looked, so every
     * run silently fell back to the script's built-in defaults.
     *
     * Not memoized by name like [exportedPath]: [debugOverlay] can change between playback starts.
     */
    private fun exportConfig(debugOverlay: Boolean) {
        runCatching {
            val template = DesktopAnimeSvp::class.java.getResourceAsStream("${resourcePrefix}svp.conf")
                ?.use { it.readBytes() }
                ?.toString(Charsets.UTF_8)
                ?: return
            // Rewrite the shipped DEBUG_OVERLAY line rather than appending, so the file keeps the
            // documentation comments around it and never accumulates duplicate keys.
            val contents = template.lineSequence().joinToString("\n") { line ->
                when (line.substringBefore('#').substringBefore('=').trim()) {
                    "DEBUG_OVERLAY" -> "DEBUG_OVERLAY = $debugOverlay"
                    "diag_log" -> "diag_log = $diagnosticLogPath"
                    else -> line
                }
            }
            val bytes = contents.toByteArray(Charsets.UTF_8)
            val target = File(File(exportRoot, "script-opts").apply { mkdirs() }, "svp.conf")
            if (!target.exists() || !target.readBytes().contentEquals(bytes)) {
                target.writeBytes(bytes)
            }
        }.onFailure { log.w(it) { "Failed to export svp.conf; SVP will run with script defaults" } }
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
