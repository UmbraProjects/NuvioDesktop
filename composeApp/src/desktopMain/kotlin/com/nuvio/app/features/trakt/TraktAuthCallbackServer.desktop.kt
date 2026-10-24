package com.nuvio.app.features.trakt

import co.touchlab.kermit.Logger
import com.sun.net.httpserver.HttpServer
import io.ktor.http.Url
import java.net.InetSocketAddress
import java.util.concurrent.Executors

internal actual object TraktAuthCallbackServer {
    private val log = Logger.withTag("TraktAuthCallbackServer")
    private var server: HttpServer? = null

    @Synchronized
    actual fun ensureStarted() {
        if (server != null) return

        val configuredRedirectUri = TraktSettingsRepository.effectiveCredentials().redirectUri
        val redirectUri = runCatching { Url(configuredRedirectUri) }.getOrNull() ?: return
        if (redirectUri.host != "localhost" && redirectUri.host != "127.0.0.1") return

        runCatching {
            val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", redirectUri.port), 0)
            httpServer.executor = Executors.newSingleThreadExecutor()
            httpServer.createContext(redirectUri.encodedPath.ifBlank { "/" }) { exchange ->
                runCatching {
                    val query = exchange.requestURI.rawQuery.orEmpty()
                    val callbackUrl = if (query.isBlank()) {
                        configuredRedirectUri
                    } else {
                        "$configuredRedirectUri?$query"
                    }

                    val responseBody = TRAKT_CALLBACK_HTML.toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                    exchange.sendResponseHeaders(200, responseBody.size.toLong())
                    exchange.responseBody.use { it.write(responseBody) }

                    handleTraktAuthCallbackUrl(callbackUrl)
                }.onFailure { error ->
                    log.w { "Failed to handle Trakt auth callback: ${error.message}" }
                    runCatching { exchange.sendResponseHeaders(500, -1) }
                }
                stop()
            }
            httpServer.start()
            server = httpServer
        }.onFailure { error ->
            log.w { "Failed to start Trakt auth callback server: ${error.message}" }
        }
    }

    @Synchronized
    actual fun stop() {
        server?.stop(0)
        server = null
    }
}

private const val TRAKT_CALLBACK_HTML = """<!DOCTYPE html>
<html>
<head><title>Nuvio</title></head>
<body style="font-family: sans-serif; background: #0D0D0D; color: #F5F7F8; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0;">
<p>Trakt sign-in complete. You can close this tab and return to Nuvio.</p>
</body>
</html>"""
