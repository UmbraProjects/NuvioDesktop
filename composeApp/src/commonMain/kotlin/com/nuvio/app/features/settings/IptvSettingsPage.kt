package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioModalDialog
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.iptv.IptvPlaylist
import com.nuvio.app.features.iptv.IptvPlaylistType
import com.nuvio.app.features.iptv.IptvRefreshPolicy
import com.nuvio.app.features.iptv.IptvRepository
import com.nuvio.app.features.iptv.IptvSettings
import com.nuvio.app.features.iptv.IptvSourceException
import com.nuvio.app.features.iptv.IptvStreamFormat
import com.nuvio.app.features.iptv.IptvTestResult
import com.nuvio.app.features.iptv.asIptvError
import com.nuvio.app.features.iptv.iptvErrorText
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_delete
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.iptv_playlist_delete_confirm
import nuvio.composeapp.generated.resources.iptv_playlist_dialog_add_title
import nuvio.composeapp.generated.resources.iptv_playlist_dialog_edit_title
import nuvio.composeapp.generated.resources.iptv_playlist_epg_url
import nuvio.composeapp.generated.resources.iptv_playlist_epg_url_description
import nuvio.composeapp.generated.resources.iptv_playlist_missing_fields
import nuvio.composeapp.generated.resources.iptv_playlist_missing_url
import nuvio.composeapp.generated.resources.iptv_playlist_name
import nuvio.composeapp.generated.resources.iptv_playlist_name_placeholder
import nuvio.composeapp.generated.resources.iptv_playlist_password
import nuvio.composeapp.generated.resources.iptv_playlist_server
import nuvio.composeapp.generated.resources.iptv_playlist_server_description
import nuvio.composeapp.generated.resources.iptv_playlist_summary_channels
import nuvio.composeapp.generated.resources.iptv_playlist_summary_loading
import nuvio.composeapp.generated.resources.iptv_playlist_summary_not_loaded
import nuvio.composeapp.generated.resources.iptv_playlist_test
import nuvio.composeapp.generated.resources.iptv_playlist_testing
import nuvio.composeapp.generated.resources.iptv_playlist_type
import nuvio.composeapp.generated.resources.iptv_playlist_type_m3u
import nuvio.composeapp.generated.resources.iptv_playlist_type_xtream
import nuvio.composeapp.generated.resources.iptv_playlist_url
import nuvio.composeapp.generated.resources.iptv_playlist_url_description
import nuvio.composeapp.generated.resources.iptv_playlist_username
import nuvio.composeapp.generated.resources.iptv_settings_add_playlist
import nuvio.composeapp.generated.resources.iptv_settings_add_playlist_description
import nuvio.composeapp.generated.resources.iptv_settings_format_auto
import nuvio.composeapp.generated.resources.iptv_settings_format_hls
import nuvio.composeapp.generated.resources.iptv_settings_format_ts
import nuvio.composeapp.generated.resources.iptv_settings_intro
import nuvio.composeapp.generated.resources.iptv_settings_preferred_format
import nuvio.composeapp.generated.resources.iptv_settings_rewind
import nuvio.composeapp.generated.resources.iptv_settings_rewind_description
import nuvio.composeapp.generated.resources.iptv_settings_rewind_minutes
import nuvio.composeapp.generated.resources.iptv_settings_rewind_standard
import nuvio.composeapp.generated.resources.iptv_settings_preferred_format_description
import nuvio.composeapp.generated.resources.iptv_settings_refresh_every_launch
import nuvio.composeapp.generated.resources.iptv_settings_refresh_hours
import nuvio.composeapp.generated.resources.iptv_settings_refresh_interval
import nuvio.composeapp.generated.resources.iptv_settings_refresh_interval_description
import nuvio.composeapp.generated.resources.iptv_settings_refresh_now
import nuvio.composeapp.generated.resources.iptv_settings_refresh_now_description
import nuvio.composeapp.generated.resources.iptv_settings_section_playback
import nuvio.composeapp.generated.resources.iptv_settings_section_playlists
import nuvio.composeapp.generated.resources.iptv_settings_section_updates
import nuvio.composeapp.generated.resources.iptv_test_ok
import nuvio.composeapp.generated.resources.iptv_test_ok_account
import nuvio.composeapp.generated.resources.iptv_test_ok_account_expires
import nuvio.composeapp.generated.resources.iptv_test_ok_channels
import androidx.compose.ui.text.input.KeyboardType
import com.nuvio.app.features.iptv.IptvProxy
import com.nuvio.app.features.iptv.IptvProxyType
import nuvio.composeapp.generated.resources.iptv_settings_section_connection
import nuvio.composeapp.generated.resources.iptv_proxy
import nuvio.composeapp.generated.resources.iptv_proxy_description
import nuvio.composeapp.generated.resources.iptv_proxy_off
import nuvio.composeapp.generated.resources.iptv_proxy_http
import nuvio.composeapp.generated.resources.iptv_proxy_socks
import nuvio.composeapp.generated.resources.iptv_proxy_host
import nuvio.composeapp.generated.resources.iptv_proxy_host_description
import nuvio.composeapp.generated.resources.iptv_proxy_port
import nuvio.composeapp.generated.resources.iptv_proxy_username
import nuvio.composeapp.generated.resources.iptv_proxy_username_description
import nuvio.composeapp.generated.resources.iptv_proxy_password
import org.jetbrains.compose.resources.stringResource

