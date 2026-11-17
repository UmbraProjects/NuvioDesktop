package com.nuvio.app.features.home

import co.touchlab.kermit.Logger
import com.nuvio.app.features.servers.ServerCatalog
import com.nuvio.app.features.servers.serverFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * One row per selected library of every enabled media server, for the Library tab (upstream's
 * `loadServerShelves` in LibraryServerContent.kt). A library that fails or is empty is left out,
 * and so is a server shown in the Local Library, whose titles are in the local rows already.
 */
internal suspend fun loadLibraryServerSections(): List<HomeCatalogSection> = coroutineScope {
    val linked = com.nuvio.app.features.locallibrary.LocalLibraryRepository.uiState.value.linkedServers
    ServerCatalog.libraries().filterNot { it.connection.id in linked }.map { ref ->
        async {
            try {
                ServerCatalog.librarySection(ref, LIBRARY_SERVER_SHELF_LIMIT)
                    .takeIf { it.items.isNotEmpty() }
                    ?.copy(key = "library_server_${ref.connection.id}_${ref.library.id}")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Logger.withTag("LibraryServers").w { "${ref.title}: ${error.serverFailure()}" }
                null
            }
        }
    }.awaitAll().filterNotNull()
}

private const val LIBRARY_SERVER_SHELF_LIMIT = 18
