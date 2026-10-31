package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.music.library.MusicBrainzOfflineState
import com.nuvio.app.features.music.library.musicBrainzOfflineCopy
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
import nuvio.composeapp.generated.resources.settings_music_lastfm_key
import nuvio.composeapp.generated.resources.settings_music_mb_offline
import nuvio.composeapp.generated.resources.settings_music_nebula_link
import nuvio.composeapp.generated.resources.settings_music_nebula_link_description
import nuvio.composeapp.generated.resources.settings_music_prefer_addon
import nuvio.composeapp.generated.resources.settings_music_prefer_addon_description
import nuvio.composeapp.generated.resources.settings_music_mb_offline_absent
import nuvio.composeapp.generated.resources.settings_music_mb_offline_building
import nuvio.composeapp.generated.resources.settings_music_mb_offline_cancel
import nuvio.composeapp.generated.resources.settings_music_mb_offline_delete
import nuvio.composeapp.generated.resources.settings_music_mb_offline_download
import nuvio.composeapp.generated.resources.settings_music_mb_offline_downloading
import nuvio.composeapp.generated.resources.settings_music_mb_offline_failed
import nuvio.composeapp.generated.resources.settings_music_mb_offline_newer
import nuvio.composeapp.generated.resources.settings_music_mb_offline_ready
import nuvio.composeapp.generated.resources.settings_music_mb_offline_retry
import nuvio.composeapp.generated.resources.settings_music_mb_offline_update
import nuvio.composeapp.generated.resources.settings_music_lastfm_key_description
import nuvio.composeapp.generated.resources.settings_music_keep_audio_alive_description
import nuvio.composeapp.generated.resources.settings_music_skip_non_music
import nuvio.composeapp.generated.resources.settings_music_skip_non_music_description
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
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsSearchAnchors("music-show-tab", "music-reduced-motion"),
            ) {
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
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsSearchAnchors(
                    "music-fanart-key",
                    "music-audiodb-key",
                    "music-lastfm-key",
                    "music-where-next",
                    "music-mb-offline",
                ),
            ) {
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
                SettingsTextInputRow(
                    title = stringResource(Res.string.settings_music_lastfm_key),
                    description = stringResource(Res.string.settings_music_lastfm_key_description),
                    value = settings.lastFmApiKey,
                    placeholder = "Optional",
                    isTablet = isTablet,
                    secret = true,
                    summarizeAsConfigured = true,
                    onSave = { MusicLibraryRepository.updateSettings(MusicLibraryRepository.state.value.settings.copy(lastFmApiKey = it)) },
                )
                SettingsGroupDivider(isTablet)
                SettingsTextInputRow(
                    title = stringResource(Res.string.settings_music_nebula_link),
                    description = stringResource(Res.string.settings_music_nebula_link_description),
                    value = settings.nebulaSecretLink,
                    placeholder = "Optional",
                    isTablet = isTablet,
                    secret = true,
                    summarizeAsConfigured = true,
                    onSave = { MusicLibraryRepository.updateSettings(MusicLibraryRepository.state.value.settings.copy(nebulaSecretLink = it.trim())) },
                )
                if (settings.nebulaSecretLink.isNotBlank()) {
                    SettingsGroupDivider(isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_music_prefer_addon),
                        description = stringResource(Res.string.settings_music_prefer_addon_description),
                        checked = settings.preferAddon,
                        isTablet = isTablet,
                        onCheckedChange = { MusicLibraryRepository.updateSettings(settings.copy(preferAddon = it)) },
                    )
                }
                SettingsGroupDivider(isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_music_where_next),
                    description = stringResource(Res.string.settings_music_where_next_description),
                    checked = settings.autoWhereNext,
                    isTablet = isTablet,
                    onCheckedChange = { MusicLibraryRepository.updateSettings(settings.copy(autoWhereNext = it)) },
                )
                SettingsGroupDivider(isTablet)
                MusicBrainzOfflineRow(isTablet)
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_music_section_playback),
            isTablet = isTablet,
        ) {
            val keepAlive by PreshowSettings.musicKeepAudioAlive.collectAsStateWithLifecycle()
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsSearchAnchors("music-keep-audio-alive"),
            ) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_music_keep_audio_alive),
                    description = stringResource(Res.string.settings_music_keep_audio_alive_description),
                    checked = keepAlive,
                    isTablet = isTablet,
                    onCheckedChange = PreshowSettings::setMusicKeepAudioAlive,
                )
            }
            val musicSettings by MusicLibraryRepository.state.collectAsStateWithLifecycle()
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsSearchAnchors("music-skip-non-music"),
            ) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_music_skip_non_music),
                    description = stringResource(Res.string.settings_music_skip_non_music_description),
                    checked = musicSettings.settings.skipNonMusic,
                    isTablet = isTablet,
                    onCheckedChange = {
                        MusicLibraryRepository.updateSettings(MusicLibraryRepository.state.value.settings.copy(skipNonMusic = it))
                    },
                )
            }
        }
    }
}

