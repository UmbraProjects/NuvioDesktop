package com.nuvio.app.features.cast

import co.touchlab.kermit.Logger
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.time.Duration
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private val relayLog = Logger.withTag("CastRelay")

/** What the relay is currently serving to the renderer. */
internal sealed interface CastMedia {
    val mime: String

    /** The source as-is, byte ranges forwarded upstream. */
    class Passthrough(
        val url: String,
        val headers: Map<String, String>,
        override val mime: String,
    ) : CastMedia

    /** The fragmented-MP4 remux, time-seekable. */
    class Remux(val router: DvRemuxRouter, val durationS: Double) : CastMedia {
        override val mime: String = "video/mp4"
    }
}

/** DLNA content features: byte-range seekable vs. time-seekable converted content. */
internal const val DLNA_FEATURES_RANGE = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
internal const val DLNA_FEATURES_TIMESEEK = "DLNA.ORG_OP=10;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=01700000000000000000000000000000"

/**
 * A minimal HTTP/1.x server for the renderer. Hand-rolled rather than the JDK's HttpServer so a
 * length-less response is sent the way the tested LG player accepted it — close-delimited, never
 * chunked — and so each request's Range and TimeSeekRange are fully in our hands.
 *
 * Serves one item at a time under an unguessable path; anything else is a 404.
 */
internal class CastRelayServer {
    private var server: ServerSocket? = null
    @Volatile private var media: CastMedia? = null
    @Volatile private var token: String = ""
    private val upstreamSlots = Semaphore(MAX_UPSTREAM)

    /** Publishes [item] and returns the URL [rendererHost] should fetch it from. */
    @Synchronized
    fun publish(item: CastMedia, rendererHost: String, extension: String): String {
        val socket = server ?: ServerSocket().also { created ->
            created.reuseAddress = true
            created.bind(InetSocketAddress(0))
            server = created
            thread(isDaemon = true, name = "cast-relay-accept") { acceptLoop(created) }
            relayLog.i { "Cast relay listening on port ${created.localPort}" }
        }
        media = item
        token = randomToken()
        return "http://${localAddressToward(rendererHost)}:${socket.localPort}/cast/$token/media.$extension"
    }

