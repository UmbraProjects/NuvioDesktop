package com.nuvio.app.features.music.potoken

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

internal class CdpEvent(val method: String, val params: JsonObject, val sessionId: String?)

/**
 * A small DevTools protocol client: commands with replies, events, and flat-mode sessions (a `sessionId`
 * on a command addresses one tab). Replies are matched by id on the socket thread; events are handed to
 * listeners on [Dispatchers.IO], so a listener may itself issue commands without blocking the socket.
 *
 * `YouTubeMusicBrowserLogin.CdpConnection` is smaller still but drops events, which interception needs.
 */
internal class CdpClient private constructor() : WebSocket.Listener {
    private val json = Json { ignoreUnknownKeys = true }
    private val ids = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
    private val listeners = CopyOnWriteArrayList<suspend (CdpEvent) -> Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val buffer = StringBuilder()

    /** `java.net.http.WebSocket` fails a send that starts while another is still going out. */
    private val sendLock = Mutex()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    var isClosed: Boolean = false
        private set

    fun onEvent(listener: suspend (CdpEvent) -> Unit) {
        listeners += listener
    }

    suspend fun call(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        sessionId: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): JsonObject {
        val ws = socket?.takeUnless { isClosed } ?: throw PoTokenException("The browser connection is closed")
        val id = ids.incrementAndGet()
        val future = CompletableFuture<JsonObject>()
        pending[id] = future
        val message = buildJsonObject {
            put("id", id)
            put("method", method)
            put("params", params)
            if (sessionId != null) put("sessionId", sessionId)
        }
        try {
            return withContext(Dispatchers.IO) {
                try {
                    sendLock.withLock { ws.sendText(message.toString(), true).get(timeoutMs, TimeUnit.MILLISECONDS) }
                    future.get(timeoutMs, TimeUnit.MILLISECONDS)
                } catch (error: TimeoutException) {
                    throw PoTokenException("$method timed out after ${timeoutMs}ms", error)
                } catch (error: java.util.concurrent.ExecutionException) {
                    throw (error.cause as? PoTokenException) ?: PoTokenException("$method failed: ${error.cause?.message}", error.cause)
                }
            }
        } finally {
            pending.remove(id)
        }
    }

    fun close() {
        if (isClosed) return
        isClosed = true
        runCatching { socket?.abort() }
        socket = null
        failAll(PoTokenException("The browser connection closed"))
        scope.cancel()
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
        buffer.append(data)
        if (last) {
            val text = buffer.toString()
            buffer.setLength(0)
            runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()?.let(::dispatch)
        }
        webSocket.request(1)
        return null
    }

    private fun dispatch(message: JsonObject) {
        val id = message["id"]?.jsonPrimitive?.int
        if (id != null) {
            val future = pending[id] ?: return
            val error = message["error"]
            if (error != null) {
                val text = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: error.toString()
                future.completeExceptionally(PoTokenException("DevTools error: $text"))
            } else {
                future.complete(message["result"]?.jsonObject ?: JsonObject(emptyMap()))
            }
            return
        }
        val method = message["method"]?.jsonPrimitive?.contentOrNull ?: return
        val event = CdpEvent(
            method = method,
            params = message["params"]?.jsonObject ?: JsonObject(emptyMap()),
            sessionId = message["sessionId"]?.jsonPrimitive?.contentOrNull,
        )
        for (listener in listeners) scope.launch { runCatching { listener(event) } }
    }

    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
        isClosed = true
        failAll(PoTokenException("The browser connection closed"))
        return null
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        isClosed = true
        failAll(PoTokenException("The browser connection failed: ${error.message}", error))
    }

    private fun failAll(error: Throwable) {
        pending.values.forEach { it.completeExceptionally(error) }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L

        suspend fun connect(url: String): CdpClient = withContext(Dispatchers.IO) {
            val client = CdpClient()
            try {
                client.socket = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .buildAsync(URI(url), client)
                    .get(10, TimeUnit.SECONDS)
            } catch (error: Exception) {
                client.close()
                throw PoTokenException("Could not connect to the browser: ${error.message}", error)
            }
            client
        }
    }
}
