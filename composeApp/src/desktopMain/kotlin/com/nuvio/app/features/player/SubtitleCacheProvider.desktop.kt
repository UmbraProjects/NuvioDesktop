package com.nuvio.app.features.player

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.downloads.hasExecutableDownloadExtension
import com.nuvio.app.features.downloads.hasExecutableFileSignature
import com.nuvio.app.features.downloads.isExecutableDownloadContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.zip.GZIPInputStream

private const val MAX_SUBTITLE_BYTES = 4 * 1024 * 1024
private const val MAX_PARALLEL_DOWNLOADS = 6
private val KNOWN_SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "sub", "ttml")

private val subtitleHttpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

internal data class DownloadedSubtitleFile(
    val bytes: ByteArray,
    val extension: String,
)

/**
 * Desktop implementation: downloads each addon subtitle to a local file and returns plain absolute
 * paths.
 *
 * This used to pass the remote HTTP URLs straight through on the assumption that desktop players
 * accept them. In practice that failed in two different ways: MPC-HC and PotPlayer only take a
 * subtitle *path* on the command line, and mpv — which does accept URLs — opens every `--sub-file`
 * sequentially before it renders a frame, so a well-seeded episode delayed playback by minutes.
 * Fetching them here instead is bounded, parallel, and makes every player behave the same.
 */
actual object SubtitleCacheProvider {
    actual suspend fun cacheForExternalPlayer(subtitles: List<SubtitleInput>): List<SubtitleInput>? =
        withContext(Dispatchers.IO) {
            val pending = subtitles.filter { it.url.isRemoteHttpUrl() }
            if (pending.isEmpty()) return@withContext subtitles.ifEmpty { null }

            val directory = prepareCacheDirectory() ?: return@withContext null
            val gate = Semaphore(MAX_PARALLEL_DOWNLOADS)
            val downloaded = coroutineScope {
                pending.mapIndexed { index, subtitle ->
                    async { gate.withPermit { download(directory, index, subtitle) } }
                }.awaitAll()
            }
            downloaded.filterNotNull().ifEmpty { null }
        }

    /**
     * Cleared on every launch: these files are only ever read by the player we are about to start,
     * and leaving them behind would accumulate one directory of stale tracks per episode watched.
     */
    private fun prepareCacheDirectory(): File? = runCatching {
        val directory = DesktopStorage.rootDir.resolve("external-subtitles").toFile()
        directory.mkdirs()
        directory.listFiles()?.forEach { file -> if (file.isFile) file.delete() }
        directory.takeIf { it.isDirectory }
    }.getOrNull()

    private fun download(directory: File, index: Int, subtitle: SubtitleInput): SubtitleInput? =
        runCatching {
            val downloaded = downloadSubtitleFile(subtitle.url) ?: return null

            // The file name is what every player shows in its track menu, so it carries the
            // language and addon label rather than an opaque id.
            val label = listOf(subtitle.lang, subtitle.name)
                .map { it.sanitizedForFileName() }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" - ")
                .ifBlank { "Subtitle" }
            val name = "${(index + 1).toString().padStart(2, '0')} $label.${downloaded.extension}"
            val target = File(directory, name.take(120))
            target.writeBytes(downloaded.bytes)
            // Desktop players take plain absolute paths; a file: URI is not understood here.
            subtitle.copy(url = target.absolutePath)
        }.getOrNull()
}

/** Shared secure fetch used by both the disposable external-player cache and user downloads. */
internal fun downloadSubtitleFile(sourceUrl: String): DownloadedSubtitleFile? {
    if (!sourceUrl.isRemoteHttpUrl() || sourceUrl.hasExecutableDownloadExtension()) return null
    val response = runCatching {
        subtitleHttpClient.send(
            HttpRequest.newBuilder()
                .uri(URI(sourceUrl.trim()))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "text/*,application/x-subrip;q=0.9,*/*;q=0.5")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
    }.getOrNull() ?: return null
    if (response.statusCode() !in 200..299) return null
    if (
        response.headers().firstValue("Content-Type").orElse(null)
            .isExecutableDownloadContentType()
    ) {
        return null
    }
    val body = response.body().decompressIfGzipped()
    if (body.isEmpty() || body.size > MAX_SUBTITLE_BYTES || body.hasExecutableFileSignature()) {
        return null
    }
    return DownloadedSubtitleFile(
        bytes = body,
        extension = body.subtitleExtension(sourceUrl),
    )
}

internal fun String.isRemoteHttpUrl(): Boolean =
    startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

/**
 * Some subtitle hosts serve gzip regardless of `Accept-Encoding`, and `java.net.http` never
 * decompresses for us — saving those bytes verbatim yields a file no player can parse.
 */
internal fun ByteArray.decompressIfGzipped(): ByteArray {
    if (size < 2 || this[0] != 0x1f.toByte() || this[1] != 0x8b.toByte()) return this
    return runCatching {
        GZIPInputStream(ByteArrayInputStream(this)).use { it.readBytes() }
    }.getOrDefault(this)
}

internal fun ByteArray.subtitleExtension(sourceUrl: String): String {
    val fromUrl = sourceUrl.substringBefore('?')
        .substringAfterLast('.', missingDelimiterValue = "")
        .lowercase(Locale.ROOT)
    if (fromUrl in KNOWN_SUBTITLE_EXTENSIONS) return fromUrl
    val head = decodeToString(0, minOf(size, 200)).trimStart('﻿', ' ', '\n', '\r')
    return when {
        head.startsWith("WEBVTT") -> "vtt"
        head.startsWith("[Script Info]", ignoreCase = true) -> "ass"
        else -> "srt"
    }
}

internal fun String.sanitizedForFileName(): String =
    trim()
        .map { character -> if (character.isLetterOrDigit() || character in " -_()[]") character else ' ' }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(48)
