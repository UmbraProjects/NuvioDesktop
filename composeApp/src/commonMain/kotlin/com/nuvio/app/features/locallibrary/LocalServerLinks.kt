package com.nuvio.app.features.locallibrary

import co.touchlab.kermit.Logger
import com.nuvio.app.features.servers.ServerCatalog
import com.nuvio.app.features.servers.ServerConnection
import com.nuvio.app.features.servers.ServerMediaKind
import com.nuvio.app.features.servers.ServerRepository
import com.nuvio.app.features.servers.ServerTitle
import kotlinx.coroutines.CancellationException

/*
 * "Show in Local Library" for media servers: a linked server's titles become Local Library items,
 * so they sit in the user's catalogs, Library rows and title browser beside the files on disk.
 *
 * The link is a view, not a copy of the files: these items carry no files, so the Local Library
 * never offers them as a source of its own. Playing one goes through the server's group in the
 * picker, the same as from anywhere else — which is the duplicate rule (one entry, the server's).
 * Only titles a server gives an IMDb or TMDB id for are taken: the Local Library's details page,
 * scrobbling and stream matching all work off those ids, and a title without one is still on the
 * server's own Home row.
 */

internal const val LOCAL_SERVER_FOLDER_PREFIX = "server:"

internal fun localServerFolderId(connectionId: String): String = "$LOCAL_SERVER_FOLDER_PREFIX$connectionId"

internal fun String.isLocalServerFolderId(): Boolean = startsWith(LOCAL_SERVER_FOLDER_PREFIX)

internal fun String.localServerConnectionId(): String? =
    takeIf { it.isLocalServerFolderId() }?.removePrefix(LOCAL_SERVER_FOLDER_PREFIX)

/** What one linked server answered: its titles, or null when it could not be read this time. */
internal data class LinkedServerScan(
    val connectionId: String,
    val items: List<LocalMediaItem>?,
)

internal object LocalServerLinks {
    private val log = Logger.withTag("LocalServerLinks")

    /** Lists every selected movie/series library of [connectionIds]' servers as Local Library items. */
    suspend fun scan(connectionIds: Set<String>): List<LinkedServerScan> =
        connectionIds.map { connectionId ->
            val connection = ServerRepository.connection(connectionId)?.takeIf { it.enabled }
            if (connection == null) {
                LinkedServerScan(connectionId, items = emptyList())
            } else {
                LinkedServerScan(connectionId, items = scanConnection(connection))
            }
        }

    private suspend fun scanConnection(connection: ServerConnection): List<LocalMediaItem>? {
        val libraries = ServerCatalog.titleLibraries().filter { it.connection.id == connection.id }
        val items = mutableListOf<LocalMediaItem>()
        for (ref in libraries) {
            var start = 0
            try {
                while (start < MAX_TITLES_PER_LIBRARY) {
                    val page = ServerRepository.call(connection.id) { provider, session ->
                        provider.libraryPage(session, ref.library, start, PAGE_SIZE)
                    }
                    items += page.items.mapNotNull { it.toLocalItem(connection.id, ref.library.kind) }
                    start += page.items.size
                    val total = page.totalCount
                    if (page.items.isEmpty() || (total != null && start >= total) || page.items.size < PAGE_SIZE) break
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.w { "${ref.title} could not be listed: ${error.message}" }
                return null // Keep what was listed last time rather than emptying the server's titles.
            }
        }
        return items.distinctBy { it.key }
    }

    internal fun ServerTitle.toLocalItem(connectionId: String, libraryKind: ServerMediaKind): LocalMediaItem? {
        val imdbId = externalIds.imdb?.takeIf { it.startsWith("tt") }
        val tmdbId = externalIds.tmdb?.toInt()
        if (imdbId == null && tmdbId == null) return null
        val isSeries = when (libraryKind) {
            ServerMediaKind.SERIES -> true
            ServerMediaKind.MOVIE -> false
            else -> preview.type.equals("series", ignoreCase = true)
        }
        return LocalMediaItem(
            key = "$LOCAL_SERVER_FOLDER_PREFIX$connectionId:${preview.id}",
            folderId = localServerFolderId(connectionId),
            type = if (isSeries) LocalFolderType.SERIES else LocalFolderType.MOVIES,
            title = preview.name,
            year = preview.releaseInfo?.let { YEAR.find(it)?.value?.toIntOrNull() },
            imdbId = imdbId,
            tmdbId = tmdbId,
            poster = preview.poster,
            background = preview.banner,
            matchState = LocalMatchState.AUTO,
        )
    }

    private val YEAR = Regex("""\b(19|20)\d{2}\b""")
    private const val PAGE_SIZE = 200
    private const val MAX_TITLES_PER_LIBRARY = 10_000
}
