package com.nuvio.app.features.settings

import nuvio.composeapp.generated.resources.settings_cw_seed_nuvio_description
import nuvio.composeapp.generated.resources.settings_cw_seed_nuvio_title
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.tracking.ContinueWatchingSource
import com.nuvio.app.features.tracking.ContinueWatchingSourceRepository
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.simkl.SIMKL_CW_DAYS_CAP_ALL
import com.nuvio.app.features.simkl.SimklSettingsRepository
import com.nuvio.app.features.trakt.TRAKT_CONTINUE_WATCHING_DAYS_CAP_ALL
import com.nuvio.app.features.trakt.TraktContinueWatchingDaysOptions
import com.nuvio.app.features.trakt.TraktSettingsRepository
import com.nuvio.app.features.trakt.normalizeTraktContinueWatchingDaysCap
import nuvio.composeapp.generated.resources.settings_cw_source_local
import nuvio.composeapp.generated.resources.settings_cw_source_mdblist
import nuvio.composeapp.generated.resources.settings_cw_source_not_connected
import nuvio.composeapp.generated.resources.settings_cw_source_section
import nuvio.composeapp.generated.resources.settings_cw_source_simkl
import nuvio.composeapp.generated.resources.settings_cw_source_title
import nuvio.composeapp.generated.resources.settings_cw_source_trakt
import nuvio.composeapp.generated.resources.settings_cw_source_floppy
import nuvio.composeapp.generated.resources.settings_cw_withdraw_imported_cancel
import nuvio.composeapp.generated.resources.settings_cw_withdraw_imported_confirm_action
import nuvio.composeapp.generated.resources.settings_cw_withdraw_imported_confirm_body
import nuvio.composeapp.generated.resources.settings_cw_withdraw_imported_confirm_title
import nuvio.composeapp.generated.resources.settings_cw_withdraw_imported_done
import nuvio.composeapp.generated.resources.settings_cw_withdraw_imported_none
import nuvio.composeapp.generated.resources.settings_cw_withdraw_imported_subtitle
import nuvio.composeapp.generated.resources.settings_cw_withdraw_imported_title
import nuvio.composeapp.generated.resources.settings_cw_window_all
import nuvio.composeapp.generated.resources.settings_cw_window_days
import nuvio.composeapp.generated.resources.settings_cw_window_title
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.TextButton
import com.nuvio.app.core.ui.NuvioAlertDialog
import com.nuvio.app.core.ui.NuvioDialogSurface
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.home.components.ContinueWatchingStylePreview
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingEnrichmentCache
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingClickAction
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import com.nuvio.app.features.watchprogress.ContinueWatchingSortMode
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_advanced_clear_cw_cache
import nuvio.composeapp.generated.resources.settings_advanced_clear_cw_cache_done
import nuvio.composeapp.generated.resources.settings_advanced_clear_cw_cache_subtitle
import nuvio.composeapp.generated.resources.settings_advanced_section_cache
import nuvio.composeapp.generated.resources.settings_continue_watching_resume_prompt_description
import nuvio.composeapp.generated.resources.settings_continue_watching_resume_prompt_title
import nuvio.composeapp.generated.resources.settings_continue_watching_click_action_details
import nuvio.composeapp.generated.resources.settings_continue_watching_click_action_description
import nuvio.composeapp.generated.resources.settings_continue_watching_click_action_play
import nuvio.composeapp.generated.resources.settings_continue_watching_click_action_title
import nuvio.composeapp.generated.resources.settings_continue_watching_section_default_action
import nuvio.composeapp.generated.resources.settings_continue_watching_blur_next_up_description
import nuvio.composeapp.generated.resources.settings_continue_watching_blur_next_up_title
import nuvio.composeapp.generated.resources.settings_continue_watching_show_unaired_next_up_description
import nuvio.composeapp.generated.resources.settings_continue_watching_show_unaired_next_up_title
import nuvio.composeapp.generated.resources.settings_continue_watching_separate_next_up_description
import nuvio.composeapp.generated.resources.settings_continue_watching_separate_next_up_title
import nuvio.composeapp.generated.resources.settings_continue_watching_section_card_style
import nuvio.composeapp.generated.resources.settings_continue_watching_section_on_launch
import nuvio.composeapp.generated.resources.settings_continue_watching_section_sort_order
import nuvio.composeapp.generated.resources.settings_continue_watching_section_up_next_behavior
import nuvio.composeapp.generated.resources.settings_continue_watching_show_description
import nuvio.composeapp.generated.resources.settings_continue_watching_show_title
import nuvio.composeapp.generated.resources.settings_continue_watching_sort_mode_default
import nuvio.composeapp.generated.resources.settings_continue_watching_sort_mode_default_desc
import nuvio.composeapp.generated.resources.settings_continue_watching_sort_mode_streaming
import nuvio.composeapp.generated.resources.settings_continue_watching_sort_mode_streaming_desc
import nuvio.composeapp.generated.resources.settings_continue_watching_sort_mode_title
import nuvio.composeapp.generated.resources.settings_continue_watching_style_card
import nuvio.composeapp.generated.resources.settings_continue_watching_style_card_description
import nuvio.composeapp.generated.resources.settings_continue_watching_style_poster
import nuvio.composeapp.generated.resources.settings_continue_watching_style_poster_description
import nuvio.composeapp.generated.resources.settings_continue_watching_style_wide
import nuvio.composeapp.generated.resources.settings_continue_watching_style_wide_description
import nuvio.composeapp.generated.resources.settings_continue_watching_up_next_description
import nuvio.composeapp.generated.resources.settings_continue_watching_up_next_title
import nuvio.composeapp.generated.resources.settings_continue_watching_use_episode_thumbnails_description
import nuvio.composeapp.generated.resources.settings_continue_watching_use_episode_thumbnails_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.continueWatchingSettingsContent(
    isTablet: Boolean,
    isVisible: Boolean,
    style: ContinueWatchingSectionStyle,
    clickAction: ContinueWatchingClickAction,
    upNextFromFurthestEpisode: Boolean,
    useEpisodeThumbnails: Boolean,
    showUnairedNextUp: Boolean,
    separateNextUpRow: Boolean,
    blurNextUp: Boolean,
    showResumePromptOnLaunch: Boolean,
    sortMode: ContinueWatchingSortMode,
) {
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_continue_watching_section_card_style),
            isTablet = isTablet,
        ) {
            ContinueWatchingStyleSelector(
                isTablet = isTablet,
                selectedStyle = style,
                onStyleSelected = ContinueWatchingPreferencesRepository::setStyle,
            )
        }
    }
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_continue_watching_section_default_action),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsChoiceRow(
                    title = stringResource(Res.string.settings_continue_watching_click_action_title),
                    description = stringResource(Res.string.settings_continue_watching_click_action_description),
                    options = listOf(
                        SettingsChoiceOption(
                            ContinueWatchingClickAction.PLAY,
                            stringResource(Res.string.settings_continue_watching_click_action_play),
                        ),
                        SettingsChoiceOption(
                            ContinueWatchingClickAction.DETAILS,
                            stringResource(Res.string.settings_continue_watching_click_action_details),
                        ),
                    ),
                    selectedValue = clickAction,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(
                        SettingsScrollAnchor.searchKey("continue-watching-click-action"),
                    ),
                    onSelected = ContinueWatchingPreferencesRepository::setClickAction,
                )
            }
        }
    }
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_cw_source_section),
            isTablet = isTablet,
        ) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("continue-watching-source")),
            ) {
                ContinueWatchingSourceRow(isTablet = isTablet)
                ContinueWatchingWindowRow(isTablet = isTablet)
                // Only SIMKL reports anime as per-entry seasons, so the identity choice only
                // changes what this row produces when SIMKL is the source. The same setting is
                // also reachable from Local Library, which it governs regardless of this source.
                val continueWatchingSource by ContinueWatchingSourceRepository.uiState
                    .collectAsStateWithLifecycle()
                if (continueWatchingSource == ContinueWatchingSource.SIMKL) {
                    AnimeIdPreferenceRow(
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(
                            SettingsScrollAnchor.searchKey("anime-id-preference"),
                        ),
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                SeedFromNuvioSyncRow(isTablet = isTablet)
            }
        }
    }
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_continue_watching_section_up_next_behavior),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_continue_watching_show_title),
                    description = stringResource(Res.string.settings_continue_watching_show_description),
                    checked = isVisible,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("continue-watching-show-continue-watching")),
                    onCheckedChange = ContinueWatchingPreferencesRepository::setVisible,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_continue_watching_use_episode_thumbnails_title),
                    description = stringResource(Res.string.settings_continue_watching_use_episode_thumbnails_description),
                    checked = useEpisodeThumbnails,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("continue-watching-episode-thumbnails")),
                    onCheckedChange = ContinueWatchingPreferencesRepository::setUseEpisodeThumbnails,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_continue_watching_up_next_title),
                    description = stringResource(Res.string.settings_continue_watching_up_next_description),
                    checked = upNextFromFurthestEpisode,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("continue-watching-up-next")),
                    onCheckedChange = ContinueWatchingPreferencesRepository::setUpNextFromFurthestEpisode,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_continue_watching_separate_next_up_title),
                    description = stringResource(Res.string.settings_continue_watching_separate_next_up_description),
                    checked = separateNextUpRow,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("continue-watching-separate-next-up")),
                    onCheckedChange = ContinueWatchingPreferencesRepository::setSeparateNextUpRow,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_continue_watching_show_unaired_next_up_title),
                    description = stringResource(Res.string.settings_continue_watching_show_unaired_next_up_description),
                    checked = showUnairedNextUp,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("continue-watching-unaired-next-up")),
                    onCheckedChange = ContinueWatchingPreferencesRepository::setShowUnairedNextUp,
                )
                if (useEpisodeThumbnails) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_continue_watching_blur_next_up_title),
                        description = stringResource(Res.string.settings_continue_watching_blur_next_up_description),
                        checked = blurNextUp,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("continue-watching-blur-next-up")),
                        onCheckedChange = ContinueWatchingPreferencesRepository::setBlurNextUp,
                    )
                }
            }
        }
    }
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_continue_watching_section_on_launch),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_continue_watching_resume_prompt_title),
                    description = stringResource(Res.string.settings_continue_watching_resume_prompt_description),
                    checked = showResumePromptOnLaunch,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("continue-watching-resume-prompt")),
                    onCheckedChange = ContinueWatchingPreferencesRepository::setShowResumePromptOnLaunch,
                )
            }
        }
    }
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_advanced_section_cache),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                val scope = rememberCoroutineScope()
                var cleared by rememberSaveable { mutableStateOf(false) }
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_advanced_clear_cw_cache),
                    description = if (cleared) {
                        stringResource(Res.string.settings_advanced_clear_cw_cache_done)
                    } else {
                        stringResource(Res.string.settings_advanced_clear_cw_cache_subtitle)
                    },
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("clear-cw-cache")),
                    onClick = {
                        if (!cleared) {
                            ContinueWatchingEnrichmentCache.clearAll()
                            cleared = true
                            scope.launch {
                                WatchProgressRepository.forceSnapshotRefreshFromServer(
                                    ProfileRepository.activeProfileId,
                                )
                            }
                        }
                    },
                )
                WithdrawImportedHistoryRow(isTablet = isTablet)
            }
        }
    }
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_continue_watching_section_sort_order),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                val currentModeLabel = stringResource(
                    when (sortMode) {
                        ContinueWatchingSortMode.DEFAULT -> Res.string.settings_continue_watching_sort_mode_default
                        ContinueWatchingSortMode.STREAMING_STYLE -> Res.string.settings_continue_watching_sort_mode_streaming
                    }
                )
                SettingsChoiceRow(
                    title = stringResource(Res.string.settings_continue_watching_sort_mode_title),
                    description = currentModeLabel,
                    options = listOf(
                        SettingsChoiceOption(
                            ContinueWatchingSortMode.DEFAULT,
                            stringResource(Res.string.settings_continue_watching_sort_mode_default),
                        ),
                        SettingsChoiceOption(
                            ContinueWatchingSortMode.STREAMING_STYLE,
                            stringResource(Res.string.settings_continue_watching_sort_mode_streaming),
                        ),
                    ),
                    selectedValue = sortMode,
                    isTablet = isTablet,
                    onSelected = ContinueWatchingPreferencesRepository::setSortMode,
                )
            }
        }
    }
}

