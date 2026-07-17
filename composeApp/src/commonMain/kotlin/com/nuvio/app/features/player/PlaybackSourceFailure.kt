package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamItem
import io.ktor.http.Url

internal enum class PlaybackSourceFailure {
    DebridRateLimited,
}

/**
 * A key identifying which provider a stream is served by, for scoping a rate limit so a 429 on one
 * provider skips only that provider's streams and still tries the others. Precedence matters for
 * aggregator addons (AIOStreams etc.) that proxy several providers behind one URL host:
 *  1. the underlying debrid provider id (from the local cache-check) — the real provider,
 *  2. the client-resolve `service` (direct debrid streams) — also the real provider,
 *  3. only as a last resort, the URL domain.
 * (3) is the weak one: a pre-resolved proxied direct link exposes just the aggregator's host, so
 * its underlying provider is invisible to the client and every such link collapses to the same
 * domain key. There is no client-side signal to split those unless the addon puts the provider in
 * structured metadata — so within one aggregator, provider-accurate scoping only holds for streams
 * that reached (1) or (2).
 */
internal fun StreamItem.rateLimitScopeKey(): String? {
    debridCacheStatus?.providerId?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        ?.let { return "provider:$it" }
    clientResolve?.service?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        ?.let { return "provider:$it" }
    return playbackProviderDomain(playableDirectUrl)?.let { "domain:$it" }
}

/**
 * The provider domain (registrable-ish: last two host labels, lowercased) of a playback URL, used
 * to scope a rate limit. Sources served from the same domain share the throttled provider, so on a
 * 429 failover skips them but still tries a different domain. This is a heuristic — the naive
 * last-two-labels rule mis-groups multi-part public suffixes (e.g. `.co.uk`), but debrid/CDN hosts
 * (torbox.app, real-debrid.com, an AIOStreams proxy host) don't use those, and different subdomains
 * of one provider (store-1.torbox.app / store-2.torbox.app) collapse to the same domain as intended.
 */
internal fun playbackProviderDomain(url: String?): String? {
    val host = url
        ?.let { runCatching { Url(it).host }.getOrNull() }
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val labels = host.split('.').filter { it.isNotBlank() }
    return if (labels.size >= 2) labels.takeLast(2).joinToString(".") else host
}

/** Detects video placeholders returned as successful URLs by stream providers. */
internal fun playbackSourceFailure(sourceUrl: String): PlaybackSourceFailure? {
    val normalized = sourceUrl.trim().lowercase()
    if (
        ("aio.myaio.xyz/" in normalized || "aiostreams" in normalized) &&
        ("title=too+many+requests" in normalized ||
            "debrid+provider+is+rate-limiting" in normalized ||
            "debrid%20provider%20is%20rate-limiting" in normalized)
    ) {
        return PlaybackSourceFailure.DebridRateLimited
    }
    return null
}

/** Detects provider failures reported by mpv/FFmpeg rather than encoded in the source URL. */
internal fun playbackErrorFailure(message: String): PlaybackSourceFailure? {
    val normalized = message.trim().lowercase()
    return if (
        "http error 429" in normalized ||
        "429 too many requests" in normalized
    ) {
        PlaybackSourceFailure.DebridRateLimited
    } else {
        null
    }
}
