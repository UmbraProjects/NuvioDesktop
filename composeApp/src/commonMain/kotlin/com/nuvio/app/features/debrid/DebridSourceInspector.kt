package com.nuvio.app.features.debrid

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Lists every file behind a magnet on the user's active debrid provider, and resolves a direct
 * link for any one of them on demand.
 *
 * [DirectDebridPlaybackResolver] deliberately collapses a torrent down to the single file it
 * believes the caller wants. The manual library grab needs the opposite: the whole listing, so the
 * user can map files to episodes themselves when addon episode detection gets it wrong.
 *
 * Only providers with [DebridProviderCapability.LocalTorrentResolve] can be inspected — the same
 * set the local torrent resolver already supports (Torbox, Premiumize).
 */
internal object DebridSourceInspector {

    /** How long a freshly added magnet gets to produce its file list before giving up. */
    private val UNCACHED_METADATA_TIMEOUT = 45.seconds
    private val UNCACHED_METADATA_POLL = 1_500.milliseconds

    /**
     * Whether a source could be inspected at all right now, without making a request.
     *
     * Torbox and Premiumize can list a torrent's contents; Real-Debrid cannot, so anything routed
     * through inspection has to have a fallback for it. Lets a caller choose between the one-call
     * listing and a slower path *before* committing to either.
     */
    fun canInspect(): Boolean {
        val settings = DebridSettingsRepository.snapshot()
        if (!settings.linkResolvingEnabled) return false
        val credential = settings.activeResolverCredential ?: return false
        if (!credential.provider.supports(DebridProviderCapability.LocalTorrentResolve)) return false
        return credential.apiKey.isNotBlank()
    }

