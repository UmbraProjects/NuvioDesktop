package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.autosync.AutoSyncAnalysisOutcome
import com.nuvio.app.features.player.autosync.AutoSyncSubtitleCandidate
import com.nuvio.app.features.player.autosync.AutoSyncSubtitleRewriter
import com.nuvio.app.features.player.autosync.AutomaticSubtitleSync
import com.nuvio.app.features.player.autosync.EmbeddedSubtitleTimelineLoader
import com.nuvio.app.features.player.autosync.maxAlignmentShiftMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

private val log = Logger.withTag("AutoSync")

/** Index prefetches outlive the player screen's composition on purpose: the loader caches them. */
private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private val syncDirectory: File by lazy {
    File(System.getProperty("java.io.tmpdir"), "nuvio-autosync").apply {
        mkdirs()
        // Retimed copies are only needed while their stream plays; drop anything from past sessions.
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }
}

private val resultCacheLock = Any()
private val resultCache = object : LinkedHashMap<String, AutomaticSubtitleSyncResult.Synced>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AutomaticSubtitleSyncResult.Synced>?) =
        size > 16
}

internal actual fun prefetchAutomaticSubtitleSyncIndex(sourceUrl: String, sourceHeaders: Map<String, String>) {
    EmbeddedSubtitleTimelineLoader.prefetch(prefetchScope, sourceUrl, sourceHeaders)
}

internal actual suspend fun runAutomaticSubtitleSync(
    request: AutomaticSubtitleSyncRequest,
): AutomaticSubtitleSyncResult {
    val cacheKey = "${request.sourceUrl}\u0000${request.subtitle.url}\u0000${request.alternatives.size}"
    synchronized(resultCacheLock) { resultCache[cacheKey] }
        ?.takeIf { File(it.localFilePath).isFile }
        ?.let { return it }

    val startedAt = System.nanoTime()
    var outcome: AutoSyncAnalysisOutcome? = null
    val candidates = (request.alternatives + request.subtitle).distinctBy { it.url }
    val resolved = AutomaticSubtitleSync.findTimelineRetime(
        sourceKey = request.sourceUrl,
        sourceHeaders = request.sourceHeaders,
        sourcePace = if (request.isTorrent) {
            EmbeddedSubtitleTimelineLoader.Pace.TORRENT
        } else {
            EmbeddedSubtitleTimelineLoader.Pace.NETWORK
        },
        selectedSubtitleUrl = request.subtitle.url,
        selectedSubtitleHeaders = emptyMap(),
        preferredLanguage = request.subtitle.language,
        alternativeSubtitles = if (request.alternatives.isEmpty()) {
            emptyList()
        } else {
            candidates.map { subtitle ->
                AutoSyncSubtitleCandidate(
                    url = subtitle.url,
                    language = subtitle.language,
                    name = subtitle.addonName ?: subtitle.display,
                )
            }
        },
        onAnalysisOutcome = { outcome = it },
    )
    val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
    if (resolved == null || !resolved.timeline.confident) {
        log.i { "no sync after ${elapsedMs}ms outcome=$outcome reject=${resolved?.timeline?.rejectReason}" }
        return when (outcome) {
            AutoSyncAnalysisOutcome.NO_SUBTITLE_TRACKS,
            AutoSyncAnalysisOutcome.NO_USABLE_REFERENCE,
            -> AutomaticSubtitleSyncResult.NoReference
            AutoSyncAnalysisOutcome.SUBTITLE_UNAVAILABLE -> AutomaticSubtitleSyncResult.SubtitleUnavailable
            null -> AutomaticSubtitleSyncResult.NotConfident
        }
    }

    val chosen = candidates.firstOrNull { it.url == resolved.subtitleUrl }
        ?: return AutomaticSubtitleSyncResult.NotConfident
    val synced = withContext(Dispatchers.IO) {
        val body = resolved.subtitleBody
            ?: AutomaticSubtitleSync.downloadSubtitleBody(chosen.url, resolved.subtitleHeaders, chosen.language)
        val rewritten = AutoSyncSubtitleRewriter.rewrite(body, chosen.url, resolved.timeline)
        val name = sha1("${request.sourceUrl}\u0000${chosen.url}").take(16)
        val file = File(syncDirectory, "$name.${rewritten.extension}")
        file.writeText(rewritten.text, Charsets.UTF_8)
        AutomaticSubtitleSyncResult.Synced(
            subtitle = chosen,
            localFilePath = file.absolutePath,
            retimedBody = rewritten.text,
        )
    }
    log.i {
        "synced after ${elapsedMs}ms swapped=${chosen.url != request.subtitle.url} " +
            "source=${resolved.timeline.alignmentSource} scale=${resolved.timeline.alignmentScale} " +
            "interceptMs=${resolved.timeline.alignmentInterceptMs.toLong()} " +
            "maxShiftMs=${resolved.timeline.maxAlignmentShiftMs().toLong()} " +
            "coverage=${"%.2f".format(resolved.timeline.targetCoverage)}"
    }
    synchronized(resultCacheLock) { resultCache[cacheKey] = synced }
    return synced
}

private fun sha1(value: String): String =
    MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
