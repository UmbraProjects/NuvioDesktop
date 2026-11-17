package com.nuvio.app.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout

/** The HTTP client the media-server providers use (upstream's `createApiHttpClient`). */
internal actual fun createApiHttpClient(): HttpClient = HttpClient(CIO) {
    // As upstream: a redirect is never followed, so a server's token is not carried to another host.
    followRedirects = false
    expectSuccess = false
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 30_000
    }
}
