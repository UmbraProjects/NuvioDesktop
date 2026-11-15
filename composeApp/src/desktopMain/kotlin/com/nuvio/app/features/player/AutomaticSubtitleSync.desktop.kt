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

internal actual suspend fun runLiveSubtitleSync(
    sourceKey: String,
    subtitle: AddonSubtitle,
    player: PlayerEngineController,
    programmeStartMs: Long,
    positionMs: () -> Long,
    liveEdgeMs: () -> Long,
    onUpdate: suspend (LiveSubtitleSyncUpdate) -> Unit,
) {
    val body = runCatching {
        withContext(Dispatchers.IO) { AutomaticSubtitleSync.downloadSubtitleBody(subtitle.url, emptyMap(), subtitle.language) }
    }.getOrNull()
    val cues = body?.let { AutoSyncTimelineRetimer.normalizeExternalTimeline(AutoSyncSubtitleCueParser.parse(it, subtitle.url)) }.orEmpty()
    if (body == null || cues.isEmpty()) {
        onUpdate(LiveSubtitleSyncUpdate.Unavailable)
        return
    }
    // Film time at media time 0: the frame at the live edge went out just now, the programme began at its guide time.
    val guideOffsetMs = System.currentTimeMillis() - liveEdgeMs() - programmeStartMs
    var writes = 0
    suspend fun apply(model: SubtitleSyncModel, stage: LiveSubtitleSyncUpdate.Stage) {
        val synced = withContext(Dispatchers.IO) {
            writeRetimedSubtitle("live:$sourceKey", subtitle, body, timelineFor(cues, model), nameSuffix = "-l${writes++}")
        }
        onUpdate(LiveSubtitleSyncUpdate.Applied(synced, stage, model.delayUsAt(positionMs() * 1_000L) / 1_000L))
    }
    log.i { "live sync: ${cues.size} lines, guide offset ${guideOffsetMs / 1_000}s" }
    apply(com.nuvio.app.features.player.audiosync.SubtitleSyncModel(
        listOf(com.nuvio.app.features.player.audiosync.SubtitleSyncSegment(0L, 1.0, -guideOffsetMs.toDouble())),
    ), LiveSubtitleSyncUpdate.Stage.Guide)
    // Without the native calls (macOS) the guide's placing is all there is.
    if (!player.supportsSpeechSpots) return
    val words = liveWordTranscriber(subtitle)
    val transcribe: (suspend (FloatArray) -> List<com.nuvio.app.features.player.audiosync.LiveWordMatch.Heard>?)? =
        if (words == null) null else { pcm ->
            words.words(pcm)?.flatMap { word ->
                com.nuvio.app.features.player.audiosync.LiveWordMatch.words(word.text).map {
                    com.nuvio.app.features.player.audiosync.LiveWordMatch.Heard((word.startMs + word.endMs) / 2, it)
                }
            }
        }
    try {
    withContext(Dispatchers.Default) {
        com.nuvio.app.features.player.audiosync.LiveAudioSubtitleSync.run(
            dialogue = cues.map { Triple(it.startTimeMs, it.endTimeMs, it.text) },
            guideOffsetMs = guideOffsetMs,
            heard = com.nuvio.app.features.player.audiosync.LiveAudioSubtitleSync.heardFor("$sourceKey@$programmeStartMs", guideOffsetMs),
            positionMs = positionMs,
            decode = { startMs, endMs -> withContext(Dispatchers.IO) { player.decodeCachedAudio(startMs / 1_000.0, endMs / 1_000.0) } },
            transcribe = transcribe,
        ) { model, confirmed, recheck ->
            apply(
                model,
                when {
                    recheck -> LiveSubtitleSyncUpdate.Stage.Rechecked
                    confirmed -> LiveSubtitleSyncUpdate.Stage.Locked
                    else -> LiveSubtitleSyncUpdate.Stage.Estimated
                },
            )
        }
    }
    } finally {
        words?.close()
    }
}

/**
 * fork:live-tv-subtitles - whisper for matching the subtitle by its words, when that setting is on, a speech model is
 * downloaded (Settings > Voice control) and nuvio_whisper.dll loads; null otherwise (speech detection alone then).
 * The models offered are English-only, so a subtitle in another language is matched by speech alone.
 */
private suspend fun liveWordTranscriber(subtitle: AddonSubtitle): com.nuvio.app.features.voice.WhisperTranscriber? {
    if (!com.nuvio.app.features.iptv.IptvRepository.settings.value.liveSubtitleWords) return null
    val language = subtitle.language.trim().lowercase()
    if (language.isNotEmpty() && !language.startsWith("en")) {
        log.i { "live sync: words not matched, the subtitle is '$language' and the speech models are English" }
        return null
    }
    val file = com.nuvio.app.features.voice.VoiceControlRepository.downloadedModelFile() ?: run {
        log.i { "live sync: words not matched, no speech model downloaded (Settings > Voice control)" }
        return null
    }
    return withContext(Dispatchers.IO) { com.nuvio.app.features.voice.WhisperTranscriber.open(file) }
        .onSuccess { log.i { "live sync: matching words with ${file.name}" } }
        .onFailure { log.i { "live sync: words not matched: ${it.message}" } }
        .getOrNull()
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
