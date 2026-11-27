package com.nuvio.app.features.iptv

import java.net.URLDecoder
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Streaming M3U/M3U8 parser: reads line by line, so playlists of tens of MB are fine. Keeps live
 * channels only; movies and series (see [isVod]) are skipped.
 *
 * Ported from GoatTV's `M3uParser`.
 */
object M3uParser {

    class Result(val categories: List<IptvCategory>, val channels: List<IptvChannel>, val epgUrl: String?)

    private val ATTR = Regex("""([A-Za-z0-9_-]+)\s*=\s*"([^"]*)"""")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(lines: Sequence<String>): Result {
        val channels = ArrayList<IptvChannel>()
        val groups = LinkedHashSet<String>()
        val usedKeys = HashSet<String>()
        var epgUrl: String? = null

        var pending: Extinf? = null
        var group: String? = null
        var userAgent: String? = null
        var referrer: String? = null
        var extraHeaders: LinkedHashMap<String, String>? = null
        var drmScheme: String? = null
        var drmLicenseKey: String? = null

        fun addHeader(rawName: String, rawValue: String) {
            val name = rawName.trim()
            val value = rawValue.trim()
            if (name.isEmpty() || value.isEmpty()) return
            when (name.lowercase()) {
                "user-agent" -> userAgent = value
                "referer", "referrer" -> referrer = value
                else -> {
                    val canonical = when (name.lowercase()) {
                        "cookie" -> "Cookie"
                        "origin" -> "Origin"
                        else -> name
                    }
                    val map = extraHeaders ?: LinkedHashMap<String, String>().also { extraHeaders = it }
                    map[canonical] = value
                }
            }
        }

        fun resetPendingProps() {
            group = null
            userAgent = null
            referrer = null
            extraHeaders = null
            drmScheme = null
            drmLicenseKey = null
        }

        for (raw in lines) {
            val line = raw.trim().removePrefix("\uFEFF")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U") -> {
                    val attrs = attributes(line)
                    epgUrl = (attrs["x-tvg-url"] ?: attrs["url-tvg"])
                        ?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                }
                line.startsWith("#EXTINF") -> {
                    // Only reset props if a previous #EXTINF had no URL line; otherwise keep
                    // #KODIPROP / #EXTHTTP / #EXTVLCOPT lines that appeared right before #EXTINF.
                    if (pending != null) resetPendingProps()
                    pending = parseExtinf(line)
                }
                line.startsWith("#EXTGRP:") -> group = line.substringAfter(':').trim()
                line.startsWith("#EXTVLCOPT:") -> {
                    val opt = line.substringAfter(':')
                    when {
                        opt.startsWith("http-user-agent=", ignoreCase = true) ->
                            userAgent = opt.substringAfter('=').trim()
                        opt.startsWith("http-referrer=", ignoreCase = true) ->
                            referrer = opt.substringAfter('=').trim()
                        opt.startsWith("http-origin=", ignoreCase = true) ->
                            addHeader("Origin", opt.substringAfter('='))
                    }
                }
                line.startsWith("#EXTHTTP:") -> {
                    val payload = line.substringAfter(':').trim()
                    runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull()?.forEach { (k, v) ->
                        (v as? JsonPrimitive)?.contentOrNull?.let { addHeader(k, it) }
                    }
                }
                line.startsWith("#KODIPROP:") -> {
                    val prop = line.substringAfter(':').trim()
                    val eq = prop.indexOf('=')
                    if (eq > 0) {
                        val key = prop.substring(0, eq).trim().lowercase()
                        val value = prop.substring(eq + 1).trim()
                        when (key) {
                            "inputstream.adaptive.license_type" ->
                                if (value.isNotEmpty()) drmScheme = value
                            "inputstream.adaptive.license_key" ->
                                if (value.isNotEmpty()) drmLicenseKey = value
                            "inputstream.adaptive.stream_headers" ->
                                parsePipeHeaders(value, ::addHeader)
                        }
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val info = pending
                    pending = null
                    if (info == null) {
                        resetPendingProps()
                        continue
                    }
                    val pipe = line.indexOf('|')
                    val streamUrl = if (pipe > 0) line.substring(0, pipe).trim() else line
                    if (pipe > 0) {
                        parsePipeHeaders(line.substring(pipe + 1), ::addHeader)
                    }
                    if (isVod(streamUrl)) {
                        resetPendingProps()
                        continue
                    }

                    val category = (info.attrs["group-title"] ?: group)?.trim()
                        ?.takeIf { it.isNotEmpty() } ?: IptvPlaylistContent.OTHER_CATEGORY
                    groups += category

                    // Key: tvg-id; when it is missing (or repeats), a short hash of the URL.
                    val tvgId = info.attrs["tvg-id"]?.trim().orEmpty()
                    val key = if (tvgId.isNotEmpty() && usedKeys.add(tvgId)) tvgId else urlKey(streamUrl)
                    usedKeys += key

                    val index = channels.size
                    channels += IptvChannel(
                        uid = index,
                        key = key,
                        num = index + 1,
                        name = info.name.ifEmpty { info.attrs["tvg-name"].orEmpty() },
                        logo = info.attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                        categoryId = category,
                        url = streamUrl,
                        userAgent = userAgent,
                        referrer = referrer,
                        headers = extraHeaders?.takeIf { it.isNotEmpty() }?.toMap(),
                        drmScheme = drmScheme,
                        drmLicenseKey = drmLicenseKey,
                        epgId = tvgId.ifEmpty { null },
                    )
                    resetPendingProps()
                }
            }
        }
        val categories = groups.filter { it != IptvPlaylistContent.OTHER_CATEGORY }.map { IptvCategory(it, it) }
        return Result(categories, channels, epgUrl)
    }

    private fun parsePipeHeaders(encoded: String, onHeader: (String, String) -> Unit) {
        for (part in encoded.split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val name = decodeQueryComponent(part.substring(0, eq))
            val value = decodeQueryComponent(part.substring(eq + 1))
            onHeader(name, value)
        }
    }

    private fun decodeQueryComponent(raw: String): String =
        runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)

    private class Extinf(val attrs: Map<String, String>, val name: String)

    private fun parseExtinf(line: String): Extinf {
        // The name follows the first comma outside quotes (attribute values can contain commas).
        var inQuotes = false
        var comma = -1
        for (i in line.indices) {
            when (line[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) { comma = i; break }
            }
        }
        val head = if (comma >= 0) line.substring(0, comma) else line
        val name = if (comma >= 0) line.substring(comma + 1).trim() else ""
        return Extinf(attributes(head), name)
    }

    private fun attributes(s: String): Map<String, String> =
        ATTR.findAll(s).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    /** Movies and series: `/movie/`, `/series/`, `.mp4` or `.mkv`. */
    fun isVod(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return "/movie/" in path || "/series/" in path || path.endsWith(".mp4") || path.endsWith(".mkv")
    }

    fun urlKey(url: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        return "u" + digest.take(6).joinToString("") { "%02x".format(it) }
    }
}
