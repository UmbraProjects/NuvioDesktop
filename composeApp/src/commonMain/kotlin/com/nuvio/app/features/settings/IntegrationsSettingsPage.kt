package com.nuvio.app.features.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Save
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.discord.DiscordEpisodeArtwork
import com.nuvio.app.features.discord.DiscordPresenceMode
import com.nuvio.app.features.discord.DiscordPresenceSettings
import com.nuvio.app.features.library.LibrarySourceMode
import com.nuvio.app.features.tracking.CalendarSource
import com.nuvio.app.features.tracking.CalendarSourceRepository
import com.nuvio.app.features.tracking.LibrarySourceRepository
import com.nuvio.app.features.tracking.RatingPromptRepository
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.trackingProvider
import com.nuvio.app.features.tracking.trackingRatingProviderFor
import com.nuvio.app.isDesktop
import nuvio.composeapp.generated.resources.compose_settings_page_debrid
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.*
import nuvio.composeapp.generated.resources.compose_settings_page_mdblist_ratings
import nuvio.composeapp.generated.resources.compose_settings_page_qualicache
import nuvio.composeapp.generated.resources.compose_settings_page_simkl
import nuvio.composeapp.generated.resources.compose_settings_page_yamtrack
import nuvio.composeapp.generated.resources.compose_settings_page_tmdb_enrichment
import nuvio.composeapp.generated.resources.compose_settings_page_trakt
import nuvio.composeapp.generated.resources.compose_settings_root_trakt_description
import nuvio.composeapp.generated.resources.settings_integrations_mdblist_description
import nuvio.composeapp.generated.resources.settings_integrations_debrid_description
import nuvio.composeapp.generated.resources.settings_integrations_qualicache_description
import nuvio.composeapp.generated.resources.settings_discord_episode_artwork
import nuvio.composeapp.generated.resources.settings_discord_episode_artwork_description
import nuvio.composeapp.generated.resources.settings_discord_episode_artwork_poster
import nuvio.composeapp.generated.resources.settings_discord_episode_artwork_still
import nuvio.composeapp.generated.resources.settings_integrations_section_title
import nuvio.composeapp.generated.resources.settings_integrations_tmdb_description
import nuvio.composeapp.generated.resources.settings_simkl_description
import nuvio.composeapp.generated.resources.settings_yamtrack_description
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.integrationsContent(
    isTablet: Boolean,
    discordPresenceSettings: DiscordPresenceSettings,
    onDiscordPresenceModeChange: (DiscordPresenceMode) -> Unit,
    onDiscordEpisodeArtworkChange: (DiscordEpisodeArtwork) -> Unit,
    onTmdbClick: () -> Unit,
    onMdbListClick: () -> Unit,
    onQualiCacheClick: () -> Unit,
    onDebridClick: () -> Unit,
    onTraktClick: () -> Unit,
    onSimklClick: () -> Unit,
    onYamtrackClick: () -> Unit,
) {
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_library_source_section),
            isTablet = isTablet,
        ) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsScrollAnchor(
                    SettingsScrollAnchor.searchKey("library-source"),
                ),
            ) {
                LibrarySourceRow(isTablet = isTablet)
                SettingsGroupDivider(isTablet = isTablet)
                // Sits with the Library source because that selection is what decides where a
                // rating is written, and whether it can be written at all.
                RatingPromptRow(isTablet = isTablet)
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_calendar_source_section),
            isTablet = isTablet,
        ) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsScrollAnchor(
                    SettingsScrollAnchor.searchKey("calendar-source"),
                ),
            ) {
                CalendarSourceRow(isTablet = isTablet)
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_integrations_section_title),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_tmdb_enrichment),
                    description = stringResource(Res.string.settings_integrations_tmdb_description),
                    iconPainter = integrationLogoPainter(IntegrationLogo.Tmdb),
                    isTablet = isTablet,
                    onClick = onTmdbClick,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_mdblist_ratings),
                    description = stringResource(Res.string.settings_integrations_mdblist_description),
                    iconPainter = integrationLogoPainter(IntegrationLogo.MdbList),
                    isTablet = isTablet,
                    onClick = onMdbListClick,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_qualicache),
                    description = stringResource(Res.string.settings_integrations_qualicache_description),
                    icon = Icons.Rounded.HighQuality,
                    isTablet = isTablet,
                    onClick = onQualiCacheClick,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_debrid),
                    description = stringResource(Res.string.settings_integrations_debrid_description),
                    icon = Icons.Rounded.CloudQueue,
                    isTablet = isTablet,
                    onClick = onDebridClick,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_trakt),
                    description = stringResource(Res.string.compose_settings_root_trakt_description),
                    iconPainter = integrationLogoPainter(IntegrationLogo.Trakt),
                    isTablet = isTablet,
                    onClick = onTraktClick,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_simkl),
                    description = stringResource(Res.string.settings_simkl_description),
                    iconPainter = integrationLogoPainter(IntegrationLogo.Simkl),
                    isTablet = isTablet,
                    onClick = onSimklClick,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_yamtrack),
                    description = stringResource(Res.string.settings_yamtrack_description),
                    icon = Icons.Rounded.Save,
                    isTablet = isTablet,
                    onClick = onYamtrackClick,
                )
                if (isDesktop) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsChoiceRow(
                        title = stringResource(Res.string.settings_discord_presence),
                        description = stringResource(Res.string.settings_discord_presence_description),
                        iconPainter = integrationLogoPainter(IntegrationLogo.Discord),
                        options = listOf(
                            SettingsChoiceOption(DiscordPresenceMode.Disabled, stringResource(Res.string.settings_discord_presence_disabled)),
                            SettingsChoiceOption(DiscordPresenceMode.Watching, stringResource(Res.string.settings_discord_presence_watching)),
                            SettingsChoiceOption(DiscordPresenceMode.Full, stringResource(Res.string.settings_discord_presence_full)),
                        ),
                        selectedValue = discordPresenceSettings.mode,
                        isTablet = isTablet,
                        modifier = androidx.compose.ui.Modifier.settingsScrollAnchor(SettingsScrollAnchor.DiscordPresence),
                        onSelected = onDiscordPresenceModeChange,
                    )
                    // Hidden while presence is off rather than disabled-but-visible: it is the only
                    // control on this page whose subject does not exist yet when the mode above is
                    // Disabled, and a greyed row invites a click that cannot do anything.
                    if (discordPresenceSettings.mode != DiscordPresenceMode.Disabled) {
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsChoiceRow(
                            title = stringResource(Res.string.settings_discord_episode_artwork),
                            description = stringResource(Res.string.settings_discord_episode_artwork_description),
                            iconPainter = integrationLogoPainter(IntegrationLogo.Discord),
                            options = listOf(
                                SettingsChoiceOption(
                                    DiscordEpisodeArtwork.Poster,
                                    stringResource(Res.string.settings_discord_episode_artwork_poster),
                                ),
                                SettingsChoiceOption(
                                    DiscordEpisodeArtwork.EpisodeThumbnail,
                                    stringResource(Res.string.settings_discord_episode_artwork_still),
                                ),
                            ),
                            selectedValue = discordPresenceSettings.episodeArtwork,
                            isTablet = isTablet,
                            modifier = androidx.compose.ui.Modifier.settingsScrollAnchor(
                                SettingsScrollAnchor.DiscordEpisodeArtwork,
                            ),
                            onSelected = onDiscordEpisodeArtworkChange,
                        )
                    }
                }
            }
        }
    }
}

