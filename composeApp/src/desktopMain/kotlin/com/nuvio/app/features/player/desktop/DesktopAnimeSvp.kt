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

    // SVP reliably crashes the whole app on real playback (confirmed on real hardware, 100%
    // reproducible on anime content) somewhere in the hwdec=d3d11va-copy + Anime4K GPU shaders +
    // hqdn3d/vapoursynth CPU filter chain combination this enables — plain Anime4K-only playback
    // (hwdec=d3d11va, no copy-back, no vf chain) is unaffected. Three rounds of targeted fixes
    // (concurrent-frames=1, restoring shader-toolchain DLLs) each made the crash surface at an
    // *earlier* point in mpv's startup log without touching anything related to what changed — a
    // heap-corruption signature, not something those fixes could address. The Python/VapourSynth
    // scripting layer itself was independently verified solid via a standalone native harness
    // (real svp_main.vpy execution, real frame requests, no crash) — so this is specifically
    // about the d3d11va-copy GPU<->CPU round-trip combined with GPU shader rendering, likely a
    // code path that was never actually exercised by real users before SVP's auto-detect bug got
    // fixed this session. Disabled until this can be root-caused with a real crash dump /
    // debugger. Anime4K itself is untouched by this and keeps working normally.
    //
    // Extensive diagnosis attempted with no crash produced: Event Viewer (Application log), a
    // configured LocalDumps registry entry, Windows Defender protection history, and the JVM's
    // own hs_err_pid native-crash handler all came up completely empty despite a 100%
    // reproducible crash. That total silence across every passive diagnostic, plus the crash
    // being instantaneous with no hang (confirmed: "just as though you alt-f4'd"), points at a
    // stack overflow (STATUS_STACK_OVERFLOW) or a raw TerminateProcess call rather than a normal
    // access violation — both bypass WER and the JVM's crash handler the same way. Confirming
    // which needs an actual debugger attached at crash time.
    //
    // TEMPORARILY re-enabled for a debugger-attached repro. Revert to false once done.
    private const val svpEnabled = true

    /**
     * Builds the mpv `vf` vapoursynth argument string, exporting the script if necessary.
     * Returns null if SVP is disabled (see [svpEnabled]), the script could not be exported, or
     * VapourSynth's own core engine isn't installed (see [vapourSynthCoreAvailable]).
     */
    fun vapoursynthArgument(): String? {
        if (!svpEnabled) return null
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
