package com.nuvio.app.features.player

import com.nuvio.app.features.addons.httpGetTextWithHeaders
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import nuvio.composeapp.generated.resources.*
import com.nuvio.app.core.i18n.localizedString

internal fun PlayerScreenRuntime.fetchAddonSubtitlesForActiveItem(): Job? {
    val type = activeAddonSubtitleType.takeIf { it.isNotBlank() } ?: return null
    val videoId = activeVideoId?.takeIf { it.isNotBlank() } ?: return null
    return SubtitleRepository.fetchAddonSubtitles(type, videoId)
}

/**
 * Loads [subtitle] into the player, then — when automatic subtitle sync is on — aligns it to the
 * stream's embedded subtitle timing and swaps in the retimed copy. The original plays meanwhile,
 * and stays if no confident match is found. [allowBetterMatch] lets an automatic pick be replaced
 * by a better-fitting same-language addon subtitle; a subtitle the viewer chose is never swapped.
 */
internal fun PlayerScreenRuntime.loadAddonSubtitle(subtitle: AddonSubtitle, allowBetterMatch: Boolean = false) {
    automaticSubtitleSyncJob?.cancel()
    automaticSubtitleSyncJob = null
    playerController?.setSubtitleUri(subtitle.url)
    if (!playerSettingsUiState.automaticSubtitleSyncEnabled) return
    // Live TV: nothing to sync against, and reading the stream costs a provider connection.
    if (isIptvPlayback) return
    // Torrents play from the active engine's local HTTP stream (Nuvio Engine or TorrServer, both
    // range-capable); the magnet in activeSourceUrl isn't fetchable. The URL may not exist yet.
    // Local library files play from a plain path, which both sync paths read from disk.
    val isTorrent = activeTorrentInfoHash != null
    fun currentSyncSource(): String? =
        (if (isTorrent) p2pResolvedSourceUrl else activeSourceUrl)?.takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) ||
                (!isTorrent && isLocalPlaybackPath(it))
        }
    if (!isTorrent && currentSyncSource() == null) return
    val torrentHashAtStart = activeTorrentInfoHash
    val sourceHeaders = if (isTorrent) emptyMap() else activeSourceHeaders
    val alternatives = if (allowBetterMatch) {
        addonSubtitles.filter { it.url != subtitle.url && !playerSettingsUiState.rejectsAddonSubtitle(it) }
    } else {
        emptyList()
    }

    var sourceUrl: String? = currentSyncSource()
    fun isStillSelected(candidate: AddonSubtitle): Boolean =
        activeTorrentInfoHash == torrentHashAtStart &&
            (sourceUrl == null || currentSyncSource() == sourceUrl) && useCustomSubtitles &&
            (selectedAddonSubtitleId == candidate.id || selectedAddonSubtitleId == candidate.url)

    automaticSubtitleSyncJob = scope.launch {
        if (isTorrent && !awaitTorrentBufferForAutomaticSync { isStillSelected(subtitle) }) return@launch
        val syncSource = currentSyncSource() ?: return@launch
        sourceUrl = syncSource
        var result = try {
            runAutomaticSubtitleSync(
                AutomaticSubtitleSyncRequest(
                    sourceUrl = syncSource,
                    sourceHeaders = sourceHeaders,
                    isTorrent = isTorrent,
                    subtitle = subtitle,
                    alternatives = alternatives,
                ),
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            null
        }
        if (!isStillSelected(subtitle)) return@launch
        val player = playerController
        if (result == AutomaticSubtitleSyncResult.NoReference && player != null) {
            result = syncToAudio(syncSource, subtitle, player) { isStillSelected(subtitle) } ?: return@launch
            // Each mapping was applied (and announced) as it was found.
            if (result is AutomaticSubtitleSyncResult.Synced) return@launch
        }
        when (result) {
            is AutomaticSubtitleSyncResult.Synced -> {
                val synced = result.subtitle
                if (synced.url != subtitle.url) {
                    selectedAddonSubtitleId = synced.id.ifBlank { synced.url }
                }
                applySyncedSubtitle(result)
                showAutomaticSyncMessage(if (synced.url != subtitle.url) localizedString(Res.string.subtitle_sync_synced_better_match_found) else localizedString(Res.string.subtitle_sync_synced))
            }
            AutomaticSubtitleSyncResult.NoReference ->
                showAutomaticSyncMessage(localizedString(Res.string.subtitle_sync_no_built_in_subtitles_to_sync))
            AutomaticSubtitleSyncResult.NotConfident ->
                showAutomaticSyncMessage(localizedString(Res.string.subtitle_sync_no_confident_match_kept_original_timing))
            AutomaticSubtitleSyncResult.SubtitleUnavailable, null ->
                showAutomaticSyncMessage("Failed, kept original timing")
        }
    }
}

