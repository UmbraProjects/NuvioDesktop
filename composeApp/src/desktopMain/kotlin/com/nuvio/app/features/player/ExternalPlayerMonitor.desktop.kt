package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private val monitorLog = Logger.withTag("ExternalPlayerMonitor")

/** How an external player can be read while it plays. */
internal enum class ExternalPlayerControl {
    /** mpv / mpv.net JSON IPC over a named pipe. */
    Mpv,

    /** VLC's Lua HTTP interface, bound to loopback with a per-launch password. */
    Vlc,

    /** MPC-HC / MPC-BE web interface, started on a free port by `/webport`. */
    Mpc,

    /** Nothing to read: only the process lifetime is known (PotPlayer, Energy, unknown players). */
    None,
}

/** The extra launch switches a [ExternalPlayerControl] needs, and the reader that goes with them. */
internal class ExternalPlayerChannel(
    val launchArgs: List<String>,
    val reader: ExternalPlayerReader?,
)

internal interface ExternalPlayerReader : Closeable {
    /**
     * One reading, or null while nothing is loaded. Throws [IOException] while the control channel
     * is not up yet or has gone away; the monitor simply tries again on its next tick.
     */
    @Throws(IOException::class)
    fun read(): ExternalPlaybackSample?

    override fun close() = Unit
}

internal fun prepareExternalPlayerChannel(
    control: ExternalPlayerControl,
    executablePath: String,
): ExternalPlayerChannel = when (control) {
    ExternalPlayerControl.Mpv -> {
        val exe = File(executablePath)
        val isMpvNet = exe.name.equals("mpvnet.exe", ignoreCase = true)
        // A pipe the user already configured (SVP and similar tools connect to it) is reused
        // rather than overridden: mpv serves only one, and replacing it would break that tool.
        val configured = configuredMpvIpcPipe(exe, isMpvNet)
        val pipe = configured ?: "\\\\.\\pipe\\nuvio-mpv-${randomToken(8)}"
        ExternalPlayerChannel(
            launchArgs = buildList {
                if (configured == null) add("--input-ipc-server=$pipe")
                // mpv.net defaults to one process and hands new files to it, which would leave
                // nothing for this launch to watch.
                if (isMpvNet) add("--process-instance=multi")
            },
            reader = MpvIpcReader(pipe),
        )
    }
    ExternalPlayerControl.Vlc -> {
        val port = freeLoopbackPort()
        val password = randomToken(16)
        ExternalPlayerChannel(
            launchArgs = listOf(
                "--no-one-instance",
                "--extraintf=http",
                "--http-host=127.0.0.1",
                "--http-port=$port",
                "--http-password=$password",
            ),
            reader = VlcHttpReader(port, password),
        )
    }
    ExternalPlayerControl.Mpc -> {
        val port = freeLoopbackPort()
        // `/webport` starts the web interface whatever the player's own setting says, and `/new`
        // stops the file being handed to an already-open window.
        ExternalPlayerChannel(
            launchArgs = listOf("/new", "/webport", port.toString()),
            reader = MpcWebReader(port),
        )
    }
    ExternalPlayerControl.None -> ExternalPlayerChannel(emptyList(), null)
}

/** A player that forwards its file to an already-open window exits within this long. */
private const val HandoffWindowMs = 10_000L
private const val PollIntervalMs = 2_000L

/** After this long without a reading, fall back to process-lifetime tracking. */
private const val ReaderGiveUpMs = 60_000L

