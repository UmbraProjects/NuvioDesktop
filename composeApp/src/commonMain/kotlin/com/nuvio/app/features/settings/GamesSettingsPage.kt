package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.games.GameBackdropStyle
import com.nuvio.app.features.games.GameLibrarySettings
import com.nuvio.app.features.games.GameLibrarySettingsRepository
import com.nuvio.app.features.games.GameMetadataSource
import com.nuvio.app.features.games.GameRowsSettingsSection
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_games_backdrop_black_shelf
import nuvio.composeapp.generated.resources.settings_games_backdrop_full
import nuvio.composeapp.generated.resources.settings_games_backdrop_style
import nuvio.composeapp.generated.resources.settings_games_backdrop_style_description
import nuvio.composeapp.generated.resources.settings_games_close_after_launch
import nuvio.composeapp.generated.resources.settings_games_close_after_launch_description
import nuvio.composeapp.generated.resources.settings_games_igdb_client_id
import nuvio.composeapp.generated.resources.settings_games_igdb_client_id_description
import nuvio.composeapp.generated.resources.settings_games_igdb_client_secret
import nuvio.composeapp.generated.resources.settings_games_igdb_client_secret_description
import nuvio.composeapp.generated.resources.settings_games_metadata_source
import nuvio.composeapp.generated.resources.settings_games_metadata_source_description
import nuvio.composeapp.generated.resources.settings_games_metadata_source_igdb
import nuvio.composeapp.generated.resources.settings_games_metadata_source_steam
import nuvio.composeapp.generated.resources.settings_games_metadata_steam_hint
import nuvio.composeapp.generated.resources.settings_games_missing_credentials
import nuvio.composeapp.generated.resources.settings_games_section_artwork
import nuvio.composeapp.generated.resources.settings_games_section_launching
import nuvio.composeapp.generated.resources.settings_games_section_metadata
import nuvio.composeapp.generated.resources.settings_games_section_presentation
import nuvio.composeapp.generated.resources.settings_games_section_rows
import nuvio.composeapp.generated.resources.settings_games_shortcut_hint
import nuvio.composeapp.generated.resources.settings_games_steamgriddb_key
import nuvio.composeapp.generated.resources.settings_games_steamgriddb_key_description
import nuvio.composeapp.generated.resources.settings_games_title
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource

/**
 * Settings for game mode.
 *
 * These used to be a dialog inside the standalone launcher; merged into Nuvio they are an ordinary
 * settings page, so the credentials sit with every other API key the app holds and are covered by
 * settings search and backup like the rest.
 */
/** Puts a Games entry in the navigation, after Music, that opens game mode; the same switch as in Navigation order. */
@androidx.compose.runtime.Composable
private fun GamesNavigationSwitch(isTablet: Boolean) {
    val inNav = androidx.compose.runtime.remember { com.nuvio.app.features.navbar.NavBarLayoutRepository.gamesInNav }
        .collectAsStateWithLifecycle().value
    SettingsSwitchRow(
        title = "Show in navigation",
        description = "A Games button in the sidebar and top bar that opens game mode. Its place can be changed " +
            "under Appearance > Navigation order.",
        checked = inNav,
        isTablet = isTablet,
        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("games-in-navigation")),
        onCheckedChange = com.nuvio.app.features.navbar.NavBarLayoutRepository::setGamesInNav,
    )
}