/**
 * Live TV (IPTV) settings: the user's playlists, how often they refresh, and the stream format.
 * Playlists stay on this PC (per profile) because they hold the provider's password.
 */
internal fun LazyListScope.iptvSettingsContent(
    isTablet: Boolean,
) {
    item {
        IptvPlaylistsSection(isTablet = isTablet, settings = rememberIptvSettings())
    }

    item {
        val settings = rememberIptvSettings()
        SettingsSection(
            title = stringResource(Res.string.iptv_settings_section_updates),
            isTablet = isTablet,
        ) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsSearchAnchors("iptv-refresh-interval", "iptv-refresh-now"),
            ) {
                val everyLaunch = stringResource(Res.string.iptv_settings_refresh_every_launch)
                SettingsChoiceRow(
                    title = stringResource(Res.string.iptv_settings_refresh_interval),
                    description = stringResource(Res.string.iptv_settings_refresh_interval_description),
                    options = IptvRefreshPolicy.OPTIONS.map { hours ->
                        SettingsChoiceOption(
                            value = hours,
                            label = if (hours == 0) everyLaunch else stringResource(Res.string.iptv_settings_refresh_hours, hours),
                        )
                    },
                    selectedValue = settings.refreshHours,
                    isTablet = isTablet,
                    onSelected = IptvRepository::setRefreshHours,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.iptv_settings_refresh_now),
                    description = stringResource(Res.string.iptv_settings_refresh_now_description),
                    enabled = settings.playlists.isNotEmpty(),
                    isTablet = isTablet,
                    onClick = { IptvRepository.refreshAllInBackground(force = true) },
                )
            }
        }
    }

    item {
        val settings = rememberIptvSettings()
        SettingsSection(
            title = stringResource(Res.string.iptv_settings_section_playback),
            isTablet = isTablet,
        ) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("iptv-preferred-format")),
            ) {
                SettingsChoiceRow(
                    title = stringResource(Res.string.iptv_settings_preferred_format),
                    description = stringResource(Res.string.iptv_settings_preferred_format_description),
                    options = listOf(
                        SettingsChoiceOption<String?>(null, stringResource(Res.string.iptv_settings_format_auto)),
                        SettingsChoiceOption<String?>(IptvStreamFormat.TS, stringResource(Res.string.iptv_settings_format_ts)),
                        SettingsChoiceOption<String?>(IptvStreamFormat.HLS, stringResource(Res.string.iptv_settings_format_hls)),
                    ),
                    selectedValue = settings.preferredFormat,
                    isTablet = isTablet,
                    onSelected = IptvRepository::setPreferredFormat,
                )
                SettingsGroupDivider(isTablet = isTablet)
                val standard = stringResource(Res.string.iptv_settings_rewind_standard)
                SettingsChoiceRow(
                    title = stringResource(Res.string.iptv_settings_rewind),
                    description = stringResource(Res.string.iptv_settings_rewind_description),
                    options = com.nuvio.app.features.iptv.IPTV_REWIND_OPTIONS.map { minutes ->
                        SettingsChoiceOption(
                            value = minutes,
                            label = if (minutes == 0) standard else stringResource(Res.string.iptv_settings_rewind_minutes, minutes),
                        )
                    },
                    selectedValue = settings.rewindMinutes,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("iptv-rewind")),
                    onSelected = IptvRepository::setRewindMinutes,
                )
            }
        }
    }

    item {
        IptvProxySection(isTablet = isTablet, proxy = rememberIptvSettings().proxy)
    }
}