/** The single place the Library's primary service is selected. */
@Composable
private fun LibrarySourceRow(isTablet: Boolean) {
    val selected by remember {
        LibrarySourceRepository.ensureLoaded()
        LibrarySourceRepository.uiState
    }.collectAsStateWithLifecycle()
    val connected by TrackingProviderRegistry.connectedProviderIds.collectAsStateWithLifecycle()

    val labels = LibrarySourceMode.entries.associateWith { source ->
        stringResource(
            when (source) {
                LibrarySourceMode.LOCAL -> Res.string.settings_library_source_nuvio
                LibrarySourceMode.TRAKT -> Res.string.settings_library_source_trakt
                LibrarySourceMode.SIMKL -> Res.string.settings_library_source_simkl
                LibrarySourceMode.MDBLIST -> Res.string.settings_library_source_mdblist
                LibrarySourceMode.YAMTRACK -> Res.string.settings_library_source_floppy
            },
        )
    }

    val selectedProvider = selected.trackingProvider
    val isSelectionConnected = selectedProvider == null || selectedProvider in connected
    SettingsChoiceRow(
        title = stringResource(Res.string.settings_library_source_title),
        description = if (isSelectionConnected) {
            labels.getValue(selected)
        } else {
            stringResource(Res.string.settings_library_source_not_connected)
        },
        options = LibrarySourceMode.entries.map { source ->
            SettingsChoiceOption(source, labels.getValue(source))
        },
        selectedValue = selected,
        isTablet = isTablet,
        onSelected = LibrarySourceRepository::setSource,
    )
}