private val localPathPattern = Regex("""^(?:[A-Za-z]:[\\/]|\\\\|/|file:)""", RegexOption.IGNORE_CASE)

/** A local library file: `C:\…`, `\\server\share\…`, `/…` or a `file:` URL. */
private fun isLocalPlaybackPath(source: String): Boolean = localPathPattern.containsMatchIn(source)

private fun PlayerScreenRuntime.applySyncedSubtitle(synced: AutomaticSubtitleSyncResult.Synced) {
    automaticallySyncedSubtitle = synced.subtitle.url to synced.retimedBody
    subtitleAutoSyncState = SubtitleAutoSyncUiState()
    playerController?.setSubtitleUri(synced.localFilePath)
    // The retimed copy already carries the correction; a leftover manual delay would push it back
    // out of sync.
    if (subtitleDelayMs != 0) setSubtitleDelay(0)
}

/**
 * No embedded subtitles to sync against: sync [subtitle] to the film's audio instead (sampled
 * dialogue spots, speech detection; see `DesktopAudioSubtitleSync`). An early estimate is applied
 * as soon as it repeats, and replaced by the confirmed mapping or withdrawn. Stops quietly when
 * the viewer changes the subtitle or sets a delay by hand (null: nothing more to say).
 */
private suspend fun PlayerScreenRuntime.syncToAudio(
    sourceUrl: String,
    subtitle: AddonSubtitle,
    player: PlayerEngineController,
    stillSelected: () -> Boolean,
): AutomaticSubtitleSyncResult? {
    showAutomaticSyncMessage(localizedString(Res.string.subtitle_sync_no_built_in_subtitles_syncing_to))
    // Applying a mapping resets the delay to 0; any other change is the viewer's own.
    var expectedDelayMs = subtitleDelayMs
    fun stillWanted() = stillSelected() && subtitleDelayMs == expectedDelayMs
    return try {
        runAudioReferencedSubtitleSync(
            sourceUrl = sourceUrl,
            subtitle = subtitle,
            player = player,
            durationMs = playbackSnapshot.durationMs,
            positionMs = { playbackSnapshot.positionMs },
        ) { update ->
            if (!stillWanted()) throw CancellationException("subtitle or delay changed")
            when (update) {
                is AudioSubtitleSyncUpdate.Applied -> {
                    applySyncedSubtitle(update.synced)
                    expectedDelayMs = subtitleDelayMs
                    val offset = formatSyncOffset(update.offsetMs)
                    showAutomaticSyncMessage(
                        if (update.confirmed) {
                            localizedString(Res.string.subtitle_sync_synced_to_audio, offset)
                        } else {
                            localizedString(Res.string.subtitle_sync_estimated_from_audio, offset)
                        },
                    )
                }
                AudioSubtitleSyncUpdate.Withdrawn -> {
                    automaticallySyncedSubtitle = null
                    subtitleAutoSyncState = SubtitleAutoSyncUiState()
                    playerController?.setSubtitleUri(subtitle.url)
                    showAutomaticSyncMessage(localizedString(Res.string.subtitle_sync_audio_estimate_withdrawn_original_timing))
                }
            }
        }
    } catch (cancel: CancellationException) {
        if (!stillWanted()) null else throw cancel
    } catch (error: Throwable) {
        null
    }?.takeIf { stillWanted() }
}

private fun formatSyncOffset(offsetMs: Long): String {
    val tenths = (abs(offsetMs) + 50) / 100
    return "${if (offsetMs < 0) "-" else "+"}${tenths / 10}.${tenths % 10}s"
}

/**
 * Waits until a torrent has a healthy buffer ahead of playback. The index read pulls pieces from the
 * end of the file, and Nuvio Engine gives its read-ahead window to the newest request — reading
 * early would take bandwidth from the pieces mpv needs to start or keep playing.
 *
 * "Healthy" is [TORRENT_SYNC_TARGET_BUFFER_MS] ahead, or — for buffer presets whose cache can't
 * hold that much (Metered keeps 10 s) — a buffer of at least [TORRENT_SYNC_MIN_BUFFER_MS] that has
 * stopped growing because the cache is full, or a buffer that already reaches the end of the file.
 * False when the subtitle/stream changed or the swarm never got that far ahead.
 */
