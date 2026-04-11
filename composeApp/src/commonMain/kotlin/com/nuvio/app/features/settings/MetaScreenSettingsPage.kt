package com.nuvio.app.features.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioActionLabel
import com.nuvio.app.features.details.MetaEpisodeCardStyle
import com.nuvio.app.features.details.MetaScreenSectionItem
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import com.nuvio.app.features.details.MetaScreenSettingsUiState
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

internal fun LazyListScope.metaScreenSettingsContent(
    isTablet: Boolean,
    uiState: MetaScreenSettingsUiState,
) {
    item {
        SettingsSection(
            title = "APPEARANCE",
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = "Cinematic Background",
                    description = "Blurred backdrop behind content, similar to stream screen.",
                    checked = uiState.cinematicBackground,
                    isTablet = isTablet,
                    onCheckedChange = { MetaScreenSettingsRepository.setCinematicBackground(it) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = "Tab Layout",
                    description = "Group sections into tabs like the TV app. Assign up to 3 sections per tab group.",
                    checked = uiState.tabLayout,
                    isTablet = isTablet,
                    onCheckedChange = { MetaScreenSettingsRepository.setTabLayout(it) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                MetaEpisodeCardStyleSelector(
                    isTablet = isTablet,
                    selectedStyle = uiState.episodeCardStyle,
                    onStyleSelected = MetaScreenSettingsRepository::setEpisodeCardStyle,
                )
            }
        }
    }
    item {
        SettingsSection(
            title = "SECTIONS",
            isTablet = isTablet,
            actions = {
                NuvioActionLabel(
                    text = "Reset",
                    onClick = MetaScreenSettingsRepository::resetToDefaults,
                )
            },
        ) {
            SettingsGroup(isTablet = isTablet) {
                MetaSectionReorderableList(
                    items = uiState.items,
                    isTablet = isTablet,
                    tabLayout = uiState.tabLayout,
                )
            }
        }
    }
}

@Composable
private fun MetaSectionReorderableList(
    items: List<MetaScreenSectionItem>,
    isTablet: Boolean,
    tabLayout: Boolean,
) {
    val hapticFeedback = LocalHapticFeedback.current
    val lazyListState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(
        lazyListState = lazyListState,
    ) { from, to ->
        MetaScreenSettingsRepository.moveByIndex(from.index, to.index)
        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    // Count members per group for enforcing max 3
    val groupCounts: Map<Int, Int> = if (tabLayout) {
        items.filter { it.tabGroup != null }.groupBy { it.tabGroup!! }.mapValues { it.value.size }
    } else {
        emptyMap()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = if (isTablet) 820.dp else 640.dp),
        state = lazyListState,
    ) {
        itemsIndexed(items, key = { _, item -> item.key.name }) { index, item ->
            ReorderableItem(reorderableLazyListState, key = item.key.name) { isDragging ->
                val elevation by animateDpAsState(if (isDragging) 4.dp else 0.dp)

                Surface(shadowElevation = elevation) {
                    Column {
                        if (index > 0) {
                            SettingsGroupDivider(isTablet = isTablet)
                        }
                        MetaSectionRow(
                            item = item,
                            isTablet = isTablet,
                            tabLayout = tabLayout,
                            groupCounts = groupCounts,
                            onEnabledChange = { MetaScreenSettingsRepository.setEnabled(item.key, it) },
                            onTabGroupChange = { MetaScreenSettingsRepository.setTabGroup(item.key, it) },
                            dragHandleScope = this@ReorderableItem,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetaSectionRow(
    item: MetaScreenSectionItem,
    isTablet: Boolean,
    tabLayout: Boolean,
    groupCounts: Map<Int, Int>,
    onEnabledChange: (Boolean) -> Unit,
    onTabGroupChange: (Int?) -> Unit,
    dragHandleScope: ReorderableCollectionItemScope,
) {
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val verticalPadding = if (isTablet) 18.dp else 16.dp
    val hapticFeedback = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (item.enabled) "Visible" else "Hidden",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (tabLayout && item.tabGroup != null) {
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                        )
                        Text(
                            text = "Tab Group ${item.tabGroup}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            Switch(
                checked = item.enabled,
                onCheckedChange = onEnabledChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    uncheckedTrackColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
            IconButton(
                modifier = with(dragHandleScope) {
                    Modifier.draggableHandle(
                        onDragStarted = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDragStopped = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                    )
                },
                onClick = {},
            ) {
                Icon(
                    Icons.Rounded.Menu,
                    contentDescription = "Reorder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        
        AnimatedVisibility(
            visible = tabLayout && item.enabled && item.key.canBeTabbed,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            FlowRow(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TabGroupChip(
                    label = "None",
                    selected = item.tabGroup == null,
                    onClick = { onTabGroupChange(null) },
                )
                for (groupId in 1..3) {
                    val currentCount = groupCounts[groupId] ?: 0
                    val isSelected = item.tabGroup == groupId
                    val isFull = currentCount >= 3 && !isSelected
                    TabGroupChip(
                        label = "Group $groupId",
                        selected = isSelected,
                        enabled = !isFull,
                        onClick = { onTabGroupChange(groupId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TabGroupChip(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

@Composable
private fun MetaEpisodeCardStyleSelector(
    isTablet: Boolean,
    selectedStyle: MetaEpisodeCardStyle,
    onStyleSelected: (MetaEpisodeCardStyle) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = if (isTablet) 18.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Episode Cards",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Choose how episodes are rendered on the metadata screen.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MetaEpisodeCardStyle.entries.forEach { style ->
                Box(modifier = Modifier.weight(1f)) {
                    MetaEpisodeCardStyleOption(
                        style = style,
                        selected = selectedStyle == style,
                        isTablet = isTablet,
                        onClick = { onStyleSelected(style) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MetaEpisodeCardStyleOption(
    style: MetaEpisodeCardStyle,
    selected: Boolean,
    isTablet: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(148.dp),
                contentAlignment = Alignment.Center,
            ) {
                MetaEpisodeCardStylePreview(
                    style = style,
                    isSelected = selected,
                )
            }
            Text(
                text = if (style == MetaEpisodeCardStyle.Horizontal) "Horizontal" else "List",
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (style == MetaEpisodeCardStyle.Horizontal) {
                    "Backdrop-style row cards"
                } else {
                    "Detail-first stacked cards"
                },
                style = if (isTablet) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MetaEpisodeCardStylePreview(
    style: MetaEpisodeCardStyle,
    isSelected: Boolean,
) {
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    }
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (style) {
            MetaEpisodeCardStyle.Horizontal -> {
                Box(
                    modifier = Modifier
                        .width(128.dp)
                        .height(80.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(26.dp)
                            .align(Alignment.BottomCenter)
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.36f)),
                    )
                    Box(
                        modifier = Modifier
                            .width(42.dp)
                            .height(7.dp)
                            .align(Alignment.TopStart)
                            .padding(start = 6.dp, top = 6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.26f)),
                    )
                    Box(
                        modifier = Modifier
                            .width(72.dp)
                            .height(6.dp)
                            .align(Alignment.BottomStart)
                            .padding(start = 8.dp, bottom = 8.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)),
                    )
                }
            }

            MetaEpisodeCardStyle.List -> {
                Row(
                    modifier = Modifier
                        .width(132.dp)
                        .height(78.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f)),
                ) {
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .height(78.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.82f)
                                .height(8.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f)),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.52f)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
                        )
                    }
                }
            }
        }
    }
}