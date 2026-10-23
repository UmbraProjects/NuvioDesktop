package com.nuvio.app.features.iptv

import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Streamed GETs for IPTV providers. Playlists, `get_live_streams` and XMLTV guides are routinely tens
 * to hundreds of MB, so unlike `httpGetText` the body is never held as one String: [read] gets the
 * stream and must finish with it before returning.
 *
 * Throws [IptvSourceException] (`Http` for a non-2xx status, `Network` for I/O failures). Cancelling
 * the calling coroutine aborts the transfer.
 */
internal expect object IptvHttp {
    suspend fun <T> get(url: String, headers: Map<String, String>, read: (InputStream) -> T): T
}

internal val IPTV_DEFAULT_HEADERS: Map<String, String> = mapOf("User-Agent" to IPTV_USER_AGENT)

/** Many guides are served as `.gz` with no Content-Encoding; the first two bytes tell. */
internal fun InputStream.gunzipIfNeeded(): InputStream {
    val buffered = if (this is BufferedInputStream) this else BufferedInputStream(this, 64 * 1024)
    buffered.mark(2)
    val b0 = buffered.read()
    val b1 = buffered.read()
    buffered.reset()
    return if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(buffered, 64 * 1024) else buffered
}

/**
 * Skips leading whitespace and a UTF-8 byte-order mark (the JSON decoder rejects both... the BOM at
 * least), then returns the first significant byte without consuming it; -1 for an empty body.
 */
internal fun BufferedInputStream.peekFirstSignificantByte(): Int {
    while (true) {
        mark(1)
        val b = read()
        if (b == ' '.code || b == '\n'.code || b == '\r'.code || b == '\t'.code || b == 0xEF || b == 0xBB || b == 0xBF) continue
        if (b != -1) reset()
        return b
    }
}
