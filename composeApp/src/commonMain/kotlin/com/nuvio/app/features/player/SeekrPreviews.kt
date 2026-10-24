package com.nuvio.app.features.player

import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.player.skip.SkipLookupTarget
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

/**
 * Seek-bar previews from Seekr (seekr.tv): pre-rendered 320x180 sprite sheets, looked up once per
 * playback by IMDb id + runtime and then served from Cloudflare. For an internet source this
 * replaces the native preview decoder, whose every hovered position is another range open against
 * the debrid host — the reason [SeekThumbnailRateLimitGate] exists.
 *
 * The key is always the user's own. Seekr's terms forbid exposing a key in client software, and a
 * shared one could not survive the per-key caps anyway (20 distinct films / 70 episodes a day,
 * repeated cap hits risk revocation). Only the lookup carries it; the WebVTT and sheet URLs it
 * returns are signed and keyless, so the HUD fetches those itself and never sees the key.
 */
internal data class SeekrTrack(
    val vttUrl: String,
    /**
     * Client duration / source duration (e.g. ~0.959 for a PAL-speed release). The HUD maps a
     * hovered position onto the sprites as `client / scale`, per Seekr's docs.
     */
    val scale: Double,
    val expiresAtMs: Long,
)

internal sealed interface SeekrLookupResult {
    data class Found(val track: SeekrTrack) : SeekrLookupResult
    /** No sheet for this title, or none within Seekr's runtime tolerance of this cut. */
    data object NotCovered : SeekrLookupResult
    /** Over a quota or rate limit, or the key was refused: try again later or not at all. */
    data object Refused : SeekrLookupResult
    data object Failed : SeekrLookupResult
}

@Serializable
private data class SeekrSpritesResponse(
    @SerialName("vtt_url") val vttUrl: String? = null,
    val scale: Double? = null,
)

internal object SeekrPreviews {
    private const val API_BASE = "https://api.seekr.tv"
    private const val LOOKUP_TIMEOUT_MS = 8_000L
    /** Re-look up this long before the signed URLs expire, so a long pause never strands the HUD. */
    private const val EXPIRY_MARGIN_MS = 30L * 60L * 1000L
    private const val DEFAULT_TTL_MS = 5L * 60L * 60L * 1000L
    /** A 429 without Retry-After: the daily caps reset at 00:00 UTC, so an hour is a fair floor. */
    private const val DEFAULT_REFUSAL_BACKOFF_MS = 60L * 60L * 1000L

    private val json = Json { ignoreUnknownKeys = true }

    private val found = HashMap<String, SeekrTrack>()
    private val notCovered = HashSet<String>()
    private var refusedUntilMs = 0L
    /** A key Seekr answered 401/403 for; not asked again until the user changes it. */
    private var rejectedKey: String? = null

    /** Movie or IMDb-addressed episode only — anime-list ids have nothing Seekr can match. */
    internal fun lookupQuery(target: SkipLookupTarget, durationMs: Long): String? = when (target) {
        is SkipLookupTarget.Movie -> "imdb_id=${target.imdbId}&duration_ms=$durationMs"
        is SkipLookupTarget.Episode ->
            "show_imdb_id=${target.imdbId}&season=${target.season}&episode=${target.episode}&duration_ms=$durationMs"
        else -> null
    }

    /**
     * Every distinct title spends one of the day's 20/70, and repeat lookups still spend the
     * 5,000-a-day lookup budget, so answers are cached for the life of the process: a hit until
     * its signed URLs near expiry, a miss for good (a re-cut that gets covered later is a rare
     * loss next to burning the caps on retries).
     */
    suspend fun lookup(
        apiKey: String,
        target: SkipLookupTarget,
        durationMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): SeekrLookupResult {
        val key = apiKey.trim()
        if (key.isEmpty() || durationMs <= 0L) return SeekrLookupResult.NotCovered
        val query = lookupQuery(target, durationMs) ?: return SeekrLookupResult.NotCovered
        // Same title, same cut to the second: one cache entry. A different source of a different
        // runtime is a separate question, since the answer (and its scale) depends on it.
        val cacheKey = query.substringBefore("&duration_ms=") + "|" + durationMs / 1000L
        synchronized(this) {
            found[cacheKey]?.let { track ->
                if (nowMs < track.expiresAtMs - EXPIRY_MARGIN_MS) return SeekrLookupResult.Found(track)
                found.remove(cacheKey)
            }
            if (cacheKey in notCovered) return SeekrLookupResult.NotCovered
            if (key == rejectedKey || nowMs < refusedUntilMs) return SeekrLookupResult.Refused
        }

        val response = try {
            httpRequestRaw(
                method = "GET",
                url = "$API_BASE/sprites?$query",
                headers = mapOf("X-API-Key" to key, "Accept" to "application/json"),
                body = "",
                followRedirects = false,
                callTimeoutMs = LOOKUP_TIMEOUT_MS,
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return SeekrLookupResult.Failed
        }

        return synchronized(this) {
            when (response.status) {
                200 -> {
                    val parsed = runCatching { json.decodeFromString<SeekrSpritesResponse>(response.body) }.getOrNull()
                    val vttUrl = parsed?.vttUrl?.takeIf { it.startsWith("https://") }
                    if (vttUrl == null) {
                        SeekrLookupResult.Failed
                    } else {
                        val track = SeekrTrack(
                            vttUrl = vttUrl,
                            scale = parsed.scale?.takeIf { it > 0.0 && it.isFinite() } ?: 1.0,
                            expiresAtMs = signedUrlExpiryMs(vttUrl) ?: (nowMs + DEFAULT_TTL_MS),
                        )
                        found[cacheKey] = track
                        SeekrLookupResult.Found(track)
                    }
                }
                404 -> {
                    notCovered += cacheKey
                    SeekrLookupResult.NotCovered
                }
                401, 403 -> {
                    rejectedKey = key
                    SeekrLookupResult.Refused
                }
                429 -> {
                    refusedUntilMs = nowMs + retryAfterMs(response.headers)
                    SeekrLookupResult.Refused
                }
                else -> SeekrLookupResult.Failed
            }
        }
    }

    /** `exp` on the signed URL is epoch seconds. */
    internal fun signedUrlExpiryMs(url: String): Long? =
        Regex("[?&]exp=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()?.times(1000L)

    internal fun retryAfterMs(headers: Map<String, String>): Long =
        headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
            ?.value?.trim()?.toLongOrNull()?.takeIf { it > 0L }?.times(1000L)
            ?: DEFAULT_REFUSAL_BACKOFF_MS

    /** Seekr's key check; costs no lookup and no distinct title. */
    suspend fun validateKey(apiKey: String): Boolean? {
        val key = apiKey.trim().takeIf { it.isNotEmpty() } ?: return false
        val response = try {
            httpRequestRaw(
                method = "GET",
                url = "$API_BASE/v1/keys/validate",
                headers = mapOf("X-API-Key" to key, "Accept" to "application/json"),
                body = "",
                followRedirects = false,
                callTimeoutMs = LOOKUP_TIMEOUT_MS,
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return null
        }
        return when (response.status) {
            200 -> response.body.contains("\"valid\":true")
            401, 403 -> false
            else -> null
        }.also { valid ->
            if (valid == true) synchronized(this) { if (rejectedKey == key) rejectedKey = null }
        }
    }
}
