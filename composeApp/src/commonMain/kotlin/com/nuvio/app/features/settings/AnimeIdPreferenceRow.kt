package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.locallibrary.LocalLibraryRepository
import com.nuvio.app.features.metadata.AnimeIdPreference
import com.nuvio.app.features.metadata.AnimeIdPreferenceRepository
import com.nuvio.app.features.simkl.SimklProgressRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_anime_id_imdb
import nuvio.composeapp.generated.resources.settings_anime_id_kitsu
import nuvio.composeapp.generated.resources.settings_anime_id_mal
import nuvio.composeapp.generated.resources.settings_anime_id_preference
import nuvio.composeapp.generated.resources.settings_anime_id_preference_description
import org.jetbrains.compose.resources.stringResource

/**
 * One control, shown in two places.
 *
 * The preference governs how anime is addressed for tracking-provider rows *and* for matched local
 * library titles, so it appears beside the Continue Watching source (when that is SIMKL) and in the
 * local library's playback section. Both edit the same stored value — it is one setting reachable
 * from either surface it affects, not two settings that could disagree.
 */
@Composable
internal fun AnimeIdPreferenceRow(
    isTablet: Boolean,
    modifier: Modifier = Modifier,
) {
    val preference by AnimeIdPreferenceRepository.uiState.collectAsStateWithLifecycle()
    SettingsChoiceRow(
        title = stringResource(Res.string.settings_anime_id_preference),
        description = stringResource(Res.string.settings_anime_id_preference_description),
        options = listOf(
            SettingsChoiceOption(
                value = AnimeIdPreference.IMDB,
                label = stringResource(Res.string.settings_anime_id_imdb),
            ),
            SettingsChoiceOption(
                value = AnimeIdPreference.MAL,
                label = stringResource(Res.string.settings_anime_id_mal),
            ),
            SettingsChoiceOption(
                value = AnimeIdPreference.KITSU,
                label = stringResource(Res.string.settings_anime_id_kitsu),
            ),
        ),
        selectedValue = preference,
        isTablet = isTablet,
        modifier = modifier,
        onSelected = { selected ->
            AnimeIdPreferenceRepository.set(selected)
            // Both surfaces derive their content ids rather than storing them, so reloading is all
            // that is needed for the change to take effect. Done here because the preference sits
            // below both and must not depend on either.
            SimklProgressRepository.refreshAsync()
            LocalLibraryRepository.rescan()
        },
    )
}
