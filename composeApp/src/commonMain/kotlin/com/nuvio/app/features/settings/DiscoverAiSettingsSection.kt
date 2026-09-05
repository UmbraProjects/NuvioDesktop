package com.nuvio.app.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioModalDialog
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.discover.AI_DISCOVER_ROW_LIMIT
import com.nuvio.app.features.discover.AiProposalEmptyReason
import com.nuvio.app.features.discover.aiProposalEmptyReason
import com.nuvio.app.features.discover.proposeAiDiscoverRows
import com.nuvio.app.features.discover.AiDiscoverPreset
import com.nuvio.app.features.discover.AiDiscoverRow
import com.nuvio.app.features.discover.DISCOVER_AI_ANTHROPIC_BASE_URL
import com.nuvio.app.features.discover.DiscoverAiError
import com.nuvio.app.features.discover.DiscoverAiException
import com.nuvio.app.features.discover.DiscoverAiGenerator
import com.nuvio.app.features.discover.DiscoverAiProvider
import com.nuvio.app.features.discover.DiscoverAiSettingsRepository
import com.nuvio.app.features.discover.aiExclusionsFromHistory
import com.nuvio.app.features.discover.aiSeedsForPreset
import com.nuvio.app.features.discover.withoutItemAt
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HomeCatalogSettingsUiState
import com.nuvio.app.features.watched.WatchedRepository
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_done
import nuvio.composeapp.generated.resources.settings_discover_ai_add_row
import nuvio.composeapp.generated.resources.settings_discover_ai_add_row_description
import nuvio.composeapp.generated.resources.settings_discover_ai_base_url
import nuvio.composeapp.generated.resources.settings_discover_ai_base_url_description
import nuvio.composeapp.generated.resources.settings_discover_ai_base_url_placeholder
import nuvio.composeapp.generated.resources.settings_discover_ai_consent_accept
import nuvio.composeapp.generated.resources.settings_discover_ai_consent_body
import nuvio.composeapp.generated.resources.settings_discover_ai_consent_title
import nuvio.composeapp.generated.resources.settings_discover_ai_enabled
import nuvio.composeapp.generated.resources.settings_discover_ai_enabled_description
import nuvio.composeapp.generated.resources.settings_discover_ai_error_empty
import nuvio.composeapp.generated.resources.settings_discover_ai_error_http
import nuvio.composeapp.generated.resources.settings_discover_ai_error_no_history
import nuvio.composeapp.generated.resources.settings_discover_ai_error_nothing_resolved
import nuvio.composeapp.generated.resources.settings_discover_ai_error_not_configured
import nuvio.composeapp.generated.resources.settings_discover_ai_error_rate_limited
import nuvio.composeapp.generated.resources.settings_discover_ai_error_rate_limited_wait
import nuvio.composeapp.generated.resources.settings_discover_ai_error_refused
import nuvio.composeapp.generated.resources.settings_discover_ai_error_timeout
import nuvio.composeapp.generated.resources.settings_discover_ai_error_truncated
import nuvio.composeapp.generated.resources.settings_discover_ai_error_unauthorized
import nuvio.composeapp.generated.resources.settings_discover_ai_error_unreadable
import nuvio.composeapp.generated.resources.settings_discover_ai_generate
import nuvio.composeapp.generated.resources.settings_discover_ai_generated_ok
import nuvio.composeapp.generated.resources.settings_discover_ai_generating
import nuvio.composeapp.generated.resources.settings_discover_ai_key
import nuvio.composeapp.generated.resources.settings_discover_ai_key_description
import nuvio.composeapp.generated.resources.settings_discover_ai_key_placeholder
import nuvio.composeapp.generated.resources.settings_discover_ai_model
import nuvio.composeapp.generated.resources.settings_discover_ai_model_description
import nuvio.composeapp.generated.resources.settings_discover_ai_never_generated
import nuvio.composeapp.generated.resources.settings_discover_ai_preset
import nuvio.composeapp.generated.resources.settings_discover_ai_preset_acclaimed
import nuvio.composeapp.generated.resources.settings_discover_ai_preset_comfort
import nuvio.composeapp.generated.resources.discover_row_ai_clustered
import nuvio.composeapp.generated.resources.discover_row_ai_just_watched
import nuvio.composeapp.generated.resources.settings_discover_ai_preset_clustered
import nuvio.composeapp.generated.resources.settings_discover_ai_preset_custom
import nuvio.composeapp.generated.resources.settings_discover_ai_preset_just_watched
import nuvio.composeapp.generated.resources.settings_discover_ai_suggested
import nuvio.composeapp.generated.resources.settings_discover_ai_suggested_description
import nuvio.composeapp.generated.resources.settings_discover_ai_suggested_evidence
import nuvio.composeapp.generated.resources.settings_discover_ai_suggested_evidence_short
import nuvio.composeapp.generated.resources.settings_discover_ai_suggested_all_added
import nuvio.composeapp.generated.resources.settings_discover_ai_suggested_at_limit
import nuvio.composeapp.generated.resources.settings_discover_ai_suggested_none
import nuvio.composeapp.generated.resources.settings_discover_ai_preset_gems
import nuvio.composeapp.generated.resources.settings_discover_ai_prompt
import nuvio.composeapp.generated.resources.settings_discover_ai_prompt_placeholder
import nuvio.composeapp.generated.resources.settings_discover_ai_provider
import nuvio.composeapp.generated.resources.settings_discover_ai_provider_anthropic
import nuvio.composeapp.generated.resources.settings_discover_ai_provider_openai
import nuvio.composeapp.generated.resources.settings_discover_ai_row_limit
import nuvio.composeapp.generated.resources.settings_discover_ai_row_remove
import nuvio.composeapp.generated.resources.settings_discover_ai_row_subtitle
import nuvio.composeapp.generated.resources.settings_discover_ai_row_title_hint
import nuvio.composeapp.generated.resources.settings_discover_ai_row_untitled
import nuvio.composeapp.generated.resources.settings_discover_ai_section
import nuvio.composeapp.generated.resources.settings_discover_custom_name
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Provider settings and AI row management — plan §5, phase 6.
 *
 * Kept in its own file rather than added to `DiscoverSettingsPage`, which is already the longest
 * settings page in the app.
 */

