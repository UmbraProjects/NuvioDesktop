package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.trackTextInputFocus
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.tmdb.CustomPosterTemplateProbe
import com.nuvio.app.features.tmdb.HeroImageSource
import com.nuvio.app.features.tmdb.TmdbSettings
import com.nuvio.app.features.tmdb.probeCustomPosterTemplate
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.tvdb.TvdbSettingsRepository
import com.nuvio.app.features.tmdb.normalizeLanguage
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.settings_tmdb_add_api_key_first
import nuvio.composeapp.generated.resources.settings_tmdb_api_key_label
import nuvio.composeapp.generated.resources.settings_tmdb_enable_enrichment
import nuvio.composeapp.generated.resources.settings_tmdb_enable_enrichment_description
import nuvio.composeapp.generated.resources.settings_tmdb_enter_api_key
import nuvio.composeapp.generated.resources.settings_tmdb_language_code_label
import nuvio.composeapp.generated.resources.settings_tmdb_module_artwork
import nuvio.composeapp.generated.resources.settings_tmdb_module_artwork_description
import nuvio.composeapp.generated.resources.settings_tmdb_module_basic_info
import nuvio.composeapp.generated.resources.settings_tmdb_module_basic_info_description
import nuvio.composeapp.generated.resources.settings_tmdb_module_collections
import nuvio.composeapp.generated.resources.settings_tmdb_module_collections_description
import nuvio.composeapp.generated.resources.settings_tmdb_module_credits
import nuvio.composeapp.generated.resources.settings_tmdb_module_credits_description
import nuvio.composeapp.generated.resources.settings_tmdb_module_details
import nuvio.composeapp.generated.resources.settings_tmdb_module_details_description
import nuvio.composeapp.generated.resources.settings_tmdb_module_episodes
import nuvio.composeapp.generated.resources.settings_tmdb_module_episodes_description
import nuvio.composeapp.generated.resources.settings_tmdb_module_more_like_this
import nuvio.composeapp.generated.resources.settings_tmdb_module_more_like_this_description
import nuvio.composeapp.generated.resources.settings_tmdb_module_networks
import nuvio.composeapp.generated.resources.settings_tmdb_module_networks_description
import nuvio.composeapp.generated.resources.settings_tmdb_module_production_companies
import nuvio.composeapp.generated.resources.settings_tmdb_module_production_companies_description
import nuvio.composeapp.generated.resources.settings_tmdb_module_season_posters
import nuvio.composeapp.generated.resources.settings_tmdb_module_season_posters_description
import nuvio.composeapp.generated.resources.settings_tmdb_module_trailers
import nuvio.composeapp.generated.resources.settings_tmdb_module_trailers_description
import nuvio.composeapp.generated.resources.settings_tmdb_personal_api_key
import nuvio.composeapp.generated.resources.settings_tmdb_preferred_language
import nuvio.composeapp.generated.resources.settings_tmdb_preferred_language_description
import nuvio.composeapp.generated.resources.settings_tmdb_section_credentials
import nuvio.composeapp.generated.resources.settings_tmdb_section_localization
import nuvio.composeapp.generated.resources.settings_tmdb_section_modules
import nuvio.composeapp.generated.resources.settings_tmdb_section_title
import nuvio.composeapp.generated.resources.settings_tmdb_filename_catalogs
import nuvio.composeapp.generated.resources.settings_tmdb_filename_catalogs_description
import nuvio.composeapp.generated.resources.settings_tmdb_filename_catalogs_missing_key
import nuvio.composeapp.generated.resources.settings_tmdb_filename_catalogs_section
import nuvio.composeapp.generated.resources.settings_tmdb_hero_artwork_addon
import nuvio.composeapp.generated.resources.settings_tmdb_hero_artwork_addon_description
import nuvio.composeapp.generated.resources.settings_tmdb_hero_artwork_description
import nuvio.composeapp.generated.resources.settings_tmdb_hero_artwork_missing_key
import nuvio.composeapp.generated.resources.settings_tmdb_hero_artwork_movies_tvdb_shows
import nuvio.composeapp.generated.resources.settings_tmdb_hero_artwork_movies_tvdb_shows_description
import nuvio.composeapp.generated.resources.settings_tmdb_hero_artwork_section
import nuvio.composeapp.generated.resources.settings_tmdb_hero_artwork_tmdb
import nuvio.composeapp.generated.resources.settings_tmdb_hero_artwork_tmdb_description
import nuvio.composeapp.generated.resources.settings_tmdb_hero_artwork_title
import nuvio.composeapp.generated.resources.settings_tmdb_library_posters_description
import nuvio.composeapp.generated.resources.settings_tmdb_library_posters_missing_template
import nuvio.composeapp.generated.resources.settings_tmdb_library_posters_section
import nuvio.composeapp.generated.resources.settings_tmdb_library_posters_test_description
import nuvio.composeapp.generated.resources.settings_tmdb_library_posters_test_ok
import nuvio.composeapp.generated.resources.settings_tmdb_library_posters_test_running
import nuvio.composeapp.generated.resources.settings_tmdb_library_posters_test_title
import nuvio.composeapp.generated.resources.settings_tmdb_library_posters_title
import nuvio.composeapp.generated.resources.settings_tmdb_poster_template
import nuvio.composeapp.generated.resources.settings_tmdb_poster_template_description
import nuvio.composeapp.generated.resources.settings_tmdb_poster_template_label
import nuvio.composeapp.generated.resources.settings_tvdb_api_key
import nuvio.composeapp.generated.resources.settings_tvdb_api_key_description
import nuvio.composeapp.generated.resources.settings_tvdb_api_key_label
import nuvio.composeapp.generated.resources.settings_tvdb_api_key_section
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.tmdbSettingsContent(
    isTablet: Boolean,
    settings: TmdbSettings,
) {
    val enrichmentControlsEnabled = settings.enabled && settings.hasApiKey
    val localizationEnabled = settings.hasApiKey

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_tmdb_section_title),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_tmdb_enable_enrichment),
                    description = stringResource(Res.string.settings_tmdb_enable_enrichment_description),
                    checked = settings.enabled,
                    enabled = settings.hasApiKey,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("tmdb-enable")),
                    onCheckedChange = TmdbSettingsRepository::setEnabled,
                )
                if (!settings.hasApiKey) {
                    SettingsGroupDivider(isTablet = isTablet)
                    TmdbInfoRow(
                        isTablet = isTablet,
                        text = stringResource(Res.string.settings_tmdb_add_api_key_first),
                    )
                }
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_tmdb_section_credentials),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                TmdbApiKeyRow(
                    isTablet = isTablet,
                    value = settings.apiKey,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("tmdb-api-key")),
                    onApiKeyCommitted = TmdbSettingsRepository::setApiKey,
                )
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_tmdb_section_localization),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                TmdbLanguageRow(
                    isTablet = isTablet,
                    value = settings.language,
                    enabled = localizationEnabled,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("tmdb-language")),
                    onLanguageCommitted = TmdbSettingsRepository::setLanguage,
                )
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_tmdb_section_modules),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                TmdbToggleRow(
                    isTablet = isTablet,
                    title = stringResource(Res.string.settings_tmdb_module_trailers),
                    description = stringResource(Res.string.settings_tmdb_module_trailers_description),
                    checked = settings.useTrailers,
                    enabled = enrichmentControlsEnabled,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("tmdb-trailers")),
                    onCheckedChange = TmdbSettingsRepository::setUseTrailers,
                )
                SettingsGroupDivider(isTablet = isTablet)
                TmdbToggleRow(
                    isTablet = isTablet,
                    title = stringResource(Res.string.settings_tmdb_module_artwork),
                    description = stringResource(Res.string.settings_tmdb_module_artwork_description),
                    checked = settings.useArtwork,
                    enabled = enrichmentControlsEnabled,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("tmdb-artwork")),
                    onCheckedChange = TmdbSettingsRepository::setUseArtwork,
                )
                SettingsGroupDivider(isTablet = isTablet)
                TmdbToggleRow(
                    isTablet = isTablet,
                    title = stringResource(Res.string.settings_tmdb_module_basic_info),
                    description = stringResource(Res.string.settings_tmdb_module_basic_info_description),
                    checked = settings.useBasicInfo,
                    enabled = enrichmentControlsEnabled,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("tmdb-basic-info")),
                    onCheckedChange = TmdbSettingsRepository::setUseBasicInfo,
                )
                SettingsGroupDivider(isTablet = isTablet)
                TmdbToggleRow(
                    isTablet = isTablet,
                    title = stringResource(Res.string.settings_tmdb_module_details),
                    description = stringResource(Res.string.settings_tmdb_module_details_description),
                    checked = settings.useDetails,
                    enabled = enrichmentControlsEnabled,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("tmdb-details")),
                    onCheckedChange = TmdbSettingsRepository::setUseDetails,
                )
                SettingsGroupDivider(isTablet = isTablet)
                TmdbToggleRow(
                    isTablet = isTablet,
                    title = stringResource(Res.string.settings_tmdb_module_credits),
                    description = stringResource(Res.string.settings_tmdb_module_credits_description),
                    checked = settings.useCredits,
                    enabled = enrichmentControlsEnabled,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("tmdb-credits")),
                    onCheckedChange = TmdbSettingsRepository::setUseCredits,
                )
                SettingsGroupDivider(isTablet = isTablet)
                TmdbToggleRow(
                    isTablet = isTablet,
                    title = stringResource(Res.string.settings_tmdb_module_production_companies),
                    description = stringResource(Res.string.settings_tmdb_module_production_companies_description),
                    checked = settings.useProductions,
                    enabled = enrichmentControlsEnabled,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("tmdb-companies")),
                    onCheckedChange = TmdbSettingsRepository::setUseProductions,
                )
                SettingsGroupDivider(isTablet = isTablet)
                TmdbToggleRow(
                    isTablet = isTablet,
                    title = stringResource(Res.string.settings_tmdb_module_networks),
                    description = stringResource(Res.string.settings_tmdb_module_networks_description),
                    checked = settings.useNetworks,
                    enabled = enrichmentControlsEnabled,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("tmdb-networks")),
                    onCheckedChange = TmdbSettingsRepository::setUseNetworks,
                )
                SettingsGroupDivider(isTablet = isTablet)
                TmdbToggleRow(
                    isTablet = isTablet,
                    title = stringResource(Res.string.settings_tmdb_module_episodes),
                    description = stringResource(Res.string.settings_tmdb_module_episodes_description),
                    checked = settings.useEpisodes,
                    enabled = enrichmentControlsEnabled,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("tmdb-episodes")),
                    onCheckedChange = TmdbSettingsRepository::setUseEpisodes,
                )
                SettingsGroupDivider(isTablet = isTablet)
                TmdbToggleRow(
                    isTablet = isTablet,
                    title = stringResource(Res.string.settings_tmdb_module_season_posters),
                    description = stringResource(Res.string.settings_tmdb_module_season_posters_description),
                    checked = settings.useSeasonPosters,
                    enabled = enrichmentControlsEnabled,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("tmdb-season-posters")),
                    onCheckedChange = TmdbSettingsRepository::setUseSeasonPosters,
                )
                SettingsGroupDivider(isTablet = isTablet)
                TmdbToggleRow(
                    isTablet = isTablet,
                    title = stringResource(Res.string.settings_tmdb_module_more_like_this),
                    description = stringResource(Res.string.settings_tmdb_module_more_like_this_description),
                    checked = settings.useMoreLikeThis,
                    enabled = enrichmentControlsEnabled,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("tmdb-more-like-this")),
                    onCheckedChange = TmdbSettingsRepository::setUseMoreLikeThis,
                )
                SettingsGroupDivider(isTablet = isTablet)
                TmdbToggleRow(
                    isTablet = isTablet,
                    title = stringResource(Res.string.settings_tmdb_module_collections),
                    description = stringResource(Res.string.settings_tmdb_module_collections_description),
                    checked = settings.useCollections,
                    enabled = enrichmentControlsEnabled,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("tmdb-collections")),
                    onCheckedChange = TmdbSettingsRepository::setUseCollections,
                )
            }
        }
    }

    item {
        val tvdbSettingsUiState by TvdbSettingsRepository.uiState.collectAsStateWithLifecycle()
        SettingsSection(
            title = stringResource(Res.string.settings_tmdb_hero_artwork_section),
            isTablet = isTablet,
        ) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.TmdbHeroImages),
            ) {
                TmdbInfoRow(
                    isTablet = isTablet,
                    text = stringResource(Res.string.settings_tmdb_hero_artwork_description),
                )
                SettingsGroupDivider(isTablet = isTablet)
                val heroImageOptions = buildList {
                    add(SettingsChoiceOption(HeroImageSource.Addon, stringResource(Res.string.settings_tmdb_hero_artwork_addon)))
                    if (settings.hasApiKey) {
                        add(SettingsChoiceOption(HeroImageSource.TmdbOnly, stringResource(Res.string.settings_tmdb_hero_artwork_tmdb)))
                    }
                    if (settings.hasApiKey && tvdbSettingsUiState.hasApiKey) {
                        add(SettingsChoiceOption(HeroImageSource.TmdbMoviesTvdbShows, stringResource(Res.string.settings_tmdb_hero_artwork_movies_tvdb_shows)))
                    }
                }
                val selectedHeroImageSource = if (heroImageOptions.any { it.value == settings.heroImageSource }) {
                    settings.heroImageSource
                } else {
                    HeroImageSource.Addon
                }
                SettingsChoiceRow(
                    title = stringResource(Res.string.settings_tmdb_hero_artwork_title),
                    description = selectedHeroImageSource.settingsDescription(),
                    options = heroImageOptions,
                    selectedValue = selectedHeroImageSource,
                    isTablet = isTablet,
                    onSelected = TmdbSettingsRepository::setHeroImageSource,
                )
                if (!settings.hasApiKey) {
                    SettingsGroupDivider(isTablet = isTablet)
                    TmdbInfoRow(
                        isTablet = isTablet,
                        text = stringResource(Res.string.settings_tmdb_hero_artwork_missing_key),
                    )
                }
            }
        }
    }

    item {
        val tvdbSettingsUiState by TvdbSettingsRepository.uiState.collectAsStateWithLifecycle()
        SettingsSection(
            title = stringResource(Res.string.settings_tvdb_api_key_section),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                TvdbApiKeyRow(
                    isTablet = isTablet,
                    value = tvdbSettingsUiState.apiKey,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.TvdbApiKey),
                    onKeyCommitted = TvdbSettingsRepository::setApiKey,
                )
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_tmdb_filename_catalogs_section),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_tmdb_filename_catalogs),
                    description = stringResource(Res.string.settings_tmdb_filename_catalogs_description),
                    checked = settings.resolveFilenameCatalogs,
                    enabled = settings.hasApiKey,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("tmdb-filename-catalogs")),
                    onCheckedChange = TmdbSettingsRepository::setResolveFilenameCatalogs,
                )
                if (!settings.hasApiKey) {
                    SettingsGroupDivider(isTablet = isTablet)
                    TmdbInfoRow(
                        isTablet = isTablet,
                        text = stringResource(Res.string.settings_tmdb_filename_catalogs_missing_key),
                    )
                }
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_tmdb_library_posters_section),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_tmdb_library_posters_title),
                    description = stringResource(Res.string.settings_tmdb_library_posters_description),
                    checked = settings.libraryPosterEnabled,
                    enabled = settings.libraryPosterUrlTemplate.isNotBlank(),
                    isTablet = isTablet,
                    onCheckedChange = TmdbSettingsRepository::setLibraryPosterEnabled,
                )
                if (settings.libraryPosterUrlTemplate.isBlank()) {
                    SettingsGroupDivider(isTablet = isTablet)
                    TmdbInfoRow(
                        isTablet = isTablet,
                        text = stringResource(Res.string.settings_tmdb_library_posters_missing_template),
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                TmdbLibraryPosterRow(
                    isTablet = isTablet,
                    value = settings.libraryPosterUrlTemplate,
                    onTemplateCommitted = TmdbSettingsRepository::setLibraryPosterUrlTemplate,
                )
                if (settings.libraryPosterUrlTemplate.isNotBlank()) {
                    SettingsGroupDivider(isTablet = isTablet)
                    TmdbLibraryPosterTestRow(isTablet = isTablet, settings = settings)
                }
            }
        }
    }
}