/**
 * Proxy for IPTV only, for providers the user's ISP blocks. Each field saves on its own, so the
 * rows below the type only appear once a type is chosen.
 */
@Composable
private fun IptvProxySection(isTablet: Boolean, proxy: IptvProxy) {
    SettingsSection(
        title = stringResource(Res.string.iptv_settings_section_connection),
        isTablet = isTablet,
    ) {
        SettingsGroup(
            isTablet = isTablet,
            modifier = Modifier.settingsSearchAnchors("iptv-proxy"),
        ) {
            SettingsChoiceRow(
                title = stringResource(Res.string.iptv_proxy),
                description = stringResource(Res.string.iptv_proxy_description),
                options = listOf(
                    SettingsChoiceOption(IptvProxyType.Off, stringResource(Res.string.iptv_proxy_off)),
                    SettingsChoiceOption(IptvProxyType.Http, stringResource(Res.string.iptv_proxy_http)),
                    SettingsChoiceOption(IptvProxyType.Socks, stringResource(Res.string.iptv_proxy_socks)),
                ),
                selectedValue = proxy.type,
                isTablet = isTablet,
                onSelected = { IptvRepository.setProxy(proxy.copy(type = it)) },
            )
            if (proxy.type == IptvProxyType.Off) return@SettingsGroup
            SettingsGroupDivider(isTablet = isTablet)
            SettingsTextInputRow(
                title = stringResource(Res.string.iptv_proxy_host),
                description = stringResource(Res.string.iptv_proxy_host_description),
                value = proxy.host,
                placeholder = "proxy.example.com",
                isTablet = isTablet,
                onSave = { raw ->
                    // A pasted host:port fills both fields.
                    val (host, port) = IptvProxy.parseHostAndPort(raw)
                    IptvRepository.setProxy(proxy.copy(host = host, port = if (port > 0) port else proxy.port))
                },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsTextInputRow(
                title = stringResource(Res.string.iptv_proxy_port),
                description = "",
                value = proxy.port.takeIf { it > 0 }?.toString().orEmpty(),
                placeholder = if (proxy.type == IptvProxyType.Socks) "1080" else "8080",
                isTablet = isTablet,
                keyboardType = KeyboardType.Number,
                normalize = { it.filter(Char::isDigit) },
                onSave = { raw -> IptvRepository.setProxy(proxy.copy(port = raw.toIntOrNull()?.coerceIn(0, 65535) ?: 0)) },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsTextInputRow(
                title = stringResource(Res.string.iptv_proxy_username),
                description = stringResource(Res.string.iptv_proxy_username_description),
                value = proxy.username,
                placeholder = stringResource(Res.string.iptv_proxy_username),
                isTablet = isTablet,
                onSave = { IptvRepository.setProxy(proxy.copy(username = it)) },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsTextInputRow(
                title = stringResource(Res.string.iptv_proxy_password),
                description = "",
                value = proxy.password,
                placeholder = stringResource(Res.string.iptv_proxy_password),
                isTablet = isTablet,
                secret = true,
                normalize = { it },
                onSave = { IptvRepository.setProxy(proxy.copy(password = it)) },
            )
        }
    }
}

/** The page reads its own state instead of having SettingsScreen thread it through both layouts. */
@Composable
private fun rememberIptvSettings(): IptvSettings {
    remember { IptvRepository.ensureLoaded() }
    val settings by IptvRepository.settings.collectAsStateWithLifecycle()
    return settings
}

/** The playlist list plus the add/edit dialog, which needs composable state of its own. */
@Composable
private fun IptvPlaylistsSection(isTablet: Boolean, settings: IptvSettings) {
    // null = closed; a playlist with a blank id = adding.
    var editing by remember { mutableStateOf<IptvPlaylist?>(null) }

    SettingsSection(
        title = stringResource(Res.string.iptv_settings_section_playlists),
        isTablet = isTablet,
    ) {
        SettingsGroup(
            isTablet = isTablet,
            modifier = Modifier.settingsSearchAnchors("iptv-playlists", "iptv-add-playlist"),
        ) {
            if (settings.playlists.isEmpty()) {
                SettingsSubtext(
                    text = stringResource(Res.string.iptv_settings_intro),
                    isTablet = isTablet,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                SettingsGroupDivider(isTablet = isTablet)
            }
            settings.playlists.forEach { playlist ->
                IptvPlaylistRow(playlist = playlist, isTablet = isTablet, onClick = { editing = playlist })
                SettingsGroupDivider(isTablet = isTablet)
            }
            SettingsNavigationRow(
                title = stringResource(Res.string.iptv_settings_add_playlist),
                description = stringResource(Res.string.iptv_settings_add_playlist_description),
                isTablet = isTablet,
                onClick = { editing = IptvPlaylist(id = "", type = IptvPlaylistType.Xtream, name = "") },
            )
        }
    }

    editing?.let { playlist ->
        IptvPlaylistDialog(
            initial = playlist,
            isTablet = isTablet,
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun IptvPlaylistRow(playlist: IptvPlaylist, isTablet: Boolean, onClick: () -> Unit) {
    val state by IptvRepository.contentState(playlist.id).collectAsStateWithLifecycle()
    val typeLabel = playlistTypeLabel(playlist.type)
    val where = playlistHost(playlist)?.let { "$typeLabel · $it" } ?: typeLabel
    val content = state.content
    val error = state.error
    val description = when {
        state.loading -> stringResource(Res.string.iptv_playlist_summary_loading, where)
        error != null -> iptvErrorText(error)
        content != null -> stringResource(Res.string.iptv_playlist_summary_channels, where, content.channels.size)
        else -> stringResource(Res.string.iptv_playlist_summary_not_loaded, where)
    }
    SettingsNavigationRow(
        title = playlist.name.ifBlank { where },
        description = description,
        isTablet = isTablet,
        onClick = onClick,
    )
}

@Composable
private fun playlistTypeLabel(type: IptvPlaylistType): String = when (type) {
    IptvPlaylistType.Xtream -> stringResource(Res.string.iptv_playlist_type_xtream)
    IptvPlaylistType.M3u -> stringResource(Res.string.iptv_playlist_type_m3u)
}

private fun playlistHost(playlist: IptvPlaylist): String? {
    val raw = (if (playlist.type == IptvPlaylistType.Xtream) playlist.server else playlist.url)?.trim() ?: return null
    val withScheme = if ("://" in raw) raw else "http://$raw"
    return runCatching { URI(withScheme).host }.getOrNull()?.takeIf { it.isNotBlank() }
}

private sealed interface IptvDialogStatus {
    data object Testing : IptvDialogStatus
    data class Tested(val result: IptvTestResult) : IptvDialogStatus
    data class Failed(val error: IptvSourceException) : IptvDialogStatus
    data object MissingFields : IptvDialogStatus
}

@Composable
private fun IptvPlaylistDialog(
    initial: IptvPlaylist,
    isTablet: Boolean,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val adding = initial.id.isBlank()

    var type by remember { mutableStateOf(initial.type) }
    var name by remember { mutableStateOf(initial.name) }
    var server by remember { mutableStateOf(initial.server.orEmpty()) }
    var username by remember { mutableStateOf(initial.username.orEmpty()) }
    var password by remember { mutableStateOf(initial.password.orEmpty()) }
    var url by remember { mutableStateOf(initial.url.orEmpty()) }
    var epgUrl by remember { mutableStateOf(initial.epgUrl.orEmpty()) }
    var status by remember { mutableStateOf<IptvDialogStatus?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }

    fun draft() = IptvPlaylist(
        id = initial.id,
        type = type,
        name = name,
        // Only the fields of the chosen type are kept, so switching type leaves nothing stale behind.
        server = server.takeIf { type == IptvPlaylistType.Xtream },
        username = username.takeIf { type == IptvPlaylistType.Xtream },
        password = password.takeIf { type == IptvPlaylistType.Xtream },
        url = url.takeIf { type == IptvPlaylistType.M3u },
        epgUrl = epgUrl,
    )

    fun complete(): Boolean = when (type) {
        IptvPlaylistType.Xtream -> server.isNotBlank() && username.isNotBlank() && password.isNotEmpty()
        IptvPlaylistType.M3u -> url.isNotBlank()
    }

    val testing = status == IptvDialogStatus.Testing
    val formScroll = rememberScrollState()
    // The message sits under the last field; in the taller Xtream form that is below the fold.
    LaunchedEffect(status) {
        if (status == null) return@LaunchedEffect
        withFrameNanos { } // let the message lay out first, or maxValue is the old bottom
        formScroll.animateScrollTo(formScroll.maxValue)
    }

    NuvioModalDialog(
        onDismissRequest = onDismiss,
        title = stringResource(if (adding) Res.string.iptv_playlist_dialog_add_title else Res.string.iptv_playlist_dialog_edit_title),
        maxWidth = 640.dp,
        modifier = Modifier.width(640.dp),
        actions = {
            if (!adding) {
                TextButton(
                    onClick = {
                        if (confirmingDelete) {
                            IptvRepository.removePlaylist(initial.id)
                            onDismiss()
                        } else {
                            confirmingDelete = true
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = tokens.colors.danger),
                ) {
                    Text(stringResource(if (confirmingDelete) Res.string.iptv_playlist_delete_confirm else Res.string.action_delete))
                }
            }
            TextButton(
                enabled = !testing,
                onClick = {
                    if (!complete()) {
                        status = IptvDialogStatus.MissingFields
                        return@TextButton
                    }
                    status = IptvDialogStatus.Testing
                    val toTest = draft()
                    scope.launch {
                        status = try {
                            IptvDialogStatus.Tested(IptvRepository.test(toTest))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            IptvDialogStatus.Failed(e.asIptvError())
                        }
                    }
                },
            ) {
                Text(stringResource(if (testing) Res.string.iptv_playlist_testing else Res.string.iptv_playlist_test))
            }
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
            TextButton(
                onClick = {
                    if (!complete()) {
                        status = IptvDialogStatus.MissingFields
                        return@TextButton
                    }
                    val id = if (adding) {
                        IptvRepository.addPlaylist(draft()).id
                    } else {
                        IptvRepository.updatePlaylist(draft())
                        initial.id
                    }
                    IptvRepository.refreshInBackground(id)
                    onDismiss()
                },
            ) { Text(stringResource(Res.string.action_save)) }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(formScroll)
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SettingsChoiceRow(
                title = stringResource(Res.string.iptv_playlist_type),
                description = null,
                options = listOf(
                    SettingsChoiceOption(IptvPlaylistType.Xtream, stringResource(Res.string.iptv_playlist_type_xtream)),
                    SettingsChoiceOption(IptvPlaylistType.M3u, stringResource(Res.string.iptv_playlist_type_m3u)),
                ),
                selectedValue = type,
                isTablet = isTablet,
                onSelected = {
                    type = it
                    status = null
                },
            )
            SettingsTextRow(
                title = stringResource(Res.string.iptv_playlist_name),
                description = null,
                value = name,
                isTablet = isTablet,
                placeholder = stringResource(Res.string.iptv_playlist_name_placeholder),
                onValueChange = { name = it },
            )
            when (type) {
                IptvPlaylistType.Xtream -> {
                    SettingsTextRow(
                        title = stringResource(Res.string.iptv_playlist_server),
                        description = stringResource(Res.string.iptv_playlist_server_description),
                        value = server,
                        isTablet = isTablet,
                        placeholder = "http://",
                        onValueChange = { server = it; status = null },
                    )
                    SettingsTextRow(
                        title = stringResource(Res.string.iptv_playlist_username),
                        description = null,
                        value = username,
                        isTablet = isTablet,
                        onValueChange = { username = it; status = null },
                    )
                    SettingsTextRow(
                        title = stringResource(Res.string.iptv_playlist_password),
                        description = null,
                        value = password,
                        isTablet = isTablet,
                        secret = true,
                        onValueChange = { password = it; status = null },
                    )
                }
                IptvPlaylistType.M3u -> {
                    SettingsTextRow(
                        title = stringResource(Res.string.iptv_playlist_url),
                        description = stringResource(Res.string.iptv_playlist_url_description),
                        value = url,
                        isTablet = isTablet,
                        placeholder = "https://",
                        onValueChange = { url = it; status = null },
                    )
                }
            }
            SettingsTextRow(
                title = stringResource(Res.string.iptv_playlist_epg_url),
                description = stringResource(Res.string.iptv_playlist_epg_url_description),
                value = epgUrl,
                isTablet = isTablet,
                placeholder = "https://",
                onValueChange = { epgUrl = it },
            )
            status?.let { current ->
                val (text, isError) = when (current) {
                    IptvDialogStatus.Testing -> stringResource(Res.string.iptv_playlist_testing) to false
                    IptvDialogStatus.MissingFields -> stringResource(
                        if (type == IptvPlaylistType.Xtream) Res.string.iptv_playlist_missing_fields else Res.string.iptv_playlist_missing_url,
                    ) to true
                    is IptvDialogStatus.Failed -> iptvErrorText(current.error) to true
                    is IptvDialogStatus.Tested -> testResultText(current.result) to false
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isError) tokens.colors.danger else tokens.colors.textMuted,
                    modifier = Modifier.padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun testResultText(result: IptvTestResult): String {
    result.channelCount?.let { return stringResource(Res.string.iptv_test_ok_channels, it) }
    val account = result.account ?: return stringResource(Res.string.iptv_test_ok)
    val status = account.status ?: "Active"
    val connections = account.maxConnections ?: return stringResource(Res.string.iptv_test_ok)
    val expires = account.expiresAtSeconds?.let {
        // Localised formats need locale data a native-image build may not carry; ISO is a fine fallback.
        val date = Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault())
        runCatching { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(date) }
            .getOrElse { DateTimeFormatter.ISO_LOCAL_DATE.format(date) }
    }
    return if (expires != null) {
        stringResource(Res.string.iptv_test_ok_account_expires, status, connections, expires)
    } else {
        stringResource(Res.string.iptv_test_ok_account, status, connections)
    }
}
