package com.nuvio.app.core.diagnostics

/**
 * Native memory that the process-level numbers cannot attribute on their own, reported by the code
 * that owns it. So far the speech model: whisper.cpp keeps its weights and compute buffers outside
 * every cache the app can measure, so the overlay's "Other native" swallowed ~700 MB of `small.en`.
 */
internal object NativeMemoryUsers {
    /**
     * [costBytes] is committed memory: Windows only puts a page in RAM (Task Manager's figure) once it
     * is touched, and the compute buffers are touched only by a decode, so less of it is in RAM until
     * the model has heard something. [exact] is whisper.cpp's own sum; otherwise an estimate.
     */
    data class VoiceModel(val fileName: String, val costBytes: Long, val exact: Boolean = false)

    @Volatile
    var voiceModel: VoiceModel? = null
        private set

    /** [costBytes] -1 when it could not be read. */
    fun voiceModelLoaded(fileName: String, costBytes: Long, exact: Boolean = false) {
        voiceModel = VoiceModel(fileName, costBytes, exact)
    }

    fun voiceModelReleased() {
        voiceModel = null
    }
}

/** The native engines worth naming when they are loaded, by DLL file name (lower case). */
internal val NotableNativeEngines: List<Pair<String, String>> = listOf(
    "libmpv-2.dll" to "mpv",
    "nuvio_whisper.dll" to "Whisper",
    "libquickjs.dll" to "QuickJS",
    "nuvio_engine.dll" to "torrent engine",
    "libvapoursynth.dll" to "VapourSynth",
    "svpflow1_vs.dll" to "SVP",
)

/** The engines among [moduleNames] (a ';'-separated DLL list), in [NotableNativeEngines] order. */
internal fun notableEngines(moduleNames: String): List<String> {
    val loaded = moduleNames.split(';').map { it.trim().lowercase() }.toSet()
    val engines = NotableNativeEngines.filter { (dll, _) -> dll in loaded }.map { it.second }.toMutableList()
    if (loaded.any { it.startsWith("libpython3") || it.matches(Regex("python3\\d*\\.dll")) }) engines += "Python"
    return engines
}

/** A child process (WebView2's browser and renderers, SVP's Python, ffmpeg) by executable name. */
internal data class ChildProcessGroup(val name: String, val count: Int, val privateWorkingSet: Long)

/** Groups [children] (executable name to private working set, -1 unknown) by name, largest first. */
internal fun groupChildProcesses(children: List<Pair<String, Long>>): List<ChildProcessGroup> =
    children.groupBy({ it.first }, { it.second })
        .map { (name, sizes) ->
            val known = sizes.filter { it >= 0 }
            ChildProcessGroup(name, sizes.size, if (known.isEmpty()) -1 else known.sum())
        }
        .sortedByDescending { it.privateWorkingSet }
