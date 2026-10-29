package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.music.MusicPlayback
import com.nuvio.app.features.music.library.MusicLibraryRepository
import com.nuvio.app.features.preroll.PreshowSettings
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_music_audiodb_key
import nuvio.composeapp.generated.resources.settings_music_audiodb_key_description
import nuvio.composeapp.generated.resources.settings_music_fanart_key
import nuvio.composeapp.generated.resources.settings_music_fanart_key_description
import nuvio.composeapp.generated.resources.settings_music_keep_audio_alive
import nuvio.composeapp.generated.resources.settings_music_keep_audio_alive_description
import nuvio.composeapp.generated.resources.settings_music_reduced_motion
import nuvio.composeapp.generated.resources.settings_music_reduced_motion_description
import nuvio.composeapp.generated.resources.settings_music_section_general
import nuvio.composeapp.generated.resources.settings_music_section_playback
import nuvio.composeapp.generated.resources.settings_music_section_sources
import nuvio.composeapp.generated.resources.settings_music_show_tab
import nuvio.composeapp.generated.resources.settings_music_show_tab_description
import nuvio.composeapp.generated.resources.settings_music_where_next
import nuvio.composeapp.generated.resources.settings_music_where_next_description
import org.jetbrains.compose.resources.stringResource

/**
 * fork:music
 *
 * Music: whether the tab is in the navigation bar at all, where artwork and details come from, and
 * how playback behaves. These used to sit behind a gear on the Library page (and the keep-alive
 * switch under Pre-show); they live here with the rest of the app's settings now.
 */
internal fun LazyListScope.musicSettingsContent(isTablet: Boolean) {
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_music_section_general),
            isTablet = isTablet,
        ) {
            remember { MusicLibraryRepository.ensureLoaded() }
            val state by MusicLibraryRepository.state.collectAsStateWithLifecycle()
            val settings = state.settings
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_music_show_tab),
                    description = stringResource(Res.string.settings_music_show_tab_description),
                    checked = settings.tabEnabled,
                    isTablet = isTablet,
                    onCheckedChange = { on ->
                        // Hidden means gone: nothing keeps playing from a tab that is no longer there.
                        if (!on) MusicPlayback.player.stop()
                        MusicLibraryRepository.updateSettings(settings.copy(tabEnabled = on))
                    },
                )
                SettingsGroupDivider(isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_music_reduced_motion),
                    description = stringResource(Res.string.settings_music_reduced_motion_description),
                    checked = settings.reducedMotion,
                    isTablet = isTablet,
                    onCheckedChange = { MusicLibraryRepository.updateSettings(settings.copy(reducedMotion = it)) },
                )
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_music_section_sources),
            isTablet = isTablet,
        ) {
            val state by MusicLibraryRepository.state.collectAsStateWithLifecycle()
            val settings = state.settings
            SettingsGroup(isTablet = isTablet) {
                SettingsTextInputRow(
                    title = stringResource(Res.string.settings_music_fanart_key),
                    description = stringResource(Res.string.settings_music_fanart_key_description),
                    value = settings.fanartApiKey,
                    placeholder = "Optional",
                    isTablet = isTablet,
                    secret = true,
                    summarizeAsConfigured = true,
                    onSave = { MusicLibraryRepository.updateSettings(MusicLibraryRepository.state.value.settings.copy(fanartApiKey = it)) },
                )
                SettingsGroupDivider(isTablet)
                SettingsTextInputRow(
                    title = stringResource(Res.string.settings_music_audiodb_key),
                    description = stringResource(Res.string.settings_music_audiodb_key_description),
                    value = settings.audioDbKey,
                    placeholder = "Optional",
                    isTablet = isTablet,
                    secret = true,
                    summarizeAsConfigured = true,
                    onSave = { MusicLibraryRepository.updateSettings(MusicLibraryRepository.state.value.settings.copy(audioDbKey = it)) },
                )
                SettingsGroupDivider(isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_music_where_next),
                    description = stringResource(Res.string.settings_music_where_next_description),
                    checked = settings.autoWhereNext,
                    isTablet = isTablet,
                    onCheckedChange = { MusicLibraryRepository.updateSettings(settings.copy(autoWhereNext = it)) },
                )
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_music_section_playback),
            isTablet = isTablet,
        ) {
            val keepAlive by PreshowSettings.musicKeepAudioAlive.collectAsStateWithLifecycle()
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_music_keep_audio_alive),
                    description = stringResource(Res.string.settings_music_keep_audio_alive_description),
                    checked = keepAlive,
                    isTablet = isTablet,
                    onCheckedChange = PreshowSettings::setMusicKeepAudioAlive,
                )
            }
        }
    }
}
