package com.nuvio.app.core.perf

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Response
import java.io.IOException

/**
 * Feeds every call on an OkHttp client into [PerfTelemetry] under [client]. Timed from callStart to
 * callEnd, which on these clients is when the body has been read and closed - the real cost of the
 * request, not just time to headers. Hands out [EventListener.NONE] while telemetry is off.
 */
internal class PerfOkHttpEventListenerFactory(private val client: String) : EventListener.Factory {
    override fun create(call: Call): EventListener =
        if (PerfTelemetry.enabled) Listener(client) else EventListener.NONE

    private class Listener(private val client: String) : EventListener() {
        private var startNanos = 0L
        private var status = 0
        private var bytes = 0L

        override fun callStart(call: Call) {
            startNanos = PerfTelemetry.beginHttp()
        }

        override fun responseHeadersEnd(call: Call, response: Response) {
            status = response.code
        }

        override fun responseBodyEnd(call: Call, byteCount: Long) {
            bytes += byteCount
        }

        override fun callEnd(call: Call) = finish(call, null)

        override fun callFailed(call: Call, ioe: IOException) = finish(call, ioe)

        private fun finish(call: Call, failure: IOException?) {
            val request = call.request()
            PerfTelemetry.endHttp(
                startNanos = startNanos,
                client = client,
                method = request.method,
                url = request.url.toString(),
                status = status,
                bytes = if (bytes > 0) bytes else -1L,
                failure = failure,
            )
            startNanos = 0L
        }
    }
}
