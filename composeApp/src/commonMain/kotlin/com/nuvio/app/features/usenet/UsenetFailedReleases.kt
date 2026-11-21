package com.nuvio.app.features.usenet

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

import com.nuvio.app.features.streams.StreamItem

/**
 * Usenet releases that failed to open this run because of the release itself (missing articles,
 * a password, an archive the engine cannot read): failover and autoplay skip them instead of
 * asking the engine again, and the next source is tried at once. A provider or engine failure is
 * not the release's fault and is not recorded. Kept in memory only, for at most [MAX] releases;
 * a release that opens is forgotten again.
 */
internal object UsenetFailedReleases {
    private const val MAX = 200
    private val failed = LinkedHashMap<String, String>()

    fun key(stream: StreamItem): String? {
        val nzb = stream.nzbUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return "$nzb|${stream.fileIdx ?: ""}|${stream.fileMustInclude.orEmpty()}"
    }

    fun record(stream: StreamItem, reason: String) {
        val key = key(stream) ?: return
        synchronized(failed) {
            failed.remove(key)
            failed[key] = reason
            while (failed.size > MAX) failed.remove(failed.keys.first())
        }
    }

    fun forget(stream: StreamItem) {
        val key = key(stream) ?: return
        synchronized(failed) { failed.remove(key) }
    }

    fun hasFailed(stream: StreamItem): Boolean {
        val key = key(stream) ?: return false
        return synchronized(failed) { key in failed }
    }

    internal fun clear() = synchronized(failed) { failed.clear() }
}

/**
 * True when [error] means no Usenet release can open right now (the engine is missing or did not
 * start, or the providers refused): failover then moves past every Usenet row at once instead of
 * waiting on the engine for each of them.
 */
/** The upload is missing or damaged data, which another upload of the same release may not be. */
internal fun usenetIncompleteUpload(error: Throwable): Boolean =
    usenetIncompleteFailure((error as? UsenetPreparationException)?.failure)

/** An engine failure that means the upload lacks articles (or holds broken ones). */
internal fun usenetIncompleteFailure(failure: String?): Boolean =
    failure in setOf("missing-article", "hole-limit", "invalid-article", "blank-content")

/** The engine skipped a compressed 7z over the size limit (Settings > Usenet). */
internal fun usenetCompressedSkip(error: Throwable): Boolean =
    (error as? UsenetPreparationException)?.failure == "archive-compressed"

internal fun usenetUnavailable(error: Throwable): Boolean {
    val preparation = error as? UsenetPreparationException ?: return false
    return preparation.scope != UsenetPreparationException.Scope.SOURCE
}

/**
 * The engine's verdict on a playing session (its diagnostics' "streamFailure"): set once the
 * release turned out unplayable mid-stream, such as "missing-article" or "invalid-article". The
 * player only sees that as the file ending early, so it asks instead. Null while all is well.
 */
internal fun usenetStreamFailure(diagnostics: JsonObject?): String? =
    runCatching { diagnostics?.get("streamFailure")?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf { it.isNotBlank() }

/** A compressed 7z decoding its way to a read: how far it got and the offset it is heading for. */
internal data class UsenetDecodeProgress(val decodedBytes: Long, val targetBytes: Long, val sizeBytes: Long)

/** Null unless a read is waiting for the compressed-7z decoder to reach it. */
internal fun usenetDecodeProgress(diagnostics: JsonObject?): UsenetDecodeProgress? = runCatching {
    if (!usenetCompressed7z(diagnostics)) return@runCatching null
    fun long(name: String) = diagnostics?.get(name)?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
    val target = long("compressed7zWaitingFor")
    val decoded = long("compressed7zDecodedBytes")
    if (target <= decoded) null else UsenetDecodeProgress(decoded, target, long("compressed7zSize"))
}.getOrNull()

/** "830 MB / 3.9 GB": how far the decoder is towards the read it is serving. */
internal fun usenetDecodeProgressLabel(progress: UsenetDecodeProgress): String {
    fun size(bytes: Long): String = if (bytes >= 1_000_000_000L) {
        val tenths = (bytes + 50_000_000L) / 100_000_000L
        "${tenths / 10}.${tenths % 10} GB"
    } else {
        "${(bytes + 500_000L) / 1_000_000L} MB"
    }
    return "${size(progress.decodedBytes)} / ${size(progress.targetBytes)}"
}

/** Whether the session plays a compressed 7z through the engine's decoder. */
internal fun usenetCompressed7z(diagnostics: JsonObject?): Boolean =
    runCatching { diagnostics?.get("compressed7z")?.jsonPrimitive?.booleanOrNull }.getOrNull() == true

/**
 * Splits SABnzbd's "Release.Name{{password}}" convention: the name without the password, and the
 * password (null when the name carries none). The engine is given it when the NZB has none.
 */
internal fun usenetNamePassword(name: String): Pair<String, String?> {
    val match = NAME_PASSWORD.find(name) ?: return name to null
    val password = match.groupValues[1].trim().takeIf { it.isNotEmpty() && it.length <= 1024 }
    return name.removeRange(match.range).trim() to password
}

private val NAME_PASSWORD = Regex("""\{\{(.+?)\}\}""")
