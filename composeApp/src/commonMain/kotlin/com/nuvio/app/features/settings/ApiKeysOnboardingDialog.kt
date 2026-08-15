package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioDialogSurface
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.api_keys_onboarding_dont_ask_again
import nuvio.composeapp.generated.resources.api_keys_onboarding_mdblist_label
import nuvio.composeapp.generated.resources.api_keys_onboarding_message
import nuvio.composeapp.generated.resources.api_keys_onboarding_skip_for_now
import nuvio.composeapp.generated.resources.api_keys_onboarding_title
import nuvio.composeapp.generated.resources.api_keys_onboarding_tmdb_label
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeysOnboardingHost(modifier: Modifier = Modifier) {
    val state by ApiKeysOnboardingController.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        ApiKeysOnboardingController.evaluateOnLaunch()
    }

    if (!state.visible) return

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

                ApiKeysOnboardingFieldRow(
                    value = state.tmdbApiKey,
                    label = stringResource(Res.string.api_keys_onboarding_tmdb_label),
                    onCommitted = ApiKeysOnboardingController::onTmdbApiKeyCommitted,
                )

                ApiKeysOnboardingFieldRow(
                    value = state.mdbListApiKey,
                    label = stringResource(Res.string.api_keys_onboarding_mdblist_label),
                    onCommitted = ApiKeysOnboardingController::onMdbListApiKeyCommitted,
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

@Composable
private fun ApiKeysOnboardingFieldRow(
    value: String,
    label: String,
    onCommitted: (String) -> Unit,
) {
    var draft by rememberSaveable(value) { mutableStateOf(value) }
    val normalizedDraft = draft.trim()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingsSecretTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            label = label,
        )
        Button(
            onClick = {
                draft = normalizedDraft
                onCommitted(normalizedDraft)
            },
            enabled = normalizedDraft != value && normalizedDraft.isNotBlank(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Text(stringResource(Res.string.action_save))
        }
    }
}
