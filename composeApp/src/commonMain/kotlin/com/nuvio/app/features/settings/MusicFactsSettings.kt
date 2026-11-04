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

/*
 * fork:facts-filler - Settings > Music > Music facts: the switch for the background filler (off by default), how many AI
 * lookups it may make a day, how far it has got and what failed. See music/booklet/FactsFiller.kt.
 */

/** How many failed lookups the page lists; the rest are counted. */
private const val RETRY_SHOWN = 8

internal fun LazyListScope.musicFactsSettingsContent(isTablet: Boolean) {
    item {
        SettingsSection(title = "Music facts", isTablet = isTablet) {
            val status by FactsFiller.status.collectAsStateWithLifecycle()
            val ai by DiscoverAiSettingsRepository.uiState.collectAsStateWithLifecycle()
            var consentPromptFor by remember { mutableStateOf<AiConsentTarget?>(null) }
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = "Fill album facts in the background",
                    description = "Writes Liner Notes, track notes and song stories for your whole library ahead of time, most played albums first, so an album opens with its facts already there. Uses your own AI key; nothing is sent until you switch this on.",
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
                    title = "Lookups per day",
                    description = "The most AI requests the filler makes in 24 hours. Press to change. A free key may allow fewer than the higher choices; if it runs into the limit the filler pauses by itself.",
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
                    title = "Check every album again",
                    description = "Looks at every album on the next pass instead of skipping the ones already finished. Nothing already written is asked for twice.",
                    isTablet = isTablet,
                    actions = { SettingsGlassButton(text = "Check again", onClick = FactsFiller::checkAgain, enabled = status.enabled) },
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
