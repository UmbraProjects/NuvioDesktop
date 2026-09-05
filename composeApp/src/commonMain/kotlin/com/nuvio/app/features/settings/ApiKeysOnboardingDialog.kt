package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioDialogSurface
import com.nuvio.app.core.ui.StartupOverlayCoordinator
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.api_keys_onboarding_dont_ask_again
import nuvio.composeapp.generated.resources.api_keys_onboarding_message
import nuvio.composeapp.generated.resources.api_keys_onboarding_skip_for_now
import nuvio.composeapp.generated.resources.api_keys_onboarding_title
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeysOnboardingHost(modifier: Modifier = Modifier) {
    val state by ApiKeysOnboardingController.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        ApiKeysOnboardingController.evaluateOnLaunch()
    }

    val visibleOverlay by StartupOverlayCoordinator.visibleOverlay.collectAsStateWithLifecycle()
    LaunchedEffect(state.visible) {
        StartupOverlayCoordinator.setWantsToShow(StartupOverlayCoordinator.Overlay.ApiKeys, state.visible)
    }

    if (!state.visible) return
    if (visibleOverlay != StartupOverlayCoordinator.Overlay.ApiKeys) return

    BasicAlertDialog(onDismissRequest = ApiKeysOnboardingController::dismissUntilRestart) {
        NuvioDialogSurface(modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)) {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.api_keys_onboarding_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(Res.string.api_keys_onboarding_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                ApiKeySetupContent(
                    tmdbApiKey = state.tmdbApiKey,
                    mdbListApiKey = state.mdbListApiKey,
                    onTmdbApiKeyChange = ApiKeysOnboardingController::onTmdbApiKeyCommitted,
                    onMdbListApiKeyChange = ApiKeysOnboardingController::onMdbListApiKeyCommitted,
                    style = ApiKeySetupStyle.CommitPerField,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = ApiKeysOnboardingController::dismissPermanently,
                    ) {
                        Text(stringResource(Res.string.api_keys_onboarding_dont_ask_again))
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = ApiKeysOnboardingController::dismissUntilRestart,
                    ) {
                        Text(stringResource(Res.string.api_keys_onboarding_skip_for_now))
                    }
                }
            }
        }
    }
}
