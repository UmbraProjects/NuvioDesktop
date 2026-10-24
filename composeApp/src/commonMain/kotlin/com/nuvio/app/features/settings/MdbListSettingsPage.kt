package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.mdblist.MDBLIST_REFRESH_DAY_CHOICES
import com.nuvio.app.features.mdblist.MdbListMetadataService
import com.nuvio.app.features.mdblist.MdbListRefreshPreset
import com.nuvio.app.features.mdblist.MdbListRefreshTier
import com.nuvio.app.features.mdblist.MdbListSettings
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.settings_mdb_add_api_key_first
import nuvio.composeapp.generated.resources.settings_mdb_api_key_description
import nuvio.composeapp.generated.resources.settings_mdb_api_key_label
import nuvio.composeapp.generated.resources.settings_mdb_api_key_title
import nuvio.composeapp.generated.resources.settings_mdb_enable_ratings
import nuvio.composeapp.generated.resources.settings_mdb_enable_ratings_description
import nuvio.composeapp.generated.resources.settings_mdb_enable_tracking
import nuvio.composeapp.generated.resources.settings_mdb_enable_tracking_description
import nuvio.composeapp.generated.resources.settings_mdb_section_api_key
import nuvio.composeapp.generated.resources.settings_mdb_refresh_pace
import nuvio.composeapp.generated.resources.settings_mdb_refresh_pace_description
import nuvio.composeapp.generated.resources.settings_mdb_refresh_pace_standard
import nuvio.composeapp.generated.resources.settings_mdb_refresh_pace_faster
import nuvio.composeapp.generated.resources.settings_mdb_refresh_pace_fastest
import nuvio.composeapp.generated.resources.settings_mdb_refresh_pace_auto
import nuvio.composeapp.generated.resources.settings_mdb_refresh_pace_custom
import nuvio.composeapp.generated.resources.settings_mdb_refresh_custom_note
import nuvio.composeapp.generated.resources.settings_mdb_refresh_hours
import nuvio.composeapp.generated.resources.settings_mdb_refresh_one_day
import nuvio.composeapp.generated.resources.settings_mdb_refresh_days
import nuvio.composeapp.generated.resources.settings_mdb_refresh_movie_unreleased
import nuvio.composeapp.generated.resources.settings_mdb_refresh_movie_unreleased_description
import nuvio.composeapp.generated.resources.settings_mdb_refresh_movie_cinema_first_week
import nuvio.composeapp.generated.resources.settings_mdb_refresh_movie_cinema_awaiting_streaming
import nuvio.composeapp.generated.resources.settings_mdb_refresh_movie_cinema_awaiting_streaming_description
import nuvio.composeapp.generated.resources.settings_mdb_refresh_movie_cinema_no_streaming_date
import nuvio.composeapp.generated.resources.settings_mdb_refresh_movie_early_streaming
import nuvio.composeapp.generated.resources.settings_mdb_refresh_movie_streaming
import nuvio.composeapp.generated.resources.settings_mdb_refresh_movie_settled
import nuvio.composeapp.generated.resources.settings_mdb_refresh_movie_settled_description
import nuvio.composeapp.generated.resources.settings_mdb_refresh_show_first_season
import nuvio.composeapp.generated.resources.settings_mdb_refresh_show_airing
import nuvio.composeapp.generated.resources.settings_mdb_refresh_show_idle
import nuvio.composeapp.generated.resources.settings_mdb_refresh_show_idle_description
import nuvio.composeapp.generated.resources.settings_mdb_refresh_not_found
import nuvio.composeapp.generated.resources.settings_mdb_section_rating_providers
import nuvio.composeapp.generated.resources.settings_mdb_section_refresh
import nuvio.composeapp.generated.resources.settings_mdb_section_title
import nuvio.composeapp.generated.resources.source_audience_score
import nuvio.composeapp.generated.resources.source_imdb
import nuvio.composeapp.generated.resources.source_letterboxd
import nuvio.composeapp.generated.resources.source_mal
import nuvio.composeapp.generated.resources.source_metacritic
import nuvio.composeapp.generated.resources.source_rotten_tomatoes
import nuvio.composeapp.generated.resources.source_tmdb
import nuvio.composeapp.generated.resources.source_trakt
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToLong

