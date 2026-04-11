package com.nuvio.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsUiState

@Composable
fun PlayerSourcesPanel(
    visible: Boolean,
    streamsUiState: StreamsUiState,
    currentStreamUrl: String?,
    currentStreamName: String?,
    onFilterSelected: (String?) -> Unit,
    onStreamSelected: (StreamItem) -> Unit,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(200)),
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss,
                )
                .background(colorScheme.scrim.copy(alpha = 0.52f)),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(tween(300)) { it / 3 } + fadeIn(tween(300)),
                exit = slideOutVertically(tween(250)) { it / 3 } + fadeOut(tween(250)),
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .fillMaxWidth(0.92f)
                        .heightIn(max = 600.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(colorScheme.surface)
                        .border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.8f), RoundedCornerShape(24.dp))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = {},
                        ),
                ) {
                    Column {
                        // Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Sources",
                                color = colorScheme.onSurface,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PanelChipButton(
                                    label = "Reload",
                                    icon = Icons.Rounded.Refresh,
                                    onClick = onReload,
                                )
                                PanelChipButton(
                                    label = "Close",
                                    onClick = onDismiss,
                                )
                            }
                        }

                        // Addon filter chips
                        val addonNames = remember(streamsUiState.groups) {
                            streamsUiState.groups.map { it.addonName }.distinct()
                        }
                        if (addonNames.size > 1) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 20.dp)
                                    .padding(bottom = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                AddonFilterChip(
                                    label = "All",
                                    isSelected = streamsUiState.selectedFilter == null,
                                    onClick = { onFilterSelected(null) },
                                )
                                addonNames.forEach { addon ->
                                    val group = streamsUiState.groups.firstOrNull { it.addonName == addon }
                                    AddonFilterChip(
                                        label = addon,
                                        isSelected = streamsUiState.selectedFilter == group?.addonId,
                                        isLoading = group?.isLoading == true,
                                        hasError = group?.error != null,
                                        onClick = { onFilterSelected(group?.addonId) },
                                    )
                                }
                            }
                        }

                        // Content
                        when {
                            streamsUiState.isAnyLoading && streamsUiState.allStreams.isEmpty() -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 40.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        color = colorScheme.primary,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(28.dp),
                                    )
                                }
                            }

                            streamsUiState.allStreams.isEmpty() -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 40.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "No streams found",
                                        color = colorScheme.onSurfaceVariant,
                                        fontSize = 14.sp,
                                    )
                                }
                            }

                            else -> {
                                val streams = streamsUiState.filteredGroups.flatMap { it.streams }
                                LazyColumn(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
                                ) {
                                    itemsIndexed(
                                        items = streams,
                                        key = { index, stream -> "${stream.addonId}::${index}::${stream.url ?: stream.infoHash ?: stream.name}" },
                                    ) { _, stream ->
                                        val isCurrent = isCurrentStream(
                                            stream = stream,
                                            currentUrl = currentStreamUrl,
                                            currentName = currentStreamName,
                                        )
                                        SourceStreamRow(
                                            stream = stream,
                                            isCurrent = isCurrent,
                                            onClick = { onStreamSelected(stream) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceStreamRow(
    stream: StreamItem,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isCurrent) colorScheme.primaryContainer.copy(alpha = 0.55f) else Color.Transparent,
            )
            .then(
                if (isCurrent) {
                    Modifier.border(1.dp, colorScheme.primary.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stream.streamLabel,
                    color = colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isCurrent) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(colorScheme.primaryContainer)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = "Playing",
                            color = colorScheme.onPrimaryContainer,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            stream.streamSubtitle?.let { subtitle ->
                if (subtitle != stream.streamLabel) {
                    Text(
                        text = subtitle,
                        color = colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = stream.addonName,
                color = colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                fontStyle = FontStyle.Italic,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isCurrent) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "Currently playing",
                tint = colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
internal fun AddonFilterChip(
    label: String,
    isSelected: Boolean,
    isLoading: Boolean = false,
    hasError: Boolean = false,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                when {
                    isSelected -> colorScheme.primaryContainer
                    else -> colorScheme.surfaceVariant.copy(alpha = 0.92f)
                },
            )
            .then(
                if (isSelected) {
                    Modifier.border(1.dp, colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                } else {
                    Modifier.border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = colorScheme.primary,
                    strokeWidth = 1.5.dp,
                    modifier = Modifier.size(12.dp),
                )
            }
            Text(
                text = label,
                color = when {
                    hasError -> colorScheme.error
                    isSelected -> colorScheme.onPrimaryContainer
                    else -> colorScheme.onSurfaceVariant
                },
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

@Composable
internal fun PanelChipButton(
    label: String,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val colorScheme = MaterialTheme.colorScheme

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(colorScheme.surfaceVariant.copy(alpha = 0.9f))
            .border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = label,
                color = colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
    }
}

private fun isCurrentStream(
    stream: StreamItem,
    currentUrl: String?,
    currentName: String?,
): Boolean {
    if (currentUrl != null && stream.directPlaybackUrl == currentUrl) return true
    if (currentName != null && stream.streamLabel.equals(currentName, ignoreCase = true) &&
        stream.directPlaybackUrl == currentUrl
    ) return true
    return false
}
