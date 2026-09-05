package com.nuvio.app.features.addons

internal expect object AddonStorage {
    fun loadInstalledAddonUrls(profileId: Int): List<String>
    fun saveInstalledAddonUrls(profileId: Int, urls: List<String>)
    fun loadAddonEnabledStates(profileId: Int): Map<String, Boolean>
    fun saveAddonEnabledStates(profileId: Int, states: Map<String, Boolean>)
}

data class RawHttpResponse(
    val status: Int,
    val statusText: String,
    val url: String,
    val body: String,
    val headers: Map<String, String>,
)

expect suspend fun httpGetText(url: String): String

expect suspend fun httpPostJson(url: String, body: String): String

expect suspend fun httpGetTextWithHeaders(
    url: String,
    headers: Map<String, String>,
): String

expect suspend fun httpPostJsonWithHeaders(
    url: String,
    body: String,
    headers: Map<String, String>,
): String

/**
 * Marker appended to a [RawHttpResponse.body] that hit the platform's response size cap.
 *
 * A truncated body is still valid text but no longer valid JSON, so a caller parsing one sees a
 * confusing "unexpected end of input" rather than "the response was too big". Callers that page
 * through large collections should check for this and say so.
 */
const val RAW_HTTP_TRUNCATION_MARKER = "...[truncated]"

/**
 * Executes an HTTP request while retaining status and headers.
 *
 * [allowLargeResponse] is reserved for trusted bulk API endpoints whose valid JSON can exceed the
 * platform's normal raw-response safety cap. Ordinary addon and plugin traffic should keep the
 * default so an unexpectedly large response cannot consume unbounded memory.
 */
expect suspend fun httpRequestRaw(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String,
    followRedirects: Boolean = true,
    allowLargeResponse: Boolean = false,
): RawHttpResponse

/** True when this body was cut short by the response size cap; see [RAW_HTTP_TRUNCATION_MARKER]. */
val RawHttpResponse.isTruncated: Boolean
    get() = body.endsWith(RAW_HTTP_TRUNCATION_MARKER)

/**
 * One revalidated download of a whole file.
 *
 * Unlike [httpRequestRaw] the body is not size-capped, because the callers here are fetching bulk
 * data files rather than addon replies — a truncated one is useless, not merely shortened. Pass the
 * [etag] kept from the previous download to turn an unchanged file into an empty 304 instead of
 * transferring it again.
 */
expect suspend fun httpGetFileRevalidated(
    url: String,
    etag: String?,
): RevalidatedFileResponse

sealed interface RevalidatedFileResponse {
    /** The file is unchanged since [httpGetFileRevalidated] was last given this etag. */
    data object NotModified : RevalidatedFileResponse

    data class Downloaded(val body: String, val etag: String?) : RevalidatedFileResponse

    data class Failed(val status: Int?, val message: String?) : RevalidatedFileResponse
}