    /**
     * @param allowUncached adds the magnet to the provider even when it is not already cached, and
     * waits for the metadata to come back, instead of refusing with [DebridSourceResult.NotCached].
     * Only for user-initiated inspections: it starts a real transfer on the account and costs a
     * write against the provider's rate limit, neither of which is acceptable automatically. Torbox
     * only — Premiumize's directdl has no equivalent and still reports NotCached.
     */
    suspend fun inspect(magnetUri: String, allowUncached: Boolean = false): DebridSourceResult {
        val settings = DebridSettingsRepository.snapshot()
        if (!settings.linkResolvingEnabled) return DebridSourceResult.MissingProvider
        val credential = settings.activeResolverCredential
            ?.takeIf { it.provider.supports(DebridProviderCapability.LocalTorrentResolve) }
            ?: return DebridSourceResult.MissingProvider
        val apiKey = credential.apiKey.trim().takeIf { it.isNotBlank() }
            ?: return DebridSourceResult.MissingProvider
        val magnet = magnetUri.trim().takeIf { it.isNotBlank() }
            ?: return DebridSourceResult.Failed(null)

        return try {
            when (credential.provider.id) {
                DebridProviders.TORBOX_ID -> inspectTorbox(apiKey, magnet, allowUncached)
                DebridProviders.PREMIUMIZE_ID -> inspectPremiumize(apiKey, magnet)
                else -> DebridSourceResult.MissingProvider
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            DebridSourceResult.Failed(error.message)
        }
    }

    /**
     * Resolves one listed file to a downloadable URL. Called per file at the moment its transfer
     * starts rather than for the whole selection up front, so links are as fresh as possible.
     */
    suspend fun resolveLink(source: DebridSource, file: DebridSourceFile): DebridSourceLink? {
        // Premiumize hands links back with the listing itself.
        file.directUrl?.takeIf { it.isNotBlank() }?.let { url ->
            return DebridSourceLink(url = url, filename = file.name, sizeBytes = file.sizeBytes)
        }
        val settings = DebridSettingsRepository.snapshot()
        val apiKey = settings.apiKeyFor(source.providerId).trim().takeIf { it.isNotBlank() } ?: return null

        return try {
            when (source.providerId) {
                DebridProviders.TORBOX_ID -> {
                    val torrentId = source.sourceId.toIntOrNull() ?: return null
                    val fileId = file.id.toIntOrNull() ?: return null
                    val link = TorboxApiClient.requestDownloadLink(
                        apiKey = apiKey,
                        torrentId = torrentId,
                        fileId = fileId,
                    )
                    val url = link.body?.data?.takeIf { link.isSuccessful && it.isNotBlank() } ?: return null
                    DebridSourceLink(url = url, filename = file.name, sizeBytes = file.sizeBytes)
                }
                else -> null
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }
    }

    private suspend fun inspectTorbox(
        apiKey: String,
        magnet: String,
        allowUncached: Boolean,
    ): DebridSourceResult {
        // Unless the caller explicitly opted in, createtorrent is sent with add_only_if_cached, so
        // an uncached magnet 409s rather than starting a transfer we would have to wait hours for.
        val create = TorboxApiClient.createTorrent(
            apiKey = apiKey,
            magnet = magnet,
            onlyIfCached = !allowUncached,
        )
        if (create.status == 409) return DebridSourceResult.NotCached
        val torrentId = create.body?.takeIf { it.success != false }?.data?.resolvedTorrentId()
            ?: return when (create.status) {
                401, 403 -> DebridSourceResult.Failed("Torbox rejected the API key")
                else -> DebridSourceResult.Failed(create.body?.detail ?: create.body?.error)
            }

        // A cached torrent is listable immediately. A freshly added one is not: Torbox has to pull
        // the metadata off the swarm first, so the file list arrives seconds later and the first
        // response legitimately has none. Only that case waits, and only for a bounded time —
        // *metadata*, not content, so this is not a wait on the transfer itself.
        val deadline = TimeSource.Monotonic.markNow() + UNCACHED_METADATA_TIMEOUT
        while (true) {
            val torrent = TorboxApiClient.getTorrent(apiKey = apiKey, id = torrentId)
            val data = torrent.body?.data?.takeIf { torrent.isSuccessful }
            val files = data?.files.orEmpty().mapNotNull { file ->
                val id = file.id?.toString() ?: return@mapNotNull null
                val name = file.displayName().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                DebridSourceFile(
                    id = id,
                    name = name.substringAfterLast('/').ifBlank { name },
                    path = name,
                    sizeBytes = file.size,
                )
            }
            if (files.isNotEmpty()) {
                return DebridSourceResult.Success(
                    DebridSource(
                        providerId = DebridProviders.TORBOX_ID,
                        providerName = DebridProviders.Torbox.displayName,
                        sourceId = torrentId.toString(),
                        name = data?.name,
                        files = files,
                    ),
                )
            }
            if (!allowUncached || deadline.hasPassedNow()) {
                return DebridSourceResult.Failed("Torbox did not return the torrent contents")
            }
            delay(UNCACHED_METADATA_POLL)
        }
    }

    private suspend fun inspectPremiumize(apiKey: String, magnet: String): DebridSourceResult {
        val response = PremiumizeApiClient.directDownload(apiKey = apiKey, source = magnet)
        if (!response.isSuccessful) {
            return when (response.status) {
                401, 403 -> DebridSourceResult.Failed("Premiumize rejected the API key")
                else -> DebridSourceResult.Failed(null)
            }
        }
        val body = response.body ?: return DebridSourceResult.Failed(null)
        if (body.status.equals("error", ignoreCase = true)) {
            val message = listOfNotNull(body.message, body.code).joinToString(" ")
            return if (message.contains("cache", ignoreCase = true) ||
                message.contains("not found", ignoreCase = true)
            ) {
                DebridSourceResult.NotCached
            } else {
                DebridSourceResult.Failed(body.message)
            }
        }

        val files = body.content.orEmpty().mapIndexedNotNull { index, file ->
            val path = file.path?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            DebridSourceFile(
                // Premiumize has no per-file id; the listing index is stable for this response and
                // the link travels with the entry anyway.
                id = "pm-$index",
                name = file.displayName().takeIf { it.isNotBlank() } ?: path,
                path = path,
                sizeBytes = file.size,
                directUrl = file.link,
            )
        }
        return DebridSourceResult.Success(
            DebridSource(
                providerId = DebridProviders.PREMIUMIZE_ID,
                providerName = DebridProviders.Premiumize.displayName,
                sourceId = magnet,
                name = null,
                files = files,
            ),
        )
    }
}

internal data class DebridSourceFile(
    val id: String,
    /** Base file name, e.g. `Friends.S03E01.1080p.mkv`. */
    val name: String,
    /** Full path inside the torrent, when the provider exposes one. */
    val path: String,
    val sizeBytes: Long?,
    val directUrl: String? = null,
)

internal data class DebridSource(
    val providerId: String,
    val providerName: String,
    /** Provider handle used to fetch per-file links later (torrent id, or the magnet itself). */
    val sourceId: String,
    /** Torrent name, when the provider reports one — a useful season hint for file naming. */
    val name: String?,
    val files: List<DebridSourceFile>,
)

internal data class DebridSourceLink(
    val url: String,
    val filename: String?,
    val sizeBytes: Long?,
)

internal sealed interface DebridSourceResult {
    data class Success(val source: DebridSource) : DebridSourceResult
    /** No debrid provider configured that can inspect a torrent. */
    data object MissingProvider : DebridSourceResult
    data object NotCached : DebridSourceResult
    data class Failed(val message: String?) : DebridSourceResult
}