internal object ExternalPlaybackMonitor {
    fun watch(
        process: Process,
        tracking: ExternalPlaybackTracking,
        reader: ExternalPlayerReader?,
        diagnosticContext: String,
    ) {
        thread(isDaemon = true, name = "external-playback-monitor-${process.pid()}") {
            val startedAtNs = System.nanoTime()
            fun elapsedMs() = (System.nanoTime() - startedAtNs) / 1_000_000L
            try {
                if (process.waitFor(HandoffWindowMs, TimeUnit.MILLISECONDS)) {
                    monitorLog.i { "External player exited within the handoff window; not tracking $diagnosticContext" }
                    return@thread
                }
                val session = ExternalPlaybackTracker.begin(tracking)
                var sawSample = false
                var unobserved = reader == null
                if (unobserved) session.onUnobservedPlaybackStarted()
                while (!process.waitFor(PollIntervalMs, TimeUnit.MILLISECONDS)) {
                    reader ?: continue
                    val sample = try {
                        reader.read()
                    } catch (_: IOException) {
                        null
                    }
                    if (sample != null) {
                        if (!sawSample) monitorLog.i { "Reading external player $diagnosticContext" }
                        sawSample = true
                        session.onSample(sample)
                    } else if (!sawSample && !unobserved && elapsedMs() >= ReaderGiveUpMs) {
                        monitorLog.w { "External player never answered; tracking process lifetime only $diagnosticContext" }
                        unobserved = true
                        session.onUnobservedPlaybackStarted()
                    }
                }
                if (sawSample) session.onEnded() else session.onUnobservedPlaybackEnded(elapsedMs())
                monitorLog.i { "External playback tracking ended observed=$sawSample $diagnosticContext" }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                runCatching { reader?.close() }
            }
        }
    }
}

// --- mpv ------------------------------------------------------------------------------------

private val ipcJson = Json { ignoreUnknownKeys = true }

/**
 * Polls mpv's JSON IPC. Strictly request-then-read on one thread: a synchronous Windows pipe
 * handle cannot be read and written concurrently (a pending read blocks the write), so nothing
 * here subscribes to events and waits on them.
 */
private class MpvIpcReader(private val pipePath: String) : ExternalPlayerReader {
    private var pipe: RandomAccessFile? = null
    private var nextRequestId = 1

    override fun read(): ExternalPlaybackSample? {
        val handle = pipe ?: try {
            RandomAccessFile(pipePath, "rw").also { pipe = it }
        } catch (_: FileNotFoundException) {
            return null
        }
        try {
            val positionSeconds = handle.property("time-pos")?.doubleOrNull ?: return null
            val durationSeconds = handle.property("duration")?.doubleOrNull ?: return null
            val paused = handle.property("pause")?.booleanOrNull ?: false
            return ExternalPlaybackSample(
                positionMs = (positionSeconds * 1000.0).toLong(),
                durationMs = (durationSeconds * 1000.0).toLong(),
                isPaused = paused,
            )
        } catch (error: IOException) {
            close()
            throw error
        }
    }

