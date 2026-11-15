package com.nuvio.app.features.iptv

import com.nuvio.app.features.cast.Ffmpeg
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** fork:live-tv-views - one frame through the viewer's own ffmpeg (found as casting finds it); 15 s at most. */
internal actual object IptvStillGrabber {
    actual val available: Boolean get() = Ffmpeg.ffmpeg != null

    actual fun grab(url: String, headers: Map<String, String>): ByteArray? {
        val exe = Ffmpeg.ffmpeg ?: return null
        val command = buildList {
            add(exe.absolutePath)
            addAll(listOf("-hide_banner", "-loglevel", "error", "-rw_timeout", "10000000"))
            addAll(Ffmpeg.inputHeaderArgs(headers))
            addAll(listOf("-i", url, "-frames:v", "1", "-vf", "scale=640:-2", "-q:v", "5", "-f", "image2", "-c:v", "mjpeg", "pipe:1"))
        }
        val process = runCatching { ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start() }.getOrNull() ?: return null
        var bytes: ByteArray? = null
        val reader = thread(isDaemon = true, name = "iptv-still") { bytes = runCatching { process.inputStream.readBytes() }.getOrNull() }
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.join(1000)
            return null
        }
        reader.join(2000)
        return bytes?.takeIf { process.exitValue() == 0 && it.isNotEmpty() }
    }
}
