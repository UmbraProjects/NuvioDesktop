package com.nuvio.app.features.settings
// FORK UI (Spyder): Nick's album page facts, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.discover.DiscoverAiSettingsRepository
import com.nuvio.app.features.music.booklet.FILL_CAP_CHOICES
import com.nuvio.app.features.music.booklet.FactsFiller
import com.nuvio.app.features.music.booklet.fillProgressLine
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
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_music_facts_cap),
                    description = stringResource(Res.string.settings_music_facts_cap_description),
                    isTablet = isTablet,
                    trailingContent = {
                        Text("${status.cap}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textPrimary)
                    },
                    onClick = {
                        val at = FILL_CAP_CHOICES.indexOf(status.cap)
                        FactsFiller.setCap(FILL_CAP_CHOICES[(at + 1) % FILL_CAP_CHOICES.size])
                    },
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
                    add("Last 24 hours: ${status.used} of ${status.cap} lookups used")
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
}
