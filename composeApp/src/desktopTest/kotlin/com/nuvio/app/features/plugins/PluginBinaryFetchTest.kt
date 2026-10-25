package com.nuvio.app.features.plugins

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Binary request and response bodies through the plugin fetch bridge (upstream 12621c654). */
class PluginBinaryFetchTest {

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private inline fun <T> withServer(block: (baseUrl: String, received: AtomicReference<ByteArray>) -> T): T {
        val received = AtomicReference(ByteArray(0))
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/echo") { exchange ->
            val body = exchange.requestBody.readBytes()
            received.set(body)
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/text") { exchange ->
            val body = "héllo €".toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        return try {
            block("http://127.0.0.1:${server.address.port}", received)
        } finally {
            server.stop(0)
        }
    }

    private val toHex = """
        function toHex(buffer) {
            var bytes = new Uint8Array(buffer);
            var out = '';
            for (var i = 0; i < bytes.length; i++) out += (bytes[i] < 16 ? '0' : '') + bytes[i].toString(16);
            return out;
        }
    """.trimIndent()

    @Test
    fun `typed array request and arrayBuffer response keep exact bytes`() = runBlocking {
        val sent = byteArrayOf(0x00, 0x01, 0x7f, 0x80.toByte(), 0xc3.toByte(), 0xff.toByte(), 0x10)
        withServer { baseUrl, received ->
            val results = PluginRuntime.executePlugin(
                code = """
                    $toHex
                    module.exports.getStreams = async function() {
                        var whole = new Uint8Array([0xaa, 0, 1, 127, 128, 195, 255, 16, 0xbb]);
                        // A view into the middle of a larger buffer: offset and length must be kept.
                        var view = new Uint8Array(whole.buffer, 1, 7);
                        var response = await fetch("$baseUrl/echo", {
                            method: "POST",
                            headers: { "Content-Type": "application/octet-stream" },
                            body: view
                        });
                        var bytes = await response.arrayBuffer();
                        return [{ title: toHex(bytes), url: "https://example.test/a.mp4" }];
                    };
                """.trimIndent(),
                tmdbId = "603",
                mediaType = "movie",
                season = null,
                episode = null,
                scraperId = "binary-fetch-test",
            )
            assertContentEquals(sent, received.get())
            assertEquals(sent.hex(), results.single().title)
        }
    }

    @Test
    fun `arrayBuffer of a utf-8 text response is its exact bytes`() = runBlocking {
        withServer { baseUrl, _ ->
            val results = PluginRuntime.executePlugin(
                code = """
                    $toHex
                    module.exports.getStreams = async function() {
                        var response = await fetch("$baseUrl/text");
                        var text = await response.text();
                        var bytes = await response.arrayBuffer();
                        return [{ title: toHex(bytes), quality: text, url: "https://example.test/a.mp4" }];
                    };
                """.trimIndent(),
                tmdbId = "603",
                mediaType = "movie",
                season = null,
                episode = null,
                scraperId = "binary-fetch-text-test",
            )
            assertEquals("héllo €".toByteArray(Charsets.UTF_8).hex(), results.single().title)
            assertEquals("héllo €", results.single().quality)
        }
    }
}