@Composable
private fun HeroImageSource.settingsDescription(): String =
    when (this) {
        HeroImageSource.Addon -> stringResource(Res.string.settings_tmdb_hero_artwork_addon_description)
        HeroImageSource.TmdbOnly -> stringResource(Res.string.settings_tmdb_hero_artwork_tmdb_description)
        HeroImageSource.TmdbMoviesTvdbShows -> stringResource(Res.string.settings_tmdb_hero_artwork_movies_tvdb_shows_description)
    }

@Composable
private fun TmdbApiKeyRow(
    isTablet: Boolean,
    value: String,
    modifier: Modifier = Modifier,
    onApiKeyCommitted: (String) -> Unit,
) {
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val verticalPadding = if (isTablet) 16.dp else 14.dp
    var draft by rememberSaveable(value) { mutableStateOf(value) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(Res.string.settings_tmdb_personal_api_key),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(Res.string.settings_tmdb_enter_api_key),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val normalizedDraft = draft.trim()

        SettingsSecretTextField(
            value = draft,
            onValueChange = {
                draft = it
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(Res.string.settings_tmdb_api_key_label),
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    draft = normalizedDraft
                    onApiKeyCommitted(normalizedDraft)
                },
                enabled = normalizedDraft != value,
            ) {
                Text(stringResource(Res.string.action_save))
            }
        }
    }
}

