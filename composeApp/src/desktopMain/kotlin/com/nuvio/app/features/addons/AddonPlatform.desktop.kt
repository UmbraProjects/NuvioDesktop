package com.nuvio.app.features.addons

import com.nuvio.app.core.network.DesktopIPv4FirstDns
import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.network_empty_response_body
import nuvio.composeapp.generated.resources.network_request_failed_http
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.jetbrains.compose.resources.getString
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

internal actual object AddonStorage {
    private val store = DesktopStorage.store("nuvio_addons")
    private val json = Json { ignoreUnknownKeys = true }

    actual fun loadInstalledAddonUrls(profileId: Int): List<String> =
        store.getString("installed_addon_urls_$profileId")
            ?.let { payload -> runCatching { json.decodeFromString<List<String>>(payload) }.getOrNull() }
            ?: emptyList()

    actual fun saveInstalledAddonUrls(profileId: Int, urls: List<String>) {
        store.putString("installed_addon_urls_$profileId", json.encodeToString(urls))
    }

    actual fun loadAddonEnabledStates(profileId: Int): Map<String, Boolean> =
        store.getString("addon_enabled_states_$profileId")
            ?.let { payload -> runCatching { json.decodeFromString<Map<String, Boolean>>(payload) }.getOrNull() }
            ?: emptyMap()

    actual fun saveAddonEnabledStates(profileId: Int, states: Map<String, Boolean>) {
        store.putString("addon_enabled_states_$profileId", json.encodeToString(states))
    }
}

private val desktopHttpClient = OkHttpClient.Builder()
    .dns(DesktopIPv4FirstDns())
    .connectTimeout(60, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

private const val MAX_RAW_RESPONSE_BODY_BYTES = 1024 * 1024
private const val RAW_RESPONSE_TRUNCATION_SUFFIX = "\n$RAW_HTTP_TRUNCATION_MARKER"

actual suspend fun httpGetText(url: String): String =
    executeTextRequest("GET", url, mapOf("Accept" to "application/json"))

actual suspend fun httpPostJson(url: String, body: String): String =
    executeTextRequest(
        method = "POST",
        url = url,
        headers = mapOf("Accept" to "application/json", "Content-Type" to "application/json"),
        body = body,
    )

actual suspend fun httpGetTextWithHeaders(
    url: String,
    headers: Map<String, String>,
): String = executeTextRequest("GET", url, mapOf("Accept" to "application/json") + headers)

actual suspend fun httpPostJsonWithHeaders(
    url: String,
    body: String,
    headers: Map<String, String>,
): String = executeTextRequest(
    method = "POST",
    url = url,
    headers = mapOf("Accept" to "application/json", "Content-Type" to "application/json") + headers,
    body = body,
)

actual suspend fun httpRequestRaw(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String,
    followRedirects: Boolean,
): RawHttpResponse = withContext(Dispatchers.IO) {
    val client = if (followRedirects) {
        desktopHttpClient
    } else {
        desktopHttpClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }
    client.newCall(buildDesktopRequest(method, url, headers, body)).execute().use { response ->
        RawHttpResponse(
            status = response.code,
            statusText = response.message,
            url = response.request.url.toString(),
            body = readResponseBodyLimited(response.body),
            headers = response.headers.toMultimap()
                .mapValues { (_, values) -> values.joinToString(",") }
                .mapKeys { (name, _) -> name.lowercase() },
        )
    }
}

actual suspend fun httpGetFileRevalidated(
    url: String,
    etag: String?,
): RevalidatedFileResponse = withContext(Dispatchers.IO) {
    val headers = buildMap {
        put("Accept", "application/json")
        if (!etag.isNullOrBlank()) put("If-None-Match", etag)
    }
    try {
        desktopHttpClient.newCall(buildDesktopRequest("GET", url, headers, "")).execute().use { response ->
            when {
                response.code == 304 -> RevalidatedFileResponse.NotModified
                !response.isSuccessful -> RevalidatedFileResponse.Failed(response.code, response.message)
                else -> RevalidatedFileResponse.Downloaded(
                    body = readResponseBody(response.body),
                    // The etag belongs to whatever actually served the bytes, which for a file
                    // parked behind a redirect is the storage host rather than the API.
                    etag = response.header("ETag"),
                )
            }
        }
    } catch (error: Exception) {
        RevalidatedFileResponse.Failed(status = null, message = error.message)
    }
}

private suspend fun executeTextRequest(
    method: String,
    url: String,
    headers: Map<String, String> = emptyMap(),
    body: String = "",
): String = withContext(Dispatchers.IO) {
    desktopHttpClient.newCall(buildDesktopRequest(method, url, headers, body)).execute().use { response ->
        val payload = readResponseBody(response.body)
        if (!response.isSuccessful) {
            error(runBlocking { getString(Res.string.network_request_failed_http, response.code) })
        }
        if (payload.isBlank()) {
            throw IllegalStateException(runBlocking { getString(Res.string.network_empty_response_body) })
        }
        payload
    }
}

private fun buildDesktopRequest(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String,
): Request {
    val normalizedMethod = method.trim().uppercase().ifBlank { "GET" }
    val sanitizedHeaders = headers.filterKeys { !it.equals("Accept-Encoding", ignoreCase = true) }
    val builder = Request.Builder().url(url.encodeUnsafeHttpUrlCharacters())
    sanitizedHeaders.forEach { (key, value) ->
        if (key.isNotBlank() && value.isNotBlank()) builder.header(key, value)
    }

    return if (normalizedMethod in setOf("POST", "PUT", "PATCH", "DELETE")) {
        val contentType = sanitizedHeaders.entries
            .firstOrNull { (key, _) -> key.equals("Content-Type", ignoreCase = true) }
            ?.value
            ?: if (normalizedMethod == "POST") "application/x-www-form-urlencoded" else "application/json"
        builder.method(
            normalizedMethod,
            body.toByteArray(Charsets.UTF_8).toRequestBody(contentType.toMediaType()),
        )
    } else {
        builder.method(normalizedMethod, null)
    }.build()
}

private data class LimitedReadResult(val bytes: ByteArray, val truncated: Boolean)

private fun readAtMostBytes(stream: InputStream, maxBytes: Int): LimitedReadResult {
    val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
    val buffer = ByteArray(8 * 1024)
    var remaining = maxBytes
    while (remaining > 0) {
        val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
        if (read <= 0) break
        output.write(buffer, 0, read)
        remaining -= read
    }
    return LimitedReadResult(
        bytes = output.toByteArray(),
        truncated = remaining == 0 && stream.read() != -1,
    )
}

private fun readResponseBodyLimited(body: ResponseBody?): String {
    if (body == null) return ""
    val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
    val readResult = body.byteStream().use { readAtMostBytes(it, MAX_RAW_RESPONSE_BODY_BYTES) }
    val decoded = runCatching { String(readResult.bytes, charset) }
        .getOrElse { String(readResult.bytes, Charsets.UTF_8) }
    return if (readResult.truncated) decoded + RAW_RESPONSE_TRUNCATION_SUFFIX else decoded
}

private fun readResponseBody(body: ResponseBody?): String {
    if (body == null) return ""
    val bytes = body.bytes()
    return runCatching {
        String(bytes, body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
    }.getOrElse { String(bytes, Charsets.UTF_8) }
}
