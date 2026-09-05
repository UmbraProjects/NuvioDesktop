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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.withDuplicateSafeLazyKeys
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.streams.StreamBadgeSettingsRepository
import com.nuvio.app.features.streams.StreamCard
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamScoreContext
import com.nuvio.app.features.streams.StreamScoreContexts
import com.nuvio.app.features.streams.StreamsUiState
import com.nuvio.app.features.streams.isSelectableForPlayback
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.material.icons.rounded.Refresh

@Composable
fun PlayerSourcesPanel(
    visible: Boolean,
    streamsUiState: StreamsUiState,
    currentStreamIdentityKey: String?,
    currentStreamUrl: String?,
    currentStreamName: String?,
    onFilterSelected: (String?) -> Unit,
    onStreamSelected: (StreamItem) -> Unit,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    // Only used to pick movie vs episode thresholds for the optional score badge.
    isEpisode: Boolean = false,
    // Same, for the animation-relaxed size floor.
    contentId: String? = null,
    contentType: String? = null,
) {
    val tokens = MaterialTheme.nuvio
    // Hoisted out of the row: it is the same for every card, and building one per row per
    // recomposition re-reads the player settings and the anime cache once per visible source.
    val scoreContext = remember(isEpisode, contentId, contentType) {
        StreamScoreContexts.forPlayback(
            isEpisode = isEpisode,
            contentId = contentId,
            contentType = contentType,
        )
    }
    val debridSettings by remember {
        DebridSettingsRepository.ensureLoaded()
        DebridSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val streamBadgeSettings by remember {
        StreamBadgeSettingsRepository.ensureLoaded()
        StreamBadgeSettingsRepository.uiState
    }.collectAsStateWithLifecycle()

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(NuvioTokens.Motion.normalMillis)),
        exit = fadeOut(tween(NuvioTokens.Motion.normalMillis)),
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss,
                )
                .background(tokens.colors.overlayScrim.copy(alpha = tokens.opacity.medium)),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(tween(NuvioTokens.Motion.sheetEnterMillis)) { it / 3 } +
                    fadeIn(tween(NuvioTokens.Motion.sheetEnterMillis)),
                exit = slideOutVertically(tween(NuvioTokens.Motion.sheetExitMillis)) { it / 3 } +
                    fadeOut(tween(NuvioTokens.Motion.sheetExitMillis)),
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(max = tokens.components.playerPanelMaxWidth)
                        .fillMaxWidth(0.92f)
                        .heightIn(max = tokens.components.dialogMaxWidth + NuvioTokens.Space.s40)
                        .clip(tokens.shapes.playerPanel)
                        .background(tokens.colors.surfaceSheet)
                        .border(tokens.borders.thin, tokens.colors.borderDefault, tokens.shapes.playerPanel)
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
                                .padding(horizontal = tokens.spacing.sheetPadding, vertical = tokens.spacing.cardPadding),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(Res.string.compose_player_panel_sources),
                                color = tokens.colors.textPrimary,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap)) {
                                PanelChipButton(
                                    label = stringResource(Res.string.compose_action_reload),
                                    icon = Icons.Rounded.Refresh,
                                    onClick = onReload,
                                )
                                PanelChipButton(
                                    label = stringResource(Res.string.action_close),
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
                                    .padding(horizontal = tokens.spacing.sheetPadding)
                                    .padding(bottom = tokens.spacing.listGap),
                                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
                            ) {
                                AddonFilterChip(
                                    label = stringResource(Res.string.collections_tab_all),
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
                                        .padding(vertical = NuvioTokens.Space.s40),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        color = tokens.colors.accent,
                                        strokeWidth = tokens.borders.medium,
                                        modifier = Modifier.size(tokens.icons.lg + NuvioTokens.Space.s4),
                                    )
                                }
                            }

                            streamsUiState.allStreams.isEmpty() -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = NuvioTokens.Space.s40),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = stringResource(Res.string.compose_player_no_streams_found),
                                        color = tokens.colors.textMuted,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }

                            else -> {
                                // The playing row is resolved once for the whole list rather than
                                // asked of each row: one file offered by two addons (or listed
                                // twice by one) shares an identity key, and a per-row test marked
                                // every copy "Playing".
                                val allStreams = streamsUiState.filteredGroups.flatMap { it.streams }
                                val currentStream = findCurrentStream(
                                    streams = allStreams,
                                    currentIdentityKey = currentStreamIdentityKey,
                                    currentUrl = currentStreamUrl,
                                    currentName = currentStreamName,
                                )
                                val streams = prioritizeCurrentItem(allStreams) { it === currentStream }
                                // Keys describe the row, not its position: sources keep arriving
                                // while the panel is open, and an index in the key made every
                                // arrival re-key the whole list and jerk the scroll position.
                                // Duplicate identities still have to be split apart — a repeated
                                // key crashes the app — so collisions take an occurrence suffix.
                                val streamKeys = remember(streams) {
                                    streams.withDuplicateSafeLazyKeys { stream ->
                                        "${stream.addonId}::${stream.url ?: stream.infoHash ?: stream.clientResolve?.infoHash ?: stream.name}"
                                    }
                                }
                                LazyColumn(
                                    modifier = Modifier.padding(horizontal = tokens.spacing.cardPadding),
                                    verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = tokens.spacing.cardPadding),
                                ) {
                                    items(
                                        items = streamKeys,
                                        key = { it.lazyKey },
                                    ) { entry ->
                                        val stream = entry.value
                                        val isCurrent = stream === currentStream
                                        StreamCard(
                                            stream = stream,
                                            enabled = stream.isSelectableForPlayback(debridSettings.canResolvePlayableLinks),
                                            appendInstantServiceToDefaultName = debridSettings.canResolvePlayableLinks &&
                                                !debridSettings.hasCustomStreamFormatting,
                                            showFileSizeBadges = streamBadgeSettings.showFileSizeBadges,
                                            showAddonLogo = streamBadgeSettings.showAddonLogo,
                                            badgePlacement = streamBadgeSettings.badgePlacement,
                                            isCurrent = isCurrent,
                                            currentLabel = stringResource(Res.string.compose_player_playing),
                                            scoreContext = scoreContext,
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
internal fun AddonFilterChip(
    label: String,
    isSelected: Boolean,
    isLoading: Boolean = false,
    hasError: Boolean = false,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio

    Box(
        modifier = Modifier
            .clip(tokens.shapes.chip)
            .background(
                when {
                    isSelected -> tokens.colors.overlaySelected
                    else -> tokens.colors.surfacePopover
                },
            )
            .then(
                if (isSelected) {
                    Modifier.border(tokens.borders.thin, tokens.colors.borderSelected, tokens.shapes.chip)
                } else {
                    Modifier.border(tokens.borders.thin, tokens.colors.borderSubtle, tokens.shapes.chip)
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = NuvioTokens.Space.s14, vertical = NuvioTokens.Space.s8),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = tokens.colors.accent,
                    strokeWidth = tokens.borders.thin + NuvioTokens.Space.hairline,
                    modifier = Modifier.size(NuvioTokens.Icon.xs),
                )
            }
            Text(
                text = label,
                color = when {
                    hasError -> tokens.colors.danger
                    isSelected -> tokens.colors.textPrimary
                    else -> tokens.colors.textMuted
                },
                fontSize = NuvioTokens.Type.labelSm,
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
    val tokens = MaterialTheme.nuvio

    Box(
        modifier = Modifier
            .clip(tokens.shapes.compactCard)
            .background(tokens.colors.surfacePopover)
            .border(tokens.borders.thin, tokens.colors.borderSubtle, tokens.shapes.compactCard)
            .clickable(onClick = onClick)
            .padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s6),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tokens.colors.textMuted,
                    modifier = Modifier.size(NuvioTokens.Space.s14),
                )
            }
            Text(
                text = label,
                color = tokens.colors.textMuted,
                fontSize = NuvioTokens.Type.labelSm,
            )
        }
    }
}