private suspend fun PlayerScreenRuntime.awaitTorrentBufferForAutomaticSync(stillRelevant: () -> Boolean): Boolean {
    var waitedMs = 0L
    var plateauAheadMs = -1L
    var plateauSinceMs = 0L
    while (waitedMs < TORRENT_SYNC_MAX_WAIT_MS) {
        if (!stillRelevant()) return false
        val snapshot = playbackSnapshot
        if (p2pResolvedSourceUrl != null && !snapshot.isLoading) {
            val aheadMs = snapshot.bufferedPositionMs - snapshot.positionMs
            val reachesEnd = snapshot.durationMs > 0 && snapshot.bufferedPositionMs >= snapshot.durationMs - 1_000
            if (aheadMs >= TORRENT_SYNC_TARGET_BUFFER_MS || reachesEnd) return true
            if (aheadMs > plateauAheadMs + 1_000) {
                plateauAheadMs = aheadMs
                plateauSinceMs = waitedMs
            } else if (aheadMs >= TORRENT_SYNC_MIN_BUFFER_MS &&
                waitedMs - plateauSinceMs >= TORRENT_SYNC_PLATEAU_MS
            ) {
                return true
            }
        }
        delay(TORRENT_SYNC_POLL_MS)
        waitedMs += TORRENT_SYNC_POLL_MS
    }
    if (stillRelevant()) showAutomaticSyncMessage("Skipped: torrent didn't buffer far enough ahead")
    return false
}

/**
 * Shows the sync outcome in the player's own HUD pill. The Compose gesture pill sits under the
 * native mpv/HTML surface during playback, so a plain gesture message is never seen there.
 */
private fun PlayerScreenRuntime.showAutomaticSyncMessage(value: String) {
    val controller = playerController
    if (controller != null) {
        controller.showTransientMessage(localizedString(Res.string.subtitle_sync_subtitle_sync), value)
    } else {
        showGestureMessage(localizedString(Res.string.subtitle_sync_gesture_message, value))
    }
}

private const val TORRENT_SYNC_TARGET_BUFFER_MS = 30_000L
private const val TORRENT_SYNC_MIN_BUFFER_MS = 8_000L
private const val TORRENT_SYNC_PLATEAU_MS = 10_000L
private const val TORRENT_SYNC_MAX_WAIT_MS = 5 * 60_000L
private const val TORRENT_SYNC_POLL_MS = 1_000L

internal fun PlayerScreenRuntime.setSubtitleDelay(delayMs: Int) {
    val clamped = delayMs.coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
    subtitleDelayMs = clamped
    PlayerTrackPreferenceStorage.saveSubtitleDelayMs(playbackSession.videoId, clamped)
    playerController?.setSubtitleDelayMs(clamped)
}

internal fun PlayerScreenRuntime.loadSubtitleAutoSyncCues(force: Boolean = false) {
    val subtitle = selectedAddonSubtitle ?: return
    if (!force && subtitleAutoSyncState.cues.isNotEmpty()) return
    subtitleAutoSyncState = subtitleAutoSyncState.copy(isLoading = true, errorMessage = null)
    scope.launch {
        val result = runCatching {
            // After an automatic sync the player shows the retimed copy, so list its timing.
            val body = automaticallySyncedSubtitle?.takeIf { it.first == subtitle.url }?.second ?: httpGetTextWithHeaders(
                url = subtitle.url,
                headers = sanitizePlaybackHeaders(activeSourceHeaders),
            )
            PlayerSubtitleCueParser.parse(body, subtitle.url)
        }
        result.fold(
            onSuccess = { cues ->
                subtitleAutoSyncState = subtitleAutoSyncState.copy(
                    cues = cues,
                    isLoading = false,
                    errorMessage = if (cues.isEmpty()) localizedString(Res.string.subtitle_sync_no_subtitle_lines_found) else null,
                )
            },
            onFailure = { error ->
                subtitleAutoSyncState = subtitleAutoSyncState.copy(
                    isLoading = false,
                    errorMessage = error.message ?: localizedString(Res.string.subtitle_sync_unable_to_load_subtitle_lines),
                )
            },
        )
    }
}

internal fun PlayerScreenRuntime.captureSubtitleAutoSyncTime() {
    subtitleAutoSyncState = subtitleAutoSyncState.copy(
        capturedPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L),
        errorMessage = null,
    )
    loadSubtitleAutoSyncCues()
}

internal fun PlayerScreenRuntime.applySubtitleAutoSyncCue(cue: SubtitleSyncCue) {
    val capturedPositionMs = subtitleAutoSyncState.capturedPositionMs ?: return
    val newDelayMs = (capturedPositionMs - cue.startTimeMs - SUBTITLE_AUTO_SYNC_REACTION_COMPENSATION_MS)
        .toInt()
        .coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
    setSubtitleDelay(newDelayMs)
}
