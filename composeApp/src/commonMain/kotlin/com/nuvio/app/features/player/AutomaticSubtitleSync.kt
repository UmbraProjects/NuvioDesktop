package com.nuvio.app.features.player

/**
 * Automatic subtitle sync (ported from NuvioTV / Nuvio Reshaped AutoSync V2).
 *
 * Aligns an addon subtitle against the timing of the subtitle tracks embedded in the stream,
 * read with HTTP range requests from the Matroska Cues index or the MP4 sample tables, and hands back a retimed local copy
 * to load in place of the original. Only confident matches come back as [Synced].
 */
internal data class AutomaticSubtitleSyncRequest(
    val sourceUrl: String,
    val sourceHeaders: Map<String, String>,
    /** The source is a local torrent stream (Nuvio Engine or TorrServer): reads may wait on peers. */
    val isTorrent: Boolean = false,
    val subtitle: AddonSubtitle,
    /** Same-language addon subtitles AutoSync may swap in when they fit better; empty = keep [subtitle]. */
    val alternatives: List<AddonSubtitle>,
)

internal sealed interface AutomaticSubtitleSyncResult {
    data class Synced(
        /** The subtitle that was synced: the requested one, or a better-fitting alternative. */
        val subtitle: AddonSubtitle,
        val localFilePath: String,
        /** Retimed file text, so the manual sync picker lists the lines as they now play. */
        val retimedBody: String,
    ) : AutomaticSubtitleSyncResult

    /** The stream carries no embedded text subtitle timing to sync against (or isn't MKV/MP4). */
    data object NoReference : AutomaticSubtitleSyncResult

    /** The addon subtitle couldn't be downloaded or parsed. */
    data object SubtitleUnavailable : AutomaticSubtitleSyncResult

    /** A reference existed but no confident alignment was found; the original timing is kept. */
    data object NotConfident : AutomaticSubtitleSyncResult
}

internal expect suspend fun runAutomaticSubtitleSync(
    request: AutomaticSubtitleSyncRequest,
): AutomaticSubtitleSyncResult

/** Starts reading the stream's subtitle index while playback opens, so a later sync finds it cached. */
internal expect fun prefetchAutomaticSubtitleSyncIndex(sourceUrl: String, sourceHeaders: Map<String, String>)

/** Progress of [runAudioReferencedSubtitleSync]; each [Applied] replaces the previous one. */
internal sealed interface AudioSubtitleSyncUpdate {
    /** Load [synced] now. [confirmed] false = an early estimate that may still move or be withdrawn. */
    data class Applied(
        val synced: AutomaticSubtitleSyncResult.Synced,
        val confirmed: Boolean,
        /** Delay the mapping applies at the playhead, for the HUD message. */
        val offsetMs: Long,
    ) : AudioSubtitleSyncUpdate

    /** The early estimate lost its support: go back to the subtitle's own timing. */
    data object Withdrawn : AudioSubtitleSyncUpdate
}

/**
 * Fallback for streams without embedded subtitles (ported from NuvioTV-Reshaped's audio subtitle
 * sync): samples a few dialogue spots of the film's audio through [player], detects speech and
 * aligns [subtitle] to it. Reports each mapping through [onUpdate] as it is found. Returns the
 * final [AutomaticSubtitleSyncResult.Synced] (confirmed or a kept estimate),
 * [AutomaticSubtitleSyncResult.NotConfident], [AutomaticSubtitleSyncResult.SubtitleUnavailable],
 * or [AutomaticSubtitleSyncResult.NoReference] when the engine can't sample audio.
 */
internal expect suspend fun runAudioReferencedSubtitleSync(
    sourceUrl: String,
    subtitle: AddonSubtitle,
    player: PlayerEngineController,
    durationMs: Long,
    positionMs: () -> Long,
    onUpdate: suspend (AudioSubtitleSyncUpdate) -> Unit,
): AutomaticSubtitleSyncResult
