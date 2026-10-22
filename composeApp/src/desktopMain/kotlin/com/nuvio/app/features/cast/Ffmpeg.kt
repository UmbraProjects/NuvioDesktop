package com.nuvio.app.features.cast

import co.touchlab.kermit.Logger
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

private val ffmpegLog = Logger.withTag("CastFfmpeg")

/**
 * The user's own ffmpeg/ffprobe, found on PATH or where winget and the usual installers put it.
 * Not bundled: only the Dolby Vision remux needs it, and it is an opt-in beta.
 */
internal object Ffmpeg {
    val ffmpeg: File? by lazy { locate("ffmpeg.exe") }
    val ffprobe: File? by lazy { locate("ffprobe.exe") }
    val available: Boolean get() = ffmpeg != null && ffprobe != null

    private fun locate(exe: String): File? {
        val dirs = buildList {
            System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).forEach { add(it.trim()) }
            System.getenv("LOCALAPPDATA")?.let { add("$it\\Microsoft\\WinGet\\Links") }
            System.getenv("ProgramFiles")?.let { add("$it\\ffmpeg\\bin") }
            System.getenv("USERPROFILE")?.let { add("$it\\scoop\\shims") }
            add("C:\\ffmpeg\\bin")
        }
        return dirs.asSequence()
            .filter { it.isNotEmpty() }
            .map { File(it, exe) }
            .firstOrNull { it.isFile }
            ?.also { ffmpegLog.i { "Found $exe at ${it.absolutePath}" } }
    }

    /** Input options carrying the stream's request headers (User-Agent has its own switch). */
    fun inputHeaderArgs(headers: Map<String, String>): List<String> = buildList {
        headers.entries.firstOrNull { it.key.equals("user-agent", ignoreCase = true) }?.let {
            add("-user_agent")
            add(it.value)
        }
        val rest = headers.filterKeys { !it.equals("user-agent", ignoreCase = true) }
        if (rest.isNotEmpty()) {
            add("-headers")
            add(rest.entries.joinToString("") { (key, value) -> "$key: $value\r\n" })
        }
    }

    /** Duration of the source in seconds, or null when ffprobe cannot tell. */
    fun probeDurationS(url: String, headers: Map<String, String>): Double? =
        runProbe(
            listOf("-show_entries", "format=duration", "-of", "default=nw=1:nk=1"),
            url, headers,
        )?.trim()?.toDoubleOrNull()?.takeIf { it > 0 }

    /** Presentation time of the video keyframe at or before [timeS]: where `-ss` will actually start. */
    fun keyframeAtOrBeforeS(url: String, headers: Map<String, String>, timeS: Double): Double =
        runProbe(
            listOf(
                "-select_streams", "v:0", "-read_intervals", "%.3f%%+#1".format(Locale.ROOT, timeS),
                "-show_entries", "packet=pts_time", "-of", "csv=p=0",
            ),
            url, headers,
        )?.lineSequence()?.firstNotNullOfOrNull { it.trim().toDoubleOrNull() } ?: timeS

    /**
     * Starts a stream-copy remux to fragmented MP4 on stdout, from [startS] (a keyframe time).
     * `delay_moov` rather than `empty_moov`: ffmpeg cannot write an empty moov ahead of E-AC3.
     * `hvc1` plus `-strict unofficial` keeps the Dolby Vision configuration (dvvC) in the moov.
     */
    fun startRemux(url: String, headers: Map<String, String>, startS: Double): Process {
        val exe = ffmpeg ?: error("ffmpeg is not installed")
        val command = buildList {
            add(exe.absolutePath)
            addAll(listOf("-hide_banner", "-nostdin", "-v", "error"))
            addAll(listOf("-reconnect", "1", "-reconnect_on_network_error", "1"))
            addAll(inputHeaderArgs(headers))
            if (startS > 0) addAll(listOf("-ss", "%.3f".format(Locale.ROOT, startS)))
            addAll(listOf("-i", url))
            addAll(listOf("-map", "0:v:0", "-map", "0:a:0?", "-c", "copy", "-tag:v", "hvc1", "-strict", "unofficial"))
            addAll(listOf("-movflags", "frag_keyframe+delay_moov+default_base_moof", "-f", "mp4", "pipe:1"))
        }
        return ProcessBuilder(command)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }

    private fun runProbe(args: List<String>, url: String, headers: Map<String, String>): String? {
        val exe = ffprobe ?: return null
        val command = listOf(exe.absolutePath, "-v", "error") + inputHeaderArgs(headers) + args + url
        return runCatching {
            val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                null
            } else {
                output
            }
        }.onFailure { ffmpegLog.w(it) { "ffprobe failed" } }.getOrNull()
    }
}