@Composable
private fun TvdbApiKeyRow(
    isTablet: Boolean,
    value: String,
    modifier: Modifier = Modifier,
    onKeyCommitted: (String) -> Unit,
) {
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val verticalPadding = if (isTablet) 16.dp else 14.dp
    var draft by rememberSaveable(value) { mutableStateOf(value) }
    val normalizedDraft = draft.trim()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(Res.string.settings_tvdb_api_key),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(Res.string.settings_tvdb_api_key_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingsSecretTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(Res.string.settings_tvdb_api_key_label),
        )
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { onKeyCommitted(normalizedDraft) },
                enabled = normalizedDraft != value.trim(),
            ) {
                Text(stringResource(Res.string.action_save))
            }
        }
    }
}

@Composable
private fun TmdbLibraryPosterRow(
    isTablet: Boolean,
    value: String,
    onTemplateCommitted: (String) -> Unit,
) {
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val verticalPadding = if (isTablet) 16.dp else 14.dp
    var draft by rememberSaveable(value) { mutableStateOf(value) }
    val normalizedDraft = draft.trim()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(Res.string.settings_tmdb_poster_template),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(Res.string.settings_tmdb_poster_template_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth().trackTextInputFocus(),
            minLines = 2,
            maxLines = 6,
            label = { Text(stringResource(Res.string.settings_tmdb_poster_template_label)) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f),
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                disabledContainerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    draft = normalizedDraft
                    onTemplateCommitted(normalizedDraft)
                },
                enabled = normalizedDraft != value,
            ) {
                Text(stringResource(Res.string.action_save))
            }
        }
    }
}

