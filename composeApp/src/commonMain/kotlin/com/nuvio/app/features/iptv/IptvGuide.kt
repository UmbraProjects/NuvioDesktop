package com.nuvio.app.features.iptv

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.nuvio
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.iptv_channel_number
import nuvio.composeapp.generated.resources.iptv_guide_no_info
import org.jetbrains.compose.resources.stringResource

/** Width of one minute on the guide's time axis. */
private val GuideMinuteWidth: Dp = 6.dp
private val GuideChannelColumnWidth: Dp = 220.dp
private val GuideRowHeight: Dp = 64.dp

/** Room a title keeps when it slides along a cell to stay in view. */
private val GuideMinTitleWidth: Dp = 140.dp
private const val GUIDE_SLOT_MS = 30 * 60_000L

/** Hours shown before and after the current half hour. Past ones are there to read, not replay. */
private const val GUIDE_HOURS_BEFORE = 2
private const val GUIDE_HOURS_AFTER = 12

private val GuideTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun guideTime(ms: Long): String = GuideTimeFormat.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/**
 * The programme guide: [channels] down the side, a time axis across that all rows scroll together,
 * and a line at the current time. A click on any programme plays its channel live; catch-up of
 * past programmes is not supported yet.
 *
 * Programmes come from the XMLTV guide only (`IptvEpgRepository.guideEntries`); a channel the guide
 * does not cover gets one "no information" bar.
 */
@Composable
internal fun IptvGuideGrid(
    playlist: IptvPlaylist,
    channels: List<IptvChannel>,
    now: Long,
    guideVersion: Int,
    listState: LazyListState,
    focusedIndex: Int,
    onPlay: (IptvChannel) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val density = LocalDensity.current
    // Anchored on the half hour so the axis labels sit on round times; re-anchored when it moves.
    val slotStart = now - Math.floorMod(now, GUIDE_SLOT_MS)
    val windowStart = slotStart - GUIDE_HOURS_BEFORE * 3_600_000L
    val windowEnd = slotStart + GUIDE_HOURS_AFTER * 3_600_000L
    val totalWidth = GuideMinuteWidth * ((windowEnd - windowStart) / 60_000L).toFloat()
    fun xFor(ms: Long): Dp = GuideMinuteWidth * ((ms.coerceIn(windowStart, windowEnd) - windowStart) / 60_000f)

    val horizontal = rememberScrollState()
    // Open with the current time a little in from the left edge.
    LaunchedEffect(windowStart) {
        val target = with(density) { (xFor(now) - GuideMinuteWidth * 30f).toPx() }.toInt().coerceAtLeast(0)
        horizontal.scrollTo(target)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Time axis.
        Row(modifier = Modifier.fillMaxWidth().height(32.dp)) {
            Spacer(Modifier.width(GuideChannelColumnWidth))
            Box(modifier = Modifier.weight(1f).fillMaxHeight().horizontalScroll(horizontal)) {
                Box(modifier = Modifier.width(totalWidth).fillMaxHeight()) {
                    var slot = windowStart
                    while (slot < windowEnd) {
                        Text(
                            text = guideTime(slot),
                            modifier = Modifier.offset(x = xFor(slot) + 6.dp).align(Alignment.CenterStart),
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.colors.textMuted,
                        )
                        slot += GUIDE_SLOT_MS
                    }
                    GuideNowLine(x = xFor(now), height = 32.dp)
                }
            }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(channels, key = { _, channel -> "guide:${playlist.id}:${channel.uid}" }) { index, channel ->
                val entries = remember(channel, guideVersion, windowStart) {
                    IptvEpgRepository.guideEntries(playlist, channel)
                        .filter { it.endMs > windowStart && it.startMs < windowEnd }
                }
                GuideRow(
                    channel = channel,
                    entries = entries,
                    now = now,
                    horizontal = horizontal,
                    totalWidth = totalWidth,
                    xFor = ::xFor,
                    keyboardFocused = index == focusedIndex,
                    onPlay = { onPlay(channel) },
                )
            }
        }
    }
}