/**
 * Offers a rating after a movie, season finale or series finale.
 *
 * Shown as disabled — rather than hidden — when the Library source cannot take a rating, so the
 * setting does not silently vanish depending on an unrelated choice made elsewhere on this page.
 */
@Composable
private fun RatingPromptRow(isTablet: Boolean) {
    val isEnabled by remember {
        RatingPromptRepository.ensureLoaded()
        RatingPromptRepository.isEnabled
    }.collectAsStateWithLifecycle()
    val librarySource by LibrarySourceRepository.uiState.collectAsStateWithLifecycle()
    val connected by TrackingProviderRegistry.connectedProviderIds.collectAsStateWithLifecycle()

    val ratingProviderId = trackingRatingProviderFor(librarySource)
    val canRate = ratingProviderId != null && ratingProviderId in connected

    SettingsSwitchRow(
        title = stringResource(Res.string.settings_rating_prompt_title),
        description = if (canRate) {
            stringResource(Res.string.settings_rating_prompt_description)
        } else {
            stringResource(Res.string.settings_rating_prompt_unavailable)
        },
        checked = isEnabled && canRate,
        enabled = canRate,
        isTablet = isTablet,
        onCheckedChange = RatingPromptRepository::setEnabled,
    )
}

/** The single place Calendar's provider is selected. */
@Composable
private fun CalendarSourceRow(isTablet: Boolean) {
    val selected by remember {
        CalendarSourceRepository.ensureLoaded()
        CalendarSourceRepository.uiState
    }.collectAsStateWithLifecycle()
    val connected by TrackingProviderRegistry.connectedProviderIds.collectAsStateWithLifecycle()

    val labels = CalendarSource.entries.associateWith { source ->
        stringResource(
            when (source) {
                CalendarSource.TRAKT -> Res.string.settings_calendar_source_trakt
                CalendarSource.SIMKL -> Res.string.settings_calendar_source_simkl
                CalendarSource.MDBLIST -> Res.string.settings_calendar_source_mdblist
            },
        )
    }

    val isSelectionConnected = selected.providerId in connected
    SettingsChoiceRow(
        title = stringResource(Res.string.settings_calendar_source_title),
        description = if (isSelectionConnected) {
            labels.getValue(selected)
        } else {
            stringResource(Res.string.settings_calendar_source_not_connected)
        },
        options = CalendarSource.entries.map { source ->
            SettingsChoiceOption(source, labels.getValue(source))
        },
        selectedValue = selected,
        isTablet = isTablet,
        onSelected = CalendarSourceRepository::setSource,
    )
}