/** One AI row's line in the row-order list. */
@Composable
internal fun AiDiscoverRowEntry(
    row: AiDiscoverRow,
    isTablet: Boolean,
    onEdit: () -> Unit,
) {
    val untitled = stringResource(Res.string.settings_discover_ai_row_untitled)
    val neverGenerated = stringResource(Res.string.settings_discover_ai_never_generated)
    DiscoverAiRowShell(
        title = row.title.ifBlank { untitled },
        subtitle = if (row.hasGenerated) {
            stringResource(Res.string.settings_discover_ai_row_subtitle, row.items.size)
        } else {
            neverGenerated
        },
        enabled = row.enabled,
        isTablet = isTablet,
        onEnabledChange = {
            HomeCatalogSettingsRepository.updateDiscoverAiRow(row.copy(enabled = it))
        },
        onClick = onEdit,
    )
}

/**
 * The editor for one AI row: what to ask, and the answer it last got.
 *
 * **Generation is a button, never a side effect of opening this.** Every press spends the user's
 * own money at their own provider, so nothing here regenerates on its own — that is also why the
 * daily refresh in the provider section is off by default.
 */
@OptIn(ExperimentalUuidApi::class)
@Composable
internal fun AiDiscoverRowDialog(
    row: AiDiscoverRow,
    isTablet: Boolean,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val aiSettings by DiscoverAiSettingsRepository.uiState.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val generating = stringResource(Res.string.settings_discover_ai_generating)
    val notConfigured = stringResource(Res.string.settings_discover_ai_error_not_configured)

    fun update(transform: (AiDiscoverRow) -> AiDiscoverRow) {
        HomeCatalogSettingsRepository.updateDiscoverAiRow(transform(row))
    }

    NuvioModalDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = row.title.ifBlank { stringResource(Res.string.settings_discover_ai_row_untitled) },
        subtitle = stringResource(Res.string.settings_discover_ai_add_row_description),
        maxWidth = 640.dp,
        modifier = Modifier.width(640.dp),
        actions = {
            TextButton(
                onClick = {
                    onDismiss()
                    HomeCatalogSettingsRepository.removeDiscoverAiRow(row.id)
                },
                colors = ButtonDefaults.textButtonColors(contentColor = tokens.colors.danger),
            ) { Text(stringResource(Res.string.settings_discover_ai_row_remove)) }
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(Res.string.action_done))
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SettingsTextRow(
                title = stringResource(Res.string.settings_discover_custom_name),
                description = null,
                value = row.title,
                isTablet = isTablet,
                placeholder = stringResource(Res.string.settings_discover_ai_row_title_hint),
                onValueChange = { title -> update { it.copy(title = title) } },
            )

            SettingsChoiceRow(
                title = stringResource(Res.string.settings_discover_ai_preset),
                description = null,
                options = listOf(
                    SettingsChoiceOption(
                        AiDiscoverPreset.HiddenGems,
                        stringResource(Res.string.settings_discover_ai_preset_gems),
                    ),
                    SettingsChoiceOption(
                        AiDiscoverPreset.ComfortWatches,
                        stringResource(Res.string.settings_discover_ai_preset_comfort),
                    ),
                    SettingsChoiceOption(
                        AiDiscoverPreset.CriticallyAcclaimed,
                        stringResource(Res.string.settings_discover_ai_preset_acclaimed),
                    ),
                    SettingsChoiceOption(
                        AiDiscoverPreset.JustWatched,
                        stringResource(Res.string.settings_discover_ai_preset_just_watched),
                    ),
                    SettingsChoiceOption(
                        AiDiscoverPreset.ClusteredFavourites,
                        stringResource(Res.string.settings_discover_ai_preset_clustered),
                    ),
                    SettingsChoiceOption(
                        AiDiscoverPreset.Custom,
                        stringResource(Res.string.settings_discover_ai_preset_custom),
                    ),
                ),
                selectedValue = row.preset,
                isTablet = isTablet,
                onSelected = { preset -> update { it.copy(preset = preset) } },
            )

            if (row.preset == AiDiscoverPreset.Custom) {
                SettingsTextRow(
                    title = stringResource(Res.string.settings_discover_ai_prompt),
                    description = null,
                    value = row.customInstruction,
                    isTablet = isTablet,
                    placeholder = stringResource(Res.string.settings_discover_ai_prompt_placeholder),
                    // A prompt is prose, and a one-line box invites a one-line prompt.
                    singleLine = false,
                    minLines = 3,
                    onValueChange = { text -> update { it.copy(customInstruction = text) } },
                )
            }

            SettingsNavigationRow(
                title = stringResource(Res.string.settings_discover_ai_generate),
                description = status,
                isTablet = isTablet,
                enabled = aiSettings.isReady && !busy,
                onClick = {
                    if (!aiSettings.isReady) {
                        status = notConfigured
                        return@SettingsNavigationRow
                    }
                    busy = true
                    status = generating
                    scope.launch {
                        val history = WatchedRepository.uiState.value.items
                        val now = currentTimeMillisForAi()
                        val outcome = DiscoverAiGenerator.generate(
                            row = row,
                            // Phase 8: the preset decides which slice of the history travels.
                            // "Just watched" wants the last ten in order; the clustered row wants
                            // the most-committed titles as a group; the phase-6 presets want the
                            // whole recent window they always had.
                            seeds = aiSeedsForPreset(row.preset, history, now),
                            exclusions = aiExclusionsFromHistory(history),
                        )
                        status = outcome.fold(
                            onSuccess = { items ->
                                HomeCatalogSettingsRepository.updateDiscoverAiRow(
                                    row.copy(items = items, generatedAtEpochMs = now),
                                )
                                getString(Res.string.settings_discover_ai_generated_ok, items.size)
                            },
                            onFailure = { error -> discoverAiErrorMessage(error) },
                        )
                        busy = false
                    }
                },
            )

            row.items.forEachIndexed { index, item ->
                SettingsGroupDivider(isTablet = isTablet)
                DiscoverAiItemLine(
                    name = item.name,
                    reason = item.reason,
                    isTablet = isTablet,
                    onRemove = { update { current -> current.withoutItemAt(index) } },
                )
            }
        }
    }
}