@Composable
private fun GuideRow(
    channel: IptvChannel,
    entries: List<IptvEpgEntry>,
    now: Long,
    horizontal: ScrollState,
    totalWidth: Dp,
    xFor: (Long) -> Dp,
    keyboardFocused: Boolean,
    onPlay: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val name = channel.name.ifBlank { stringResource(Res.string.iptv_channel_number, channel.num) }
    Row(modifier = Modifier.fillMaxWidth().height(GuideRowHeight)) {
        Row(
            modifier = Modifier
                .width(GuideChannelColumnWidth)
                .fillMaxHeight()
                .padding(end = 6.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (keyboardFocused) tokens.colors.surfaceElevated else tokens.colors.surface.copy(alpha = 0.7f))
                .then(if (keyboardFocused) Modifier.border(1.5.dp, tokens.colors.borderFocus, RoundedCornerShape(10.dp)) else Modifier)
                .clickable(onClick = onPlay)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = channel.num.toString(),
                modifier = Modifier.width(34.dp),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
            Box(modifier = Modifier.size(width = 44.dp, height = 30.dp), contentAlignment = Alignment.Center) {
                if (channel.logo != null) {
                    NuvioAsyncImage(model = channel.logo, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                } else {
                    Icon(Icons.Rounded.LiveTv, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxHeight().horizontalScroll(horizontal)) {
            Box(modifier = Modifier.width(totalWidth).fillMaxHeight()) {
                if (entries.isEmpty()) {
                    // Starts where the guide opens (half an hour before now), so its label is in view.
                    val start = xFor(now - 30 * 60_000L)
                    GuideCell(
                        title = stringResource(Res.string.iptv_guide_no_info),
                        timeLabel = "",
                        x = start,
                        width = totalWidth - start,
                        live = false,
                        past = false,
                        scroll = horizontal,
                        onClick = onPlay,
                    )
                } else {
                    entries.forEach { entry ->
                        val start = xFor(entry.startMs)
                        val width = (xFor(entry.endMs) - start).coerceAtLeast(GuideMinuteWidth * 4f)
                        GuideCell(
                            title = entry.title,
                            timeLabel = guideTime(entry.startMs),
                            x = start,
                            width = width,
                            live = entry.startMs <= now && entry.endMs > now,
                            past = entry.endMs <= now,
                            scroll = horizontal,
                            onClick = onPlay,
                        )
                    }
                }
                GuideNowLine(x = xFor(now), height = GuideRowHeight)
            }
        }
    }
}

@Composable
private fun GuideCell(
    title: String,
    timeLabel: String,
    x: Dp,
    width: Dp,
    live: Boolean,
    past: Boolean,
    scroll: ScrollState,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier
            .offset(x = x)
            .width(width)
            .fillMaxHeight()
            .padding(end = 3.dp)
            .clip(shape)
            .background(
                when {
                    focused || hovered -> tokens.colors.surfaceElevated
                    live -> tokens.colors.accent.copy(alpha = 0.22f)
                    else -> tokens.colors.surface.copy(alpha = if (past) 0.35f else 0.6f)
                },
            )
            .then(if (focused) Modifier.border(1.5.dp, tokens.colors.borderFocus, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            // A programme that began left of the visible area keeps its title in view: the text
            // slides right with the scroll, up to the cell's end. Read in the layout phase, so
            // scrolling moves it without recomposing every cell.
            .offset {
                val hiddenPx = scroll.value - x.roundToPx()
                val roomPx = (width.roundToPx() - GuideMinTitleWidth.roundToPx()).coerceAtLeast(0)
                IntOffset(hiddenPx.coerceIn(0, roomPx), 0)
            },
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal,
            color = if (past) tokens.colors.textMuted else tokens.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (timeLabel.isNotEmpty()) {
            Text(
                text = timeLabel,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun GuideNowLine(x: Dp, height: Dp) {
    Box(
        modifier = Modifier
            .offset(x = x - 1.dp)
            .width(2.dp)
            .height(height)
            .background(MaterialTheme.nuvio.colors.accent),
    )
}
