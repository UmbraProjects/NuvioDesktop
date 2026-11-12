package com.nuvio.app.features.settings
// FORK UI (Spyder): Nick's album page facts, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.width
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.discover.DiscoverAiSettingsRepository
import com.nuvio.app.features.music.booklet.FILL_CAP_CHOICES
import com.nuvio.app.features.music.booklet.capLabel
import com.nuvio.app.features.music.booklet.FactsFiller
import com.nuvio.app.features.music.booklet.fillProgressLine
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioModalDialog
import com.nuvio.app.features.music.booklet.FactsSharing
import com.nuvio.app.features.music.booklet.describeCounts
import kotlinx.coroutines.launch
import kotlin.time.Instant
import nuvio.composeapp.generated.resources.settings_music_share_dialog_apply
import nuvio.composeapp.generated.resources.settings_music_share_dialog_cancel
import nuvio.composeapp.generated.resources.settings_music_share_dialog_title
import nuvio.composeapp.generated.resources.settings_music_share_export
import nuvio.composeapp.generated.resources.settings_music_share_export_button
import nuvio.composeapp.generated.resources.settings_music_share_export_description
import nuvio.composeapp.generated.resources.settings_music_share_import
import nuvio.composeapp.generated.resources.settings_music_share_import_button
import nuvio.composeapp.generated.resources.settings_music_share_import_description
import nuvio.composeapp.generated.resources.settings_music_share_section
import nuvio.composeapp.generated.resources.settings_music_share_undo_button
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_music_facts_cap
import nuvio.composeapp.generated.resources.settings_music_facts_cap_description
import nuvio.composeapp.generated.resources.settings_music_facts_check
import nuvio.composeapp.generated.resources.settings_music_facts_check_button
import nuvio.composeapp.generated.resources.settings_music_facts_check_description
import nuvio.composeapp.generated.resources.settings_music_facts_retry
import nuvio.composeapp.generated.resources.settings_music_facts_retry_button
import nuvio.composeapp.generated.resources.settings_music_facts_retry_description
import nuvio.composeapp.generated.resources.settings_music_facts_section
import nuvio.composeapp.generated.resources.settings_music_facts_switch
import nuvio.composeapp.generated.resources.settings_music_facts_switch_description
import org.jetbrains.compose.resources.stringResource

/*
 * fork:facts-filler - Settings > Music > Music facts: the switch for the background filler (off by default), how many AI
 * lookups it may make a day, how far it has got and what failed. See music/booklet/FactsFiller.kt.
 */

/** How many failed lookups the page lists; the rest are counted. */
private const val RETRY_SHOWN = 8

