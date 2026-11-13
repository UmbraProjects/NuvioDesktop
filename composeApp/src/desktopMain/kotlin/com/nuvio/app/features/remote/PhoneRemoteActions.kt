package com.nuvio.app.features.remote

import co.touchlab.kermit.Logger
import com.nuvio.app.core.deeplink.buildMetaDeepLinkUrl
import com.nuvio.app.core.deeplink.handleAppUrl
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.effectiveEpisodeNumber
import com.nuvio.app.features.details.effectiveSeasonNumber
import com.nuvio.app.features.details.progressForEpisodeVideo
import com.nuvio.app.features.discover.DiscoverAiSettingsRepository
import com.nuvio.app.features.discover.DiscoverRecommendationsRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.LibrarySourceMode
import com.nuvio.app.features.library.toLibraryItem
import com.nuvio.app.features.library.toMetaPreview
import com.nuvio.app.features.playlist.PlaylistEntries
import com.nuvio.app.features.playlist.PlaylistEntry
import com.nuvio.app.features.playlist.PlaylistRepository
import com.nuvio.app.features.recap.RecapBoundary
import com.nuvio.app.features.recap.RecapRepository
import com.nuvio.app.features.recap.RecapUnavailable
import com.nuvio.app.features.recap.RecapUnavailableException
import com.nuvio.app.features.simkl.SimklAuthRepository
import com.nuvio.app.features.simkl.SimklRewatchRepository
import com.nuvio.app.features.simkl.SimklRewatchStartResult
import com.nuvio.app.features.simkl.SimklSettingsRepository
import com.nuvio.app.features.simkl.canUseRewatches
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.previousReleasedEpisodesBefore
import com.nuvio.app.features.watched.releasedEpisodesForSeason
import com.nuvio.app.features.watched.releasedPlayableEpisodes
import com.nuvio.app.features.watching.application.WatchingActions
import com.nuvio.app.features.watching.application.WatchingState
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.toContinueWatchingItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * fork:phone-remote menus — the phone's long-press sheet on a library poster, a Continue Watching card, an episode row
 * and a season header. It offers what the TV's own long-press menus offer (NuvioPosterActionSheet in `App.kt`,
 * NuvioContinueWatchingActionSheet, and the details page's EpisodeWatchedActionSheet / SeasonWatchedActionSheet), and
 * does each thing through the same repository call those menus make, so marking an episode from the phone is exactly
 * marking it on the TV (Trakt, Simkl and the rest follow as they do there).
 *
 * The PC builds the sheet (the phone draws whatever rows it is sent), because only the PC knows what is saved, watched,
 * resumable or recap-able. Called on the remote's connection threads; the work runs on Main.
 */
internal object PhoneRemoteActions {
    private val log = Logger.withTag("PhoneRemote")
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private const val META_TIMEOUT_MS = 20_000L
    /** Long enough for a watched change to reach the repositories' published state before the phone asks again. */
    private const val SETTLE_MS = 400L

    private val everything = listOf(
        PhoneRemoteLibraryLines.REFRESH_LIBRARY,
        PhoneRemoteLibraryLines.REFRESH_CONTINUE,
        PhoneRemoteLibraryLines.REFRESH_EPISODES,
    )

    private fun today(): String = CurrentDateProvider.todayIsoDate()

    private fun isSeriesType(type: String): Boolean = type.lowercase() in setOf("series", "tv", "anime")

    // ---------------------------------------------------------------- What is pointed at

    /** A library title as the Library tab holds it, or null when it is not in the library (an addon search result). */
    private fun libraryItem(type: String, id: String): LibraryItem? {
        val state = LibraryRepository.uiState.value
        return state.items.firstOrNull { it.id == id && it.type == type }
            ?: state.sections.asSequence().flatMap { it.items.asSequence() }.firstOrNull { it.id == id && it.type == type }
    }

    private fun MetaDetails.preview(): MetaPreview = MetaPreview(
        id = id, type = type, name = name, poster = poster, banner = background, logo = logo,
        description = description, releaseInfo = releaseInfo,
    )

    private suspend fun preview(type: String, id: String): MetaPreview? =
        libraryItem(type, id)?.toMetaPreview()
            ?: withTimeoutOrNull(META_TIMEOUT_MS) { PhoneRemoteLibrary.meta(type, id) }?.preview()

    private suspend fun meta(type: String, id: String): MetaDetails? =
        withTimeoutOrNull(META_TIMEOUT_MS) { PhoneRemoteLibrary.meta(type, id) }