@Composable
private fun ContinueWatchingStyleSelector(
    isTablet: Boolean,
    selectedStyle: ContinueWatchingSectionStyle,
    onStyleSelected: (ContinueWatchingSectionStyle) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(if (isTablet) 12.dp else 8.dp),
    ) {
        ContinueWatchingSectionStyle.entries.forEach { style ->
            Box(modifier = Modifier.weight(1f)) {
                ContinueWatchingStyleOption(
                    style = style,
                    selected = selectedStyle == style,
                    isTablet = isTablet,
                    onClick = { onStyleSelected(style) },
                )
            }
        }
    }
}

@Composable
private fun ContinueWatchingStyleOption(
    style: ContinueWatchingSectionStyle,
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
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (isTablet) 12.dp else 8.dp, vertical = if (isTablet) 14.dp else 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (isTablet) 8.dp else 6.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = if (isTablet) 4.dp else 0.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(if (isTablet) 24.dp else 18.dp)
                        .alpha(if (selected) 1f else 0f),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isTablet) 96.dp else 66.dp),
                contentAlignment = Alignment.Center,
            ) {
                ContinueWatchingStylePreview(
                    style = style,
                    isSelected = selected,
                )
            }
            Text(
                text = stringResource(style.labelRes),
                style = if (isTablet) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(style.descriptionRes),
                style = if (isTablet) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val ContinueWatchingSectionStyle.labelRes: StringResource
    get() = when (this) {
        ContinueWatchingSectionStyle.Card -> Res.string.settings_continue_watching_style_card
        ContinueWatchingSectionStyle.Wide -> Res.string.settings_continue_watching_style_wide
        ContinueWatchingSectionStyle.Poster -> Res.string.settings_continue_watching_style_poster
    }

private val ContinueWatchingSectionStyle.descriptionRes: StringResource
    get() = when (this) {
        ContinueWatchingSectionStyle.Card -> Res.string.settings_continue_watching_style_card_description
        ContinueWatchingSectionStyle.Wide -> Res.string.settings_continue_watching_style_wide_description
        ContinueWatchingSectionStyle.Poster -> Res.string.settings_continue_watching_style_poster_description
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContinueWatchingSortModeDialog(
    currentMode: ContinueWatchingSortMode,
    onModeSelected: (ContinueWatchingSortMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(
        Triple(
            ContinueWatchingSortMode.DEFAULT,
            Res.string.settings_continue_watching_sort_mode_default,
            Res.string.settings_continue_watching_sort_mode_default_desc,
        ),
        Triple(
            ContinueWatchingSortMode.STREAMING_STYLE,
            Res.string.settings_continue_watching_sort_mode_streaming,
            Res.string.settings_continue_watching_sort_mode_streaming_desc,
        ),
    )

    BasicAlertDialog(
        onDismissRequest = onDismiss,
    ) {
        NuvioDialogSurface(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_continue_watching_sort_mode_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.forEach { (mode, titleRes, descriptionRes) ->
                        val isSelected = mode == currentMode
                        val containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onModeSelected(mode) },
                            shape = RoundedCornerShape(12.dp),
                            color = containerColor,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(titleRes),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = stringResource(descriptionRes),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Box(
                                    modifier = Modifier.size(24.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
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


/**
 * The single place Continue Watching's source is chosen.
 *
 * One control rather than a switch on each provider's own page: those could all be on at once, and
 * which one actually won was decided by a precedence chain nobody could see from the UI.
 */
@Composable
private fun ContinueWatchingSourceRow(isTablet: Boolean) {
    val selected by ContinueWatchingSourceRepository.uiState.collectAsStateWithLifecycle()
    val connected by TrackingProviderRegistry.connectedProviderIds.collectAsStateWithLifecycle()

    val labels = ContinueWatchingSource.entries.associateWith { source ->
        stringResource(
            when (source) {
                ContinueWatchingSource.LOCAL -> Res.string.settings_cw_source_local
                ContinueWatchingSource.TRAKT -> Res.string.settings_cw_source_trakt
                ContinueWatchingSource.SIMKL -> Res.string.settings_cw_source_simkl
                ContinueWatchingSource.MDBLIST -> Res.string.settings_cw_source_mdblist
                ContinueWatchingSource.YAMTRACK -> Res.string.settings_cw_source_floppy
            },
        )
    }

    val selectedProvider = selected.providerId
    val isSelectionConnected = selectedProvider == null || selectedProvider in connected

    SettingsChoiceRow(
        title = stringResource(Res.string.settings_cw_source_title),
        description = if (isSelectionConnected) {
            labels.getValue(selected)
        } else {
            stringResource(Res.string.settings_cw_source_not_connected)
        },
        options = ContinueWatchingSource.entries.map { source ->
            SettingsChoiceOption(source, labels.getValue(source))
        },
        selectedValue = selected,
        isTablet = isTablet,
        onSelected = ContinueWatchingSourceRepository::setSource,
    )
}

/** Edits the history window for whichever provider currently owns Continue Watching. */
@Composable
private fun ContinueWatchingWindowRow(isTablet: Boolean) {
    val source by ContinueWatchingSourceRepository.uiState.collectAsStateWithLifecycle()
    if (source != ContinueWatchingSource.TRAKT && source != ContinueWatchingSource.SIMKL) return

    val traktSettings by remember {
        TraktSettingsRepository.ensureLoaded()
        TraktSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val simklSettings by remember {
        SimklSettingsRepository.ensureLoaded()
        SimklSettingsRepository.uiState
    }.collectAsStateWithLifecycle()

    val selectedDays = when (source) {
        ContinueWatchingSource.TRAKT ->
            normalizeTraktContinueWatchingDaysCap(traktSettings.continueWatchingDaysCap)
        ContinueWatchingSource.SIMKL ->
            simklSettings.simklContinueWatchingDaysCap.coerceAtLeast(SIMKL_CW_DAYS_CAP_ALL)
        ContinueWatchingSource.LOCAL,
        ContinueWatchingSource.MDBLIST,
        ContinueWatchingSource.YAMTRACK -> return
    }

    SettingsGroupDivider(isTablet = isTablet)
    SettingsChoiceRow(
        title = stringResource(Res.string.settings_cw_window_title),
        description = continueWatchingWindowLabel(selectedDays),
        options = TraktContinueWatchingDaysOptions.map { days ->
            val normalized = if (source == ContinueWatchingSource.TRAKT) {
                normalizeTraktContinueWatchingDaysCap(days)
            } else {
                days.coerceAtLeast(SIMKL_CW_DAYS_CAP_ALL)
            }
            SettingsChoiceOption(normalized, continueWatchingWindowLabel(normalized))
        },
        selectedValue = selectedDays,
        isTablet = isTablet,
        modifier = Modifier.settingsScrollAnchor(
            SettingsScrollAnchor.searchKey("continue-watching-window"),
        ),
        onSelected = { days ->
            when (source) {
                ContinueWatchingSource.TRAKT ->
                    TraktSettingsRepository.setContinueWatchingDaysCap(days)
                ContinueWatchingSource.SIMKL ->
                    SimklSettingsRepository.setSimklContinueWatchingDaysCap(days)
                ContinueWatchingSource.LOCAL,
                ContinueWatchingSource.MDBLIST -> Unit
                ContinueWatchingSource.YAMTRACK -> Unit
            }
        },
    )
}

@Composable
private fun continueWatchingWindowLabel(days: Int): String =
    if (days == TRAKT_CONTINUE_WATCHING_DAYS_CAP_ALL) {
        stringResource(Res.string.settings_cw_window_all)
    } else {
        stringResource(Res.string.settings_cw_window_days, days)
    }


/**
 * Clears watched marks imported from services that do not provide Continue Watching.
 *
 * Only shown when such a service is connected, and only useful for imports made by builds that
 * recorded no provenance — current ones are withdrawn automatically when the source changes. It
 * deletes local watched marks, so it asks first and names what it will match against.
 */
@Composable
private fun WithdrawImportedHistoryRow(isTablet: Boolean) {
    val connectedProviders by TrackingProviderRegistry.connectedProviderIds.collectAsStateWithLifecycle()
    val source by ContinueWatchingSourceRepository.uiState.collectAsStateWithLifecycle()
    val foreignProviderNames = remember(connectedProviders, source) {
        TrackingProviderRegistry.connectedWatchedProviders()
            .filter { provider -> provider.providerId != source.providerId }
            .mapNotNull { provider ->
                TrackingProviderRegistry.authProvider(provider.providerId)?.descriptor?.displayName
            }
    }
    if (foreignProviderNames.isEmpty()) return

    val scope = rememberCoroutineScope()
    var removedCount by remember { mutableStateOf<Int?>(null) }
    var showConfirmation by rememberSaveable { mutableStateOf(false) }
    val providerList = foreignProviderNames.joinToString(", ")

    SettingsGroupDivider(isTablet = isTablet)
    SettingsNavigationRow(
        title = stringResource(Res.string.settings_cw_withdraw_imported_title),
        description = removedCount?.let { count ->
            if (count > 0) {
                stringResource(Res.string.settings_cw_withdraw_imported_done, count)
            } else {
                stringResource(Res.string.settings_cw_withdraw_imported_none)
            }
        } ?: stringResource(Res.string.settings_cw_withdraw_imported_subtitle),
        isTablet = isTablet,
        modifier = Modifier.settingsScrollAnchor(
            SettingsScrollAnchor.searchKey("continue-watching-withdraw-imported-history"),
        ),
        onClick = { showConfirmation = true },
    )

    if (!showConfirmation) return
    NuvioAlertDialog(
        onDismissRequest = { showConfirmation = false },
        title = { Text(stringResource(Res.string.settings_cw_withdraw_imported_confirm_title)) },
        text = {
            Text(stringResource(Res.string.settings_cw_withdraw_imported_confirm_body, providerList))
        },
        confirmButton = {
            TextButton(
                onClick = {
                    showConfirmation = false
                    scope.launch {
                        removedCount = runCatching {
                            WatchedRepository.withdrawImportedProviderHistory(
                                ProfileRepository.activeProfileId,
                            )
                        }.getOrDefault(0)
                    }
                },
            ) {
                Text(stringResource(Res.string.settings_cw_withdraw_imported_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = { showConfirmation = false }) {
                Text(stringResource(Res.string.settings_cw_withdraw_imported_cancel))
            }
        },
    )
}

/**
 * Whether Up Next may also draw on the Nuvio Sync watched history while another service is the
 * Continue Watching source.
 *
 * Only meaningful for a remote source, so it is disabled when Nuvio Sync is already the source.
 */
@Composable
private fun SeedFromNuvioSyncRow(isTablet: Boolean) {
    val preferences by ContinueWatchingPreferencesRepository.uiState.collectAsStateWithLifecycle()
    val source by ContinueWatchingSourceRepository.uiState.collectAsStateWithLifecycle()

    SettingsSwitchRow(
        title = stringResource(Res.string.settings_cw_seed_nuvio_title),
        description = stringResource(Res.string.settings_cw_seed_nuvio_description),
        checked = preferences.seedNextUpFromNuvioSync,
        enabled = source != ContinueWatchingSource.LOCAL,
        isTablet = isTablet,
        onCheckedChange = ContinueWatchingPreferencesRepository::setSeedNextUpFromNuvioSync,
    )
}
