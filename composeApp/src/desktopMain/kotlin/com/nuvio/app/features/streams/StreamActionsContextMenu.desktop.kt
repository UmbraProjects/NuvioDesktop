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
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import nuvio.composeapp.generated.resources.streams_score_label
import nuvio.composeapp.generated.resources.streams_score_more
import nuvio.composeapp.generated.resources.streams_score_no_matches
import nuvio.composeapp.generated.resources.streams_download_file
import nuvio.composeapp.generated.resources.streams_download_season
import nuvio.composeapp.generated.resources.streams_inspect_source
import nuvio.composeapp.generated.resources.streams_open_external_player
import nuvio.composeapp.generated.resources.streams_open_in_browser
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun StreamRowContextMenu(
    stream: StreamItem,
    enabled: Boolean,
    scoreContext: StreamScoreContext,
    content: @Composable () -> Unit,
) {
    val actions = LocalStreamRowActions.current
    val copyLabel = stringResource(Res.string.streams_copy_link)
    val downloadLabel = stringResource(Res.string.streams_download_file)
    val downloadSeasonLabel = stringResource(Res.string.streams_download_season)
    val inspectSourceLabel = stringResource(Res.string.streams_inspect_source)
    val openInBrowserLabel = stringResource(Res.string.streams_open_in_browser)
    val externalLabel = stringResource(Res.string.streams_open_external_player)
    var menuOpen by remember(stream) { mutableStateOf(false) }
    var clickPosition by remember(stream) { mutableStateOf(Offset.Zero) }
    var rowCoordinates by remember(stream) { mutableStateOf<LayoutCoordinates?>(null) }
    // Explains why this stream ranked where it did, from the same computation that ranked it.
    val scoreProfile by StreamScoreRepository.uiState.collectAsState()
    val scoreBreakdown = remember(stream, scoreProfile, scoreContext) {
        if (scoreProfile.enabled && stream.isScorableStream) {
            StreamScorer.score(stream, scoreProfile, scoreContext)
        } else {
            null
        }
    }
    // Only a pack holds a season, so the row is offered per stream rather than per screen.
    val packTraits = remember(stream) { StreamTraitDetector.detect(stream) }
    val isSeasonPack = packTraits.isSeasonPack && actions?.isEpisodeView == true
    val offersPackRoute = stream.offersSeasonPackRoute(
        traits = packTraits,
        browsedSeason = actions?.browsedSeason,
        isEpisodeView = actions?.isEpisodeView == true,
    )
    // The same action, offered on any row we could open on the provider. Detection decides the
    // *label*, not the availability: when we are confident it says what it will find, and when we
    // are not it says "inspect", which is exactly the fallback for detection getting it wrong.
    val canInspectSource = stream.canInspectSource

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
                    scoreBreakdown = scoreBreakdown,
                    copyLabel = copyLabel,
                    downloadLabel = downloadLabel,
                    // One entry, but its wording still tracks what will happen: a detected pack is a
                    // season grab, while a row we only know we *can* open is an inspection — the
                    // user asked for the latter precisely because detection is fallible, and calling
                    // it "download season" would hide that it is also how you check.
                    downloadSeasonLabel = if (isSeasonPack) downloadSeasonLabel else inspectSourceLabel,
                    openInBrowserLabel = openInBrowserLabel,
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
                    // Shown when either route could work: a detected pack (the per-episode search
                    // needs nothing but a release identity) or a source we can list outright.
                    onDownloadSeason = actions.onDownloadSeason
                        ?.takeIf { isSeasonPack || canInspectSource }
                        ?.let { downloadSeason ->
                            {
                                menuOpen = false
                                downloadSeason(stream)
                            }
                        },
                    onOpenInBrowser = actions.onOpenInBrowser?.let { openInBrowser ->
                        {
                            menuOpen = false
                            openInBrowser(stream)
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
    scoreBreakdown: StreamScore?,
    copyLabel: String,
    downloadLabel: String,
    downloadSeasonLabel: String,
    openInBrowserLabel: String,
    externalLabel: String,
    onCopy: () -> Unit,
    onDownload: (() -> Unit)?,
    onDownloadSeason: (() -> Unit)?,
    onOpenInBrowser: (() -> Unit)?,
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
            if (onDownloadSeason != null) {
                StreamActionRow(downloadSeasonLabel, Icons.Rounded.LibraryAdd, onDownloadSeason)
            }
            if (onOpenInBrowser != null) {
                StreamActionRow(openInBrowserLabel, Icons.Rounded.Language, onOpenInBrowser)
            }
            StreamActionRow(externalLabel, Icons.AutoMirrored.Rounded.OpenInNew, onExternal)
            // Below the actions on purpose: the breakdown is read-only, so the clickable rows stay
            // closest to the cursor where the menu opens.
            scoreBreakdown?.let { score ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 5.dp, vertical = 4.dp)
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.10f)),
                )
                StreamScoreBreakdown(score)
            }
        }
    }
}

/**
 * The per-trait contributions behind a stream's score, biggest absolute effect first — the entries
 * that actually decided the ranking are the ones worth reading.
 */
@Composable
private fun StreamScoreBreakdown(score: StreamScore) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Res.string.streams_score_label),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.62f),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = score.total.signedLabel(),
                style = MaterialTheme.typography.titleSmall,
                color = if (score.total >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        if (score.components.isEmpty()) {
            Text(
                text = stringResource(Res.string.streams_score_no_matches),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        // Largest contributions first, but the rows must always reconcile with the total above:
        // anything past the cap is summarised in a final "+N more" line rather than silently
        // dropped. Previously a truncated trait just vanished, so the visible rows added up to less
        // than the score and there was no way to tell a missing trait from a trait that never fired.
        val ordered = score.components.sortedByDescending { kotlin.math.abs(it.points) }
        val shown = ordered.take(SCORE_BREAKDOWN_LIMIT)
        val hidden = ordered.drop(SCORE_BREAKDOWN_LIMIT)

        shown.forEach { component ->
            ScoreBreakdownRow(
                label = stringResource(component.trait.labelRes),
                points = component.points,
            )
        }
        if (hidden.isNotEmpty()) {
            ScoreBreakdownRow(
                label = stringResource(Res.string.streams_score_more, hidden.size),
                points = hidden.sumOf { it.points },
                muted = true,
            )
        }
    }
}

@Composable
private fun ScoreBreakdownRow(label: String, points: Int, muted: Boolean = false) {
    val alpha = if (muted) 0.55f else 0.78f
    Row(modifier = Modifier.fillMaxWidth().padding(top = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = alpha),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = points.signedLabel(),
            style = MaterialTheme.typography.bodySmall,
            color = if (points >= 0) Color.White.copy(alpha = alpha) else MaterialTheme.colorScheme.error,
        )
    }
}

private fun Int.signedLabel(): String = if (this > 0) "+$this" else toString()

/** Rows before the breakdown collapses the remainder into a single "+N more" line. */
private const val SCORE_BREAKDOWN_LIMIT = 10

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
