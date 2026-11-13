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
import com.nuvio.app.features.details.effectiveEpisodeNumber
import com.nuvio.app.features.details.effectiveSeasonNumber
import com.nuvio.app.features.details.streamVideoIdForPlayback
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.toContinueWatchingItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first

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
    private const val MAX_CONTINUE = 20
    private const val MAX_EPISODES = 600
    private const val MAX_SEARCH_RESULTS = 120
    private const val SEARCH_TIMEOUT_MS = 30_000L
    private const val SEARCH_THROTTLE_MS = 300L

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
        // Not the debrid cloud's files (Torbox and the like): they are files, not titles. The Library tab plays them from
        // its own cloud view; opened as a title from the phone they failed on the TV. Only the library's titles go.
        val titles = state.sections.filterNot { it.type.startsWith(com.nuvio.app.features.cloud.CloudLibraryContentType) }.flatMap { section ->
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
        // Still the stand-in from before the chosen source (Trakt, MDBList...) loaded: the phone asks again shortly.
        val ready = state.isLoaded && !state.isLoading && state.sourceMode == LibraryRepository.intendedSourceMode()
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

    // --- Continue watching (phase 3): the phone's Watch tab opens with it, and Resume on the TV plays it. ---

    fun continueLine(): String? = runCatching {
        onMain { WatchProgressRepository.ensureLoaded() }
        val items = WatchProgressRepository.continueWatching()
            .filter { it.isResumable }
            .take(MAX_CONTINUE)
            .map { entry ->
                val episode = if (entry.seasonNumber != null && entry.episodeNumber != null) {
                    listOfNotNull("S${entry.seasonNumber} E${entry.episodeNumber}", entry.episodeTitle?.takeIf { it.isNotBlank() })
                        .joinToString(" · ")
                } else {
                    null
                }
                RemoteResume(
                    type = entry.parentMetaType,
                    parentId = entry.parentMetaId,
                    videoId = entry.videoId,
                    title = entry.title,
                    episode = episode,
                    poster = entry.poster,
                    still = entry.episodeThumbnail ?: entry.background,
                    positionMs = entry.lastPositionMs,
                    durationMs = entry.durationMs,
                    lastSource = entry.lastStreamTitle ?: entry.providerName,
                    progressPermille = (entry.progressFraction * 1000f).toInt(),
                )
            }
        PhoneRemoteLibraryLines.continueLine(items)
    }.onFailure { error -> log.w { "Phone remote: could not list Continue Watching: ${error.message}" } }.getOrNull()

    /**
     * Plays a Continue Watching title as the card's last-link shortcut does: the same source again, from where it
     * stopped (the source list opens when the last link is not kept or no longer plays).
     */
    fun resume(parentId: String, videoId: String): String? {
        val entry = WatchProgressRepository.continueWatching()
            .firstOrNull { it.parentMetaId == parentId && it.videoId == videoId }
            ?: return null
        log.i { "Phone remote: resuming ${entry.parentMetaType} $parentId ($videoId)" }
        RemotePlayerHub.resume(entry.toContinueWatchingItem())
        return entry.title
    }

    // --- Search (phase 6): Nuvio's own catalog search, run for the phone. ---

    private var searchJob: kotlinx.coroutines.Job? = null

    /** Names of what the phone was last shown by a search, so opening one says its name. */
    private val searchNames = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Runs Nuvio's search (the same as typing [query] into the Search tab) and sends what it finds as it arrives, a few
     * times a second at most, and once more when every catalog has answered. A new search from the phone replaces one
     * still running.
     */
    fun search(query: String, reply: (String) -> Unit) {
        searchJob?.cancel()
        searchJob = mainScope.launch {
            com.nuvio.app.features.addons.AddonRepository.initialize()
            WatchedRepository.ensureLoaded()
            com.nuvio.app.features.search.SearchRepository.search(
                query = query,
                addons = com.nuvio.app.features.addons.AddonRepository.uiState.value.addons,
            )
            withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                var lastLine: String? = null
                com.nuvio.app.features.search.SearchRepository.uiState.first { state ->
                    if (!state.query.equals(query.trim(), ignoreCase = true)) return@first false
                    val watched = WatchedContentFilter.forHistory(WatchedRepository.uiState.value.items)
                    val seen = HashSet<String>()
                    val titles = state.sections.flatMap { section ->
                        section.items.mapNotNull { item ->
                            if (!seen.add("${item.type}:${item.id}")) return@mapNotNull null
                            RemoteTitle(
                                section = listOf(section.title, section.addonName).filter { it.isNotBlank() }.distinct().joinToString(" · "),
                                type = item.type,
                                id = item.id,
                                name = item.name,
                                year = item.releaseInfo,
                                poster = item.poster,
                                posterFallback = item.posterFallback,
                                watched = watched.isWatched(item.type, item.id),
                            )
                        }
                    }.take(MAX_SEARCH_RESULTS)
                    titles.forEach { searchNames["${it.type}:${it.id}"] = it.name }
                    val line = PhoneRemoteLibraryLines.searchLine(query, state.isLoading, titles)
                    if (line != lastLine) {
                        lastLine = line
                        withContext(Dispatchers.Default) { reply(line) }
                    }
                    if (state.isLoading) kotlinx.coroutines.delay(SEARCH_THROTTLE_MS)
                    !state.isLoading
                }
            }
        }
    }

    // --- A show's episodes: the phone's season list, and playing one as the details page does. ---

    private val metas = java.util.concurrent.ConcurrentHashMap<String, com.nuvio.app.features.details.MetaDetails>()

    internal suspend fun meta(type: String, id: String): com.nuvio.app.features.details.MetaDetails? =
        metas["$type:$id"] ?: runCatching { com.nuvio.app.features.details.MetaDetailsRepository.fetch(type, id) }.getOrNull()
            ?.also { metas["$type:$id"] = it }

    /** The id the details page plays [video] by (the same steps as its episode click). */
    internal fun playbackId(meta: com.nuvio.app.features.details.MetaDetails, video: com.nuvio.app.features.details.MetaVideo): String {
        val id = com.nuvio.app.features.watchprogress.buildPlaybackVideoId(
            parentMetaId = meta.id,
            seasonNumber = video.effectiveSeasonNumber(),
            episodeNumber = video.effectiveEpisodeNumber(),
            fallbackVideoId = video.id,
        )
        return video.streamVideoIdForPlayback(meta.id, id)
    }

    fun episodes(type: String, id: String, reply: (String) -> Unit) {
        mainScope.launch {
            val meta = withTimeoutOrNull(20_000L) { meta(type, id) }
            WatchProgressRepository.ensureLoaded()
            WatchedRepository.ensureLoaded()
            val progress = WatchProgressRepository.uiState.value.byVideoId
            val watchedKeys = WatchedRepository.uiState.value.watchedKeys
            val episodes = meta?.videos.orEmpty()
                .filter { (it.season ?: 1) >= 0 }
                .map { video ->
                    val playId = playbackId(meta!!, video)
                    val saved = progress[playId]
                    // fork:phone-remote menus - an episode marked watched (no progress kept) reads as watched too, so a
                    // Mark as watched from the phone's long-press menu shows in the list.
                    val marked = com.nuvio.app.features.watching.application.WatchingState.isEpisodeWatched(
                        watchedKeys = watchedKeys, metaType = meta.type, metaId = meta.id, episode = video,
                    )
                    RemoteEpisode(
                        season = video.effectiveSeasonNumber(),
                        episode = video.effectiveEpisodeNumber(),
                        title = video.title,
                        videoId = playId,
                        thumbnail = video.thumbnail,
                        released = video.released,
                        progressPermille = when {
                            saved?.isEffectivelyCompleted == true || marked -> 1000
                            saved == null -> 0
                            else -> (saved.progressFraction * 1000f).toInt()
                        },
                    )
                }
            val line = PhoneRemoteLibraryLines.episodesLine(type, id, meta != null, episodes.take(MAX_EPISODES))
            withContext(Dispatchers.Default) { reply(line) }
        }
    }

    fun playEpisode(type: String, id: String, videoId: String): String? {
        val meta = metas["$type:$id"] ?: return null
        val video = meta.videos.firstOrNull { playbackId(meta, it) == videoId } ?: return null
        val saved = WatchProgressRepository.uiState.value.byVideoId[videoId]?.takeUnless { it.isCompleted }
        val season = video.effectiveSeasonNumber()
        val episode = video.effectiveEpisodeNumber()
        log.i { "Phone remote: playing ${meta.name} S${season}E$episode" }
        RemotePlayerHub.playEpisode(
            RemoteEpisodePlay(
                type = meta.type, videoId = videoId, parentMetaId = meta.id, parentMetaType = meta.type, title = meta.name,
                logo = meta.logo, poster = meta.poster, background = meta.background, seasonNumber = season,
                episodeNumber = episode, episodeTitle = video.title, episodeThumbnail = video.thumbnail,
                pauseDescription = video.overview, resumePositionMs = saved?.lastPositionMs,
            ),
        )
        return listOfNotNull(meta.name, if (season != null && episode != null) "S$season E$episode" else null).joinToString(" ")
    }

    /** Opens the title's details on the TV, as clicking its poster in the Library does. */
    fun openTitle(type: String, id: String): String? {
        val name = LibraryRepository.uiState.value.items.firstOrNull { it.id == id && it.type == type }?.name
            ?: LibraryRepository.uiState.value.sections.asSequence().flatMap { it.items }.firstOrNull { it.id == id }?.name
        log.i { "Phone remote: opening $type $id" }
        mainScope.launch { handleAppUrl(buildMetaDeepLinkUrl(type, id)) }
        return name ?: searchNames["$type:$id"] ?: "the title"
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
