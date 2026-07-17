package com.nuvio.app.features.streams

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.streams_copy_link
import nuvio.composeapp.generated.resources.streams_download_file
import nuvio.composeapp.generated.resources.streams_open_external_player
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun StreamRowContextMenu(
    stream: StreamItem,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    val actions = LocalStreamRowActions.current
    val copyLabel = stringResource(Res.string.streams_copy_link)
    val downloadLabel = stringResource(Res.string.streams_download_file)
    val externalLabel = stringResource(Res.string.streams_open_external_player)
    var menuOpen by remember(stream) { mutableStateOf(false) }
    var clickPosition by remember(stream) { mutableStateOf(Offset.Zero) }
    var rowCoordinates by remember(stream) { mutableStateOf<LayoutCoordinates?>(null) }

    if (actions == null || !enabled) {
        content()
        return
    }

    Box(
        modifier = Modifier
            .onGloballyPositioned { rowCoordinates = it }
            .pointerInput(stream, enabled) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && event.button == PointerButton.Secondary) {
                            clickPosition = event.changes.firstOrNull()?.position ?: Offset.Zero
                            event.changes.forEach { it.consume() }
                            menuOpen = true
                        }
                    }
                }
            },
    ) {
        content()

        if (menuOpen) {
            val windowPosition = rowCoordinates
                ?.localToWindow(clickPosition)
                ?.round()
                ?: IntOffset.Zero
            Popup(
                popupPositionProvider = remember(windowPosition) {
                    StreamMenuPositionProvider(windowPosition)
                },
                onDismissRequest = { menuOpen = false },
                properties = PopupProperties(focusable = true),
            ) {
                StreamActionsMenu(
                    copyLabel = copyLabel,
                    downloadLabel = downloadLabel,
                    externalLabel = externalLabel,
                    onCopy = {
                        menuOpen = false
                        actions.onCopyUrl(stream)
                    },
                    onDownload = actions.onDownload?.let { download ->
                        {
                            menuOpen = false
                            download(stream)
                        }
                    },
                    onExternal = {
                        menuOpen = false
                        actions.onOpenExternal(stream)
                    },
                )
            }
        }
    }
}

private class StreamMenuPositionProvider(
    private val windowPosition: IntOffset,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val margin = 8
        return IntOffset(
            x = windowPosition.x.coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)),
            y = windowPosition.y.coerceIn(margin, (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)),
        )
    }
}

@Composable
private fun StreamActionsMenu(
    copyLabel: String,
    downloadLabel: String,
    externalLabel: String,
    onCopy: () -> Unit,
    onDownload: (() -> Unit)?,
    onExternal: () -> Unit,
) {
    Surface(
        modifier = Modifier.width(244.dp),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFA18191D),
        contentColor = Color.White,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
        shadowElevation = 16.dp,
    ) {
        Column(modifier = Modifier.padding(6.dp)) {
            StreamActionRow(copyLabel, Icons.Rounded.ContentCopy, onCopy)
            if (onDownload != null) {
                StreamActionRow(downloadLabel, Icons.Rounded.CloudDownload, onDownload)
            }
            StreamActionRow(externalLabel, Icons.AutoMirrored.Rounded.OpenInNew, onExternal)
        }
    }
}

@Composable
private fun StreamActionRow(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .background(
                color = if (hovered) Color.White.copy(alpha = 0.10f) else Color.Transparent,
                shape = RoundedCornerShape(7.dp),
            )
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        when (awaitPointerEvent().type) {
                            PointerEventType.Enter -> hovered = true
                            PointerEventType.Exit -> hovered = false
                        }
                    }
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (hovered) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.76f),
        )
        Text(
            text = label,
            modifier = Modifier.padding(start = 11.dp),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
        )
    }
}
