package com.nuvio.app.features.discord

/**
 * Whether Discord can actually fetch this artwork.
 *
 * Rich Presence artwork is not uploaded — Discord is handed a URL and fetches it from its own
 * servers, as does the resizing proxy in front of it. So an address that only resolves on the
 * user's machine or LAN is unusable here even though every image in the app loads from it
 * perfectly. That is the whole difference between a stock poster and a custom poster service: the
 * stock one is a public CDN URL, while a self-hosted PostersPlus lives at something like
 * `http://postersplus:8000` or `http://192.168.1.50:8000`, which the proxy answers with
 * "hostname unresolvable" or "IP address blocked by policy".
 *
 * Rejecting those here lets the caller fall through to the next artwork it has rather than
 * publishing a URL that can only ever resolve to an error, which is what made Rich Presence look
 * broken instead of merely un-postered.
 */
internal fun isExternallyFetchableArtworkUrl(url: String?): Boolean {
    val trimmed = url?.trim().orEmpty()
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return false
    val host = artworkUrlHost(trimmed) ?: return false
    return !host.isPrivateArtworkHost()
}

/** The host of an absolute http(s) URL, lowercased, with any userinfo and port removed. */
private fun artworkUrlHost(url: String): String? {
    val authority = url.substringAfter("://", missingDelimiterValue = "")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('@')
    if (authority.isEmpty()) return null
    val host = if (authority.startsWith("[")) {
        authority.substringAfter('[').substringBefore(']')
    } else {
        authority.substringBefore(':')
    }
    return host.lowercase().takeIf { it.isNotEmpty() }
}

private fun String.isPrivateArtworkHost(): Boolean {
    if (this == "localhost" || endsWith(".localhost")) return true
    // Suffixes that only mean anything inside one network.
    if (endsWith(".local") || endsWith(".internal") || endsWith(".lan") || endsWith(".home")) return true

    if (contains(':')) {
        // IPv6 loopback, unique-local (fc00::/7) and link-local (fe80::/10).
        return this == "::1" || startsWith("fc") || startsWith("fd") || startsWith("fe8") ||
            startsWith("fe9") || startsWith("fea") || startsWith("feb")
    }

    val octets = split('.')
    if (octets.size == 4 && octets.all { (it.toIntOrNull() ?: -1) in 0..255 }) {
        val first = octets[0].toInt()
        val second = octets[1].toInt()
        return first == 0 || first == 10 || first == 127 ||
            (first == 169 && second == 254) ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168)
    }

    // A single-label hostname ("postersplus", "nas") resolves only on the user's own network.
    return !contains('.')
}
