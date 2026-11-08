package com.nuvio.app.features.remote

import co.touchlab.kermit.Logger
import com.nuvio.app.core.deeplink.buildMetaDeepLinkUrl
import com.nuvio.app.core.deeplink.handleAppUrl
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.home.WatchedContentFilter
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.toMetaPreview
import com.nuvio.app.features.music.MusicPlayback
import com.nuvio.app.features.music.defaultMusicCatalog
import com.nuvio.app.features.music.library.MusicLibraryRepository
import com.nuvio.app.features.music.library.playArtistMix
import com.nuvio.app.features.music.library.shelfAlbumsOf
import com.nuvio.app.features.music.library.shelfSortKey
import com.nuvio.app.features.music.loadArtistRadio
import com.nuvio.app.features.watched.WatchedRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * fork:phone-remote — the libraries the phone browses, and what it asks for from them. Reads the same repositories the
 * Library tab and the music Shelf read, and plays through the same calls they make, so the phone never sees a title or
 * an album the PC would not show, and choosing one does on the TV what choosing it there would.
 *
 * Called on the remote's connection threads. Anything that loads a repository or touches a player goes to Main.
 */
internal object PhoneRemoteLibrary {
    private val log = Logger.withTag("PhoneRemote")
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The repositories are loaded the first time a phone asks; a phone asks again while `ready` is 0. */
    private fun onMain(work: () -> Unit) {
        // A repository that fails to load must not take the phone's connection down with it: the phone is sent
        // whatever is there, and asks again.
        runCatching {
            runBlocking { withTimeoutOrNull(3_000L) { withContext(Dispatchers.Main) { work() } } }
        }.onFailure { error -> log.w { "Phone remote: loading the library failed: ${error.message}" } }
    }

    fun line(kind: String): String? = runCatching {
        when (kind) {
            PhoneRemoteLibraryLines.VIDEO -> videoLine()
            PhoneRemoteLibraryLines.MUSIC -> musicLine()
            else -> null
        }
    }.onFailure { error -> log.w { "Phone remote: could not list the $kind library: ${error.message}" } }.getOrNull()

    private fun videoLine(): String {
        onMain {
            LibraryRepository.ensureLoaded()
            WatchedRepository.ensureLoaded()
        }
        val state = LibraryRepository.uiState.value
        val watched = WatchedContentFilter.forHistory(WatchedRepository.uiState.value.items)
        val seen = HashSet<String>()
        val titles = state.sections.flatMap { section ->
            section.items.mapNotNull { item ->
                // A title saved to two lists shows once, in the first.
                if (!seen.add("${item.type}:${item.id}")) return@mapNotNull null
                val preview = item.toMetaPreview()
                RemoteTitle(
                    section = section.displayTitle,
                    type = item.type,
                    id = item.id,
                    name = item.name,
                    year = item.releaseInfo,
                    poster = preview.poster,
                    posterFallback = preview.posterFallback,
                    watched = watched.isWatched(item.type, item.id),
                )
            }
        }
        val ready = state.isLoaded && !state.isLoading
        return PhoneRemoteLibraryLines.videoLine(ready, titles)
    }

    private fun musicLine(): String {
        onMain { MusicLibraryRepository.ensureLoaded() }
        val state = MusicLibraryRepository.state.value
        val artists = state.artists.sortedBy { it.shelfSortKey() }
        val albums = artists.flatMap { artist ->
            shelfAlbumsOf(artist).mapNotNull { album ->
                val release = album.release
                val browseId = release.ytBrowseId ?: return@mapNotNull null
                RemoteAlbum(
                    artistId = artist.id,
                    browseId = browseId,
                    title = release.title,
                    kind = release.kind.label,
                    year = release.year ?: release.releaseDate?.take(4),
                    cover = release.art.cover,
                    starred = album.pinned,
                )
            }
        }
        return PhoneRemoteLibraryLines.musicLine(
            ready = true,
            artists = artists.map { RemoteArtist(it.id, it.name, it.art.thumb, it.favourite) },
            albums = albums,
        )
    }

    /** Opens the title's details on the TV, as clicking its poster in the Library does. */
    fun openTitle(type: String, id: String): String? {
        val name = LibraryRepository.uiState.value.items.firstOrNull { it.id == id && it.type == type }?.name
            ?: LibraryRepository.uiState.value.sections.asSequence().flatMap { it.items }.firstOrNull { it.id == id }?.name
        log.i { "Phone remote: opening $type $id" }
        mainScope.launch { handleAppUrl(buildMetaDeepLinkUrl(type, id)) }
        return name ?: "the title"
    }

    fun playAlbum(browseId: String, action: AlbumAction): String {
        val title = MusicLibraryRepository.state.value.artists.asSequence()
            .flatMap { it.releases }.firstOrNull { it.ytBrowseId == browseId }?.title
        mainScope.launch {
            val album = try {
                defaultMusicCatalog().album(browseId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            if (album == null || album.tracks.isEmpty()) {
                NuvioToastController.show("Couldn't open ${title ?: "the album"} from your phone")
                return@launch
            }
            when (action) {
                AlbumAction.Play -> MusicPlayback.player.play(album, trackIndex = 0, shuffled = false)
                AlbumAction.Shuffle -> MusicPlayback.player.play(album, trackIndex = 0, shuffled = true)
                AlbumAction.Queue -> if (MusicPlayback.player.enqueue(album)) NuvioToastController.show("Added ${album.title} to the queue")
            }
        }
        return title ?: "the album"
    }

    fun artist(artistId: String, action: ArtistAction): String? {
        val artist = MusicLibraryRepository.state.value.artists.firstOrNull { it.id == artistId } ?: return null
        mainScope.launch {
            val catalog = defaultMusicCatalog()
            val ok = try {
                when (action) {
                    ArtistAction.Mix -> playArtistMix(catalog, artist, shelfAlbumsOf(artist).map { it.release })
                    ArtistAction.Radio -> {
                        val radio = loadArtistRadio(catalog, artist.id, artist.name)?.takeIf { it.tracks.isNotEmpty() }
                        if (radio != null) MusicPlayback.player.play(radio)
                        radio != null
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                false
            }
            if (!ok) NuvioToastController.show("Couldn't play ${artist.name} from your phone")
        }
        return when (action) {
            ArtistAction.Mix -> "a mix of ${artist.name}"
            ArtistAction.Radio -> "${artist.name} radio"
        }
    }
}