    private fun episodeIn(meta: MetaDetails, videoId: String): MetaVideo? =
        meta.videos.firstOrNull { PhoneRemoteLibrary.playbackId(meta, it) == videoId || it.id == videoId }

    private fun continueItem(parentId: String, videoId: String): ContinueWatchingItem? =
        WatchProgressRepository.continueWatching()
            .firstOrNull { it.parentMetaId == parentId && it.videoId == videoId }
            ?.toContinueWatchingItem()

    /** The details page's rule (isEpisodeWatchedForActions): watched through, or marked watched. */
    private fun isEpisodeWatched(meta: MetaDetails, episode: MetaVideo): Boolean {
        val playId = com.nuvio.app.features.watchprogress.buildPlaybackVideoId(
            parentMetaId = meta.id,
            seasonNumber = episode.effectiveSeasonNumber(),
            episodeNumber = episode.effectiveEpisodeNumber(),
            fallbackVideoId = episode.id,
        )
        return WatchProgressRepository.uiState.value.byVideoId.progressForEpisodeVideo(playId, episode.id)?.isEffectivelyCompleted == true ||
            WatchingState.isEpisodeWatched(
                watchedKeys = WatchedRepository.uiState.value.watchedKeys,
                metaType = meta.type,
                metaId = meta.id,
                episode = episode,
            )
    }

    private fun allWatched(meta: MetaDetails, episodes: Collection<MetaVideo>): Boolean =
        episodes.isNotEmpty() && episodes.all { isEpisodeWatched(meta, it) }

    private fun seasonOf(episode: MetaVideo): Int = episode.effectiveSeasonNumber()?.coerceAtLeast(0) ?: 0

    private fun seasonLabel(season: Int): String = if (season == 0) "Specials" else "Season $season"

    private fun episodeLabel(episode: MetaVideo): String = listOfNotNull(
        episode.effectiveSeasonNumber()?.let { s -> episode.effectiveEpisodeNumber()?.let { e -> "S$s E$e" } },
        episode.title.takeIf { it.isNotBlank() },
    ).joinToString(" · ").ifEmpty { "This episode" }

    private fun canRewatch(watched: Boolean): Boolean = watched &&
        SimklAuthRepository.uiState.value.canUseRewatches && SimklSettingsRepository.isRewatchTrackingEnabled()

    private fun recapReady(): Boolean = DiscoverAiSettingsRepository.snapshot().isRecapReady

    private fun previousSeasonEpisodes(meta: MetaDetails, season: Int): List<MetaVideo> =
        meta.releasedPlayableEpisodes(today()).filter { seasonOf(it) in 1 until season }

    // ---------------------------------------------------------------- The sheet

