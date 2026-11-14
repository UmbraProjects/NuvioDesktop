package com.nuvio.app.features.youtube

import co.touchlab.kermit.Logger
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.BitSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/*
 * fork:faststream-optin - see YouTubeFastStreamStatus.kt for what FastStream is.
 *
 * Each googlevideo stream (the video, and each audio track) gets a [FastStreamDownload]: a temporary
 * file filled in 4 MiB ranges by a few connections at once, ahead of where the player is reading
 * first, then the rest of the file. mpv opens it through [FastStreamServer], a loopback HTTP server
 * that answers its range requests from the file, waiting for a range that has not arrived yet. If
 * YouTube keeps refusing, the download gives up and the server redirects mpv to the stream itself.
 * Everything is dropped (connections closed, files deleted) with the next video or the player.
 */

internal actual fun requestYouTubeFastStream(on: Boolean) {
    YouTubeFastStream.request(on)
}

internal object YouTubeFastStream {
    private val log = Logger.withTag("FastStream")

    /** Video streams: what the player waits on, so the most connections. */
    private const val VIDEO_CONNECTIONS = 4

    /** Audio is a tenth of the size and needs few. */
    private const val AUDIO_CONNECTIONS = 2

    private val downloads = ConcurrentHashMap<String, FastStreamDownload>()
    private val requests = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)

    /** The watch page's button, for the main player to act on (PlayerEngine.desktop.kt). */
    val toggleRequests: SharedFlow<Boolean> = requests.asSharedFlow()

    @Volatile
    private var lastPercent = -1

    fun request(on: Boolean) {
        requests.tryEmit(on)
    }

    /** A direct googlevideo stream: FastStream can fetch it in ranges. Manifests (HLS/DASH) cannot. */
    fun eligible(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return (host == "googlevideo.com" || host.endsWith(".googlevideo.com")) &&
            uri.rawPath.orEmpty().contains("/videoplayback")
    }

    fun isEnabled(url: String): Boolean = downloads.containsKey(url)

    /** What mpv opens for [url]: the loopback copy when FastStream is on for it, else [url] itself. */
    fun playbackUrl(url: String): String = downloads[url]?.let { FastStreamServer.urlFor(it) } ?: url

    /** The audio-track string the player hands mpv (one track per line, its URL last), mapped like [playbackUrl]. */
    fun playbackAudio(audio: String?): String? = audio?.lines()?.joinToString("\n") { line ->
        val url = line.substringAfterLast('\t')
        if (downloads.containsKey(url)) line.removeSuffix(url) + playbackUrl(url) else line
    }

    @Synchronized
    fun enable(urls: List<String>, headers: Map<String, String>) {
        val forwarded = forwardedHeaders(headers)
        urls.filter(::eligible).forEach { url ->
            downloads.getOrPut(url) {
                val audio = mimeOf(url)?.startsWith("audio/") == true
                FastStreamDownload(
                    upstreamUrl = url,
                    headers = forwarded,
                    file = FastStreamServer.newFile(),
                    connections = if (audio) AUDIO_CONNECTIONS else VIDEO_CONNECTIONS,
                    onChange = ::publish,
                ).also {
                    log.i { "on for ${if (audio) "audio" else "video"} ${redact(url)}" }
                    it.start()
                }
            }
        }
        publish()
    }

    /** Drops every download but those for [urls] (the next video's streams are never among them). */
    @Synchronized
    fun retainOnly(urls: Collection<String>) {
        val keep = urls.toSet()
        downloads.keys.filter { it !in keep }.forEach { url ->
            downloads.remove(url)?.let {
                log.i { "off for ${redact(url)} (${it.downloadedBytes / 1_048_576} of ${it.totalBytes / 1_048_576} MiB fetched)" }
                it.close()
            }
        }
        publish()
    }

    /** Drops the downloads for [urls] only (a closing player's own; the next one may have started). */
    @Synchronized
    fun release(urls: Collection<String>) {
        retainOnly(downloads.keys.filter { it !in urls.toSet() })
    }

    private fun publish() {
        val all = downloads.values.toList()
        val total = all.sumOf { it.totalBytes.coerceAtLeast(0L) }
        val done = all.sumOf { it.downloadedBytes }
        val failed = all.any { it.failed }
        val throttled = all.any { it.throttled }
        val percent = if (total > 0L) ((done * 100L) / total).toInt() else 0
        val current = YouTubeFastStreamState.status.value
        // A chunk lands several times a second: only a new percent (or state) reaches the page.
        if (current.active == all.isNotEmpty() && percent == lastPercent && current.failed == failed &&
            current.throttled == throttled && current.totalBytes == total
        ) {
            return
        }
        lastPercent = percent
        YouTubeFastStreamState.update {
            it.copy(active = all.isNotEmpty(), downloadedBytes = done, totalBytes = total, throttled = throttled, failed = failed)
        }
    }

    /**
     * The player's headers minus what must not travel: no cookies or authorisation (googlevideo
     * URLs are signed in themselves, and the account stays out of FastStream's traffic), and none
     * of the headers Java's client sets itself.
     */
    internal fun forwardedHeaders(headers: Map<String, String>): Map<String, String> =
        headers.filterKeys { it.lowercase() !in DROPPED_HEADERS }

    private val DROPPED_HEADERS = setOf(
        "cookie", "authorization", "range", "host", "connection", "content-length", "expect", "upgrade",
        "transfer-encoding", "keep-alive", "te", "accept-encoding",
    )

    internal fun queryParam(url: String, name: String): String? =
        runCatching { URI(url).rawQuery }.getOrNull()
            ?.split('&')
            ?.firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }

    internal fun mimeOf(url: String): String? = queryParam(url, "mime")?.takeIf { it.isNotBlank() }

    /** Host and itag only: the URL itself is a signed credential. */
    private fun redact(url: String): String =
        "${runCatching { URI(url).host }.getOrNull()} itag=${queryParam(url, "itag")}"
}

