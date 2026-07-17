package com.nuvio.app.features.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.foundation.lazy.LazyListScope
import com.nuvio.app.features.discord.DiscordPresenceMode
import com.nuvio.app.features.discord.DiscordPresenceSettings
import com.nuvio.app.isDesktop
import nuvio.composeapp.generated.resources.compose_settings_page_debrid
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_settings_page_mdblist_ratings
import nuvio.composeapp.generated.resources.compose_settings_page_simkl
import nuvio.composeapp.generated.resources.compose_settings_page_tmdb_enrichment
import nuvio.composeapp.generated.resources.compose_settings_page_trakt
import nuvio.composeapp.generated.resources.compose_settings_root_trakt_description
import nuvio.composeapp.generated.resources.settings_integrations_mdblist_description
import nuvio.composeapp.generated.resources.settings_integrations_debrid_description
import nuvio.composeapp.generated.resources.settings_integrations_section_title
import nuvio.composeapp.generated.resources.settings_integrations_tmdb_description
import nuvio.composeapp.generated.resources.settings_simkl_description
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.integrationsContent(
    isTablet: Boolean,
    discordPresenceSettings: DiscordPresenceSettings,
    onDiscordPresenceModeChange: (DiscordPresenceMode) -> Unit,
    onTmdbClick: () -> Unit,
    onMdbListClick: () -> Unit,
    onDebridClick: () -> Unit,
    onTraktClick: () -> Unit,
    onSimklClick: () -> Unit,
) {
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
                    icon = Icons.Rounded.Bookmarks,
                    isTablet = isTablet,
                    onClick = onSimklClick,
                )
                if (isDesktop) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsChoiceRow(
                        title = "Discord Rich Presence",
                        description = "Show what you are doing in Nuvio on your Discord profile. " +
                            "Watching shares only active playback; Full also shares browsing, library, and viewing.",
                        options = listOf(
                            SettingsChoiceOption(DiscordPresenceMode.Disabled, "Disabled"),
                            SettingsChoiceOption(DiscordPresenceMode.Watching, "Watching"),
                            SettingsChoiceOption(DiscordPresenceMode.Full, "Full"),
                        ),
                        selectedValue = discordPresenceSettings.mode,
                        isTablet = isTablet,
                        modifier = androidx.compose.ui.Modifier.settingsScrollAnchor(SettingsScrollAnchor.DiscordPresence),
                        onSelected = onDiscordPresenceModeChange,
                    )
                }
            }
        }
    }
}