internal fun LazyListScope.musicFactsSettingsContent(isTablet: Boolean) {
    item {
        SettingsSection(title = stringResource(Res.string.settings_music_facts_section), isTablet = isTablet) {
            val status by FactsFiller.status.collectAsStateWithLifecycle()
            val ai by DiscoverAiSettingsRepository.uiState.collectAsStateWithLifecycle()
            var consentPromptFor by remember { mutableStateOf<AiConsentTarget?>(null) }
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_music_facts_switch),
                    description = stringResource(Res.string.settings_music_facts_switch_description),
                    checked = status.enabled,
                    isTablet = isTablet,
                    onCheckedChange = { on ->
                        when {
                            !on -> FactsFiller.setEnabled(false)
                            ai.apiKey.isNotBlank() && !ai.consentGiven -> consentPromptFor = AiConsentTarget.MusicFacts
                            else -> FactsFiller.setEnabled(true)
                        }
                    },
                )
                SettingsGroupDivider(isTablet)
                // A dropdown like the app's other choices (it was a row that stepped to the next amount on each click).
                SettingsDropdownChoiceRow(
                    title = stringResource(Res.string.settings_music_facts_cap),
                    description = stringResource(Res.string.settings_music_facts_cap_description),
                    options = (FILL_CAP_CHOICES + listOf(status.cap).filter { it !in FILL_CAP_CHOICES })
                        .map { SettingsChoiceOption(it, capLabel(it)) },
                    selectedValue = status.cap,
                    isTablet = isTablet,
                    onSelected = { FactsFiller.setCap(it) },
                )
                SettingsGroupDivider(isTablet)
                SettingsActionRow(
                    title = stringResource(Res.string.settings_music_facts_retry),
                    description = stringResource(Res.string.settings_music_facts_retry_description),
                    isTablet = isTablet,
                    actions = {
                        SettingsGlassButton(
                            text = stringResource(Res.string.settings_music_facts_retry_button),
                            onClick = FactsFiller::retryNow,
                            enabled = status.enabled && status.retry.isNotEmpty(),
                        )
                    },
                )
                SettingsGroupDivider(isTablet)
                SettingsActionRow(
                    title = stringResource(Res.string.settings_music_facts_check),
                    description = stringResource(Res.string.settings_music_facts_check_description),
                    isTablet = isTablet,
                    actions = {
                        SettingsGlassButton(
                            text = stringResource(Res.string.settings_music_facts_check_button),
                            onClick = FactsFiller::checkAgain,
                            enabled = status.enabled,
                        )
                    },
                )
            }
            if (status.enabled || status.albumsTotal > 0) {
                val lines = buildList {
                    add(fillProgressLine(status.albumsDone, status.albumsTotal, status.songsKept))
                    if (status.credits.isNotBlank()) add(status.credits)
                    add("Last 24 hours: ${status.used} of ${status.cap} tokens used")
                    if (status.activity.isNotBlank() && status.enabled) add(status.activity)
                    if (status.retry.isNotEmpty()) {
                        add("Failed a moment ago, tried again by itself after two hours (${status.retry.size}):")
                        addAll(status.retry.take(RETRY_SHOWN).map { "  $it" })
                        if (status.retry.size > RETRY_SHOWN) add("  and ${status.retry.size - RETRY_SHOWN} more")
                    }
                }
                SettingsSectionNote(text = lines.joinToString("\n"), isTablet = isTablet)
            }
            AiConsentDialog(target = consentPromptFor, onDismiss = { consentPromptFor = null })
        }
    }
    item {
        SettingsSection(title = stringResource(Res.string.settings_music_share_section), isTablet = isTablet) {
            val scope = rememberCoroutineScope()
            val preview by FactsSharing.preview.collectAsStateWithLifecycle()
            val message by FactsSharing.message.collectAsStateWithLifecycle()
            val imports by FactsSharing.imports.collectAsStateWithLifecycle()
            SettingsGroup(isTablet = isTablet) {
                SettingsActionRow(
                    title = stringResource(Res.string.settings_music_share_export),
                    description = stringResource(Res.string.settings_music_share_export_description),
                    isTablet = isTablet,
                    actions = {
                        SettingsGlassButton(
                            text = stringResource(Res.string.settings_music_share_export_button),
                            onClick = { scope.launch { FactsSharing.export() } },
                        )
                    },
                )
                SettingsGroupDivider(isTablet)
                SettingsActionRow(
                    title = stringResource(Res.string.settings_music_share_import),
                    description = stringResource(Res.string.settings_music_share_import_description),
                    isTablet = isTablet,
                    actions = {
                        SettingsGlassButton(
                            text = stringResource(Res.string.settings_music_share_import_button),
                            onClick = { scope.launch { FactsSharing.chooseImport() } },
                        )
                    },
                )
                imports.forEach { record ->
                    SettingsGroupDivider(isTablet)
                    SettingsActionRow(
                        title = "Added from ${record.fileName}",
                        description = "${record.keys.size} entries, ${Instant.fromEpochMilliseconds(record.atMs).toString().take(10)}",
                        isTablet = isTablet,
                        actions = {
                            SettingsGlassButton(
                                text = stringResource(Res.string.settings_music_share_undo_button),
                                onClick = { scope.launch { FactsSharing.undo(record.id) } },
                            )
                        },
                    )
                }
            }
            message?.let { SettingsSectionNote(text = it, isTablet = isTablet) }
            preview?.let { shown ->
                NuvioModalDialog(
                    onDismissRequest = { FactsSharing.cancel() },
                    title = stringResource(Res.string.settings_music_share_dialog_title),
                    subtitle = null,
                    maxWidth = 560.dp,
                    modifier = Modifier.width(560.dp),
                    actions = {
                        TextButton(onClick = { FactsSharing.cancel() }) { Text(stringResource(Res.string.settings_music_share_dialog_cancel)) }
                        TextButton(onClick = { scope.launch { FactsSharing.apply() } }) { Text(stringResource(Res.string.settings_music_share_dialog_apply)) }
                    },
                ) {
                    val plan = shown.plan
                    val lines = buildList {
                        add("${shown.fileName} would add ${describeCounts(plan.counts)}.")
                        if (plan.alreadyHave > 0) add("${plan.alreadyHave} are already here and stay as they are.")
                        if (plan.rejected > 0) add("${plan.rejected} entries in the file were not readable and are skipped.")
                        add("Your own pins are never replaced. Pins from the file are marked as imported.")
                    }
                    Text(
                        text = lines.joinToString("\n"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.nuvio.colors.textMuted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