/**
 * One stream's download. Thread-safe; [read] is called from the server's connection threads and
 * blocks until the bytes it needs are in the file.
 */
internal class FastStreamDownload(
    val upstreamUrl: String,
    private val headers: Map<String, String>,
    private val file: File,
    private val connections: Int,
    private val onChange: () -> Unit = {},
    private val chunkSize: Int = DEFAULT_CHUNK_BYTES,
    private val client: HttpClient = sharedClient,
    private val backoff: (Int) -> Long = ::backoffMs,
) {
    private val log = Logger.withTag("FastStream")
    private val lock = Object()
    private val channel: FileChannel = RandomAccessFile(file, "rw").channel

    val token: String = UUID.randomUUID().toString().replace("-", "")

    // Guarded by lock.
    private val done = BitSet()
    private val inFlight = BitSet()
    private var chunkCount = 0
    private var focusChunk = 0
    private var allowedConnections = connections
    private var runningWorkers = 0
    private var consecutiveFailures = 0
    private var ready = false

    @Volatile
    var totalBytes: Long = -1L
        private set

    @Volatile
    var contentType: String = FastStreamServer.DEFAULT_CONTENT_TYPE
        private set

    @Volatile
    var downloadedBytes: Long = 0L
        private set

    @Volatile
    var failed: Boolean = false
        private set

    @Volatile
    var throttled: Boolean = false
        private set

    @Volatile
    var closed: Boolean = false
        private set

    fun start() {
        thread(isDaemon = true, name = "FastStream-Init") {
            try {
                val size = YouTubeFastStream.queryParam(upstreamUrl, "clen")?.toLongOrNull()?.takeIf { it > 0L }
                    ?: probeSize()
                val mime = YouTubeFastStream.mimeOf(upstreamUrl)
                synchronized(lock) {
                    if (closed) return@thread
                    totalBytes = size
                    if (mime != null) contentType = mime
                    chunkCount = ((size + chunkSize - 1) / chunkSize).toInt()
                    ready = true
                    lock.notifyAll()
                }
                channel.truncate(size)
                repeat(connections) { index ->
                    synchronized(lock) { runningWorkers++ }
                    thread(isDaemon = true, name = "FastStream-$index") { workerLoop() }
                }
                onChange()
            } catch (error: Throwable) {
                if (!closed) {
                    log.w { "could not size the stream: ${error.message}" }
                    giveUp()
                }
            }
        }
    }

    /** The size from a one-byte range: "Content-Range: bytes 0-0/12345". */
    private fun probeSize(): Long {
        val response = client.send(request(0L, 0L), HttpResponse.BodyHandlers.discarding())
        response.headers().firstValue("content-type").ifPresent { contentType = it }
        val range = response.headers().firstValue("content-range").orElse(null)
        return range?.substringAfterLast('/')?.trim()?.toLongOrNull()
            ?: response.headers().firstValueAsLong("content-length").orElse(-1L).takeIf { response.statusCode() == 200 && it > 0L }
            ?: throw IOException("HTTP ${response.statusCode()} with no size")
    }

    private fun request(start: Long, endInclusive: Long): HttpRequest {
        val builder = HttpRequest.newBuilder(URI(upstreamUrl))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .header("Range", "bytes=$start-$endInclusive")
            .GET()
        headers.forEach { (name, value) -> runCatching { builder.header(name, value) } }
        return builder.build()
    }

    private fun workerLoop() {
        try {
            while (true) {
                val chunk = synchronized(lock) {
                    if (closed || failed || runningWorkers > allowedConnections) {
                        runningWorkers--
                        return
                    }
                    nextChunkLocked()?.also { inFlight.set(it) } ?: run {
                        runningWorkers--
                        return
                    }
                }
                val error = runCatching { fetchChunk(chunk) }.exceptionOrNull()
                var gaveUp = false
                synchronized(lock) {
                    inFlight.clear(chunk)
                    if (error == null) {
                        done.set(chunk)
                        consecutiveFailures = 0
                    } else if (!closed) {
                        consecutiveFailures++
                        val refused = error is RefusedException
                        if (refused) {
                            throttled = true
                            // One connection fewer each time YouTube refuses one.
                            if (allowedConnections > 1) allowedConnections--
                        }
                        log.w { "chunk $chunk failed (${error.message}); ${allowedConnections} connection(s), failure $consecutiveFailures" }
                        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES && !failed) gaveUp = true
                    }
                    lock.notifyAll()
                }
                if (error == null) {
                    onChange()
                } else if (gaveUp) {
                    giveUp()
                } else if (!closed && !failed) {
                    Thread.sleep(backoff(consecutiveFailures))
                }
            }
        } catch (_: InterruptedException) {
            synchronized(lock) { runningWorkers-- }
        }
    }

    /** The first chunk still missing from where the player reads onwards, then from the start. */
    private fun nextChunkLocked(): Int? {
        for (index in focusChunk until chunkCount) if (!done[index] && !inFlight[index]) return index
        for (index in 0 until minOf(focusChunk, chunkCount)) if (!done[index] && !inFlight[index]) return index
        return null
    }

    private fun fetchChunk(chunk: Int) {
        val start = chunk.toLong() * chunkSize
        val end = minOf(start + chunkSize, totalBytes) - 1
        val response = client.send(request(start, end), HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { body ->
            val status = response.statusCode()
            if (status == 403 || status == 429) throw RefusedException(status)
            if (status != 206 && !(status == 200 && start == 0L && end == totalBytes - 1)) throw IOException("HTTP $status")
            val expected = (end - start + 1).toInt()
            val bytes = body.readNBytes(expected)
            if (bytes.size != expected) throw IOException("short read ${bytes.size}/$expected")
            if (closed) return
            var position = start
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) position += channel.write(buffer, position)
        }
        synchronized(lock) { downloadedBytes += (end - start + 1) }
    }

    private fun giveUp() {
        synchronized(lock) {
            failed = true
            lock.notifyAll()
        }
        log.w { "gave up; the player streams from YouTube instead" }
        onChange()
    }

    /** Waits until the size is known. False if the download failed or closed first. */
    fun awaitReady(timeoutMs: Long = READY_TIMEOUT_MS): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (!ready && !failed && !closed) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0L) return false
                lock.wait(left)
            }
            return ready && !failed && !closed
        }
    }

    /**
     * Writes bytes [start]..[endInclusive] to [out], waiting for each chunk. Throws when the
     * download fails or closes before a chunk arrives (the server then drops the connection, and
     * mpv's reconnect is answered with the redirect to YouTube).
     */
    fun read(start: Long, endInclusive: Long, out: OutputStream) {
        var position = start
        val buffer = ByteBuffer.allocate(READ_BUFFER_BYTES)
        while (position <= endInclusive) {
            val chunk = (position / chunkSize).toInt()
            awaitChunk(chunk)
            val chunkEnd = minOf((chunk + 1).toLong() * chunkSize - 1, endInclusive)
            while (position <= chunkEnd) {
                buffer.clear()
                buffer.limit(minOf(buffer.capacity().toLong(), chunkEnd - position + 1).toInt())
                val read = channel.read(buffer, position)
                if (read <= 0) throw IOException("file read stopped at $position")
                out.write(buffer.array(), 0, read)
                position += read
            }
        }
        out.flush()
    }

    private fun awaitChunk(chunk: Int) {
        synchronized(lock) {
            // Where the player reads is where the connections go next.
            if (!done[chunk]) focusChunk = chunk
            while (!done[chunk]) {
                if (closed || failed) throw IOException("FastStream stopped")
                lock.wait(1_000L)
            }
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
        runCatching { channel.close() }
        if (!file.delete()) file.deleteOnExit()
    }

    private class RefusedException(status: Int) : IOException("HTTP $status")

    companion object {
        const val DEFAULT_CHUNK_BYTES = 4 * 1024 * 1024
        private const val READ_BUFFER_BYTES = 256 * 1024
        private const val REQUEST_TIMEOUT_SECONDS = 30L
        private const val READY_TIMEOUT_MS = 20_000L
        private const val MAX_CONSECUTIVE_FAILURES = 6

        internal fun backoffMs(failures: Int): Long = 1_000L shl (failures - 1).coerceIn(0, 4)

        // HTTP/1.1 so parallel ranges travel on separate connections, as FastStream's do.
        private val sharedClient: HttpClient by lazy {
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build()
        }
    }
}