@Composable
private fun TmdbLanguageRow(
    isTablet: Boolean,
    value: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onLanguageCommitted: (String) -> Unit,
) {
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val verticalPadding = if (isTablet) 16.dp else 14.dp
    var draft by rememberSaveable(value) { mutableStateOf(value) }
    val normalizedDraft = normalizeLanguage(draft)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(Res.string.settings_tmdb_preferred_language),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(Res.string.settings_tmdb_preferred_language_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it
            },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().trackTextInputFocus(),
            singleLine = true,
            label = { Text(stringResource(Res.string.settings_tmdb_language_code_label)) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f),
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                disabledContainerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    draft = normalizedDraft
                    onLanguageCommitted(normalizedDraft)
                },
                enabled = enabled && normalizedDraft != value,
            ) {
                Text(stringResource(Res.string.action_save))
            }
        }
    }
}

/**
 * One-shot check of the poster template against the service, with its answer shown verbatim.
 *
 * A poster service that rejects the request is invisible from the library: the card quietly falls
 * back to the plain poster, so a broken template and a service with no art for a title look exactly
 * the same. The service's own error text names the problem (a missing `tmdb_key=` on a self-hosted
 * instance, a bad host, an expired key) far faster than reading the library ever could.
 */
@Composable
private fun TmdbLibraryPosterTestRow(
    isTablet: Boolean,
    settings: TmdbSettings,
) {
    val scope = rememberCoroutineScope()
    val runningText = stringResource(Res.string.settings_tmdb_library_posters_test_running)
    val okText = stringResource(Res.string.settings_tmdb_library_posters_test_ok)
    var isRunning by remember { mutableStateOf(false) }
    var result by remember(settings.libraryPosterUrlTemplate) { mutableStateOf<String?>(null) }

    SettingsNavigationRow(
        title = stringResource(Res.string.settings_tmdb_library_posters_test_title),
        description = stringResource(Res.string.settings_tmdb_library_posters_test_description),
        enabled = !isRunning,
        isTablet = isTablet,
        onClick = {
            if (isRunning) return@SettingsNavigationRow
            isRunning = true
            result = null
            scope.launch {
                result = when (val probe = probeCustomPosterTemplate(settings)) {
                    is CustomPosterTemplateProbe.Ok -> okText
                    is CustomPosterTemplateProbe.Failed -> probe.detail
                }
                isRunning = false
            }
        },
    )
    val message = if (isRunning) runningText else result
    if (message != null) {
        TmdbInfoRow(isTablet = isTablet, text = message)
    }
}

@Composable
private fun TmdbInfoRow(
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

@Composable
private fun TmdbToggleRow(
    isTablet: Boolean,
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsSwitchRow(
        title = title,
        description = description,
        checked = checked,
        enabled = enabled,
        isTablet = isTablet,
        modifier = modifier,
        onCheckedChange = onCheckedChange,
    )
}