    fun menu(target: RemoteMenuTarget, reply: (String) -> Unit) {
        mainScope.launch {
            val built: String? = try {
                LibraryRepository.ensureLoaded()
                WatchedRepository.ensureLoaded()
                WatchProgressRepository.ensureLoaded()
                when (target.kind) {
                    RemoteMenuKind.Title -> titleMenu(target)
                    RemoteMenuKind.Resume -> resumeMenu(target)
                    RemoteMenuKind.Episode -> episodeMenu(target)
                    RemoteMenuKind.Season -> seasonMenu(target)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.w { "Phone remote: could not build the ${target.kind.wire} menu: ${e.message}" }
                null
            }
            val line = built ?: PhoneRemoteLibraryLines.menuLine(target, "Couldn't load this", "The TV didn't find it. Try again.", emptyList())
            withContext(Dispatchers.Default) { reply(line) }
        }
    }

    private suspend fun titleMenu(target: RemoteMenuTarget): String? {
        val preview = preview(target.type, target.id) ?: return null
        val series = isSeriesType(preview.type)
        val saved = LibraryRepository.isSaved(preview.id, preview.type)
        val watched = WatchingState.isPosterWatched(WatchedRepository.uiState.value.watchedKeys, preview)
        val progress = if (series) null else WatchProgressRepository.uiState.value.byVideoId[preview.id]
            ?.takeIf { !it.isEffectivelyCompleted && it.lastPositionMs > 0L }
        val actions = buildList {
            add(RemoteMenuAction(RemoteMenuCode.WATCHED, if (watched) "Mark as unwatched" else "Mark as watched"))
            add(RemoteMenuAction(RemoteMenuCode.LIBRARY, if (saved) "Remove from library" else "Add to library"))
            if (LibraryRepository.hasConnectedLibraryProviders()) add(RemoteMenuAction(RemoteMenuCode.ALL_LIBRARIES, "Add to all libraries"))
            if (!series) {
                if (progress != null) add(RemoteMenuAction(RemoteMenuCode.FROM_START, "Start from the beginning"))
                add(RemoteMenuAction(RemoteMenuCode.CHOOSE_SOURCE, "Choose a source on the TV"))
            }
            if (canRewatch(watched)) add(RemoteMenuAction(RemoteMenuCode.REWATCH, "Start a rewatch"))
            add(RemoteMenuAction(RemoteMenuCode.PLAYLIST, "Add to a playlist"))
            if (DiscoverRecommendationsRepository.canMarkNotInterested(preview)) {
                add(RemoteMenuAction(RemoteMenuCode.NOT_INTERESTED, "Not interested"))
            }
            add(RemoteMenuAction(RemoteMenuCode.DETAILS, "Details on the TV"))
        }
        val detail = listOfNotNull(if (series) "Show" else "Film", preview.releaseInfo?.takeIf { it.isNotBlank() }).joinToString(" · ")
        return PhoneRemoteLibraryLines.menuLine(target, preview.name, detail, actions)
    }

    private suspend fun resumeMenu(target: RemoteMenuTarget): String? {
        val item = continueItem(target.id, target.ref ?: return null) ?: return null
        val isEpisode = item.seasonNumber != null && item.episodeNumber != null
        val watched = if (isEpisode) {
            meta(item.parentMetaType, item.parentMetaId)?.let { meta -> episodeIn(meta, item.videoId)?.let { isEpisodeWatched(meta, it) } } == true
        } else {
            WatchedRepository.isWatched(id = item.parentMetaId, type = item.parentMetaType)
        }
        val actions = buildList {
            add(RemoteMenuAction(RemoteMenuCode.RESUME, if (item.isNextUp) "Play on the TV" else "Resume on the TV"))
            if (!item.isNextUp) add(RemoteMenuAction(RemoteMenuCode.FROM_START, "Start from the beginning"))
            add(RemoteMenuAction(RemoteMenuCode.CHOOSE_SOURCE, "Choose a source on the TV"))
            add(
                RemoteMenuAction(
                    RemoteMenuCode.WATCHED,
                    when {
                        watched && isEpisode -> "Mark episode as unwatched"
                        watched -> "Mark as unwatched"
                        isEpisode -> "Mark episode as watched"
                        else -> "Mark as watched"
                    },
                ),
            )
            add(RemoteMenuAction(RemoteMenuCode.PLAYLIST, if (isEpisode) "Add episode to a playlist" else "Add to a playlist"))
            add(RemoteMenuAction(RemoteMenuCode.DETAILS, "Details on the TV"))
            add(RemoteMenuAction(RemoteMenuCode.REMOVE, "Remove from Continue watching"))
        }
        val detail = if (isEpisode) {
            listOfNotNull("S${item.seasonNumber} E${item.episodeNumber}", item.episodeTitle?.takeIf { it.isNotBlank() }).joinToString(" · ")
        } else {
            "Film"
        }
        return PhoneRemoteLibraryLines.menuLine(target, item.title, detail, actions)
    }

    private suspend fun episodeMenu(target: RemoteMenuTarget): String? {
        val meta = meta(target.type, target.id) ?: return null
        val episode = episodeIn(meta, target.ref ?: return null) ?: return null
        val season = seasonOf(episode)
        val watched = isEpisodeWatched(meta, episode)
        val previous = meta.previousReleasedEpisodesBefore(episode, today())
        val seasonEpisodes = meta.releasedEpisodesForSeason(episode.effectiveSeasonNumber(), today())
        val playId = PhoneRemoteLibrary.playbackId(meta, episode)
        val progress = WatchProgressRepository.uiState.value.byVideoId[playId]
            ?.takeIf { !it.isEffectivelyCompleted && it.lastPositionMs > 0L }
        val canRecap = recapReady() && season > 0 && episode.effectiveEpisodeNumber() != null &&
            previous.any { seasonOf(it) > 0 && !it.overview.isNullOrBlank() }
        val actions = buildList {
            add(RemoteMenuAction(RemoteMenuCode.WATCHED, if (watched) "Mark as unwatched" else "Mark as watched"))
            if (previous.isNotEmpty()) {
                add(
                    RemoteMenuAction(
                        RemoteMenuCode.PREVIOUS_WATCHED,
                        if (allWatched(meta, previous)) "Mark earlier episodes as unwatched" else "Mark earlier episodes as watched",
                    ),
                )
            }
            if (seasonEpisodes.isNotEmpty()) {
                add(
                    RemoteMenuAction(
                        RemoteMenuCode.SEASON_WATCHED,
                        if (allWatched(meta, seasonEpisodes)) "Mark ${seasonLabel(season)} as unwatched" else "Mark ${seasonLabel(season)} as watched",
                    ),
                )
            }
            if (progress != null) add(RemoteMenuAction(RemoteMenuCode.FROM_START, "Start from the beginning"))
            add(RemoteMenuAction(RemoteMenuCode.CHOOSE_SOURCE, "Choose a source on the TV"))
            if (canRecap) add(RemoteMenuAction(RemoteMenuCode.RECAP, "Recap the story so far"))
            add(RemoteMenuAction(RemoteMenuCode.PLAYLIST, "Add episode to a playlist"))
            if (seasonEpisodes.isNotEmpty()) add(RemoteMenuAction(RemoteMenuCode.PLAYLIST_SEASON, "Add ${seasonLabel(season)} to a playlist"))
        }
        return PhoneRemoteLibraryLines.menuLine(target, episodeLabel(episode), meta.name, actions)
    }

    private suspend fun seasonMenu(target: RemoteMenuTarget): String? {
        val meta = meta(target.type, target.id) ?: return null
        val season = target.ref?.toIntOrNull() ?: return null
        val seasonEpisodes = meta.releasedEpisodesForSeason(season, today())
        val earlier = previousSeasonEpisodes(meta, season)
        val actions = buildList {
            if (seasonEpisodes.isNotEmpty()) {
                add(
                    RemoteMenuAction(
                        RemoteMenuCode.SEASON_WATCHED,
                        if (allWatched(meta, seasonEpisodes)) "Mark ${seasonLabel(season)} as unwatched" else "Mark ${seasonLabel(season)} as watched",
                    ),
                )
            }
            if (earlier.isNotEmpty() && !allWatched(meta, earlier)) {
                add(RemoteMenuAction(RemoteMenuCode.EARLIER_SEASONS_WATCHED, "Mark earlier seasons as watched"))
            }
            if (recapReady() && season > 0 && earlier.any { !it.overview.isNullOrBlank() }) {
                add(RemoteMenuAction(RemoteMenuCode.RECAP, "Recap before ${seasonLabel(season)}"))
            }
            if (seasonEpisodes.isNotEmpty()) add(RemoteMenuAction(RemoteMenuCode.PLAYLIST_SEASON, "Add ${seasonLabel(season)} to a playlist"))
            add(RemoteMenuAction(RemoteMenuCode.DETAILS, "Details on the TV"))
        }
        return PhoneRemoteLibraryLines.menuLine(target, seasonLabel(season), meta.name, actions)
    }

    // ---------------------------------------------------------------- Doing it

    fun act(target: RemoteMenuTarget, code: String, args: List<String>, reply: (String) -> Unit) {
        mainScope.launch {
            val lines = try {
                perform(target, code, args) { early -> withContext(Dispatchers.Default) { reply(early) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.w { "Phone remote: menu action $code on ${target.kind.wire} ${target.type}:${target.id} failed: ${e.message}" }
                listOf(PhoneRemoteLibraryLines.doneLine(false, e.message?.take(120) ?: "That didn't work on the TV"))
            }
            withContext(Dispatchers.Default) { lines.forEach(reply) }
        }
    }

    private fun done(message: String, refresh: Collection<String> = everything) = listOf(PhoneRemoteLibraryLines.doneLine(true, message, refresh))
    private fun failed(message: String) = listOf(PhoneRemoteLibraryLines.doneLine(false, message))

    /** Does [code]; returns the lines to answer with. [early] sends a line straight away (the recap's "writing…"). */
    private suspend fun perform(
        target: RemoteMenuTarget,
        code: String,
        args: List<String>,
        early: suspend (String) -> Unit,
    ): List<String> {
        log.i { "Phone remote: menu $code on ${target.kind.wire} ${target.type}:${target.id} ${target.ref.orEmpty()}" }
        return when (code) {
            RemoteMenuCode.DETAILS -> {
                val (type, id) = if (target.kind == RemoteMenuKind.Resume) {
                    (continueItem(target.id, target.ref.orEmpty())?.parentMetaType ?: target.type) to target.id
                } else {
                    target.type to target.id
                }
                handleAppUrl(buildMetaDeepLinkUrl(type, id))
                done("Opening the details on the TV", emptyList())
            }
            RemoteMenuCode.WATCHED -> toggleWatched(target)
            RemoteMenuCode.PREVIOUS_WATCHED, RemoteMenuCode.SEASON_WATCHED, RemoteMenuCode.EARLIER_SEASONS_WATCHED ->
                toggleEpisodes(target, code)
            RemoteMenuCode.LIBRARY -> toggleLibrary(target)
            RemoteMenuCode.ALL_LIBRARIES -> {
                val preview = preview(target.type, target.id) ?: return failed("The TV couldn't find this title")
                LibraryRepository.addToAllLibraries(libraryItem(target.type, target.id) ?: preview.toLibraryItem(savedAtEpochMs = 0L))
                settled("Added ${preview.name} to all your libraries")
            }
            RemoteMenuCode.NOT_INTERESTED -> {
                val preview = preview(target.type, target.id) ?: return failed("The TV couldn't find this title")
                DiscoverRecommendationsRepository.markNotInterested(preview)
                done("${preview.name} won't be suggested again", emptyList())
            }
            RemoteMenuCode.REWATCH -> {
                val preview = preview(target.type, target.id) ?: return failed("The TV couldn't find this title")
                when (val result = SimklRewatchRepository.startRewatch(preview)) {
                    is SimklRewatchStartResult.Started -> done("Rewatch started: ${result.session.title}")
                    is SimklRewatchStartResult.Failed -> failed(result.reason)
                }
            }
            RemoteMenuCode.REMOVE -> {
                val item = continueItem(target.id, target.ref.orEmpty()) ?: return failed("It's already gone from Continue watching")
                WatchProgressRepository.dismissContinueWatchingCard(item)
                settled("Removed ${item.title} from Continue watching", listOf(PhoneRemoteLibraryLines.REFRESH_CONTINUE))
            }
            RemoteMenuCode.RESUME -> play(target, RemotePlayMode.Normal)
            RemoteMenuCode.FROM_START -> play(target, RemotePlayMode.FromStart)
            RemoteMenuCode.CHOOSE_SOURCE -> play(target, RemotePlayMode.ChooseSource)
            RemoteMenuCode.RECAP -> recap(target, early)
            RemoteMenuCode.PLAYLIST -> playlists(target, scopeFor(target))
            RemoteMenuCode.PLAYLIST_SEASON -> playlists(target, RemoteMenuCode.PLAYLIST_SCOPE_SEASON)
            RemoteMenuCode.ADD_TO_PLAYLIST -> addToPlaylist(target, args)
            else -> failed("This Nuvio doesn't know that action yet")
        }
    }

    /** Waits for the repositories to publish the change, then tells the phone to reload what it shows. */
    private suspend fun settled(message: String, refresh: Collection<String> = everything): List<String> {
        delay(SETTLE_MS)
        return done(message, refresh)
    }

    private suspend fun toggleWatched(target: RemoteMenuTarget): List<String> {
        return when (target.kind) {
            RemoteMenuKind.Title -> {
                val preview = preview(target.type, target.id) ?: return failed("The TV couldn't find this title")
                val was = WatchingState.isPosterWatched(WatchedRepository.uiState.value.watchedKeys, preview)
                WatchingActions.togglePosterWatched(preview, origin = "phone remote")
                return settled(if (was) "${preview.name} marked as unwatched" else "${preview.name} marked as watched")
            }
            RemoteMenuKind.Resume -> {
                val item = continueItem(target.id, target.ref.orEmpty()) ?: return failed("It's no longer in Continue watching")
                if (item.seasonNumber != null && item.episodeNumber != null) {
                    val meta = meta(item.parentMetaType, item.parentMetaId) ?: return failed("The TV couldn't load the show")
                    val episode = episodeIn(meta, item.videoId) ?: return failed("The TV couldn't find that episode")
                    val was = isEpisodeWatched(meta, episode)
                    WatchingActions.toggleEpisodeWatched(meta, episode, isCurrentlyWatched = was)
                    return settled(if (was) "${episodeLabel(episode)} marked as unwatched" else "${episodeLabel(episode)} marked as watched")
                }
                val preview = libraryItem(item.parentMetaType, item.parentMetaId)?.toMetaPreview()
                    ?: MetaPreview(id = item.parentMetaId, type = item.parentMetaType, name = item.title, poster = item.poster)
                val was = WatchedRepository.isWatched(id = preview.id, type = preview.type)
                WatchingActions.togglePosterWatched(preview, origin = "phone remote")
                return settled(if (was) "${item.title} marked as unwatched" else "${item.title} marked as watched")
            }
            RemoteMenuKind.Episode -> {
                val meta = meta(target.type, target.id) ?: return failed("The TV couldn't load the show")
                val episode = episodeIn(meta, target.ref.orEmpty()) ?: return failed("The TV couldn't find that episode")
                val was = isEpisodeWatched(meta, episode)
                WatchingActions.toggleEpisodeWatched(meta, episode, isCurrentlyWatched = was)
                return settled(if (was) "${episodeLabel(episode)} marked as unwatched" else "${episodeLabel(episode)} marked as watched")
            }
            RemoteMenuKind.Season -> toggleEpisodes(target, RemoteMenuCode.SEASON_WATCHED)
        }
    }

    private suspend fun toggleEpisodes(target: RemoteMenuTarget, code: String): List<String> {
        val meta = meta(target.type, target.id) ?: return failed("The TV couldn't load the show")
        val anchor = if (target.kind == RemoteMenuKind.Episode) episodeIn(meta, target.ref.orEmpty()) else null
        val season = anchor?.let(::seasonOf) ?: target.ref?.toIntOrNull() ?: return failed("The TV couldn't find that season")
        return when (code) {
            RemoteMenuCode.PREVIOUS_WATCHED -> {
                val episode = anchor ?: return failed("The TV couldn't find that episode")
                val previous = meta.previousReleasedEpisodesBefore(episode, today())
                if (previous.isEmpty()) return failed("There are no earlier episodes")
                val were = allWatched(meta, previous)
                WatchingActions.togglePreviousEpisodesWatched(meta, previous, areCurrentlyWatched = were)
                return settled(if (were) "Earlier episodes marked as unwatched" else "Earlier episodes marked as watched")
            }
            RemoteMenuCode.EARLIER_SEASONS_WATCHED -> {
                val earlier = previousSeasonEpisodes(meta, season)
                if (earlier.isEmpty()) return failed("There are no earlier seasons")
                WatchingActions.togglePreviousEpisodesWatched(meta, earlier, areCurrentlyWatched = false)
                return settled("Earlier seasons marked as watched")
            }
            else -> {
                val episodes = meta.releasedEpisodesForSeason(season, today())
                if (episodes.isEmpty()) return failed("${seasonLabel(season)} has no released episodes")
                val were = allWatched(meta, episodes)
                WatchingActions.toggleSeasonWatched(meta, episodes, areCurrentlyWatched = were)
                return settled(if (were) "${seasonLabel(season)} marked as unwatched" else "${seasonLabel(season)} marked as watched")
            }
        }
    }

    /** The TV poster menu's library row: a plain press adds to the active library, or takes it out of every list. */
    private suspend fun toggleLibrary(target: RemoteMenuTarget): List<String> {
        val existing = libraryItem(target.type, target.id)
        val preview = existing?.toMetaPreview() ?: preview(target.type, target.id) ?: return failed("The TV couldn't find this title")
        val saved = LibraryRepository.isSaved(preview.id, preview.type)
        val item = existing ?: LibraryRepository.savedItem(preview.id) ?: preview.toLibraryItem(savedAtEpochMs = 0L)
        if (saved) {
            if (LibraryRepository.uiState.value.sourceMode != LibrarySourceMode.LOCAL) {
                val membership = LibraryRepository.getMembershipSnapshot(item)
                LibraryRepository.applyMembershipChanges(item = item, desiredMembership = membership.mapValues { false })
            } else {
                LibraryRepository.remove(item.id)
            }
            return settled("Removed ${preview.name} from the library", listOf(PhoneRemoteLibraryLines.REFRESH_LIBRARY))
        }
        LibraryRepository.toggleSaved(item)
        return settled("Added ${preview.name} to the library", listOf(PhoneRemoteLibraryLines.REFRESH_LIBRARY))
    }

    private suspend fun play(target: RemoteMenuTarget, mode: RemotePlayMode): List<String> {
        return when (target.kind) {
            RemoteMenuKind.Resume -> {
                val item = continueItem(target.id, target.ref.orEmpty()) ?: return failed("It's no longer in Continue watching")
                RemotePlayerHub.resume(item, mode)
                return done(playMessage(item.title, mode), emptyList())
            }
            RemoteMenuKind.Episode -> {
                val meta = meta(target.type, target.id) ?: return failed("The TV couldn't load the show")
                val episode = episodeIn(meta, target.ref.orEmpty()) ?: return failed("The TV couldn't find that episode")
                val playId = PhoneRemoteLibrary.playbackId(meta, episode)
                val saved = WatchProgressRepository.uiState.value.byVideoId[playId]?.takeUnless { it.isCompleted }
                RemotePlayerHub.playEpisode(
                    RemoteEpisodePlay(
                        type = meta.type, videoId = playId, parentMetaId = meta.id, parentMetaType = meta.type, title = meta.name,
                        logo = meta.logo, poster = meta.poster, background = meta.background,
                        seasonNumber = episode.effectiveSeasonNumber(), episodeNumber = episode.effectiveEpisodeNumber(),
                        episodeTitle = episode.title, episodeThumbnail = episode.thumbnail, pauseDescription = episode.overview,
                        resumePositionMs = saved?.lastPositionMs, mode = mode,
                    ),
                )
                return done(playMessage("${meta.name} ${episodeLabel(episode)}", mode), emptyList())
            }
            RemoteMenuKind.Title -> {
                // A film: played by its own id, as the details page's Play button does.
                val meta = meta(target.type, target.id) ?: return failed("The TV couldn't load this film")
                val saved = WatchProgressRepository.uiState.value.byVideoId[meta.id]?.takeUnless { it.isCompleted }
                RemotePlayerHub.playEpisode(
                    RemoteEpisodePlay(
                        type = meta.type, videoId = meta.id, parentMetaId = meta.id, parentMetaType = meta.type, title = meta.name,
                        logo = meta.logo, poster = meta.poster, background = meta.background, seasonNumber = null,
                        episodeNumber = null, episodeTitle = null, episodeThumbnail = null, pauseDescription = meta.description,
                        resumePositionMs = saved?.lastPositionMs, mode = mode,
                    ),
                )
                return done(playMessage(meta.name, mode), emptyList())
            }
            RemoteMenuKind.Season -> failed("Pick an episode to play")
        }
    }

    private fun playMessage(what: String, mode: RemotePlayMode): String = when (mode) {
        RemotePlayMode.ChooseSource -> "Sources for $what are on the TV"
        RemotePlayMode.FromStart -> "Playing $what from the start"
        RemotePlayMode.Normal -> "Playing $what on the TV"
    }

    // ---------------------------------------------------------------- Recap

    private suspend fun recap(target: RemoteMenuTarget, early: suspend (String) -> Unit): List<String> {
        val meta = meta(target.type, target.id) ?: return failed("The TV couldn't load the show")
        val (boundary, heading) = when (target.kind) {
            RemoteMenuKind.Episode -> {
                val episode = episodeIn(meta, target.ref.orEmpty()) ?: return failed("The TV couldn't find that episode")
                RecapBoundary(season = seasonOf(episode), episode = episode.effectiveEpisodeNumber()) to "Before ${episodeLabel(episode)}"
            }
            RemoteMenuKind.Season -> {
                val season = target.ref?.toIntOrNull() ?: return failed("The TV couldn't find that season")
                RecapBoundary(season = season, episode = null) to "Before ${seasonLabel(season)}"
            }
            else -> return failed("Recaps are for shows")
        }
        val title = "${meta.name} · $heading"
        // The phone shows "Writing the recap…" until the answer: a fresh one can take a few seconds.
        early(PhoneRemoteLibraryLines.recapLine(0, title, null))
        val result = RecapRepository.recap(meta, boundary, today())
        return result.fold(
            onSuccess = { listOf(PhoneRemoteLibraryLines.recapLine(1, title, it.text)) },
            onFailure = { error ->
                val why = when ((error as? RecapUnavailableException)?.reason) {
                    RecapUnavailable.NothingToRecap -> "There's nothing earlier to recap: the episodes before this have no synopsis."
                    RecapUnavailable.NotEnabled -> "Recaps are off on the TV (Settings › Discover › AI)."
                    null -> error.message?.take(200) ?: "The recap couldn't be written."
                }
                listOf(PhoneRemoteLibraryLines.recapLine(2, title, why))
            },
        )
    }

    // ---------------------------------------------------------------- Playlists

    private fun scopeFor(target: RemoteMenuTarget): String = when (target.kind) {
        RemoteMenuKind.Title -> RemoteMenuCode.PLAYLIST_SCOPE_TITLE
        RemoteMenuKind.Season -> RemoteMenuCode.PLAYLIST_SCOPE_SEASON
        RemoteMenuKind.Resume, RemoteMenuKind.Episode -> RemoteMenuCode.PLAYLIST_SCOPE_EPISODE
    }

    private suspend fun playlists(target: RemoteMenuTarget, scope: String): List<String> {
        PlaylistRepository.ensureLoaded()
        val choices = PlaylistRepository.playlists.value.map { RemotePlaylistChoice(it.id, it.name, it.entries.size) }
        var seasons = emptyList<Int>()
        val label = when (scope) {
            RemoteMenuCode.PLAYLIST_SCOPE_TITLE -> {
                val preview = preview(target.type, target.id) ?: return failed("The TV couldn't find this title")
                if (isSeriesType(preview.type)) {
                    val meta = meta(target.type, target.id) ?: return failed("The TV couldn't load the show")
                    seasons = meta.releasedPlayableEpisodes(today()).map(::seasonOf).filter { it > 0 }.distinct().sorted()
                    if (seasons.isEmpty()) return failed("${meta.name} has no released episodes yet")
                }
                preview.name
            }
            RemoteMenuCode.PLAYLIST_SCOPE_SEASON -> {
                val meta = meta(target.type, target.id) ?: return failed("The TV couldn't load the show")
                val season = seasonFor(meta, target) ?: return failed("The TV couldn't find that season")
                "${meta.name} · ${seasonLabel(season)}"
            }
            else -> episodeEntries(target)?.first ?: return failed("The TV couldn't find that episode")
        }
        return listOf(PhoneRemoteLibraryLines.playlistsLine(target, scope, label, seasons, choices))
    }

    private fun seasonFor(meta: MetaDetails, target: RemoteMenuTarget): Int? = when (target.kind) {
        RemoteMenuKind.Season -> target.ref?.toIntOrNull()
        RemoteMenuKind.Episode -> episodeIn(meta, target.ref.orEmpty())?.let(::seasonOf)
        else -> null
    }

    /** The one episode a Continue Watching card or an episode row points at, with its name. */
    private suspend fun episodeEntries(target: RemoteMenuTarget): Pair<String, List<PlaylistEntry>>? {
        val (type, id, videoId) = when (target.kind) {
            RemoteMenuKind.Resume -> {
                val item = continueItem(target.id, target.ref.orEmpty()) ?: return null
                if (item.seasonNumber == null || item.episodeNumber == null) {
                    val meta = meta(item.parentMetaType, item.parentMetaId) ?: return null
                    return meta.name to listOf(PlaylistEntries.movie(meta))
                }
                Triple(item.parentMetaType, item.parentMetaId, item.videoId)
            }
            else -> Triple(target.type, target.id, target.ref.orEmpty())
        }
        val meta = meta(type, id) ?: return null
        val episode = episodeIn(meta, videoId) ?: return null
        val entry = PlaylistEntries.episode(meta, episode)
        return "${meta.name} ${episodeLabel(episode)}" to listOf(entry)
    }

    private suspend fun addToPlaylist(target: RemoteMenuTarget, args: List<String>): List<String> {
        val playlistArg = args.getOrNull(0).orEmpty()
        val scope = args.getOrNull(1).orEmpty()
        val season = args.getOrNull(2)?.toIntOrNull() ?: 0
        val (label, entries) = when (scope) {
            RemoteMenuCode.PLAYLIST_SCOPE_TITLE -> {
                val meta = meta(target.type, target.id) ?: return failed("The TV couldn't load this title")
                if (isSeriesType(meta.type) || meta.videos.isNotEmpty()) {
                    val episodes = meta.releasedPlayableEpisodes(today())
                        .filter { seasonOf(it) > 0 && (season == 0 || seasonOf(it) == season) }
                    if (episodes.isEmpty()) return failed("Nothing released to add")
                    val name = if (season == 0) meta.name else "${meta.name} · ${seasonLabel(season)}"
                    name to episodes.map { PlaylistEntries.episode(meta, it) }
                } else {
                    meta.name to listOf(PlaylistEntries.movie(meta))
                }
            }
            RemoteMenuCode.PLAYLIST_SCOPE_SEASON -> {
                val meta = meta(target.type, target.id) ?: return failed("The TV couldn't load the show")
                val number = seasonFor(meta, target) ?: return failed("The TV couldn't find that season")
                val episodes = meta.releasedEpisodesForSeason(number, today())
                if (episodes.isEmpty()) return failed("${seasonLabel(number)} has no released episodes")
                "${meta.name} · ${seasonLabel(number)}" to episodes.map { PlaylistEntries.episode(meta, it) }
            }
            else -> episodeEntries(target) ?: return failed("The TV couldn't find that episode")
        }
        PlaylistRepository.ensureLoaded()
        val playlist = if (playlistArg == NEW_PLAYLIST) {
            PlaylistRepository.create(label.take(60))
        } else {
            PlaylistRepository.get(playlistArg) ?: return failed("That playlist is gone from the TV")
        }
        val added = PlaylistRepository.addEntries(playlist.id, entries)
        val what = if (entries.size == 1) label else "$added of ${entries.size} episodes"
        return done(
            when {
                added == 0 -> "Already in ${playlist.name}"
                else -> "Added $what to ${playlist.name}"
            },
            emptyList(),
        )
    }

    /** The playlist id that makes a new playlist, named after what is added. */
    const val NEW_PLAYLIST = "+"
}
