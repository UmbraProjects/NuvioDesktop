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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
