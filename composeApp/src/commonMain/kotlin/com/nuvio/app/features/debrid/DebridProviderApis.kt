package com.nuvio.app.features.debrid

import com.nuvio.app.features.streams.StreamClientResolve
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CancellationException

internal interface DebridProviderApi {
    val provider: DebridProvider

    suspend fun validateApiKey(apiKey: String): Boolean

    suspend fun resolveClientStream(
        stream: StreamItem,
        apiKey: String,
        season: Int?,
        episode: Int?,
    ): DirectDebridResolveResult
}

internal object DebridProviderApis {
    private val registered = listOf(
        TorboxDebridProviderApi(),
        RealDebridProviderApi(),
    )

    fun apiFor(providerId: String?): DebridProviderApi? {
        val normalized = DebridProviders.byId(providerId)?.id ?: return null
        return registered.firstOrNull { it.provider.id == normalized }
    }
}

private class TorboxDebridProviderApi(
    private val fileSelector: TorboxFileSelector = TorboxFileSelector(),
) : DebridProviderApi {
    override val provider: DebridProvider = DebridProviders.Torbox

    override suspend fun validateApiKey(apiKey: String): Boolean =
        TorboxApiClient.validateApiKey(apiKey)

    override suspend fun resolveClientStream(
        stream: StreamItem,
        apiKey: String,
        season: Int?,
        episode: Int?,
    ): DirectDebridResolveResult {
        val resolve = stream.clientResolve ?: return DirectDebridResolveResult.Error
        val magnet = resolve.magnetUri?.takeIf { it.isNotBlank() }
            ?: buildMagnetUri(resolve)
            ?: return DirectDebridResolveResult.Stale

        return try {
            val create = TorboxApiClient.createTorrent(apiKey = apiKey, magnet = magnet)
            val torrentId = create.body?.takeIf { it.success != false }?.data?.resolvedTorrentId()
                ?: return create.toFailureForCreate()

            val torrent = TorboxApiClient.getTorrent(apiKey = apiKey, id = torrentId)
            if (!torrent.isSuccessful) {
                return DirectDebridResolveResult.Stale
            }
            val files = torrent.body?.data?.files.orEmpty()
            val file = fileSelector.selectFile(files, resolve, season, episode)
                ?: return DirectDebridResolveResult.Stale
            val fileId = file.id ?: return DirectDebridResolveResult.Stale

            val link = TorboxApiClient.requestDownloadLink(
                apiKey = apiKey,
                torrentId = torrentId,
                fileId = fileId,
            )
            if (!link.isSuccessful) {
                return DirectDebridResolveResult.Stale
            }
            val url = link.body?.data?.takeIf { it.isNotBlank() }
                ?: return DirectDebridResolveResult.Stale

            DirectDebridResolveResult.Success(
                url = url,
                filename = file.displayName().takeIf { it.isNotBlank() },
                videoSize = file.size,
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            DirectDebridResolveResult.Error
        }
    }
}

private class RealDebridProviderApi(
    private val fileSelector: RealDebridFileSelector = RealDebridFileSelector(),
) : DebridProviderApi {
    override val provider: DebridProvider = DebridProviders.RealDebrid

    override suspend fun validateApiKey(apiKey: String): Boolean =
        RealDebridApiClient.validateApiKey(apiKey)

    override suspend fun resolveClientStream(
        stream: StreamItem,
        apiKey: String,
        season: Int?,
        episode: Int?,
    ): DirectDebridResolveResult {
        val resolve = stream.clientResolve ?: return DirectDebridResolveResult.Error
        val magnet = resolve.magnetUri?.takeIf { it.isNotBlank() }
            ?: buildMagnetUri(resolve)
            ?: return DirectDebridResolveResult.Stale

        return try {
            val add = RealDebridApiClient.addMagnet(apiKey, magnet)
            val torrentId = add.body?.id?.takeIf { add.isSuccessful && it.isNotBlank() }
                ?: return add.toFailureForAdd()
            var resolved = false
            try {
                val infoBefore = RealDebridApiClient.getTorrentInfo(apiKey, torrentId)
                if (!infoBefore.isSuccessful) {
                    return DirectDebridResolveResult.Stale
                }
                val filesBefore = infoBefore.body?.files.orEmpty()
                val file = fileSelector.selectFile(
                    files = filesBefore,
                    resolve = resolve,
                    season = season,
                    episode = episode,
                ) ?: return DirectDebridResolveResult.Stale
                val fileId = file.id ?: return DirectDebridResolveResult.Stale
                val select = RealDebridApiClient.selectFiles(apiKey, torrentId, fileId.toString())
                if (!select.isSuccessful && select.status != 202) {
                    return DirectDebridResolveResult.Stale
                }

                val infoAfter = RealDebridApiClient.getTorrentInfo(apiKey, torrentId)
                if (!infoAfter.isSuccessful) {
                    return DirectDebridResolveResult.Stale
                }
                val link = infoAfter.body?.firstDownloadLink()
                    ?: return DirectDebridResolveResult.Stale
                val unrestrict = RealDebridApiClient.unrestrictLink(apiKey, link)
                if (!unrestrict.isSuccessful) {
                    return DirectDebridResolveResult.Stale
                }
                val url = unrestrict.body?.download?.takeIf { it.isNotBlank() }
                    ?: return DirectDebridResolveResult.Stale
                resolved = true
                DirectDebridResolveResult.Success(
                    url = url,
                    filename = unrestrict.body.filename?.takeIf { it.isNotBlank() }
                        ?: file.displayName().takeIf { it.isNotBlank() },
                    videoSize = unrestrict.body.filesize ?: file.bytes,
                )
            } finally {
                if (!resolved) {
                    runCatching { RealDebridApiClient.deleteTorrent(apiKey, torrentId) }
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            DirectDebridResolveResult.Error
        }
    }
}

private fun buildMagnetUri(resolve: StreamClientResolve): String? {
    val hash = resolve.infoHash?.takeIf { it.isNotBlank() } ?: return null
    return buildString {
        append("magnet:?xt=urn:btih:")
        append(hash)
        resolve.sources
            .mapNotNull { it.toTrackerUrlOrNull() }
            .distinct()
            .forEach { source ->
                append("&tr=")
                append(encodePathSegment(source))
            }
    }
}

private fun String.toTrackerUrlOrNull(): String? {
    val value = trim()
    if (value.isBlank() || value.startsWith("dht:", ignoreCase = true)) return null
    return value.removePrefix("tracker:").trim().takeIf { it.isNotBlank() }
}

private fun DebridApiResponse<TorboxEnvelopeDto<TorboxCreateTorrentDataDto>>.toFailureForCreate(): DirectDebridResolveResult =
    when (status) {
        401, 403 -> DirectDebridResolveResult.Error
        409 -> DirectDebridResolveResult.NotCached
        else -> DirectDebridResolveResult.Stale
    }

private fun DebridApiResponse<RealDebridAddTorrentDto>.toFailureForAdd(): DirectDebridResolveResult =
    when (status) {
        401, 403 -> DirectDebridResolveResult.Error
        else -> DirectDebridResolveResult.Stale
    }

private fun RealDebridTorrentInfoDto.firstDownloadLink(): String? {
    if (!status.equals("downloaded", ignoreCase = true)) return null
    return links.orEmpty().firstOrNull { it.isNotBlank() }
}
