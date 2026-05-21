package com.nuvio.app.features.cloud

import com.nuvio.app.features.debrid.DebridProviders
import com.nuvio.app.features.debrid.TorboxApiClient
import com.nuvio.app.features.debrid.TorboxCloudFileDto
import com.nuvio.app.features.debrid.TorboxCloudItemDto
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive

internal class TorboxCloudLibraryProviderApi : CloudLibraryProviderApi {
    override val provider = DebridProviders.Torbox

    override suspend fun listItems(apiKey: String): Result<List<CloudLibraryItem>> =
        runCatching {
            val torrents = TorboxApiClient.listCloudTorrents(apiKey).itemsOrThrow(CloudLibraryItemType.Torrent)
            val usenet = TorboxApiClient.listCloudUsenet(apiKey).itemsOrThrow(CloudLibraryItemType.Usenet)
            val web = TorboxApiClient.listCloudWebDownloads(apiKey).itemsOrThrow(CloudLibraryItemType.WebDownload)
            torrents + usenet + web
        }

    override suspend fun resolvePlayback(
        apiKey: String,
        item: CloudLibraryItem,
        file: CloudLibraryFile,
    ): CloudLibraryPlaybackResult {
        if (!file.playable) return CloudLibraryPlaybackResult.NotPlayable

        return try {
            val response = when (item.type) {
                CloudLibraryItemType.Torrent -> TorboxApiClient.requestCloudTorrentDownloadLink(
                    apiKey = apiKey,
                    torrentId = item.id,
                    fileId = file.id,
                )
                CloudLibraryItemType.Usenet -> TorboxApiClient.requestCloudUsenetDownloadLink(
                    apiKey = apiKey,
                    usenetId = item.id,
                    fileId = file.id,
                )
                CloudLibraryItemType.WebDownload -> TorboxApiClient.requestCloudWebDownloadLink(
                    apiKey = apiKey,
                    webId = item.id,
                    fileId = file.id,
                )
            }
            if (!response.isSuccessful || response.body?.success == false) {
                return CloudLibraryPlaybackResult.Failed(response.body?.detail ?: response.body?.error)
            }
            val url = response.body?.data?.takeIf { it.isNotBlank() }
                ?: return CloudLibraryPlaybackResult.Failed()
            CloudLibraryPlaybackResult.Success(
                url = url,
                filename = file.name.takeIf { it.isNotBlank() },
                videoSizeBytes = file.sizeBytes,
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            CloudLibraryPlaybackResult.Failed(error.message)
        }
    }

    private fun com.nuvio.app.features.debrid.DebridApiResponse<com.nuvio.app.features.debrid.TorboxEnvelopeDto<List<TorboxCloudItemDto>>>.itemsOrThrow(
        type: CloudLibraryItemType,
    ): List<CloudLibraryItem> {
        if (!isSuccessful || body?.success == false) {
            throw IllegalStateException(body?.detail ?: body?.error ?: rawBody.takeIf { it.isNotBlank() })
        }
        return body?.data.orEmpty().mapNotNull { dto ->
            dto.toCloudLibraryItem(
                providerId = provider.id,
                providerName = provider.displayName,
                type = type,
            )
        }
    }
}

internal fun TorboxCloudItemDto.toCloudLibraryItem(
    providerId: String,
    providerName: String,
    type: CloudLibraryItemType,
): CloudLibraryItem? {
    val itemId = id.scalarString()
        ?: hash?.trim()?.takeIf { it.isNotBlank() }
        ?: return null
    val mappedFiles = files.orEmpty().mapNotNull { file ->
        file.toCloudLibraryFile()
    }
    val filesSize = mappedFiles
        .mapNotNull { it.sizeBytes }
        .takeIf { it.isNotEmpty() }
        ?.sum()
    return CloudLibraryItem(
        providerId = providerId,
        providerName = providerName,
        id = itemId,
        type = type,
        name = name?.trim()?.takeIf { it.isNotBlank() } ?: itemId,
        status = listOf(status, downloadState, state)
            .firstNonBlank(),
        sizeBytes = size ?: totalSize ?: filesSize,
        progressFraction = listOfNotNull(progress, downloadProgress).firstOrNull()?.toProgressFraction(),
        files = mappedFiles,
    )
}

internal fun TorboxCloudFileDto.toCloudLibraryFile(): CloudLibraryFile? {
    val name = listOf(name, shortName, absolutePath)
        .firstNonBlank()
        ?: return null
    val fileId = id.scalarString()
    val mime = listOf(mimeType, mimeTypeAlt).firstNonBlank()
    return CloudLibraryFile(
        id = fileId,
        name = name,
        sizeBytes = size,
        mimeType = mime,
        playable = fileId != null && isPlayableCloudFile(name = name, mimeType = mime),
    )
}

internal fun torboxRequestIdParameterName(type: CloudLibraryItemType): String =
    when (type) {
        CloudLibraryItemType.Torrent -> "torrent_id"
        CloudLibraryItemType.Usenet -> "usenet_id"
        CloudLibraryItemType.WebDownload -> "web_id"
    }

private fun List<String?>.firstNonBlank(): String? =
    firstOrNull { !it.isNullOrBlank() }?.trim()

private fun kotlinx.serialization.json.JsonElement?.scalarString(): String? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.content.trim().takeIf { it.isNotBlank() }
}

private fun Double.toProgressFraction(): Float {
    val normalized = if (this > 1.0) this / 100.0 else this
    return normalized.toFloat().coerceIn(0f, 1f)
}

private fun isPlayableCloudFile(name: String, mimeType: String?): Boolean {
    val normalizedMime = mimeType?.lowercase().orEmpty()
    if (normalizedMime.startsWith("video/")) return true
    val extension = name.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
    return extension in playableVideoExtensions
}

private val playableVideoExtensions = setOf(
    "3g2",
    "3gp",
    "avi",
    "divx",
    "flv",
    "m2ts",
    "m4v",
    "mkv",
    "mov",
    "mp4",
    "mpeg",
    "mpg",
    "mts",
    "ogm",
    "ogv",
    "ts",
    "webm",
    "wmv",
)
