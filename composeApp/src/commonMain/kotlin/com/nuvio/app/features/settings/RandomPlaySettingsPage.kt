package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HomeCatalogSettingsUiState
import com.nuvio.app.features.home.RandomPlayAction
import com.nuvio.app.features.home.RandomPlayCategory
import com.nuvio.app.features.home.RandomPlayGenres
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.randomPlaySettingsContent(
    isTablet: Boolean,
    settings: HomeCatalogSettingsUiState,
) {
    item {
        SettingsSection(
            title = stringResource(Res.string.random_play_section_catalog),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.random_play_enable),
                    description = stringResource(Res.string.random_play_enable_description),
                    checked = settings.randomPlayEnabled,
                    isTablet = isTablet,
                    onCheckedChange = HomeCatalogSettingsRepository::setRandomPlayEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.random_play_include_collections),
                    description = stringResource(Res.string.random_play_include_collections_description),
                    checked = settings.randomPlayIncludeCollections,
                    enabled = settings.randomPlayEnabled,
                    isTablet = isTablet,
                    onCheckedChange = HomeCatalogSettingsRepository::setRandomPlayIncludeCollections,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsChoiceRow(
                    title = stringResource(Res.string.random_play_click_action),
                    description = stringResource(Res.string.random_play_click_action_description),
                    options = listOf(
                        SettingsChoiceOption(
                            RandomPlayAction.Details,
                            stringResource(Res.string.random_play_action_details),
                        ),
                        SettingsChoiceOption(
                            RandomPlayAction.Play,
                            stringResource(Res.string.random_play_action_play),
                        ),
                    ),
                    selectedValue = settings.randomPlayAction,
                    enabled = settings.randomPlayEnabled,
                    isTablet = isTablet,
                    onSelected = HomeCatalogSettingsRepository::setRandomPlayAction,
                )
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.random_play_section_types),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                RandomPlayCategory.entries.forEachIndexed { index, category ->
                    SettingsSwitchRow(
                        title = stringResource(category.titleRes()),
                        checked = category in settings.randomPlayCategories,
                        enabled = settings.randomPlayEnabled,
                        isTablet = isTablet,
                        onCheckedChange = { enabled ->
                            HomeCatalogSettingsRepository.setRandomPlayCategoryEnabled(category, enabled)
                        },
                    )
                    if (index != RandomPlayCategory.entries.lastIndex) {
                        SettingsGroupDivider(isTablet = isTablet)
                    }
                }
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.random_play_section_filters),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsChoiceRow(
                    title = stringResource(Res.string.random_play_minimum_imdb),
                    description = stringResource(Res.string.random_play_minimum_imdb_description),
                    options = (0..18).map { halfPoint ->
                        val value = halfPoint / 2f
                        SettingsChoiceOption(
                            value,
                            if (value == 0f) {
                                stringResource(Res.string.random_play_any_rating)
                            } else {
                                value.toString()
                            },
                        )
                    },
                    selectedValue = settings.randomPlayMinimumImdbRating,
                    enabled = settings.randomPlayEnabled,
                    isTablet = isTablet,
                    onSelected = HomeCatalogSettingsRepository::setRandomPlayMinimumImdbRating,
                )
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.random_play_section_genres),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                RandomPlayGenres.forEachIndexed { index, genre ->
                    SettingsSwitchRow(
                        title = genre,
                        checked = genre in settings.randomPlayGenres,
                        enabled = settings.randomPlayEnabled,
                        isTablet = isTablet,
                        onCheckedChange = { enabled ->
                            HomeCatalogSettingsRepository.setRandomPlayGenreEnabled(genre, enabled)
                        },
                    )
                    if (index != RandomPlayGenres.lastIndex) {
                        SettingsGroupDivider(isTablet = isTablet)
                    }
                }
            }
        }
    }
}

private fun RandomPlayCategory.titleRes() = when (this) {
    RandomPlayCategory.Movie -> Res.string.random_play_movie
    RandomPlayCategory.Series -> Res.string.random_play_series
    RandomPlayCategory.AnimeMovie -> Res.string.random_play_anime_movie
    RandomPlayCategory.AnimeSeries -> Res.string.random_play_anime_series
}