internal fun LazyListScope.mdbListSettingsContent(
    isTablet: Boolean,
    settings: MdbListSettings,
) {
    val providerControlsEnabled = settings.enabled && settings.hasApiKey

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_mdb_section_title),
            isTablet = isTablet,
        ) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("mdb-enable")),
            ) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_mdb_enable_ratings),
                    description = stringResource(Res.string.settings_mdb_enable_ratings_description),
                    checked = settings.enabled,
                    enabled = settings.hasApiKey,
                    isTablet = isTablet,
                    onCheckedChange = MdbListSettingsRepository::setEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                // Separate consent from the ratings toggle: one spends the key's request budget on
                // lookups, the other writes playback history to the user's MDBList account.
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_mdb_enable_tracking),
                    description = stringResource(Res.string.settings_mdb_enable_tracking_description),
                    checked = settings.trackingEnabled,
                    enabled = settings.hasApiKey,
                    isTablet = isTablet,
                    onCheckedChange = MdbListSettingsRepository::setTrackingEnabled,
                )
                if (!settings.hasApiKey) {
                    SettingsGroupDivider(isTablet = isTablet)
                    MdbListInfoRow(
                        isTablet = isTablet,
                        text = stringResource(Res.string.settings_mdb_add_api_key_first),
                    )
                }
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_mdb_section_api_key),
            isTablet = isTablet,
        ) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("mdb-api-key")),
            ) {
                MdbListApiKeyRow(
                    isTablet = isTablet,
                    value = settings.apiKey,
                    onApiKeyCommitted = MdbListSettingsRepository::setApiKey,
                )
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_mdb_section_rating_providers),
            isTablet = isTablet,
        ) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsSearchAnchors(
                    "mdb-imdb",
                    "mdb-tmdb",
                    "mdb-tomatoes",
                    "mdb-metacritic",
                    "mdb-trakt",
                    "mdb-letterboxd",
                    "mdb-audience",
                    "mdb-mal",
                ),
            ) {
                ProviderRows(
                    isTablet = isTablet,
                    settings = settings,
                    controlsEnabled = providerControlsEnabled,
                )
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_mdb_section_refresh),
            isTablet = isTablet,
        ) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("mdb-refresh-pace")),
            ) {
                RefreshRows(
                    isTablet = isTablet,
                    settings = settings,
                    controlsEnabled = providerControlsEnabled,
                )
            }
            if (settings.refreshPreset == MdbListRefreshPreset.Custom) {
                SettingsSectionNote(
                    text = stringResource(Res.string.settings_mdb_refresh_custom_note),
                    isTablet = isTablet,
                )
            }
        }
    }
}

private val refreshTierLabels: List<Triple<MdbListRefreshTier, StringResource, StringResource?>> = listOf(
    Triple(MdbListRefreshTier.MovieUnreleased, Res.string.settings_mdb_refresh_movie_unreleased, Res.string.settings_mdb_refresh_movie_unreleased_description),
    Triple(MdbListRefreshTier.MovieCinemaFirstWeek, Res.string.settings_mdb_refresh_movie_cinema_first_week, null),
    Triple(MdbListRefreshTier.MovieCinemaAwaitingStreaming, Res.string.settings_mdb_refresh_movie_cinema_awaiting_streaming, Res.string.settings_mdb_refresh_movie_cinema_awaiting_streaming_description),
    Triple(MdbListRefreshTier.MovieCinemaNoStreamingDate, Res.string.settings_mdb_refresh_movie_cinema_no_streaming_date, null),
    Triple(MdbListRefreshTier.MovieEarlyStreaming, Res.string.settings_mdb_refresh_movie_early_streaming, null),
    Triple(MdbListRefreshTier.MovieStreaming, Res.string.settings_mdb_refresh_movie_streaming, null),
    Triple(MdbListRefreshTier.MovieSettled, Res.string.settings_mdb_refresh_movie_settled, Res.string.settings_mdb_refresh_movie_settled_description),
    Triple(MdbListRefreshTier.ShowFirstSeason, Res.string.settings_mdb_refresh_show_first_season, null),
    Triple(MdbListRefreshTier.ShowAiring, Res.string.settings_mdb_refresh_show_airing, null),
    Triple(MdbListRefreshTier.ShowIdle, Res.string.settings_mdb_refresh_show_idle, Res.string.settings_mdb_refresh_show_idle_description),
    Triple(MdbListRefreshTier.NotFound, Res.string.settings_mdb_refresh_not_found, null),
)

