package com.nuvio.app.features.iptv

import java.security.MessageDigest

/**
 * Streaming M3U/M3U8 parser: reads line by line, so playlists of tens of MB are fine. Keeps live
 * channels only; movies and series (see [isVod]) are skipped.
 *
 * Ported from GoatTV's `M3uParser`.
 */
object M3uParser {

    class Result(val categories: List<IptvCategory>, val channels: List<IptvChannel>, val epgUrl: String?)

    private val ATTR = Regex("""([A-Za-z0-9_-]+)\s*=\s*"([^"]*)"""")

    fun parse(lines: Sequence<String>): Result {
        val channels = ArrayList<IptvChannel>()
        val groups = LinkedHashSet<String>()
        val usedKeys = HashSet<String>()
        var epgUrl: String? = null

        var pending: Extinf? = null
        var group: String? = null
        var userAgent: String? = null
        var referrer: String? = null

        for (raw in lines) {
            val line = raw.trim().removePrefix("﻿")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U") -> {
                    val attrs = attributes(line)
                    epgUrl = (attrs["x-tvg-url"] ?: attrs["url-tvg"])
                        ?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                }
                line.startsWith("#EXTINF") -> {
                    pending = parseExtinf(line)
                    group = null
                    userAgent = null
                    referrer = null
                }
                line.startsWith("#EXTGRP:") -> group = line.substringAfter(':').trim()
                line.startsWith("#EXTVLCOPT:") -> {
                    val opt = line.substringAfter(':')
                    when {
                        opt.startsWith("http-user-agent=") -> userAgent = opt.substringAfter('=').trim()
                        opt.startsWith("http-referrer=") -> referrer = opt.substringAfter('=').trim()
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val info = pending ?: continue
                    pending = null
                    if (isVod(line)) continue

                    val category = (info.attrs["group-title"] ?: group)?.trim()
                        ?.takeIf { it.isNotEmpty() } ?: IptvPlaylistContent.OTHER_CATEGORY
                    groups += category

                    // Key: tvg-id; when it is missing (or repeats), a short hash of the URL.
                    val tvgId = info.attrs["tvg-id"]?.trim().orEmpty()
                    val key = if (tvgId.isNotEmpty() && usedKeys.add(tvgId)) tvgId else urlKey(line)
                    usedKeys += key

                    val index = channels.size
                    channels += IptvChannel(
                        uid = index,
                        key = key,
                        num = index + 1,
                        name = info.name.ifEmpty { info.attrs["tvg-name"].orEmpty() },
                        logo = info.attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                        categoryId = category,
                        url = line,
                        userAgent = userAgent,
                        referrer = referrer,
                        epgId = tvgId.ifEmpty { null },
                    )
                }
            }
        }
        val categories = groups.filter { it != IptvPlaylistContent.OTHER_CATEGORY }.map { IptvCategory(it, it) }
        return Result(categories, channels, epgUrl)
    }

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
