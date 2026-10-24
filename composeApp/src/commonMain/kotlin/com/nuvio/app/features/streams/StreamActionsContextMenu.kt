package com.nuvio.app.features.streams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/** Handlers for a stream row's desktop right-click menu, supplied via [LocalStreamRowActions]. */
internal data class StreamRowActions(
    val onCopyUrl: (StreamItem) -> Unit,
    val onDownload: ((StreamItem) -> Unit)?,
    val onOpenInBrowser: ((StreamItem) -> Unit)? = null,
    val onOpenExternal: (StreamItem) -> Unit,
    /**
     * Grabs a whole season into the local library.
     *
     * One action, two routes, chosen by the handler: read the source's file listing in a single
     * provider call where that is possible, otherwise re-query the addon per episode. These used to
     * be separate menu entries, from when only the second existed for most sources — now that the
     * season route takes the listing whenever it can, offering both would be offering the same
     * outcome twice and asking the user to know which mechanism their provider supports.
     */
    val onDownloadSeason: ((StreamItem) -> Unit)? = null,
    /**
     * Season the stream list is being browsed for, or null when it is not a season view. Lets the
     * menu hide the pack route on a pack that covers some *other* season — that action opens this
     * specific torrent, so an S01 pack cannot serve an S03 episode.
     */
    val browsedSeason: Int? = null,
    /**
     * Whether the list is being browsed for an episode at all. Distinct from [browsedSeason], which
     * is null both for a movie and for an episode with no franchise season.
     */
    val isEpisodeView: Boolean = false,
)

internal val LocalStreamRowActions = staticCompositionLocalOf<StreamRowActions?> { null }

/**
 * Wraps a stream row so a desktop right-click shows the available stream actions, sourced from
 * [LocalStreamRowActions]. On platforms without a desktop context menu this is a plain passthrough.
 */
@Composable
internal expect fun StreamRowContextMenu(
    stream: StreamItem,
    enabled: Boolean,
    /**
     * Content type and language preferences for the score breakdown the menu shows. Required, not
     * defaulted: the menu used to score with the implicit MOVIE context, so every episode's
     * breakdown claimed "far from preferred size" while the card's own badge — scored correctly —
     * disagreed with it.
     */
    scoreContext: StreamScoreContext,
    content: @Composable () -> Unit,
)