/** The offline MusicBrainz copy: what it is, how far a download or build has got, and its buttons. */
@Composable
private fun MusicBrainzOfflineRow(isTablet: Boolean) {
    val copy = remember { musicBrainzOfflineCopy() }
    val state by copy.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { copy.checkForUpdate() }
    if (state is MusicBrainzOfflineState.Unsupported) return
    val description = when (val s = state) {
        MusicBrainzOfflineState.Unsupported, MusicBrainzOfflineState.Absent -> stringResource(Res.string.settings_music_mb_offline_absent)
        is MusicBrainzOfflineState.Downloading ->
            stringResource(Res.string.settings_music_mb_offline_downloading, gigabytes(s.doneBytes), gigabytes(s.totalBytes))
        is MusicBrainzOfflineState.Building -> stringResource(Res.string.settings_music_mb_offline_building, (s.fraction * 100).toInt())
        is MusicBrainzOfflineState.Ready -> {
            val line = stringResource(
                Res.string.settings_music_mb_offline_ready,
                dumpDate(s.dump), grouped(s.artists), grouped(s.releaseGroups), gigabytes(s.sizeBytes),
            )
            s.newer?.let { "$line ${stringResource(Res.string.settings_music_mb_offline_newer, dumpDate(it))}" } ?: line
        }
        is MusicBrainzOfflineState.Failed -> stringResource(Res.string.settings_music_mb_offline_failed, s.message)
    }
    SettingsActionRow(
        title = stringResource(Res.string.settings_music_mb_offline),
        description = description,
        descriptionColor = if (state is MusicBrainzOfflineState.Failed) MaterialTheme.colorScheme.error else null,
        isTablet = isTablet,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (val s = state) {
                MusicBrainzOfflineState.Unsupported -> Unit
                MusicBrainzOfflineState.Absent ->
                    SettingsGlassButton(text = stringResource(Res.string.settings_music_mb_offline_download), onClick = copy::download, accent = true)
                is MusicBrainzOfflineState.Downloading, is MusicBrainzOfflineState.Building ->
                    SettingsGlassButton(text = stringResource(Res.string.settings_music_mb_offline_cancel), onClick = copy::cancel)
                is MusicBrainzOfflineState.Ready -> {
                    if (s.newer != null) {
                        SettingsGlassButton(text = stringResource(Res.string.settings_music_mb_offline_update), onClick = copy::download, accent = true)
                    }
                    SettingsGlassButton(text = stringResource(Res.string.settings_music_mb_offline_delete), onClick = copy::delete)
                }
                is MusicBrainzOfflineState.Failed ->
                    SettingsGlassButton(text = stringResource(Res.string.settings_music_mb_offline_retry), onClick = copy::download, accent = true)
            }
        }
    }
}

/** "20261028-001001" as "2026-10-28". */
private fun dumpDate(stamp: String): String =
    if (stamp.length >= 8) "${stamp.substring(0, 4)}-${stamp.substring(4, 6)}-${stamp.substring(6, 8)}" else stamp

private fun gigabytes(bytes: Long): String {
    val tenths = (bytes + 50_000_000L) / 100_000_000L
    return "${tenths / 10}.${tenths % 10} GB"
}

/** 2712345 as "2,712,345". */
private fun grouped(n: Int): String = n.toString().reversed().chunked(3).joinToString(",").reversed()
