package com.nuvio.app.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.api_keys_onboarding_mdblist_label
import nuvio.composeapp.generated.resources.api_keys_onboarding_tmdb_label
import nuvio.composeapp.generated.resources.setup_wizard_keys_mdblist_benefit
import nuvio.composeapp.generated.resources.setup_wizard_keys_tmdb_benefit
import nuvio.composeapp.generated.resources.setup_wizard_keys_where
import org.jetbrains.compose.resources.stringResource
import com.nuvio.app.core.ui.accentBrush

private const val TMDB_API_KEY_URL = "https://www.themoviedb.org/settings/api"
private const val MDBLIST_API_KEY_URL = "https://mdblist.com/preferences/"

/**
 * How the shared TMDB/MDBList field pair behaves.
 *
 * The legacy missing-key dialog commits one field at a time through a Save button; the setup wizard
 * stages both into its draft and applies them on Finish. Same fields, same auto-enable semantics —
 * only the commit moment differs, so this stays one component rather than two that drift.
 */
internal enum class ApiKeySetupStyle {
    /** Per-field Save button; [onTmdbApiKeyChange]/[onMdbListApiKeyChange] fire only on Save. */
    CommitPerField,

    /** Live updates on every keystroke, plus benefit copy and "Where do I get this?" links. */
    Staged,
}

@Composable
internal fun ApiKeySetupContent(
    tmdbApiKey: String,
    mdbListApiKey: String,
    onTmdbApiKeyChange: (String) -> Unit,
    onMdbListApiKeyChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: ApiKeySetupStyle = ApiKeySetupStyle.CommitPerField,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(
            if (style == ApiKeySetupStyle.Staged) NuvioTokens.Space.s20 else NuvioTokens.Space.s12,
        ),
    ) {
        ApiKeyField(
            value = tmdbApiKey,
            label = stringResource(Res.string.api_keys_onboarding_tmdb_label),
            benefit = stringResource(Res.string.setup_wizard_keys_tmdb_benefit),
            helpUrl = TMDB_API_KEY_URL,
            style = style,
            onChange = onTmdbApiKeyChange,
        )
        ApiKeyField(
            value = mdbListApiKey,
            label = stringResource(Res.string.api_keys_onboarding_mdblist_label),
            benefit = stringResource(Res.string.setup_wizard_keys_mdblist_benefit),
            helpUrl = MDBLIST_API_KEY_URL,
            style = style,
            onChange = onMdbListApiKeyChange,
        )
    }
}

@Composable
private fun ApiKeyField(
    value: String,
    label: String,
    benefit: String,
    helpUrl: String,
    style: ApiKeySetupStyle,
    onChange: (String) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val uriHandler = LocalUriHandler.current
    var draft by rememberSaveable(value) { mutableStateOf(value) }
    val normalizedDraft = draft.trim()

    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
        if (style == ApiKeySetupStyle.Staged) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = benefit,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
            )
        }
        SettingsSecretTextField(
            value = draft,
            onValueChange = {
                draft = it
                if (style == ApiKeySetupStyle.Staged) onChange(it.trim())
            },
            modifier = Modifier.fillMaxWidth(),
            label = label,
        )
        when (style) {
            ApiKeySetupStyle.CommitPerField -> {
                Button(
                    onClick = {
                        draft = normalizedDraft
                        onChange(normalizedDraft)
                    },
                    enabled = normalizedDraft != value && normalizedDraft.isNotBlank(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Text(stringResource(Res.string.action_save))
                }
            }

            ApiKeySetupStyle.Staged -> {
                Text(
                    text = stringResource(Res.string.setup_wizard_keys_where),
                    style = MaterialTheme.typography.bodySmall.accentBrush(),
                    color = tokens.colors.accent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { uriHandler.openUri(helpUrl) },
                )
            }
        }
    }
}
