package com.nuvio.app.features.iptv

import co.touchlab.kermit.Logger
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import kotlin.concurrent.thread
import okhttp3.Request

/**
 * A local HTTP proxy that mpv uses for IPTV playback, forwarding everything through the user's
 * IPTV proxy ([IptvProxy]) with the same OkHttp client that loads playlists.
 *
 * Why: mpv (FFmpeg) only speaks HTTP proxies, and the providers that need a proxy are usually
 * behind an ISP block that a SOCKS5-only VPN service gets around. Going through this relay for
 * HTTP proxies too keeps the proxy's password out of mpv's options (which mpv logs) and puts both
 * types on one tested path.
 *
 * Bound to the loopback interface only, and every request must carry a per-run random password
 * ([mpvProxyUrl] hands it to mpv), so other local programs cannot use it as an open proxy.
 * One request per connection (`Connection: close`): FFmpeg reconnects per HLS segment anyway, and
 * a live MPEG-TS stream is a single endless response.
 */
internal object IptvProxyRelay {
    private val log = Logger.withTag("IptvRelay")
    private const val USER = "nuvio"

    private val token: String = ByteArray(18).also { SecureRandom().nextBytes(it) }
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private val expectedAuthorization = "Basic " + Base64.getEncoder().encodeToString("$USER:$token".toByteArray())

    @Volatile private var server: ServerSocket? = null

    /** `http://nuvio:<token>@127.0.0.1:<port>` for mpv's `http-proxy`, starting the relay if needed. */
    @Synchronized
    fun mpvProxyUrl(): String {
        val socket = server ?: start()
        return "http://$USER:$token@127.0.0.1:${socket.localPort}"
    }

