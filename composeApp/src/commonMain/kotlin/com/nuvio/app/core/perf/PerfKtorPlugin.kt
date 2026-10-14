package com.nuvio.app.core.perf

import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.contentLength

/**
 * Feeds a Ktor client's requests into [PerfTelemetry] under [client]. Timed to response headers:
 * Ktor streams the body after Send returns, so this is time-to-first-byte rather than total.
 */
fun perfTelemetryPlugin(client: String): ClientPlugin<Unit> =
    createClientPlugin("NuvioPerfTelemetry-$client") {
        on(Send) { request ->
            if (!PerfTelemetry.enabled) return@on proceed(request)
            val method = request.method.value
            val url = request.url.buildString()
            val start = PerfTelemetry.beginHttp()
            val call = try {
                proceed(request)
            } catch (error: Throwable) {
                PerfTelemetry.endHttp(start, client, method, url, status = 0, failure = error)
                throw error
            }
            PerfTelemetry.endHttp(
                start,
                client,
                method,
                url,
                status = call.response.status.value,
                bytes = call.response.contentLength() ?: -1L,
            )
            call
        }
    }