    private fun RandomAccessFile.property(name: String): JsonPrimitive? {
        val id = nextRequestId++
        write("{\"command\":[\"get_property\",\"$name\"],\"request_id\":$id}\n".toByteArray(Charsets.UTF_8))
        while (true) {
            val line = readUtf8Line() ?: throw EOFException("mpv closed its IPC pipe")
            val reply = runCatching { ipcJson.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            if (reply["request_id"]?.jsonPrimitive?.intOrNull != id) continue
            if (reply["error"]?.jsonPrimitive?.content != "success") return null
            return reply["data"] as? JsonPrimitive
        }
    }

    override fun close() {
        runCatching { pipe?.close() }
        pipe = null
    }
}

private fun RandomAccessFile.readUtf8Line(): String? {
    val buffer = ByteArrayOutputStream(128)
    while (true) {
        val byte = read()
        if (byte < 0) return if (buffer.size() > 0) buffer.toString(Charsets.UTF_8) else null
        if (byte == '\n'.code) return buffer.toString(Charsets.UTF_8)
        buffer.write(byte)
    }
}

/**
 * The `input-ipc-server` a user set in mpv.conf, as a full pipe path. mpv prefixes a bare name
 * with `\\.\pipe\` itself, so a bare name is expanded the same way.
 */
private fun configuredMpvIpcPipe(executable: File, isMpvNet: Boolean): String? {
    val appData = System.getenv("APPDATA")
    val candidates = buildList {
        executable.parentFile?.let { dir -> add(File(dir, "portable_config\\mpv.conf")) }
        if (appData != null) {
            add(File(appData, if (isMpvNet) "mpv.net\\mpv.conf" else "mpv\\mpv.conf"))
        }
    }
    val value = candidates.asSequence()
        .filter(File::isFile)
        .mapNotNull { conf ->
            runCatching { conf.readLines() }.getOrNull()
                ?.asSequence()
                ?.map { it.substringBefore('#').trim() }
                ?.mapNotNull { MPV_IPC_OPTION.matchEntire(it)?.groupValues?.get(1) }
                ?.lastOrNull()
        }
        .firstOrNull()
        ?.trim()
        ?.trim('"')
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return if (value.startsWith("\\\\.\\pipe\\", ignoreCase = true)) value else "\\\\.\\pipe\\$value"
}

private val MPV_IPC_OPTION = Regex("""(?i)-{0,2}input-ipc-server\s*=\s*(.+)""")

// --- VLC ------------------------------------------------------------------------------------

private val loopbackHttp: HttpClient by lazy {
    HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()
}

private fun loopbackGet(url: String, authorization: String? = null): String? {
    val request = HttpRequest.newBuilder(URI(url))
        .timeout(Duration.ofSeconds(3))
        .apply { authorization?.let { header("Authorization", it) } }
        .GET()
        .build()
    val response = try {
        loopbackHttp.send(request, HttpResponse.BodyHandlers.ofString())
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IOException(error)
    }
    return response.body().takeIf { response.statusCode() == 200 }
}

/** VLC's `/requests/status.json`: `state` playing|paused|stopped, `time` and `length` in seconds. */
private class VlcHttpReader(port: Int, password: String) : ExternalPlayerReader {
    private val url = "http://127.0.0.1:$port/requests/status.json"
    private val authorization = "Basic " + Base64.getEncoder().encodeToString(":$password".toByteArray(Charsets.UTF_8))

    override fun read(): ExternalPlaybackSample? {
        val body = loopbackGet(url, authorization) ?: return null
        return parseVlcStatus(body)
    }
}

internal fun parseVlcStatus(body: String): ExternalPlaybackSample? {
    val status = runCatching { ipcJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    val state = status.string("state") ?: return null
    if (state == "stopped") return null
    val length = status.number("length") ?: return null
    val time = status.number("time") ?: return null
    if (length <= 0L) return null
    return ExternalPlaybackSample(
        positionMs = time * 1000L,
        durationMs = length * 1000L,
        isPaused = state != "playing",
    )
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content
private fun JsonObject.number(key: String): Long? =
    (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

// --- MPC-HC / MPC-BE ------------------------------------------------------------------------

/**
 * MPC's `/variables.html`: `state` is the DirectShow filter state (0 stopped, 1 paused,
 * 2 playing, -1 nothing loaded), `position` and `duration` are milliseconds.
 */
private class MpcWebReader(port: Int) : ExternalPlayerReader {
    private val url = "http://127.0.0.1:$port/variables.html"

    override fun read(): ExternalPlaybackSample? {
        val body = loopbackGet(url) ?: return null
        return parseMpcVariables(body)
    }
}

internal fun parseMpcVariables(body: String): ExternalPlaybackSample? {
    val values = MPC_VARIABLE.findAll(body).associate { it.groupValues[1] to it.groupValues[2].trim() }
    val state = values["state"]?.toIntOrNull() ?: return null
    if (state < 0) return null
    val duration = values["duration"]?.toLongOrNull()?.takeIf { it > 0L } ?: return null
    val position = values["position"]?.toLongOrNull() ?: return null
    return ExternalPlaybackSample(positionMs = position, durationMs = duration, isPaused = state != 2)
}

private val MPC_VARIABLE = Regex("""<p id="(\w+)">([^<]*)</p>""")

// --- helpers --------------------------------------------------------------------------------

private val tokenRandom = SecureRandom()

private fun randomToken(bytes: Int): String =
    ByteArray(bytes).also(tokenRandom::nextBytes).joinToString("") { "%02x".format(it) }

private fun freeLoopbackPort(): Int =
    ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
