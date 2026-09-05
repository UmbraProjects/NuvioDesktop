package com.nuvio.app.features.downloads

import com.nuvio.app.features.locallibrary.LOCAL_LIBRARY_PROVIDER_NAME
import com.nuvio.app.features.locallibrary.LOCAL_LIBRARY_STREAM_NAME
import com.nuvio.app.features.locallibrary.LocalLibraryRepository

/** The provenance strings the player HUD and pause overlay show for a source. */
data class DownloadPlaybackLabels(
    val streamTitle: String,
    val streamSubtitle: String?,
    val providerName: String,
)

/**
 * What a completed download should be *called* while it plays.
 *
 * Two problems are fixed here, both of which the stored [DownloadItem] fields get wrong:
 *
 *  - **Provenance vs. location.** A download that landed in a local-library folder is the same file
 *    the scanner serves as a `Local File` / `Local Library` stream, but the registry remembers how
 *    it was *acquired*. Playing an episode from the scanned stream and then auto-advancing into the
 *    next one — which the registry answers first, see `launchPlayerNextEpisodeAutoPlay` — renamed
 *    the source mid-show. Reported as an episode switching from "Local Library / Local File" to
 *    "Torbox / Torbox". Files outside every library root keep their acquisition provenance, which
 *    is the only true thing about them.
 *
 *  - **Duplicated provider name.** The PVR and manual-grab services build their `StreamItem` with
 *    `name` and `addonName` both set to the debrid provider, putting the real filename in `title`,
 *    which `enqueueFromStream` drops. Both stored fields then read "Torbox". Whenever the stored
 *    title says nothing the provider has not already said, fall back to something that identifies
 *    the file. Applied on read so the records already on disk are covered without a migration.
 */
internal fun resolveDownloadPlaybackLabels(
    streamTitle: String,
    streamSubtitle: String?,
    providerName: String,
    fileName: String,
    episodeTitle: String?,
    fallbackTitle: String,
    downloadedLabel: String,
    servedByLocalLibrary: Boolean,
): DownloadPlaybackLabels {
    if (servedByLocalLibrary) {
        // Exactly what the scanner's own stream for this file carries, subtitle included: the two
        // representations have to be indistinguishable or the parity above is only half done.
        return DownloadPlaybackLabels(
            streamTitle = LOCAL_LIBRARY_STREAM_NAME,
            streamSubtitle = null,
            providerName = LOCAL_LIBRARY_PROVIDER_NAME,
        )
    }
    val provider = providerName.trim().ifBlank { downloadedLabel }
    val storedTitle = streamTitle.trim()
        .takeIf { it.isNotBlank() && !it.equals(provider, ignoreCase = true) }
    val title = storedTitle
        ?: fileName.downloadFileDisplayName()
        ?: episodeTitle?.trim()?.takeIf { it.isNotBlank() }
        ?: fallbackTitle
    return DownloadPlaybackLabels(
        streamTitle = title,
        streamSubtitle = streamSubtitle?.trim()?.takeIf { it.isNotBlank() },
        providerName = provider,
    )
}

/**
 * Resolves the labels for [item] playing from [localPath] (the path
 * [DownloadsRepository.playableLocalFileUri] resolved, which is what actually decides whether this
 * is a library file — [DownloadItem.destinationDir] can be stale or unset).
 */
internal fun DownloadItem.playbackLabels(
    localPath: String?,
    fallbackTitle: String,
    downloadedLabel: String,
): DownloadPlaybackLabels = resolveDownloadPlaybackLabels(
    streamTitle = streamTitle,
    streamSubtitle = streamSubtitle,
    providerName = providerName,
    fileName = fileName,
    episodeTitle = episodeTitle,
    fallbackTitle = fallbackTitle,
    downloadedLabel = downloadedLabel,
    servedByLocalLibrary = LocalLibraryRepository.servesPath(localPath),
)

/**
 * The stored destination's base name without its extension. [DownloadItem.fileName] is a path
 * relative to the destination root for library downloads (`Show (2024)/Season 01/…`) and a bare
 * name otherwise, so both shapes have to reduce to the same thing.
 */
private fun String.downloadFileDisplayName(): String? = trim()
    .substringAfterLast('/')
    .substringAfterLast('\\')
    .substringBeforeLast('.')
    .trim()
    .takeIf { it.isNotBlank() }
