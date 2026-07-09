package com.nuvio.app.features.player

import androidx.compose.ui.Modifier

internal data class PlayerScreenArgs(
    val title: String,
    val sourceUrl: String,
    val sourceAudioUrl: String?,
    val sourceHeaders: Map<String, String>,
    val sourceResponseHeaders: Map<String, String>,
    val streamType: String?,
    val providerName: String,
    val streamTitle: String,
    val streamSubtitle: String?,
    val initialBingeGroup: String?,
    val pauseDescription: String?,
    val onBack: () -> Unit,
    val onOpenInExternalPlayer: ((ExternalPlayerPlaybackRequest) -> Unit)?,
    val modifier: Modifier,
    val logo: String?,
    val poster: String?,
    val background: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val episodeTitle: String?,
    val episodeThumbnail: String?,
    val contentType: String?,
    val videoId: String?,
    val parentMetaId: String,
    val parentMetaType: String,
    val watchProgressSource: String?,
    val providerAddonId: String?,
    val torrentInfoHash: String?,
    val torrentFileIdx: Int?,
    val torrentFilename: String?,
    val torrentTrackers: List<String>,
    val initialPositionMs: Long,
    val initialProgressFraction: Float?,
    // When true, this playback is ephemeral (e.g. a trailer or random rewatch):
    // no watch-progress persistence and no Trakt/Simkl scrobbling.
    val disableProgressTracking: Boolean = false,
    val autoPlayMode: PlayerAutoPlayMode = PlayerAutoPlayMode.NextEpisode,
)
