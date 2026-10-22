package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.audiosync.DesktopAudioSubtitleSync
import com.nuvio.app.features.player.audiosync.SubtitleSyncModel
import com.nuvio.app.features.player.autosync.AutoSyncAnalysisOutcome
import com.nuvio.app.features.player.autosync.AutoSyncSubtitleCandidate
import com.nuvio.app.features.player.autosync.AutoSyncSubtitleCueParser
import com.nuvio.app.features.player.autosync.AutoSyncSubtitleRewriter
import com.nuvio.app.features.player.autosync.AutoSyncCue
import com.nuvio.app.features.player.autosync.AutoSyncRetimedCue
import com.nuvio.app.features.player.autosync.AutoSyncTimelineRetimeResult
import com.nuvio.app.features.player.autosync.AutoSyncTimelineRetimer
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
        writeRetimedSubtitle(request.sourceUrl, chosen, body, resolved.timeline)
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

private fun writeRetimedSubtitle(
    sourceUrl: String,
    subtitle: AddonSubtitle,
    body: String,
    timeline: AutoSyncTimelineRetimeResult,
    nameSuffix: String = "",
): AutomaticSubtitleSyncResult.Synced {
    val rewritten = AutoSyncSubtitleRewriter.rewrite(body, subtitle.url, timeline)
    // A refined audio sync gets its own name: mpv would treat a reload of the same path as a no-op.
    val name = sha1("$sourceUrl\u0000${subtitle.url}").take(16) + nameSuffix
    val file = File(syncDirectory, "$name.${rewritten.extension}")
    file.writeText(rewritten.text, Charsets.UTF_8)
    return AutomaticSubtitleSyncResult.Synced(
        subtitle = subtitle,
        localFilePath = file.absolutePath,
        retimedBody = rewritten.text,
    )
}

internal actual suspend fun runAudioReferencedSubtitleSync(
    sourceUrl: String,
    subtitle: AddonSubtitle,
    player: PlayerEngineController,
    durationMs: Long,
    positionMs: () -> Long,
    onUpdate: suspend (AudioSubtitleSyncUpdate) -> Unit,
): AutomaticSubtitleSyncResult {
    // Engines that can't sample audio keep the old answer.
    if (!player.supportsSpeechSpots) return AutomaticSubtitleSyncResult.NoReference
    val body = runCatching {
        withContext(Dispatchers.IO) {
            AutomaticSubtitleSync.downloadSubtitleBody(subtitle.url, emptyMap(), subtitle.language)
        }
    }.getOrNull() ?: return AutomaticSubtitleSyncResult.SubtitleUnavailable
    val cues = AutoSyncTimelineRetimer.normalizeExternalTimeline(AutoSyncSubtitleCueParser.parse(body, subtitle.url))
    if (cues.isEmpty()) return AutomaticSubtitleSyncResult.SubtitleUnavailable

    suspend fun write(model: SubtitleSyncModel, suffix: String) = withContext(Dispatchers.IO) {
        writeRetimedSubtitle(sourceUrl, subtitle, body, timelineFor(cues, model), nameSuffix = suffix)
    }

    var writes = 0
    val outcome = withContext(Dispatchers.Default) {
        DesktopAudioSubtitleSync.run(
            sourceKey = sourceUrl,
            dialogue = cues.map { Triple(it.startTimeMs, it.endTimeMs, it.text) },
            durationMs = durationMs,
            bytesPerSecond = withContext(Dispatchers.IO) { player.sourceBytesPerSecond() },
            positionMs = positionMs,
            decode = { startMs, lengthMs ->
                withContext(Dispatchers.IO) { player.decodeSpeechSpot(startMs / 1_000.0, lengthMs / 1_000.0) }
            },
        ) { event ->
            when (event) {
                is DesktopAudioSubtitleSync.Event.Estimated -> onUpdate(
                    AudioSubtitleSyncUpdate.Applied(
                        synced = write(event.model, "-a${writes++}"),
                        confirmed = false,
                        offsetMs = event.model.delayUsAt(positionMs() * 1_000L) / 1_000L,
                    ),
                )
                is DesktopAudioSubtitleSync.Event.Locked -> onUpdate(
                    AudioSubtitleSyncUpdate.Applied(
                        synced = write(event.model, "-a${writes++}"),
                        confirmed = true,
                        offsetMs = event.model.delayUsAt(positionMs() * 1_000L) / 1_000L,
                    ),
                )
                DesktopAudioSubtitleSync.Event.Withdrawn -> onUpdate(AudioSubtitleSyncUpdate.Withdrawn)
            }
        }
    }
    return when (outcome) {
        is DesktopAudioSubtitleSync.Outcome.Done -> write(outcome.model, "-a${writes - 1}")
        is DesktopAudioSubtitleSync.Outcome.NoMatch -> AutomaticSubtitleSyncResult.NotConfident
    }
}

/**
 * The rewriter's view of an audio mapping: every cue placed by [model] (subtitle time to media
 * time, piecewise for releases with a different cut), its first piece as the fallback transform.
 */
private fun timelineFor(cues: List<AutoSyncCue>, model: SubtitleSyncModel): AutoSyncTimelineRetimeResult {
    val first = model.segments.first()
    val retimed = cues.map { cue ->
        val start = model.mediaTimeUs(cue.startTimeMs * 1_000L)?.div(1_000L)
            ?: (cue.startTimeMs * first.scale + first.shiftMs).toLong()
        val scale = model.segmentAt(start).scale
        val end = start + ((cue.endTimeMs - cue.startTimeMs) * scale).toLong()
        AutoSyncRetimedCue(
            originalStartTimeMs = cue.startTimeMs,
            originalEndTimeMs = cue.endTimeMs,
            startTimeMs = start.coerceAtLeast(0L),
            endTimeMs = end.coerceAtLeast(start + 1L),
        )
    }
    return AutoSyncTimelineRetimeResult(
        cues = retimed,
        groups = emptyList(),
        targetCoverage = 1.0,
        referenceCoverage = 0.0,
        skippedTargetCues = 0,
        skippedReferenceCues = 0,
        longestTargetSkipRun = 0,
        averageGroupCost = 0.0,
        oneToOneGroups = 0,
        oneToTwoGroups = 0,
        twoToOneGroups = 0,
        oneToThreeGroups = 0,
        threeToOneGroups = 0,
        twoToTwoGroups = 0,
        confident = true,
        alignmentSource = "audio-vad",
        alignmentScale = first.scale,
        alignmentInterceptMs = first.shiftMs,
    )
}

private fun sha1(value: String): String =
    MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
