package com.nuvio.app.features.settings

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
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_games_backdrop_black_shelf
import nuvio.composeapp.generated.resources.settings_games_backdrop_full
import nuvio.composeapp.generated.resources.settings_games_backdrop_style
import nuvio.composeapp.generated.resources.settings_games_backdrop_style_description
import nuvio.composeapp.generated.resources.settings_games_igdb_client_id
import nuvio.composeapp.generated.resources.settings_games_igdb_client_id_description
import nuvio.composeapp.generated.resources.settings_games_igdb_client_secret
import nuvio.composeapp.generated.resources.settings_games_igdb_client_secret_description
import nuvio.composeapp.generated.resources.settings_games_missing_credentials
import nuvio.composeapp.generated.resources.settings_games_section_artwork
import nuvio.composeapp.generated.resources.settings_games_section_igdb
import nuvio.composeapp.generated.resources.settings_games_section_presentation
import nuvio.composeapp.generated.resources.settings_games_shortcut_hint
import nuvio.composeapp.generated.resources.settings_games_steamgriddb_key
import nuvio.composeapp.generated.resources.settings_games_steamgriddb_key_description
import nuvio.composeapp.generated.resources.settings_games_title
import org.jetbrains.compose.resources.stringResource

/**
 * Settings for game mode.
 *
 * These used to be a dialog inside the standalone launcher; merged into Nuvio they are an ordinary
 * settings page, so the credentials sit with every other API key the app holds and are covered by
 * settings search and backup like the rest.
 */
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
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_games_section_igdb),
            isTablet = isTablet,
        ) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsSearchAnchors(
                    "games-igdb-client-id",
                    "games-igdb-client-secret",
                ),
            ) {
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
                if (!settings.igdbConfigured) {
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