@Composable
private fun RefreshRows(
    isTablet: Boolean,
    settings: MdbListSettings,
    controlsEnabled: Boolean,
) {
    SettingsDropdownChoiceRow(
        title = stringResource(Res.string.settings_mdb_refresh_pace),
        description = stringResource(Res.string.settings_mdb_refresh_pace_description),
        options = listOf(
            SettingsChoiceOption(MdbListRefreshPreset.Auto, stringResource(Res.string.settings_mdb_refresh_pace_auto)),
            SettingsChoiceOption(MdbListRefreshPreset.Standard, stringResource(Res.string.settings_mdb_refresh_pace_standard)),
            SettingsChoiceOption(MdbListRefreshPreset.Faster, stringResource(Res.string.settings_mdb_refresh_pace_faster)),
            SettingsChoiceOption(MdbListRefreshPreset.Fastest, stringResource(Res.string.settings_mdb_refresh_pace_fastest)),
            SettingsChoiceOption(MdbListRefreshPreset.Custom, stringResource(Res.string.settings_mdb_refresh_pace_custom)),
        ),
        selectedValue = settings.refreshPreset,
        enabled = controlsEnabled,
        isTablet = isTablet,
        onSelected = MdbListSettingsRepository::setRefreshPreset,
    )
    if (settings.refreshPreset != MdbListRefreshPreset.Custom) return

    // Templates resolved once, not per option: each stringResource is a blocking resource read.
    val hoursTemplate = stringResource(Res.string.settings_mdb_refresh_hours)
    val oneDay = stringResource(Res.string.settings_mdb_refresh_one_day)
    val daysTemplate = stringResource(Res.string.settings_mdb_refresh_days)
    fun label(days: Double): String = when {
        days < 1.0 -> hoursTemplate.replace(REFRESH_PLACEHOLDER, formatRefreshNumber(days * 24.0))
        days == 1.0 -> oneDay
        else -> daysTemplate.replace(REFRESH_PLACEHOLDER, formatRefreshNumber(days))
    }

    refreshTierLabels.forEach { (tier, titleRes, descriptionRes) ->
        val current = settings.refreshDays(tier)
        // A value carried over from a scaled preset (1.5 days) stays selectable alongside the steps.
        val choices = (MDBLIST_REFRESH_DAY_CHOICES + current).distinct().sorted()
        SettingsGroupDivider(isTablet = isTablet)
        SettingsDropdownChoiceRow(
            title = stringResource(titleRes),
            description = descriptionRes?.let { stringResource(it) },
            options = choices.map { SettingsChoiceOption(it, label(it)) },
            selectedValue = current,
            enabled = controlsEnabled,
            isTablet = isTablet,
            onSelected = { days -> MdbListSettingsRepository.setRefreshDays(tier, days) },
        )
    }
}

private const val REFRESH_PLACEHOLDER = "%1${'$'}s"

private fun formatRefreshNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else ((value * 10.0).roundToLong() / 10.0).toString()

@Composable
private fun ProviderRows(
    isTablet: Boolean,
    settings: MdbListSettings,
    controlsEnabled: Boolean,
) {
    val providers = listOf(
        MdbListMetadataService.PROVIDER_IMDB to Res.string.source_imdb,
        MdbListMetadataService.PROVIDER_TMDB to Res.string.source_tmdb,
        MdbListMetadataService.PROVIDER_TOMATOES to Res.string.source_rotten_tomatoes,
        MdbListMetadataService.PROVIDER_METACRITIC to Res.string.source_metacritic,
        MdbListMetadataService.PROVIDER_TRAKT to Res.string.source_trakt,
        MdbListMetadataService.PROVIDER_LETTERBOXD to Res.string.source_letterboxd,
        MdbListMetadataService.PROVIDER_AUDIENCE to Res.string.source_audience_score,
        MdbListMetadataService.PROVIDER_MAL to Res.string.source_mal,
    )

    providers.forEachIndexed { index, (providerId, providerLabelRes) ->
        SettingsSwitchRow(
            title = stringResource(providerLabelRes),
            checked = settings.isProviderEnabled(providerId),
            enabled = controlsEnabled,
            isTablet = isTablet,
            onCheckedChange = { checked ->
                MdbListSettingsRepository.setProviderEnabled(providerId, checked)
            },
        )
        if (index < providers.lastIndex) {
            SettingsGroupDivider(isTablet = isTablet)
        }
    }
}

@Composable
private fun MdbListApiKeyRow(
    isTablet: Boolean,
    value: String,
    onApiKeyCommitted: (String) -> Unit,
) {
    SettingsTextInputRow(
        title = stringResource(Res.string.settings_mdb_api_key_title),
        description = stringResource(Res.string.settings_mdb_api_key_description),
        value = value,
        placeholder = stringResource(Res.string.settings_mdb_api_key_label),
        isTablet = isTablet,
        secret = true,
        onSave = onApiKeyCommitted,
    )
}

@Composable
private fun MdbListInfoRow(
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