    private fun start(): ServerSocket {
        val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        server = socket
        thread(name = "iptv-relay-accept", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                thread(name = "iptv-relay-conn", isDaemon = true) {
                    try {
                        handle(client)
                    } catch (e: IOException) {
                        // mpv closing a connection mid-stream (seek, channel change, stop) lands here.
                    } catch (e: Exception) {
                        log.w { "relay request failed: ${describeIptvFailure(e)}" }
                    } finally {
                        runCatching { client.close() }
                    }
                }
            }
        }
        log.i { "IPTV relay listening on 127.0.0.1:${socket.localPort}" }
        return socket
    }

    /** For tests: stops the relay so the next [mpvProxyUrl] starts a fresh one. */
    @Synchronized
    internal fun stopForTest() {
        runCatching { server?.close() }
        server = null
    }

    private class RequestHead(val method: String, val target: String, val headers: List<Pair<String, String>>) {
        fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
    }

    private fun handle(client: Socket) {
        client.soTimeout = 120_000
        val input = BufferedInputStream(client.getInputStream())
        val output = client.getOutputStream()
        val head = readHead(input) ?: return
        if (head.header("Proxy-Authorization") != expectedAuthorization) {
            output.writeHead(407, "Proxy Authentication Required", listOf("Proxy-Authenticate" to "Basic realm=\"nuvio\""))
            return
        }
        when {
            head.method.equals("CONNECT", ignoreCase = true) -> tunnel(head, input, output)
            head.method.equals("GET", ignoreCase = true) || head.method.equals("HEAD", ignoreCase = true) -> forward(head, output)
            else -> output.writeHead(405, "Method Not Allowed")
        }
    }

    /** Plain `http://` requests (absolute-form): re-issued through OkHttp and its proxy, streamed back. */
    private fun forward(head: RequestHead, output: OutputStream) {
        val request = try {
            Request.Builder().url(head.target).method(head.method.uppercase(), null).apply {
                head.headers.forEach { (name, value) -> if (name.lowercase() !in HopByHop) addHeader(name, value) }
            }.build()
        } catch (e: IllegalArgumentException) {
            output.writeHead(400, "Bad Request")
            return
        }
        val client = IptvProxiedClients.clientFor(IptvRepository.settings.value.proxy).newBuilder()
            // mpv follows redirects itself (back through this relay), and must see them to do so.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            log.w { "relay upstream failed: ${describeIptvFailure(e)}" }
            output.writeHead(502, "Bad Gateway")
            return
        }
        response.use {
            val headers = response.headers.filter { (name, _) -> name.lowercase() !in HopByHop }
            output.writeHead(response.code, response.message.ifBlank { "OK" }, headers)
            if (head.method.equals("HEAD", ignoreCase = true)) return
            response.body?.byteStream()?.use { body -> copy(body, output) }
        }
    }

    /** `CONNECT host:port` (https through the proxy): a raw tunnel opened via the user's proxy. */
    private fun tunnel(head: RequestHead, clientIn: InputStream, clientOut: OutputStream) {
        val host = head.target.substringBeforeLast(':')
        val port = head.target.substringAfterLast(':').toIntOrNull() ?: 443
        val upstream = try {
            openUpstream(host, port)
        } catch (e: IOException) {
            log.w { "relay tunnel failed: ${describeIptvFailure(e)}" }
            clientOut.writeHead(502, "Bad Gateway")
            return
        }
        upstream.use {
            clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            clientOut.flush()
            val toUpstream = thread(isDaemon = true) {
                runCatching { copy(clientIn, upstream.getOutputStream()) }
                runCatching { upstream.shutdownOutput() }
            }
            runCatching { copy(upstream.getInputStream(), clientOut) }
            toUpstream.join(1_000)
        }
    }

    private fun openUpstream(host: String, port: Int): Socket {
        val proxy = IptvRepository.settings.value.proxy
        val target = InetSocketAddress.createUnresolved(host, port)
        if (!proxy.enabled) return Socket().apply { connect(InetSocketAddress(host, port), 20_000) }
        return when (proxy.type) {
            IptvProxyType.Socks -> {
                IptvProxiedClients.clientFor(proxy) // installs the SOCKS credentials
                Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(proxy.host, proxy.port)))
                    .apply { connect(target, 20_000) }
            }
            else -> {
                val socket = Socket().apply { connect(InetSocketAddress(proxy.host, proxy.port), 20_000) }
                val auth = if (proxy.username.isNotEmpty()) {
                    "Proxy-Authorization: ${okhttp3.Credentials.basic(proxy.username, proxy.password)}\r\n"
                } else {
                    ""
                }
                val out = socket.getOutputStream()
                out.write("CONNECT $host:$port HTTP/1.1\r\nHost: $host:$port\r\n$auth\r\n".toByteArray(Charsets.ISO_8859_1))
                out.flush()
                val reply = readHead(BufferedInputStream(socket.getInputStream(), 1)) // unbuffered: the tunnel follows
                val status = reply?.target?.toIntOrNull()
                if (status != 200) {
                    socket.close()
                    throw IOException("proxy refused CONNECT: ${reply?.target}")
                }
                socket
            }
        }
    }

    /**
     * Reads a request (or, for the upstream CONNECT reply, a status) head up to the blank line.
     * For a status line, [RequestHead.target] holds the status code.
     */
    private fun readHead(input: InputStream): RequestHead? {
        val first = readLine(input) ?: return null
        val parts = first.split(' ', limit = 3)
        if (parts.size < 2) return null
        val headers = ArrayList<Pair<String, String>>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers += line.substring(0, colon).trim() to line.substring(colon + 1).trim()
            if (headers.size > 100) return null
        }
        return RequestHead(parts[0], parts[1], headers)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 16_384) return null
        }
    }

    private fun OutputStream.writeHead(code: Int, message: String, headers: List<Pair<String, String>> = emptyList()) {
        val text = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(message).append("\r\n")
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        write(text.toByteArray(Charsets.ISO_8859_1))
        flush()
    }

    private fun copy(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = from.read(buffer)
            if (n < 0) break
            to.write(buffer, 0, n)
            to.flush()
        }
    }

    private val HopByHop = setOf(
        "connection", "keep-alive", "proxy-authorization", "proxy-authenticate", "proxy-connection",
        "te", "trailer", "transfer-encoding", "upgrade", "host",
    )
}