/**
 * The single stream that is playing, or null when the list holds none of them.
 *
 * Resolved for a whole list rather than asked of each row on its own. An identity key describes a
 * *file*, so a release offered by two addons — or listed twice by one — gives several rows the same
 * key, and a per-row predicate answered "yes" for every copy.
 *
 * The tiers are tried in order and the first that matches anything wins. Identity stays
 * authoritative: it is never overridden by a weaker signal, only fallen back on when it matches
 * nothing at all. That case is the list having been re-fetched since playback started — proxying
 * addons such as AIOStreams re-sign their URLs each time, so a key minted at launch can go missing
 * from a later list and leave no row marked. Falling through is safe now only because a second row
 * can no longer be marked as a result.
 */
internal fun findCurrentStream(
    streams: List<StreamItem>,
    currentIdentityKey: String?,
    currentUrl: String?,
    currentName: String?,
): StreamItem? {
    if (currentIdentityKey != null) {
        streams.firstOrNull { it.playerSourceIdentityKey() == currentIdentityKey }?.let { return it }
    }
    if (!currentUrl.isNullOrBlank()) {
        streams.firstOrNull { it.playableDirectUrl == currentUrl }?.let { return it }
    }
    if (!currentName.isNullOrBlank()) {
        streams.firstOrNull { it.streamLabel.equals(currentName, ignoreCase = true) }?.let { return it }
    }
    return null
}

/** Moves the active entry to the front without changing the relative order of any other entry. */
internal fun <T> prioritizeCurrentItem(items: List<T>, isCurrent: (T) -> Boolean): List<T> {
    val currentIndex = items.indexOfFirst(isCurrent)
    if (currentIndex <= 0) return items
    return buildList(items.size) {
        add(items[currentIndex])
        addAll(items.subList(0, currentIndex))
        addAll(items.subList(currentIndex + 1, items.size))
    }
}
