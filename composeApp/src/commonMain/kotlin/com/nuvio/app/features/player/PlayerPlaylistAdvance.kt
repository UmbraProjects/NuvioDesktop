package com.nuvio.app.features.player

import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.locallibrary.LocalLibraryRepository
import com.nuvio.app.features.player.skip.NextEpisodeInfo
import com.nuvio.app.features.playlist.PlaylistEntry
import com.nuvio.app.features.playlist.PlaylistHandoff
import com.nuvio.app.features.playlist.PlaylistPlaybackSession
import com.nuvio.app.features.playlist.randomSlotLabel
import kotlinx.coroutines.launch

/*
 * Playlist advance, run the way a binge next-episode advance is.
 *
 * The next entry's source is searched for inside the player, through the same
 * launchPlayerNextEpisodeAutoPlay pipeline (auto-play mode, source scoping, scoring, binge-group
 * preference, the empty-result retry, "Apply To Next Episode"), with the next-episode card showing
 * the search. Two ways in:
 *
 *  - [preparePlaylistAdvance] at the up-next threshold. With Binge Mode on it hands off as soon as a
 *    source is chosen, like binge; otherwise it only holds the result, so the end of the file (or a
 *    click on the card) plays the next entry immediately instead of starting a search then.
 *  - [requestPlaylistAdvance] at the end of the file, or from the card / next button / skip key.
 *
 * The handoff itself replaces the player route — see PlaylistHandoff for why it cannot be in place.
 */

private fun PlaylistEntry.asNextVideo(): MetaVideo = MetaVideo(
    id = videoId,
    title = episodeTitle ?: title,
    thumbnail = episodeThumbnail,
    season = seasonNumber,
    episode = episodeNumber,
    overview = description,
)

private fun PlaylistEntry.asNextEpisodeInfo(): NextEpisodeInfo = NextEpisodeInfo(
    videoId = videoId,
    season = seasonNumber ?: 0,
    episode = episodeNumber ?: 0,
    title = episodeTitle ?: title,
    thumbnail = episodeThumbnail,
    overview = description,
    released = null,
    hasAired = true,
    unairedMessage = null,
)

/**
 * The playlist's own "Prefer local files" decides, not the app-wide local-library setting: with it
 * on, an entry that has a local-library file plays it; off, entries search streams.
 */
private fun PlaylistEntry.sourceAffinity(preferLocal: Boolean): PlayerSourceAffinity {
    if (!preferLocal) return PlayerSourceAffinity.Stream
    val hasLocalStream = LocalLibraryRepository.localStreamsFor(parentMetaId, videoId).isNotEmpty()
    return if (hasLocalStream) PlayerSourceAffinity.Local else PlayerSourceAffinity.Stream
}

/**
 * Starts the background search for the next entry, if it is not already running or done.
 * [handOffWhenReady] plays the result as soon as it is chosen (Binge Mode, end of file, a click).
 */
