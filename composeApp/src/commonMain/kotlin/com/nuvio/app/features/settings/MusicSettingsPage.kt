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
import com.nuvio.app.features.music.library.addonLinks
import com.nuvio.app.features.preroll.PreshowSettings
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_music_album_notes
import nuvio.composeapp.generated.resources.settings_music_album_lenses
import nuvio.composeapp.generated.resources.settings_music_album_lenses_description
import nuvio.composeapp.generated.resources.settings_music_artist_stage
import nuvio.composeapp.generated.resources.settings_music_artist_stage_description
import nuvio.composeapp.generated.resources.settings_music_home
import nuvio.composeapp.generated.resources.settings_music_home_description
import nuvio.composeapp.generated.resources.settings_music_album_notes_description
import com.nuvio.app.features.music.library.LibraryView
import nuvio.composeapp.generated.resources.settings_music_section_views
import nuvio.composeapp.generated.resources.settings_music_view_shelf
import nuvio.composeapp.generated.resources.settings_music_view_shelf_description
import nuvio.composeapp.generated.resources.settings_music_view_crates
import nuvio.composeapp.generated.resources.settings_music_view_crates_description
import nuvio.composeapp.generated.resources.settings_music_view_constellation
import nuvio.composeapp.generated.resources.settings_music_view_constellation_description
import nuvio.composeapp.generated.resources.settings_music_view_eras
import nuvio.composeapp.generated.resources.settings_music_view_eras_description
import nuvio.composeapp.generated.resources.settings_music_view_playlists
import nuvio.composeapp.generated.resources.settings_music_view_playlists_description
import nuvio.composeapp.generated.resources.settings_music_audiodb_key
import nuvio.composeapp.generated.resources.settings_music_audiodb_key_description
import nuvio.composeapp.generated.resources.settings_music_fanart_key
import nuvio.composeapp.generated.resources.settings_music_fanart_key_description
import nuvio.composeapp.generated.resources.settings_music_keep_audio_alive
import nuvio.composeapp.generated.resources.settings_music_lastfm_key
import nuvio.composeapp.generated.resources.settings_music_gemini_key
import nuvio.composeapp.generated.resources.settings_music_gemini_key_description
import nuvio.composeapp.generated.resources.settings_music_mb_offline
import nuvio.composeapp.generated.resources.settings_music_nebula_link
import nuvio.composeapp.generated.resources.settings_music_nebula_link_2
import nuvio.composeapp.generated.resources.settings_music_nebula_link_3
import nuvio.composeapp.generated.resources.settings_music_nebula_link_extra_description
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
import nuvio.composeapp.generated.resources.settings_music_pause_for_trailers
import nuvio.composeapp.generated.resources.settings_music_pause_for_trailers_description
import nuvio.composeapp.generated.resources.settings_music_resume_after_playback
import nuvio.composeapp.generated.resources.settings_music_resume_after_playback_description
import nuvio.composeapp.generated.resources.settings_music_reduced_motion
import nuvio.composeapp.generated.resources.settings_music_reduced_motion_description
import nuvio.composeapp.generated.resources.settings_music_section_general
import nuvio.composeapp.generated.resources.settings_music_lyrics_amll
import nuvio.composeapp.generated.resources.settings_music_lyrics_amll_description
import nuvio.composeapp.generated.resources.settings_music_lyrics_lrcmux
import nuvio.composeapp.generated.resources.settings_music_lyrics_lrcmux_description
import nuvio.composeapp.generated.resources.settings_music_lyrics_lyricsplus
import nuvio.composeapp.generated.resources.settings_music_lyrics_lyricsplus_description
import nuvio.composeapp.generated.resources.settings_music_lyrics_netease
import nuvio.composeapp.generated.resources.settings_music_lyrics_netease_description
import nuvio.composeapp.generated.resources.settings_music_lyrics_unison
import nuvio.composeapp.generated.resources.settings_music_lyrics_unison_description
import nuvio.composeapp.generated.resources.settings_music_section_lyrics
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
                modifier = Modifier.settingsSearchAnchors("music-show-tab", "music-reduced-motion", "music-album-lenses", "music-artist-stage", "music-album-notes", "music-home"),
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
                SettingsGroupDivider(isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_music_album_notes),
                    description = stringResource(Res.string.settings_music_album_notes_description),
                    checked = settings.albumNotes,
                    isTablet = isTablet,
                    onCheckedChange = { MusicLibraryRepository.updateSettings(settings.copy(albumNotes = it)) },
                )
                // fork: the album page, artist page and music home switches (upstream removed all three on Nov 11-12 2026).
                SettingsGroupDivider(isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_music_album_lenses),
                    description = stringResource(Res.string.settings_music_album_lenses_description),
                    checked = settings.albumLenses,
                    isTablet = isTablet,
                    onCheckedChange = { MusicLibraryRepository.updateSettings(settings.copy(albumLenses = it)) },
                )
                SettingsGroupDivider(isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_music_artist_stage),
                    description = stringResource(Res.string.settings_music_artist_stage_description),
                    checked = settings.artistStage,
                    isTablet = isTablet,
                    onCheckedChange = { MusicLibraryRepository.updateSettings(settings.copy(artistStage = it)) },
                )
                SettingsGroupDivider(isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_music_home),
                    description = stringResource(Res.string.settings_music_home_description),
                    checked = settings.musicHome,
                    isTablet = isTablet,
                    onCheckedChange = { MusicLibraryRepository.updateSettings(settings.copy(musicHome = it)) },
                )
            }
        }
    }

    // The Library's views: each can be switched off, and its icon leaves the Library's header (and the music home's).
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_music_section_views),
            isTablet = isTablet,
        ) {
            val state by MusicLibraryRepository.state.collectAsStateWithLifecycle()
            val settings = state.settings
            val rows = listOf(
                Triple(LibraryView.Shelf, Res.string.settings_music_view_shelf, Res.string.settings_music_view_shelf_description),
                Triple(LibraryView.Crates, Res.string.settings_music_view_crates, Res.string.settings_music_view_crates_description),
                Triple(LibraryView.Constellation, Res.string.settings_music_view_constellation, Res.string.settings_music_view_constellation_description),
                Triple(LibraryView.Eras, Res.string.settings_music_view_eras, Res.string.settings_music_view_eras_description),
                Triple(LibraryView.Playlists, Res.string.settings_music_view_playlists, Res.string.settings_music_view_playlists_description),
            )
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsSearchAnchors(*rows.map { "music-view-${it.first.name.lowercase()}" }.toTypedArray()),
            ) {
                rows.forEachIndexed { index, (view, title, description) ->
                    if (index > 0) SettingsGroupDivider(isTablet)
                    val on = view.name !in settings.hiddenLibraryViews
                    SettingsSwitchRow(
                        title = stringResource(title),
                        description = stringResource(description),
                        checked = on,
                        isTablet = isTablet,
                        onCheckedChange = { show ->
                            val hidden = if (show) settings.hiddenLibraryViews - view.name else settings.hiddenLibraryViews + view.name
                            MusicLibraryRepository.updateSettings(settings.copy(hiddenLibraryViews = hidden))
                        },
                    )
                }
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
                    "music-gemini-key",
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
                    title = stringResource(Res.string.settings_music_gemini_key),
                    description = stringResource(Res.string.settings_music_gemini_key_description),
                    value = settings.geminiApiKey,
                    placeholder = "Optional",
                    isTablet = isTablet,
                    secret = true,
                    summarizeAsConfigured = true,
                    onSave = { MusicLibraryRepository.updateSettings(MusicLibraryRepository.state.value.settings.copy(geminiApiKey = it)) },
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
                SettingsGroupDivider(isTablet)
                SettingsTextInputRow(
                    title = stringResource(Res.string.settings_music_nebula_link_2),
                    description = stringResource(Res.string.settings_music_nebula_link_extra_description),
                    value = settings.nebulaSecretLink2,
                    placeholder = "Optional",
                    isTablet = isTablet,
                    secret = true,
                    summarizeAsConfigured = true,
                    onSave = { MusicLibraryRepository.updateSettings(MusicLibraryRepository.state.value.settings.copy(nebulaSecretLink2 = it.trim())) },
                )
                SettingsGroupDivider(isTablet)
                SettingsTextInputRow(
                    title = stringResource(Res.string.settings_music_nebula_link_3),
                    description = stringResource(Res.string.settings_music_nebula_link_extra_description),
                    value = settings.nebulaSecretLink3,
                    placeholder = "Optional",
                    isTablet = isTablet,
                    secret = true,
                    summarizeAsConfigured = true,
                    onSave = { MusicLibraryRepository.updateSettings(MusicLibraryRepository.state.value.settings.copy(nebulaSecretLink3 = it.trim())) },
                )
                if (settings.addonLinks().any { it.isNotBlank() }) {
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
            title = stringResource(Res.string.settings_music_section_lyrics),
            isTablet = isTablet,
        ) {
            val state by MusicLibraryRepository.state.collectAsStateWithLifecycle()
            val settings = state.settings
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsSearchAnchors(
                    "music-lyrics-unison",
                    "music-lyrics-amll",
                    "music-lyrics-netease",
                    "music-lyrics-lrcmux",
                ),
            ) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_music_lyrics_unison),
                    description = stringResource(Res.string.settings_music_lyrics_unison_description),
                    checked = settings.unisonLyrics,
                    isTablet = isTablet,
                    onCheckedChange = { MusicLibraryRepository.updateSettings(settings.copy(unisonLyrics = it)) },
                )
                SettingsGroupDivider(isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_music_lyrics_amll),
                    description = stringResource(Res.string.settings_music_lyrics_amll_description),
                    checked = settings.amllLyrics,
                    isTablet = isTablet,
                    onCheckedChange = { MusicLibraryRepository.updateSettings(settings.copy(amllLyrics = it)) },
                )
                SettingsGroupDivider(isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_music_lyrics_netease),
                    description = stringResource(Res.string.settings_music_lyrics_netease_description),
                    checked = settings.neteaseLyrics,
                    isTablet = isTablet,
                    onCheckedChange = { MusicLibraryRepository.updateSettings(settings.copy(neteaseLyrics = it)) },
                )
                SettingsGroupDivider(isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_music_lyrics_lrcmux),
                    description = stringResource(Res.string.settings_music_lyrics_lrcmux_description),
                    checked = settings.lrcMuxLyrics,
                    isTablet = isTablet,
                    onCheckedChange = { MusicLibraryRepository.updateSettings(settings.copy(lrcMuxLyrics = it)) },
                )
                SettingsGroupDivider(isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_music_lyrics_lyricsplus),
                    description = stringResource(Res.string.settings_music_lyrics_lyricsplus_description),
                    checked = settings.lyricsPlusLyrics,
                    isTablet = isTablet,
                    onCheckedChange = { MusicLibraryRepository.updateSettings(settings.copy(lyricsPlusLyrics = it)) },
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
                modifier = Modifier.settingsSearchAnchors("music-pause-for-trailers", "music-resume-after-playback"),
            ) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_music_pause_for_trailers),
                    description = stringResource(Res.string.settings_music_pause_for_trailers_description),
                    checked = musicSettings.settings.pauseForTrailers,
                    isTablet = isTablet,
                    onCheckedChange = {
                        MusicLibraryRepository.updateSettings(MusicLibraryRepository.state.value.settings.copy(pauseForTrailers = it))
                    },
                )
                SettingsGroupDivider(isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_music_resume_after_playback),
                    description = stringResource(Res.string.settings_music_resume_after_playback_description),
                    checked = musicSettings.settings.resumeAfterPlayback,
                    isTablet = isTablet,
                    onCheckedChange = {
                        MusicLibraryRepository.updateSettings(MusicLibraryRepository.state.value.settings.copy(resumeAfterPlayback = it))
                    },
                )
            }
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

    musicFactsSettingsContent(isTablet) // fork:facts-filler
    musicPlayLogSettingsContent(isTablet) // fork:shelf
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
