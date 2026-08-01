package com.nuvio.app.features.librarypvr

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Normalises whatever the user pasted into a magnet URI.
 *
 * Debrid sites hand share links out in several shapes: a plain magnet, a bare infohash, or a
 * "quick add" URL carrying the magnet in a query parameter that is often base64'd — e.g.
 * `https://torbox.app/quickadd?magnet=bWFnbmV0Oj94dD11cm46YnRpaDoz…`. All of them have to reduce
 * to a magnet before a provider can be asked what's inside.
 */
object ManualLinkParser {

    private val hexHashRegex = Regex("^[0-9a-fA-F]{40}$")
    private val base32HashRegex = Regex("^[A-Za-z2-7]{32}$")
    private val base64AlphabetRegex = Regex("^[A-Za-z0-9+/]+={0,2}$")

    // Checked in order; the first parameter present wins.
    private val sourceParamNames = listOf("magnet", "url", "src", "link", "torrent", "hash", "infohash")

    data class Parsed(
        val magnetUri: String,
        /** The magnet's `dn` (display name) when present — a useful season/pack hint. */
        val displayName: String?,
    )

    fun parse(raw: String): Parsed? {
        val input = raw.trim()
        if (input.isEmpty()) return null
        magnetOrNull(input)?.let { return it.toParsed() }
        if (input.startsWith("http://", ignoreCase = true) || input.startsWith("https://", ignoreCase = true)) {
            return fromWebLink(input)?.toParsed()
        }
        return null
    }

    /**
     * Reduces a single value to a magnet: a magnet passes through, a bare infohash is wrapped, and
     * anything else gets one base64 decode attempt (quick-add links encode the magnet that way).
     */
    private fun magnetOrNull(value: String, allowBase64: Boolean = true): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("magnet:", ignoreCase = true)) return trimmed
        if (hexHashRegex.matches(trimmed)) return "magnet:?xt=urn:btih:${trimmed.lowercase()}"
        if (base32HashRegex.matches(trimmed)) return "magnet:?xt=urn:btih:${trimmed.uppercase()}"
        if (!allowBase64) return null
        return decodeBase64OrNull(trimmed)?.let { magnetOrNull(it, allowBase64 = false) }
    }

    private fun fromWebLink(link: String): String? {
        val afterScheme = link.substringAfter("://", "")
        val query = afterScheme.substringAfter('?', "").substringBefore('#')
        val fragment = afterScheme.substringAfter('#', "")
        val params = parseQuery(query) + parseQuery(fragment)

        sourceParamNames.forEach { name ->
            params[name]?.let { value -> magnetOrNull(value)?.let { return it } }
        }
        // Some sites put the hash in the last path segment instead of a parameter.
        val lastSegment = afterScheme.substringBefore('?').substringBefore('#').substringAfterLast('/')
        return magnetOrNull(lastSegment, allowBase64 = false)
    }

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&')
            .mapNotNull { pair ->
                if (pair.isBlank() || '=' !in pair) return@mapNotNull null
                val name = pair.substringBefore('=').trim().lowercase()
                val value = percentDecode(pair.substringAfter('='))
                if (name.isBlank() || value.isBlank()) null else name to value
            }
            .toMap()

    private fun String.toParsed(): Parsed {
        val displayName = substringAfter("&dn=", "")
            .ifBlank { substringAfter("?dn=", "") }
            .substringBefore('&')
            .takeIf { it.isNotBlank() }
            ?.let(::percentDecode)
        return Parsed(magnetUri = this, displayName = displayName)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeBase64OrNull(value: String): String? {
        // percentDecode turns '+' into a space, and quick-add links may use the URL-safe alphabet.
        val normalized = value.replace(' ', '+').replace('-', '+').replace('_', '/').trimEnd('=')
        if (normalized.length < 12 || !base64AlphabetRegex.matches(normalized)) return null
        val padding = (4 - normalized.length % 4) % 4
        val padded = normalized + "=".repeat(padding)
        return runCatching { Base64.decode(padded).decodeToString() }
            .getOrNull()
            // A decode of non-base64 input yields mojibake; only accept printable ASCII.
            ?.takeIf { decoded -> decoded.isNotBlank() && decoded.all { it.code in 32..126 } }
    }

    private fun percentDecode(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when {
                char == '+' -> {
                    append(' ')
                    index++
                }
                char == '%' && index + 2 < value.length -> {
                    // Consume the whole %XX run so multi-byte UTF-8 sequences decode as one char.
                    val bytes = mutableListOf<Byte>()
                    while (index + 2 < value.length && value[index] == '%') {
                        val byte = value.substring(index + 1, index + 3).toIntOrNull(16) ?: break
                        bytes += byte.toByte()
                        index += 3
                    }
                    if (bytes.isEmpty()) {
                        append(char)
                        index++
                    } else {
                        append(bytes.toByteArray().decodeToString())
                    }
                }
                else -> {
                    append(char)
                    index++
                }
            }
        }
    }
}