internal fun PlayerScreenRuntime.preparePlaylistAdvance(
    trigger: String,
    handOffWhenReady: Boolean,
    showCountdown: Boolean = false,
) {
    val entry = playlistUpNext ?: return
    if (handOffWhenReady) playlistHandoffRequested = true
    // Already prepared ahead of the threshold (the early "lead" search): Binge Mode plays it now.
    val prepared = playlistPrepared
    if (prepared != null && handOffWhenReady) {
        handOffPlaylist(prepared)
        return
    }
    if (playlistHandedOff || prepared != null || playlistAdvanceJob?.isActive == true) return

    PlaylistPlaybackSession.log.i {
        "preparing next entry=${entry.entryId} trigger=$trigger handOffWhenReady=$handOffWhenReady"
    }
    // A random slot whose episode could not be picked when the player opened: let the app try
    // again through the source-list path, which resolves it first.
    if (entry.isUnresolvedRandom) {
        onPlaylistPrepared(PlaylistHandoff.SourceList(entry))
        return
    }
    val preferLocal = PlaylistPlaybackSession.currentPlaylist()?.preferLocalLibrary ?: true
    val affinity = entry.sourceAffinity(preferLocal)
    PlaylistPlaybackSession.log.i { "next entry affinity=$affinity preferLocal=$preferLocal" }
    // Binge-group preference is only meaningful within one show; across shows it simply finds no
    // match and the configured selection applies.
    val bingeGroup = currentStreamBingeGroup.takeIf { entry.parentMetaId == parentMetaId }
    val job = scope.launchPlayerNextEpisodeAutoPlay(
        previousJob = playlistAdvanceJob,
        nextEpisodeInfo = entry.asNextEpisodeInfo(),
        allEpisodes = listOf(entry.asNextVideo()),
        parentMetaId = entry.parentMetaId,
        parentMetaType = entry.parentMetaType,
        contentType = entry.type,
        settings = playerSettingsUiState,
        sourceAffinity = affinity,
        currentStreamBingeGroup = bingeGroup,
        currentStream = activeStream.takeIf { bingeGroup != null },
        onDownloadedEpisodeSelected = { item, _ -> onPlaylistPrepared(PlaylistHandoff.Downloaded(entry, item)) },
        onEpisodeStreamSelected = { stream, _ -> onPlaylistPrepared(PlaylistHandoff.Stream(entry, stream)) },
        onManualSelectionRequired = { onPlaylistPrepared(PlaylistHandoff.SourceList(entry)) },
        onSearchingChanged = { nextEpisodeAutoPlaySearching = it },
        onSourceNameChanged = { nextEpisodeAutoPlaySourceName = it },
        onCountdownChanged = { nextEpisodeAutoPlayCountdown = it },
        onNextEpisodeCardVisibleChanged = {},
        skipSourceCountdown = !showCountdown,
        allowDownloaded = preferLocal,
    )
    playlistAdvanceJob = job
}

/** End of file, the card, the next button or the skip key: play the next entry now. */
internal fun PlayerScreenRuntime.requestPlaylistAdvance(trigger: String) {
    if (playlistUpNext == null) return
    val prepared = playlistPrepared
    if (prepared != null) {
        playlistHandoffRequested = true
        handOffPlaylist(prepared)
        return
    }
    // A search still running from the threshold just needs to be told to play its result.
    if (playlistAdvanceJob?.isActive == true) {
        PlaylistPlaybackSession.log.i { "advance requested ($trigger) while the search runs" }
        playlistHandoffRequested = true
        return
    }
    // Nothing prepared: search now. A countdown only when it happens by itself at the end of the
    // file, as for a non-binge next episode; a click already said "now".
    preparePlaylistAdvance(
        trigger = trigger,
        handOffWhenReady = true,
        showCountdown = trigger == "eof" && !playerSettingsUiState.streamAutoPlayNextEpisodeEnabled,
    )
}

/**
 * A row clicked in the HUD's playlist peek ([position] is 1-based). The up-next entry with a source
 * already prepared plays exactly as an advance would; anything else closes this player and starts
 * that entry through the normal playlist launch (source search / source list).
 */
internal fun PlayerScreenRuntime.jumpToPlaylistEntry(position: Int) {
    val playlist = PlaylistPlaybackSession.currentPlaylist() ?: return
    val target = playlist.entries.getOrNull(position - 1) ?: return
    if (target.entryId == PlaylistPlaybackSession.current()?.entryId || playlistHandedOff) return
    PlaylistPlaybackSession.log.i { "jump requested to entry=${target.entryId} ($position/${playlist.entries.size})" }
    if (target.entryId == playlistUpNext?.entryId && playlistPrepared != null) {
        requestPlaylistAdvance("peek")
        return
    }
    val onJump = args.onPlaylistJump ?: return
    playlistAdvanceJob?.cancel()
    playlistHandedOff = true
    playbackEndExitRequested = true
    PlaylistPlaybackSession.playbackSpeed = sessionPlaybackSpeed
    flushWatchProgress()
    scope.launch { onJump(target.entryId) }
}