/** The loopback HTTP server mpv reads FastStream downloads through. One for the app, started on first use. */
internal object FastStreamServer {
    private val log = Logger.withTag("FastStream")
    const val DEFAULT_CONTENT_TYPE = "application/octet-stream"

    private val directory: File by lazy {
        File(System.getProperty("java.io.tmpdir"), "nuvio-faststream").also { dir ->
            // Left over from a session that did not close cleanly.
            dir.listFiles()?.forEach { it.delete() }
            dir.mkdirs()
        }
    }

    private val byToken = ConcurrentHashMap<String, FastStreamDownload>()

    @Volatile
    private var socket: ServerSocket? = null

    fun newFile(): File = File(directory, "${UUID.randomUUID()}.part")

    @Synchronized
    fun urlFor(download: FastStreamDownload): String {
        byToken.entries.removeIf { it.value.closed }
        byToken[download.token] = download
        val server = socket ?: ServerSocket(0, 50, InetAddress.getLoopbackAddress()).also { created ->
            socket = created
            thread(isDaemon = true, name = "FastStream-Server") { acceptLoop(created) }
        }
        return "http://127.0.0.1:${server.localPort}/fs/${download.token}"
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull() ?: continue
            thread(isDaemon = true, name = "FastStream-Conn") {
                client.use { runCatching { serve(it) } }
            }
        }
    }

    private fun serve(client: Socket) {
        client.soTimeout = 30_000
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = generateSequence { readLine(input)?.takeIf { it.isNotEmpty() } }
            .mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() } }
            .toMap()
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val token = parts.getOrNull(1)?.removePrefix("/fs/")?.substringBefore('?')
        val out = client.getOutputStream().buffered(64 * 1024)
        val download = token?.let(byToken::get)
        if (download == null || download.closed || (method != "GET" && method != "HEAD")) {
            out.writeHead("404 Not Found", listOf("Content-Length" to "0"))
            out.flush()
            return
        }
        // Given up (or never sized): send mpv to YouTube itself. FFmpeg keeps the redirected
        // address for the rest of the file, so playback simply carries on without FastStream.
        if (!download.awaitReady()) {
            out.writeHead("302 Found", listOf("Location" to download.upstreamUrl, "Content-Length" to "0"))
            out.flush()
            return
        }
        val total = download.totalBytes
        val range = parseRange(headers["range"], total)
        if (range == null) {
            out.writeHead("416 Range Not Satisfiable", listOf("Content-Range" to "bytes */$total", "Content-Length" to "0"))
            out.flush()
            return
        }
        val (start, end) = range
        val partial = headers.containsKey("range")
        out.writeHead(
            if (partial) "206 Partial Content" else "200 OK",
            buildList {
                add("Content-Type" to download.contentType)
                add("Content-Length" to (end - start + 1).toString())
                add("Accept-Ranges" to "bytes")
                if (partial) add("Content-Range" to "bytes $start-$end/$total")
            },
        )
        if (method == "HEAD") {
            out.flush()
            return
        }
        try {
            download.read(start, end, out)
        } catch (error: IOException) {
            // mpv seeking away closes its side; anything else is FastStream stopping.
            if (!download.closed && download.failed) log.i { "reader dropped: ${error.message}" }
        }
    }

    /** bytes=a-b, bytes=a- or bytes=-n against [total]; the whole file without a header; null if unsatisfiable. */
    internal fun parseRange(header: String?, total: Long): Pair<Long, Long>? {
        if (header == null) return if (total > 0L) 0L to total - 1 else null
        val spec = header.substringAfter("bytes=", "").substringBefore(',').trim()
        val startText = spec.substringBefore('-').trim()
        val endText = spec.substringAfter('-', "").trim()
        val (start, end) = if (startText.isEmpty()) {
            val suffix = endText.toLongOrNull() ?: return null
            (total - suffix).coerceAtLeast(0L) to total - 1
        } else {
            val start = startText.toLongOrNull() ?: return null
            start to (endText.toLongOrNull() ?: (total - 1)).coerceAtMost(total - 1)
        }
        return if (start in 0 until total && end >= start) start to end else null
    }

    private fun OutputStream.writeHead(status: String, headers: List<Pair<String, String>>) {
        val text = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        write(text.toByteArray(StandardCharsets.ISO_8859_1))
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte == -1) return if (line.isEmpty()) null else line.toString()
            if (byte == '\n'.code) return line.toString().trimEnd('\r')
            if (line.length > 8_192) throw IOException("header line too long")
            line.append(byte.toChar())
        }
    }
}
