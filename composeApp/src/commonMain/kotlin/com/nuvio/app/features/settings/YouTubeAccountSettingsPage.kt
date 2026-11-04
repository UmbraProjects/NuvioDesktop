package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.youtube.YouTubeFeedRepository
import com.nuvio.app.features.youtubemusic.YouTubeMusicAuth
import com.nuvio.app.features.youtubemusic.YouTubeMusicAuthState
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_settings_page_youtube
import nuvio.composeapp.generated.resources.settings_youtube_cancel
import nuvio.composeapp.generated.resources.settings_youtube_explainer
import nuvio.composeapp.generated.resources.settings_youtube_section_account
import nuvio.composeapp.generated.resources.settings_youtube_sign_in
import nuvio.composeapp.generated.resources.settings_youtube_sign_in_again
import nuvio.composeapp.generated.resources.settings_youtube_sign_out
import nuvio.composeapp.generated.resources.settings_youtube_state_rejected
import nuvio.composeapp.generated.resources.settings_youtube_state_signed_in
import nuvio.composeapp.generated.resources.settings_youtube_state_signed_out
import nuvio.composeapp.generated.resources.settings_youtube_state_signing_in
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/*
 * fork:ytmusic-login
 *
 * Settings page for the YouTube / YouTube Music Google sign-in. All the state and the login window
 * live in [YouTubeMusicAuth]; this page is only the buttons. The session is used by the trailer
 * extractor for videos YouTube refuses to serve anonymously.
 */
internal fun LazyListScope.youTubeAccountSettingsContent(isTablet: Boolean) {
    item {
        SettingsSection(
            title = stringResource(Res.string.compose_settings_page_youtube),
            isTablet = isTablet,
        ) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("youtube-account")),
            ) {
                YouTubeAccountRows(isTablet = isTablet)
                YouTubeTabSwitch(isTablet = isTablet) // fork:youtube-mode
            }
        }
    }
}

@Composable
private fun YouTubeAccountRows(isTablet: Boolean) {
    val state: YouTubeMusicAuthState by remember {
        YouTubeMusicAuth.ensureLoaded()
        YouTubeMusicAuth.state
    }.collectAsState()
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val verticalPadding = if (isTablet) 16.dp else 14.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(Res.string.settings_youtube_section_account),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(Res.string.settings_youtube_explainer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val statusText = when {
            state.signingIn -> stringResource(Res.string.settings_youtube_state_signing_in)
            state.signedIn && state.sessionRejected -> stringResource(Res.string.settings_youtube_state_rejected)
            state.signedIn -> stringResource(Res.string.settings_youtube_state_signed_in)
            else -> stringResource(Res.string.settings_youtube_state_signed_out)
        }
        val statusIsProblem = state.sessionRejected && state.signedIn
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = if (statusIsProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        state.error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                state.signingIn -> OutlinedButton(onClick = YouTubeMusicAuth::cancelSignIn) {
                    Text(stringResource(Res.string.settings_youtube_cancel))
                }
                state.signedIn -> {
                    if (state.sessionRejected) {
                        Button(onClick = YouTubeMusicAuth::signIn) {
                            Text(stringResource(Res.string.settings_youtube_sign_in_again))
                        }
                    }
                    OutlinedButton(onClick = YouTubeMusicAuth::signOut) {
                        Text(stringResource(Res.string.settings_youtube_sign_out))
                    }
                }
                else -> Button(onClick = YouTubeMusicAuth::signIn) {
                    Text(stringResource(Res.string.settings_youtube_sign_in))
                }
            }
        }
    }
}

