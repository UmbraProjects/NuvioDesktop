package com.nuvio.app.features.iptv

import co.touchlab.kermit.Logger
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val clearKeyLog = Logger.withTag("IptvClearKey")
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private const val CLEARKEY_HTTP_TIMEOUT_SECONDS = 8L
private const val MAX_MPD_BYTES = 1024 * 1024

/**
 * Resolves ClearKey decryption options for an IPTV channel before mpv opens it.
 *
 * Inline `kid:key` and inline JWK JSON require no network calls. HTTP(S) ClearKey license URLs
 * (such as `#KODIPROP:inputstream.adaptive.license_key=https://.../license?id=143`) fetch the MPD
 * to read `default_KID` attributes and query the license endpoint (POST with W3C ClearKey JSON,
 * falling back to GET if the server rejects POST or returns no keys). Must be called off the UI
 * thread.
 */
internal fun resolveIptvClearKeyMpvOptions(
    sourceUrl: String,
    headerLines: List<String>,
): List<String> {
    val channel = IptvPlaybackSession.channelForUrl(sourceUrl) ?: return emptyList()
    if (!IptvClearKeyParser.isClearKeyScheme(channel.drmScheme)) return emptyList()
    val licenseKey = channel.drmLicenseKey?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()

    val keys = if (IptvClearKeyParser.isLicenseUrl(licenseKey)) {
        fetchRemoteClearKeys(sourceUrl, licenseKey, parseHeaderLines(headerLines))
    } else {
        IptvClearKeyParser.parsePayload(licenseKey)
    }
    if (keys.isEmpty()) {
        clearKeyLog.w { "ClearKey channel '${channel.name}' yielded no usable decryption keys" }
        return emptyList()
    }
    val opt = IptvClearKeyParser.mpvDemuxerOption(keys) ?: return emptyList()
    clearKeyLog.i { "ClearKey resolved ${keys.size} key(s) for '${channel.name}'" }
    return listOf(opt)
}

private fun fetchRemoteClearKeys(
    manifestUrl: String,
    rawLicenseUrl: String,
    streamHeaders: Map<String, String>,
): List<IptvClearKey> {
    val pipe = rawLicenseUrl.indexOf('|')
    val licenseUrl = if (pipe > 0) rawLicenseUrl.substring(0, pipe).trim() else rawLicenseUrl
    val licenseHeaders = LinkedHashMap(streamHeaders)
    if (pipe > 0) {
        for (part in rawLicenseUrl.substring(pipe + 1).split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val k = runCatching { URLDecoder.decode(part.substring(0, eq), "UTF-8") }.getOrDefault(part.substring(0, eq)).trim()
            val v = runCatching { URLDecoder.decode(part.substring(eq + 1), "UTF-8") }.getOrDefault(part.substring(eq + 1)).trim()
            if (k.isNotEmpty() && v.isNotEmpty()) licenseHeaders[k] = v
        }
    }

    val client = IptvProxiedClients.clientFor(IptvRepository.settings.value.proxy).newBuilder()
        .connectTimeout(CLEARKEY_HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(CLEARKEY_HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CLEARKEY_HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    // 1. Fetch MPD to extract cenc:default_KID values for W3C ClearKey POST body.
    val kidsHex = runCatching {
        val req = Request.Builder().url(manifestUrl).applyHeaders(streamHeaders).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use emptyList()
            val bytes = resp.body?.source()?.let { src ->
                src.request(MAX_MPD_BYTES.toLong())
                src.buffer.clone().readByteArray(minOf(src.buffer.size, MAX_MPD_BYTES.toLong()))
            } ?: return@use emptyList()
            IptvClearKeyParser.extractMpdKidsHex(String(bytes, Charsets.UTF_8))
        }
    }.getOrElse { error ->
        clearKeyLog.w(error) { "Could not read MPD for ClearKey KIDs: ${redactClearKeyLogUrl(manifestUrl)}" }
        emptyList()
    }

    val safeLicenseUrl = redactClearKeyLogUrl(licenseUrl)

    fun tryPost(kids: List<String>): List<IptvClearKey> = runCatching {
        val body = IptvClearKeyParser.buildLicenseRequestJson(kids).toRequestBody(JSON_MEDIA_TYPE)
        val req = Request.Builder().url(licenseUrl).applyHeaders(licenseHeaders).post(body).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                clearKeyLog.d { "ClearKey POST $safeLicenseUrl returned HTTP ${resp.code}" }
                return@use emptyList()
            }
            IptvClearKeyParser.parsePayload(resp.body?.string())
        }
    }.getOrElse { error ->
        clearKeyLog.w(error) { "ClearKey POST failed for $safeLicenseUrl" }
        emptyList()
    }

    fun tryGet(): List<IptvClearKey> = runCatching {
        val req = Request.Builder().url(licenseUrl).applyHeaders(licenseHeaders).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                clearKeyLog.d { "ClearKey GET $safeLicenseUrl returned HTTP ${resp.code}" }
                return@use emptyList()
            }
            IptvClearKeyParser.parsePayload(resp.body?.string())
        }
    }.getOrElse { error ->
        clearKeyLog.w(error) { "ClearKey GET failed for $safeLicenseUrl" }
        emptyList()
    }

    // Prefer POST when we have KIDs from the MPD (standard W3C ClearKey EME); fall back to GET.
    return if (kidsHex.isNotEmpty()) {
        tryPost(kidsHex).ifEmpty { tryGet() }
    } else {
        tryGet().ifEmpty { tryPost(emptyList()) }
    }
}

private fun Request.Builder.applyHeaders(headers: Map<String, String>): Request.Builder {
    for ((k, v) in headers) {
        header(k, v)
    }
    return this
}

private fun parseHeaderLines(headerLines: List<String>): Map<String, String> = buildMap {
    for (line in headerLines) {
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        val name = line.substring(0, colon).trim()
        val value = line.substring(colon + 1).trim()
        if (name.isNotEmpty() && value.isNotEmpty()) put(name, value)
    }
}

/** Strips userinfo, query parameters, and fragments so tokens/credentials never reach `nuvio.log`. */
internal fun redactClearKeyLogUrl(raw: String): String {
    val parsed = runCatching { java.net.URI(raw.trim()) }.getOrNull()
    val scheme = parsed?.scheme
    val host = parsed?.host
    if (!scheme.isNullOrBlank() && !host.isNullOrBlank()) {
        val port = if (parsed.port > 0) ":${parsed.port}" else ""
        val path = parsed.rawPath.orEmpty()
        return "$scheme://$host$port$path"
    }
    return "<redacted-url>"
}
