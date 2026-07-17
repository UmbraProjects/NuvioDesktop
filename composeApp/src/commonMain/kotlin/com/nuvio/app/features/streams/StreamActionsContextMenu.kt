package com.nuvio.app.features.streams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/** Handlers for a stream row's desktop right-click menu, supplied via [LocalStreamRowActions]. */
internal data class StreamRowActions(
    val onCopyUrl: (StreamItem) -> Unit,
    val onDownload: ((StreamItem) -> Unit)?,
    val onOpenExternal: (StreamItem) -> Unit,
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
    content: @Composable () -> Unit,
)