internal fun LazyListScope.gamesSettingsContent(
    isTablet: Boolean,
    settings: GameLibrarySettings,
) {
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_games_title),
            isTablet = isTablet,
        ) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("games-shortcut")),
            ) {
                GamesInfoRow(
                    isTablet = isTablet,
                    text = stringResource(Res.string.settings_games_shortcut_hint),
                )
                SettingsGroupDivider(isTablet = isTablet)
                GamesNavigationSwitch(isTablet = isTablet)
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_games_section_metadata),
            isTablet = isTablet,
        ) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsSearchAnchors(
                    "games-metadata-source",
                    "games-igdb-client-id",
                    "games-igdb-client-secret",
                ),
            ) {
                SettingsChoiceRow(
                    title = stringResource(Res.string.settings_games_metadata_source),
                    description = stringResource(Res.string.settings_games_metadata_source_description),
                    options = listOf(
                        SettingsChoiceOption(
                            value = GameMetadataSource.Igdb,
                            label = stringResource(Res.string.settings_games_metadata_source_igdb),
                        ),
                        SettingsChoiceOption(
                            value = GameMetadataSource.Steam,
                            label = stringResource(Res.string.settings_games_metadata_source_steam),
                        ),
                    ),
                    selectedValue = settings.metadataSource,
                    isTablet = isTablet,
                    onSelected = GameLibrarySettingsRepository::setMetadataSource,
                )
                SettingsGroupDivider(isTablet = isTablet)
                if (settings.metadataSource == GameMetadataSource.Steam) {
                    GamesInfoRow(
                        isTablet = isTablet,
                        text = stringResource(Res.string.settings_games_metadata_steam_hint),
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                }
                // The Twitch rows stay visible with Steam selected: hiding them would strand the
                // settings-search entries that scroll to them, and they are what the user comes
                // back to when Steam turns out not to know half their library.
                SettingsTextInputRow(
                    title = stringResource(Res.string.settings_games_igdb_client_id),
                    description = stringResource(Res.string.settings_games_igdb_client_id_description),
                    value = settings.igdbClientId,
                    placeholder = stringResource(Res.string.settings_games_igdb_client_id),
                    isTablet = isTablet,
                    // Not a secret on its own, but summarising it as "Configured" keeps the row
                    // narrow and matches the secret beneath it.
                    summarizeAsConfigured = true,
                    onSave = GameLibrarySettingsRepository::setIgdbClientId,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsTextInputRow(
                    title = stringResource(Res.string.settings_games_igdb_client_secret),
                    description = stringResource(Res.string.settings_games_igdb_client_secret_description),
                    value = settings.igdbClientSecret,
                    placeholder = stringResource(Res.string.settings_games_igdb_client_secret),
                    isTablet = isTablet,
                    secret = true,
                    onSave = GameLibrarySettingsRepository::setIgdbClientSecret,
                )
                if (!settings.igdbConfigured && settings.metadataSource == GameMetadataSource.Igdb) {
                    SettingsGroupDivider(isTablet = isTablet)
                    GamesInfoRow(
                        isTablet = isTablet,
                        text = stringResource(Res.string.settings_games_missing_credentials),
                    )
                }
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_games_section_presentation),
            isTablet = isTablet,
        ) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("games-backdrop-style")),
            ) {
                SettingsChoiceRow(
                    title = stringResource(Res.string.settings_games_backdrop_style),
                    description = stringResource(Res.string.settings_games_backdrop_style_description),
                    options = listOf(
                        SettingsChoiceOption(
                            value = GameBackdropStyle.BlackShelf,
                            label = stringResource(Res.string.settings_games_backdrop_black_shelf),
                        ),
                        SettingsChoiceOption(
                            value = GameBackdropStyle.FullBackdrop,
                            label = stringResource(Res.string.settings_games_backdrop_full),
                        ),
                    ),
                    selectedValue = settings.backdropStyle,
                    isTablet = isTablet,
                    onSelected = GameLibrarySettingsRepository::setBackdropStyle,
                )
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_games_section_launching),
            isTablet = isTablet,
        ) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("games-close-after-launch")),
            ) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_games_close_after_launch),
                    description = stringResource(Res.string.settings_games_close_after_launch_description),
                    checked = settings.closeAfterLaunch,
                    isTablet = isTablet,
                    onCheckedChange = GameLibrarySettingsRepository::setCloseAfterLaunch,
                )
            }
        }
    }

    item {
        // Row management lives with the library (desktop-only), so the section body is an actual.
        Box(Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("games-rows"))) {
            GameRowsSettingsSection(
                isTablet = isTablet,
                title = stringResource(Res.string.settings_games_section_rows),
            )
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_games_section_artwork),
            isTablet = isTablet,
        ) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("games-steamgriddb-key")),
            ) {
                SettingsTextInputRow(
                    title = stringResource(Res.string.settings_games_steamgriddb_key),
                    description = stringResource(Res.string.settings_games_steamgriddb_key_description),
                    value = settings.steamGridDbApiKey,
                    placeholder = stringResource(Res.string.settings_games_steamgriddb_key),
                    isTablet = isTablet,
                    secret = true,
                    onSave = GameLibrarySettingsRepository::setSteamGridDbApiKey,
                )
            }
        }
    }
}

@Composable
private fun GamesInfoRow(
    isTablet: Boolean,
    text: String,
) {
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val verticalPadding = if (isTablet) 14.dp else 12.dp

    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