/** fork:youtube-mode - puts the YouTube tab (home and subscriptions feeds) in the navigation. */
@Composable
private fun YouTubeTabSwitch(isTablet: Boolean) {
    val enabled by remember { YouTubeFeedRepository.tabEnabled }.collectAsState()
    SettingsSwitchRow(
        title = "Show YouTube tab",
        description = "Your YouTube home feed and subscriptions in their own tab, played in Nuvio's player. Needs the account above.",
        checked = enabled == true,
        isTablet = isTablet,
        onCheckedChange = YouTubeFeedRepository::setTabEnabled,
    )
    if (enabled == true) {
        val openWindowed by remember { YouTubeFeedRepository.openWindowed }.collectAsState()
        SettingsDropdownChoiceRow(
            title = "Open videos",
            description = "Windowed shows a watch page: the video with related videos beside it, and its description and comments " +
                "below. The player's fullscreen button switches between the two either way.",
            options = listOf(SettingsChoiceOption(true, "Windowed"), SettingsChoiceOption(false, "Full screen")),
            selectedValue = openWindowed,
            isTablet = isTablet,
            modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("youtube-open-videos")),
            onSelected = YouTubeFeedRepository::setOpenWindowed,
        )
        // fork:youtube-mini-player
        val miniPlayer by remember { YouTubeFeedRepository.miniPlayerOnBack }.collectAsState()
        SettingsSwitchRow(
            title = "Mini player",
            description = "Back from a video returns to the tab with the video still playing in a small box in the corner, " +
                "so you can search, browse and queue meanwhile. Click the box to go back to the video; its X stops it.",
            checked = miniPlayer,
            isTablet = isTablet,
            modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("youtube-mini-player")),
            onCheckedChange = YouTubeFeedRepository::setMiniPlayerOnBack,
        )
        // fork:youtube-languages
        val allLanguages by remember { YouTubeFeedRepository.allLanguages }.collectAsState()
        SettingsSwitchRow(
            title = "All languages",
            description = "Opens a dubbed video's other audio languages with it, and every subtitle language a video has " +
                "rather than the first few. Videos with dubs take a little longer to start.",
            checked = allLanguages,
            isTablet = isTablet,
            modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("youtube-all-languages")),
            onCheckedChange = YouTubeFeedRepository::setAllLanguages,
        )
        val maxQuality by remember { YouTubeFeedRepository.maxQuality }.collectAsState()
        SettingsDropdownChoiceRow(
            title = "Max video quality",
            description = "The sharpest a video plays at, when YouTube has it. Lower it for a slow connection or an older graphics card.",
            options = com.nuvio.app.features.youtube.YOUTUBE_MAX_QUALITY_CHOICES.map { SettingsChoiceOption(it, youTubeQualityLabel(it)) },
            selectedValue = maxQuality,
            isTablet = isTablet,
            modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("youtube-max-quality")),
            onSelected = YouTubeFeedRepository::setMaxQuality,
        )
        YouTubeHistoryRows(isTablet = isTablet)
        val sponsorSkip by remember { YouTubeFeedRepository.sponsorAutoSkip }.collectAsState()
        SettingsSwitchRow(
            title = "Skip sponsors",
            description = "Skips segments SponsorBlock users marked as sponsors, self-promotion or \"like and subscribe\" " +
                "reminders without asking. Intros, end cards and previews still get a skip button.",
            checked = sponsorSkip,
            isTablet = isTablet,
            onCheckedChange = YouTubeFeedRepository::setSponsorAutoSkip,
        )
        val deArrow by remember { YouTubeFeedRepository.deArrow }.collectAsState()
        SettingsSwitchRow(
            title = "DeArrow titles and thumbnails",
            description = "Shows the titles and thumbnails DeArrow users wrote in place of clickbait. " +
                "Videos nobody has submitted keep YouTube's own.",
            checked = deArrow,
            isTablet = isTablet,
            onCheckedChange = YouTubeFeedRepository::setDeArrow,
        )
        val sky by remember { YouTubeFeedRepository.skyBackground }.collectAsState()
        SettingsSwitchRow(
            title = "Gradient background",
            description = "Draws the tab on the same gradient as this screen instead of a plain background.",
            checked = sky,
            isTablet = isTablet,
            onCheckedChange = YouTubeFeedRepository::setSkyBackground,
        )
    }
}

/** A max quality choice as people say it: 8K and 4K by name, the rest by height. */
private fun youTubeQualityLabel(shortSide: Int): String = when (shortSide) {
    4320 -> "8K"
    2160 -> "4K"
    else -> "${shortSide}p"
}

/** fork:youtube-history - the watched-date badge on the tab's cards, and this PC's copy of the history. */
@Composable
private fun YouTubeHistoryRows(isTablet: Boolean) {
    val mode by remember { YouTubeFeedRepository.watchedDateMode }.collectAsState()
    SettingsDropdownChoiceRow(
        title = "Watched date",
        description = "Shows the day a video was watched in the top left of its thumbnail.",
        options = com.nuvio.app.features.youtube.YouTubeWatchedDateMode.entries.map { SettingsChoiceOption(it, it.label) },
        selectedValue = mode,
        isTablet = isTablet,
        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("youtube-watched-date")),
        onSelected = YouTubeFeedRepository::setWatchedDateMode,
    )
    if (mode != com.nuvio.app.features.youtube.YouTubeWatchedDateMode.Off) {
        val format by remember { YouTubeFeedRepository.dateFormat }.collectAsState()
        SettingsDropdownChoiceRow(
            title = "Date format",
            description = null,
            options = com.nuvio.app.features.youtube.YouTubeDateFormat.entries.map { SettingsChoiceOption(it, it.example) },
            selectedValue = format,
            isTablet = isTablet,
            onSelected = YouTubeFeedRepository::setDateFormat,
        )
    }
    val history by remember { com.nuvio.app.features.youtube.YouTubeWatchHistory.entries }.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var exported by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    SettingsActionRow(
        title = "Watch history on this PC",
        description = buildString {
            append("${history.size} videos. Nuvio keeps its own copy of your history, since YouTube drops old entries: ")
            append("what the History tab shows as it loads, and everything played here. It's kept in ")
            append(com.nuvio.app.features.youtube.YouTubeHistoryFile.location)
            append(".")
            exported?.let { append(" Exported to $it.") }
        },
        isTablet = isTablet,
        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("youtube-history-export")),
    ) {
        SettingsGlassButton(
            text = "Export as CSV",
            enabled = history.isNotEmpty(),
            onClick = {
                scope.launch {
                    val csv = com.nuvio.app.features.youtube.YouTubeWatchHistory.exportCsv()
                    com.nuvio.app.features.youtube.YouTubeHistoryFile.saveExport("youtube-history.csv", csv)?.let { exported = it }
                }
            },
        )
    }
}