/**
 * The rows the app has worked out the history can support — plan §19.3, phase 8.
 *
 * **These are offers, not rows.** Everything behind them ([proposeAiDiscoverRows]) is pure and
 * local: no network, no TMDB, no provider, no cost. Accepting one creates an ordinary saved
 * [AiDiscoverRow] with its Generate button unpressed, which is what keeps §5's rule intact — the
 * app may work out *what* is worth asking, but only the user decides to pay for the answer.
 *
 * Each offer shows the titles it was built from, because "because you finished Dark, Lost and FROM"
 * is something a person can accept or dismiss on sight where a bare row name is a guess. A history
 * too thin to support anything renders one line saying so rather than an empty group.
 */
@OptIn(ExperimentalUuidApi::class)
@Composable
internal fun SuggestedAiDiscoverRows(
    isTablet: Boolean,
    settings: HomeCatalogSettingsUiState,
    onRowAdded: (String) -> Unit,
) {
    val watched by WatchedRepository.uiState.collectAsStateWithLifecycle()
    // Keyed on the history and the saved rows, the two things that can change the answer. The work
    // is a collapse and a sort over the watch history, so it is cheap — but it is not free, and the
    // settings page recomposes for every unrelated toggle on it.
    val proposals = remember(watched.items, settings.discoverAiRows) {
        proposeAiDiscoverRows(
            history = watched.items,
            existing = settings.discoverAiRows,
            now = currentTimeMillisForAi(),
        )
    }

    val justWatchedTitle = stringResource(Res.string.discover_row_ai_just_watched)
    val clusteredTitle = stringResource(Res.string.discover_row_ai_clustered)

    // A header either way, carrying the line that matters most: adding a row spends nothing. Without
    // it a list of offers under an AI heading reads as something the app is about to go and buy.
    //
    // **A caption, not a disabled row.** This was a SettingsNavigationRow with `enabled = false`,
    // which the row idiom renders at reduced opacity — so a heading that was never meant to be
    // clicked read as a control that had been switched off, and the offers under it looked
    // unavailable too. Nothing here is interactive, so nothing here should borrow a control's
    // shape.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = if (isTablet) 10.dp else 14.dp),
    ) {
        Text(
            text = stringResource(Res.string.settings_discover_ai_suggested),
            style = if (isTablet) {
                MaterialTheme.typography.bodyMedium
            } else {
                MaterialTheme.typography.bodyLarge
            },
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
        SettingsSubtext(
            text = if (proposals.isEmpty()) {
                // The three empty cases want opposite things from the user — watch something,
                // delete a row, or nothing at all — so they must not share a sentence.
                when (aiProposalEmptyReason(settings.discoverAiRows)) {
                    AiProposalEmptyReason.AllAdded ->
                        stringResource(Res.string.settings_discover_ai_suggested_all_added)

                    AiProposalEmptyReason.RowLimitReached ->
                        stringResource(Res.string.settings_discover_ai_suggested_at_limit)

                    AiProposalEmptyReason.NotEnoughHistory ->
                        stringResource(Res.string.settings_discover_ai_suggested_none)
                }
            } else {
                stringResource(Res.string.settings_discover_ai_suggested_description)
            },
            isTablet = isTablet,
        )
    }
    if (proposals.isEmpty()) return

    proposals.forEach { proposal ->
        SettingsGroupDivider(isTablet = isTablet)
        val named = proposal.evidence.joinToString(", ")
        val remaining = proposal.seedCount - proposal.evidence.size
        SettingsNavigationRow(
            title = when (proposal.preset) {
                AiDiscoverPreset.ClusteredFavourites -> clusteredTitle
                else -> justWatchedTitle
            },
            description = if (remaining > 0) {
                stringResource(Res.string.settings_discover_ai_suggested_evidence, named, remaining)
            } else {
                stringResource(Res.string.settings_discover_ai_suggested_evidence_short, named)
            },
            isTablet = isTablet,
            onClick = {
                // Added and titled in one action rather than dropped into the editor empty: unlike
                // a custom row, there is no question left to ask — the preset and the name are what
                // the proposal *was*. The editor is still one tap away on the row itself.
                val id = Uuid.random().toString()
                val added = HomeCatalogSettingsRepository.addDiscoverAiRow(id) ?: return@SettingsNavigationRow
                HomeCatalogSettingsRepository.updateDiscoverAiRow(
                    added.copy(
                        preset = proposal.preset,
                        title = when (proposal.preset) {
                            AiDiscoverPreset.ClusteredFavourites -> clusteredTitle
                            else -> justWatchedTitle
                        },
                    ),
                )
                onRowAdded(id)
            },
        )
    }
}

