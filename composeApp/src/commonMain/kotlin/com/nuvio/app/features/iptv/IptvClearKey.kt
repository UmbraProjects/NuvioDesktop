package com.nuvio.app.features.iptv

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One ClearKey entry: 32-char lowercase hex [keyHex] and optional 32-char lowercase hex [kidHex]. */
data class IptvClearKey(
    val kidHex: String?,
    val keyHex: String,
)

/**
 * Pure ClearKey parsing and formatting helpers for M3U `#KODIPROP` DASH streams.
 *
 * Supports:
 * - Hex `kid:key` pairs (with or without UUID hyphens, single or comma/semicolon-separated)
 * - Bare 32-hex decryption keys
 * - W3C ClearKey JWK JSON (`{"keys":[{"kty":"oct","k":"...","kid":"..."}]}`)
 * - Flat JSON maps (`{"<kidHex>":"<keyHex>"}`)
 * - Extracting `cenc:default_KID` UUIDs from an MPD manifest for W3C ClearKey license POSTs
 */
object IptvClearKeyParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val HEX_32 = Regex("^[0-9a-f]{32}$")
    private val DEFAULT_KID_REGEX = Regex("""(?i)default_KID\s*=\s*["']([0-9a-fA-F-]{32,36})["']""")

    private const val CLEARKEY_SYSTEM_UUID = "e2719d58a985b3c9781ab030af78d30e"

    fun isClearKeyScheme(scheme: String?): Boolean {
        val norm = scheme?.trim()?.lowercase()?.ifEmpty { return false } ?: return false
        return norm == "clearkey" ||
            norm == "org.w3.clearkey" ||
            norm.replace("-", "") == CLEARKEY_SYSTEM_UUID
    }

    fun isLicenseUrl(licenseKey: String?): Boolean {
        val trimmed = licenseKey?.trim().orEmpty()
        return trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
    }

    /** Parses an inline `license_key` value or HTTP license response body into ClearKey entries. */
    fun parsePayload(raw: String?): List<IptvClearKey> {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        if (trimmed.startsWith("{")) {
            return parseJson(trimmed)
        }
        val out = ArrayList<IptvClearKey>()
        for (segment in trimmed.split(',', ';')) {
            val part = segment.trim()
            if (part.isEmpty()) continue
            val colon = part.indexOf(':')
            if (colon > 0) {
                val kid = normalizeHex32(part.substring(0, colon))
                val key = normalizeHex32(part.substring(colon + 1))
                if (kid != null && key != null) {
                    out += IptvClearKey(kidHex = kid, keyHex = key)
                }
            } else {
                val key = normalizeHex32(part)
                if (key != null) {
                    out += IptvClearKey(kidHex = null, keyHex = key)
                }
            }
        }
        return out
    }

    private fun parseJson(payload: String): List<IptvClearKey> {
        val obj = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return emptyList()
        val keysArray = obj["keys"] as? JsonArray
        if (keysArray != null) {
            val out = ArrayList<IptvClearKey>()
            for (elem in keysArray) {
                val entry = elem as? JsonObject ?: continue
                val kRaw = (entry["k"] as? JsonPrimitive)?.contentOrNull ?: continue
                val keyHex = decodeBase64UrlToHex32(kRaw) ?: normalizeHex32(kRaw) ?: continue
                val kidRaw = (entry["kid"] as? JsonPrimitive)?.contentOrNull
                val kidHex = kidRaw?.let { decodeBase64UrlToHex32(it) ?: normalizeHex32(it) }
                out += IptvClearKey(kidHex = kidHex, keyHex = keyHex)
            }
            return out
        }
        // Flat map: {"<kidHex>": "<keyHex>"}
        val out = ArrayList<IptvClearKey>()
        for ((k, v) in obj) {
            val kid = normalizeHex32(k) ?: continue
            val valStr = (v as? JsonPrimitive)?.contentOrNull ?: continue
            val key = normalizeHex32(valStr) ?: decodeBase64UrlToHex32(valStr) ?: continue
            out += IptvClearKey(kidHex = kid, keyHex = key)
        }
        return out
    }

    /** Extracts unique `default_KID` values from an MPD XML as 32-char lowercase hex strings. */
    fun extractMpdKidsHex(mpdXml: String): List<String> =
        DEFAULT_KID_REGEX.findAll(mpdXml)
            .mapNotNull { normalizeHex32(it.groupValues[1]) }
            .distinct()
            .toList()

    /** Builds the W3C ClearKey EME JSON request body for [kidsHex]. */
    fun buildLicenseRequestJson(kidsHex: List<String>): String {
        val encoded = kidsHex.mapNotNull(::hex32ToBase64Url)
        val items = encoded.joinToString(",") { "\"$it\"" }
        return """{"kids":[$items],"type":"temporary"}"""
    }

    /**
     * Builds the mpv `demuxer-lavf-o` option for FFmpeg's DASH demuxer (`cenc_decryption_key` /
     * `cenc_decryption_keys`). Returns null when [keys] is empty.
     */
    fun mpvDemuxerOption(keys: List<IptvClearKey>): String? {
        if (keys.isEmpty()) return null
        val keyed = keys.filter { it.kidHex != null }.distinctBy { it.kidHex }
        val defaultKey = keys.first().keyHex
        if (keyed.size <= 1) {
            return "demuxer-lavf-o=cenc_decryption_key=$defaultKey"
        }
        val dict = keyed.joinToString(":") { "${it.kidHex}=${it.keyHex}" }
        return "demuxer-lavf-o=cenc_decryption_keys=[$dict],cenc_decryption_key=$defaultKey"
    }

    fun normalizeHex32(raw: String): String? {
        val clean = raw.trim().replace("-", "").lowercase()
        return clean.takeIf { HEX_32.matches(it) }
    }

    fun decodeBase64UrlToHex32(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val standard = trimmed.replace('-', '+').replace('_', '/')
        val padded = when (standard.length % 4) {
            2 -> "$standard=="
            3 -> "$standard="
            else -> standard
        }
        val bytes = runCatching { Base64.getDecoder().decode(padded) }.getOrNull() ?: return null
        if (bytes.size != 16) return null
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun hex32ToBase64Url(hex: String): String? {
        val clean = normalizeHex32(hex) ?: return null
        val bytes = ByteArray(16) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
