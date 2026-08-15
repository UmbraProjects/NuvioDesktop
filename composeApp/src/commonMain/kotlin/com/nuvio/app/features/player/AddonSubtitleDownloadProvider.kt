package com.nuvio.app.features.player

data class AddonSubtitleDownloadRequest(
    val subtitleUrl: String,
    val subtitleLabel: String,
    val language: String,
    val activeMediaSource: String,
    val suggestedBaseName: String,
)

sealed interface AddonSubtitleDownloadResult {
    data class Saved(val path: String, val savedBesideMedia: Boolean) : AddonSubtitleDownloadResult
    data object Cancelled : AddonSubtitleDownloadResult
    data class Failed(val reason: String) : AddonSubtitleDownloadResult
}

/** Saves one addon subtitle as a permanent user-owned file. */
expect object AddonSubtitleDownloadProvider {
    suspend fun download(request: AddonSubtitleDownloadRequest): AddonSubtitleDownloadResult
}