/** "Add an AI row", with the limit stated only when it is the reason for a refusal. */
@OptIn(ExperimentalUuidApi::class)
@Composable
internal fun AddAiDiscoverRowButton(
    isTablet: Boolean,
    settings: HomeCatalogSettingsUiState,
    onRowAdded: (String) -> Unit,
) {
    val atLimit = settings.discoverAiRows.size >= AI_DISCOVER_ROW_LIMIT
    SettingsNavigationRow(
        title = stringResource(Res.string.settings_discover_ai_add_row),
        description = if (atLimit) {
            stringResource(Res.string.settings_discover_ai_row_limit, AI_DISCOVER_ROW_LIMIT)
        } else {
            stringResource(Res.string.settings_discover_ai_add_row_description)
        },
        isTablet = isTablet,
        enabled = !atLimit,
        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("discover-ai-rows")),
        onClick = {
            HomeCatalogSettingsRepository.addDiscoverAiRow(Uuid.random().toString())
                ?.let { added -> onRowAdded(added.id) }
        },
    )
}

/**
 * Provider configuration.
 *
 * The privacy dialog gates the *first* enable and nothing else: it says what leaves the machine,
 * and re-asking someone who has already read it would train them to dismiss it.
 */
@Composable
internal fun DiscoverAiProviderSection(isTablet: Boolean) {
    val settings by DiscoverAiSettingsRepository.uiState.collectAsStateWithLifecycle()
    var consentPrompt by remember { mutableStateOf(false) }

    SettingsSection(
        title = stringResource(Res.string.settings_discover_ai_section),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_discover_ai_enabled),
                description = stringResource(Res.string.settings_discover_ai_enabled_description),
                checked = settings.enabled,
                isTablet = isTablet,
                modifier = Modifier.settingsScrollAnchor(
                    SettingsScrollAnchor.searchKey("discover-ai-enabled"),
                ),
                onCheckedChange = { wanted ->
                    when {
                        !wanted -> DiscoverAiSettingsRepository.setEnabled(false)
                        // Consent first, and only once. The dialog does the enabling.
                        !settings.consentGiven -> consentPrompt = true
                        else -> DiscoverAiSettingsRepository.setEnabled(true)
                    }
                },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsChoiceRow(
                title = stringResource(Res.string.settings_discover_ai_provider),
                description = null,
                options = listOf(
                    SettingsChoiceOption(
                        DiscoverAiProvider.OpenAiCompat,
                        stringResource(Res.string.settings_discover_ai_provider_openai),
                    ),
                    SettingsChoiceOption(
                        DiscoverAiProvider.Anthropic,
                        stringResource(Res.string.settings_discover_ai_provider_anthropic),
                    ),
                ),
                selectedValue = settings.provider,
                isTablet = isTablet,
                onSelected = DiscoverAiSettingsRepository::setProvider,
            )
            SettingsGroupDivider(isTablet = isTablet)
            // All three of these are SettingsTextInputRow, the same row-plus-modal every other
            // credential in the app uses, rather than text boxes drawn into the page. Masked too:
            // this was the one key field on any settings page rendered in plain text on a screen
            // someone might be sharing.
            SettingsTextInputRow(
                title = stringResource(Res.string.settings_discover_ai_key),
                description = stringResource(Res.string.settings_discover_ai_key_description),
                value = settings.apiKey,
                placeholder = stringResource(Res.string.settings_discover_ai_key_placeholder),
                isTablet = isTablet,
                secret = true,
                onSave = DiscoverAiSettingsRepository::setApiKey,
            )
            if (settings.provider == DiscoverAiProvider.OpenAiCompat) {
                SettingsGroupDivider(isTablet = isTablet)
                SettingsTextInputRow(
                    title = stringResource(Res.string.settings_discover_ai_base_url),
                    description = stringResource(Res.string.settings_discover_ai_base_url_description),
                    value = settings.baseUrl,
                    placeholder = stringResource(Res.string.settings_discover_ai_base_url_placeholder),
                    isTablet = isTablet,
                    onSave = DiscoverAiSettingsRepository::setBaseUrl,
                )
            }
            SettingsGroupDivider(isTablet = isTablet)
            SettingsTextInputRow(
                title = stringResource(Res.string.settings_discover_ai_model),
                description = stringResource(
                    Res.string.settings_discover_ai_model_description,
                    settings.provider.defaultModel,
                ),
                value = settings.model,
                // The provider's default, which is exactly what leaving this blank gets you.
                placeholder = settings.provider.defaultModel,
                isTablet = isTablet,
                onSave = DiscoverAiSettingsRepository::setModel,
            )
            // The daily-refresh switch used to sit here. Nothing schedules anything — there is no
            // reader for `DiscoverAiSettings.dailyRefresh` — so it was a control that spent nothing
            // and changed nothing, which is worse than an absent feature: a user who turns it on is
            // owed a refresh that never comes. Plan §18 named it "wire it or hide it"; hiding it is
            // the honest half until Phase 8 decides what an automatic generation is allowed to cost.
            // The setting itself is kept — off by default, still stored and synced — so turning the
            // switch back on is a matter of restoring this row once something reads it.
        }
    }

    if (!consentPrompt) return
    NuvioModalDialog(
        onDismissRequest = { consentPrompt = false },
        title = stringResource(Res.string.settings_discover_ai_consent_title),
        subtitle = null,
        maxWidth = 560.dp,
        modifier = Modifier.width(560.dp),
        actions = {
            TextButton(onClick = { consentPrompt = false }) {
                Text(stringResource(Res.string.action_done))
            }
            TextButton(
                onClick = {
                    consentPrompt = false
                    DiscoverAiSettingsRepository.setConsentGiven(true)
                    DiscoverAiSettingsRepository.setEnabled(true)
                },
            ) { Text(stringResource(Res.string.settings_discover_ai_consent_accept)) }
        },
    ) {
        Text(
            text = stringResource(
                Res.string.settings_discover_ai_consent_body,
                when (DiscoverAiSettingsRepository.snapshot().provider) {
                    DiscoverAiProvider.Anthropic -> DISCOVER_AI_ANTHROPIC_BASE_URL
                    DiscoverAiProvider.OpenAiCompat ->
                        DiscoverAiSettingsRepository.snapshot().effectiveBaseUrl
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.nuvio.colors.textMuted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** Each failure says what to do about it — they need different things from the user. */
internal suspend fun discoverAiErrorMessage(error: Throwable): String =
    when (val reason = (error as? DiscoverAiException)?.error) {
        DiscoverAiError.NotConfigured, null ->
            getString(Res.string.settings_discover_ai_error_not_configured)

        is DiscoverAiError.Unauthorized ->
            getString(Res.string.settings_discover_ai_error_unauthorized)

        DiscoverAiError.NoHistorySlice ->
            getString(Res.string.settings_discover_ai_error_no_history)

        is DiscoverAiError.Http ->
            getString(Res.string.settings_discover_ai_error_http, reason.status, reason.detail)

        // The provider's own message is the half that discriminates: an account cap reads
        // "Rate limit exceeded", while an upstream one names the model as temporarily rate-limited.
        // Dropping it and printing a generic line is what sent the last report chasing a quota that
        // was not the cause.
        is DiscoverAiError.RateLimited -> reason.retryAfterSeconds?.let { seconds ->
            getString(
                Res.string.settings_discover_ai_error_rate_limited_wait,
                reason.detail,
                seconds,
            )
        } ?: getString(Res.string.settings_discover_ai_error_rate_limited, reason.detail)

        DiscoverAiError.Timeout -> getString(Res.string.settings_discover_ai_error_timeout)
        DiscoverAiError.Unreadable -> getString(Res.string.settings_discover_ai_error_unreadable)
        DiscoverAiError.Empty -> getString(Res.string.settings_discover_ai_error_empty)
        DiscoverAiError.Truncated -> getString(Res.string.settings_discover_ai_error_truncated)
        DiscoverAiError.NothingResolved ->
            getString(Res.string.settings_discover_ai_error_nothing_resolved)

        is DiscoverAiError.Refused ->
            getString(Res.string.settings_discover_ai_error_refused, reason.explanation)
    }


/** The AI row's line in the row-order list. Mirrors the shell the other row kinds use. */
@Composable
private fun DiscoverAiRowShell(
    title: String,
    subtitle: String,
    enabled: Boolean,
    isTablet: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SettingsSquareSwitch(checked = enabled, onCheckedChange = onEnabledChange)
    }
}

/**
 * One generated title, with the model's reason under it.
 *
 * The reason is the whole point of an AI row — it is what a plain recommendations feed cannot give
 * — so it is shown here in full rather than hidden behind a hover.
 */
@Composable
private fun DiscoverAiItemLine(
    name: String,
    reason: String?,
    isTablet: Boolean,
    onRemove: () -> Unit,
) {
    SettingsNavigationRow(
        title = name,
        description = reason.orEmpty(),
        isTablet = isTablet,
        onClick = onRemove,
    )
}

/** Wall clock. Matches how the rest of the feature stamps times. */
private fun currentTimeMillisForAi(): Long = System.currentTimeMillis()