    @Synchronized
    fun clear() {
        (media as? CastMedia.Remux)?.router?.close()
        media = null
        token = ""
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                break
            }
            thread(isDaemon = true, name = "cast-relay-conn") { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 30_000
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val request = readRequest(input) ?: return
            val output = socket.getOutputStream()
            val item = media
            val path = "/cast/$token/"
            if (item == null || token.isEmpty() || !request.target.startsWith(path)) {
                writeHead(output, 404, "Not Found", mapOf("Content-Length" to "0"))
                return
            }
            relayLog.d {
                "${request.method} range=${request.header("range")} timeseek=${request.header("timeseekrange.dlna.org")} " +
                    "from=${socket.inetAddress.hostAddress}"
            }
            try {
                when (item) {
                    is CastMedia.Passthrough -> servePassthrough(request, item, output)
                    is CastMedia.Remux -> serveRemux(request, item, output)
                }
            } catch (_: IOException) {
                // The renderer hangs up whenever it has buffered enough or changes its mind.
            }
        }
    }

    private fun servePassthrough(request: RelayRequest, item: CastMedia.Passthrough, output: OutputStream) {
        val range = request.header("range")
        val isHead = request.method == "HEAD"
        if (!upstreamSlots.tryAcquire(20, TimeUnit.SECONDS)) {
            writeHead(output, 503, "Service Unavailable", mapOf("Content-Length" to "0"))
            return
        }
        try {
            val upstream = HttpRequest.newBuilder(URI(item.url)).timeout(Duration.ofSeconds(30)).GET().apply {
                item.headers.forEach { (key, value) -> runCatching { header(key, value) } }
                (range ?: if (isHead) "bytes=0-0" else null)?.let { header("Range", it) }
            }.build()
            val response = dlnaHttp.send(upstream, HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { body ->
                val status = response.statusCode()
                if (status !in 200..299) {
                    relayLog.w { "Upstream answered $status for range=$range" }
                    writeHead(output, status, "Upstream Error", mapOf("Content-Length" to "0"))
                    return
                }
                var length = response.headers().firstValue("Content-Length").orElse(null)
                var contentRange = response.headers().firstValue("Content-Range").orElse(null)
                var code = status
                if (isHead && range == null) {
                    // Report the whole size for a HEAD, not the one-byte probe behind it.
                    code = 200
                    length = contentRange?.substringAfterLast('/')?.takeIf { it != "*" } ?: length
                    contentRange = null
                }
                writeHead(
                    output, code, if (code == 206) "Partial Content" else "OK",
                    buildMap {
                        put("Content-Type", item.mime)
                        put("Accept-Ranges", "bytes")
                        length?.let { put("Content-Length", it) }
                        contentRange?.let { put("Content-Range", it) }
                        put("transferMode.dlna.org", "Streaming")
                        put("contentFeatures.dlna.org", DLNA_FEATURES_RANGE)
                    },
                )
                if (!isHead) body.copyTo(output, 256 * 1024)
            }
        } finally {
            upstreamSlots.release()
        }
    }

    private fun serveRemux(request: RelayRequest, item: CastMedia.Remux, output: OutputStream) {
        val npt = request.header("timeseekrange.dlna.org")?.let(::parseNptStart)
        val headers = mutableMapOf(
            "Content-Type" to item.mime,
            "transferMode.dlna.org" to "Streaming",
            "contentFeatures.dlna.org" to DLNA_FEATURES_TIMESEEK,
        )
        if (request.method == "HEAD") {
            writeHead(output, 200, "OK", headers)
            return
        }
        val route = item.router.route(request.header("range")?.let(::parseRangeStart), npt)
        route.replyNptS?.let { start ->
            headers["TimeSeekRange.dlna.org"] = "npt=%.3f-%.3f/%.3f".format(Locale.ROOT, start, item.durationS, item.durationS)
        }
        writeHead(output, 200, "OK", headers)
        if (route.prefix.isNotEmpty()) output.write(route.prefix)
        val sent = route.session.stream(output, route.outOffset)
        relayLog.d { "Remux connection ended after ${sent / 1_048_576} MiB (session ${route.session.keyS}s)" }
    }

    private class RelayRequest(val method: String, val target: String, val headers: Map<String, String>) {
        fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]
    }

    private fun readRequest(input: InputStream): RelayRequest? {
        val lines = mutableListOf<String>()
        val line = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val byte = input.read()
            if (byte < 0) return null
            if (++total > MAX_HEADER_BYTES) return null
            if (byte == '\n'.code) {
                val text = line.toString(Charsets.ISO_8859_1).trimEnd('\r')
                line.reset()
                if (text.isEmpty()) break
                lines += text
            } else {
                line.write(byte)
            }
        }
        val parts = lines.firstOrNull()?.split(' ') ?: return null
        if (parts.size < 2) return null
        val headers = lines.drop(1).mapNotNull { header ->
            val colon = header.indexOf(':')
            if (colon <= 0) null else header.substring(0, colon).trim().lowercase(Locale.ROOT) to header.substring(colon + 1).trim()
        }.toMap()
        return RelayRequest(parts[0].uppercase(Locale.ROOT), parts[1], headers)
    }

    private fun writeHead(output: OutputStream, code: Int, reason: String, headers: Map<String, String>) {
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            headers.forEach { (key, value) -> append(key).append(": ").append(value).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    companion object {
        private const val MAX_UPSTREAM = 6
        private const val MAX_HEADER_BYTES = 16 * 1024
        private val random = SecureRandom()

        private fun randomToken(): String =
            ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }

        /** The local address the OS would use to reach the renderer: the one it can reach us on. */
        fun localAddressToward(host: String): String =
            DatagramSocket().use { socket ->
                socket.connect(InetAddress.getByName(host), 1900)
                socket.localAddress.hostAddress
            }
    }
}