private fun PlayerScreenRuntime.onPlaylistPrepared(handoff: PlaylistHandoff) {
    PlaylistPlaybackSession.log.i {
        "next entry prepared kind=${handoff::class.simpleName} " +
            "source=${(handoff as? PlaylistHandoff.Stream)?.stream?.addonName ?: "-"} handOff=$playlistHandoffRequested"
    }
    playlistPrepared = handoff
    if (playlistHandoffRequested) handOffPlaylist(handoff)
}

private fun PlayerScreenRuntime.handOffPlaylist(handoff: PlaylistHandoff) {
    if (playlistHandedOff) return
    val onHandoff = args.onPlaylistHandoff
    playlistHandedOff = true
    PlaylistPlaybackSession.playbackSpeed = sessionPlaybackSpeed
    playbackEndExitRequested = true
    flushWatchProgress()
    if (onHandoff == null) {
        args.onPlaybackCompleted()
        return
    }
    scope.launch {
        onHandoff(handoff.resolvedForPlayback())
    }
}

/**
 * A debrid-cached stream still has to be turned into a link before a player can take it — the
 * streams screen does this for a first play, and so must the handoff. Anything that ends without a
 * directly playable URL (not cached, P2P) goes to the source list, which knows how to offer it.
 */
private suspend fun PlaylistHandoff.resolvedForPlayback(): PlaylistHandoff {
    if (this !is PlaylistHandoff.Stream) return this
    val resolved = if (DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream)) {
        when (
            val result = DirectDebridPlaybackResolver.resolveToPlayableStream(
                stream = stream,
                season = entry.seasonNumber,
                episode = entry.episodeNumber,
            )
        ) {
            is DirectDebridPlayableResult.Success -> result.stream
            else -> {
                PlaylistPlaybackSession.log.i { "debrid resolve failed ($result); falling back to the source list" }
                return PlaylistHandoff.SourceList(entry)
            }
        }
    } else {
        stream
    }
    if (resolved.playableDirectUrl == null) return PlaylistHandoff.SourceList(entry)
    return copy(stream = resolved)
}

/**
 * The HUD's playlist peek: the whole playlist, marked played / current / up next. The HUD scrolls
 * it to the playing entry and plays a row on click (see [jumpToPlaylistEntry]). The up-next row
 * shows its resolved episode when it is a random slot.
 */
internal fun buildPlaylistPeek(
    playlist: com.nuvio.app.features.playlist.Playlist?,
    currentEntryId: String?,
    upNext: PlaylistEntry?,
): Pair<String, List<PlayerControlPlaylistItem>> {
    if (playlist == null || currentEntryId == null) return "" to emptyList()
    val entries = playlist.entries
    val current = entries.indexOfFirst { it.entryId == currentEntryId }
    if (current < 0) return "" to emptyList()
    val header = "${playlist.name} · ${current + 1}/${entries.size}"
    val items = entries.mapIndexed { index, stored ->
        val entry = upNext?.takeIf { it.entryId == stored.entryId } ?: stored
        PlayerControlPlaylistItem(
            position = index + 1,
            title = when {
                entry.isUnresolvedRandom -> entry.randomSlotLabel()
                entry.isEpisode -> listOfNotNull(
                    "S${entry.seasonNumber}E${entry.episodeNumber}",
                    entry.episodeTitle?.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                else -> entry.title
            },
            subtitle = when {
                entry.isEpisode || entry.randomEpisode -> entry.title
                else -> "Movie"
            } + if (entry.randomEpisode && !entry.isUnresolvedRandom) " · Random" else "",
            state = when {
                index == current -> "current"
                entry.entryId == upNext?.entryId -> "next"
                index < current -> "played"
                else -> "upcoming"
            },
        )
    }
    return header to items
}
