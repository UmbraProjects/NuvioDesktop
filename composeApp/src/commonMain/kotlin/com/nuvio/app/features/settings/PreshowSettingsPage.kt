package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.discover.DiscoverAiSettingsRepository
import com.nuvio.app.features.preroll.PREROLL_MAX_TRAILERS
import com.nuvio.app.features.preroll.PrerollSettings
import com.nuvio.app.features.preroll.PreshowSettings
import com.nuvio.app.features.preroll.PreshowSlot
import com.nuvio.app.features.preroll.RatingRegion
import com.nuvio.app.features.themesong.ThemeSongSettings
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_ai_trivia_enabled
import nuvio.composeapp.generated.resources.settings_ai_trivia_enabled_description
import nuvio.composeapp.generated.resources.settings_preroll_trailers
import nuvio.composeapp.generated.resources.settings_preroll_trailers_description
import nuvio.composeapp.generated.resources.settings_preroll_trailers_off
import nuvio.composeapp.generated.resources.settings_preshow_keep_audio_alive
import nuvio.composeapp.generated.resources.settings_preshow_keep_audio_alive_description
import nuvio.composeapp.generated.resources.settings_preshow_section_trailers
import nuvio.composeapp.generated.resources.settings_theme_song_details
import nuvio.composeapp.generated.resources.settings_theme_song_details_description
import nuvio.composeapp.generated.resources.settings_theme_song_section
import nuvio.composeapp.generated.resources.settings_theme_song_trivia
import nuvio.composeapp.generated.resources.settings_theme_song_trivia_description
import nuvio.composeapp.generated.resources.settings_theme_song_volume
import nuvio.composeapp.generated.resources.settings_preshow_section_trivia
import nuvio.composeapp.generated.resources.settings_preshow_trivia_needs_ai
import org.jetbrains.compose.resources.stringResource

/**
 * Pre-show: everything that plays between pressing play and the film starting. Trailers of similar
 * titles first, then trivia over the film's own fanart. Both used to live elsewhere (the trailer
 * count under Playback, the trivia switch under Discover's AI section); the trivia switch still
 * depends on the AI provider configured there, and says so when that is missing.
 */
internal fun LazyListScope.preshowSettingsContent(isTablet: Boolean) {
    item {
        SettingsSection(
            title = "Running order",
            isTablet = isTablet,
        ) {
            val order by PreshowSettings.order.collectAsStateWithLifecycle()
            val enabled by PreshowSettings.enabledSlots.collectAsStateWithLifecycle()
            val positions = order.indices.map { position ->
                SettingsChoiceOption(position + 1, (position + 1).toString())
            }
            SettingsGroup(isTablet = isTablet) {
                order.forEachIndexed { index, slot ->
                    if (index > 0) SettingsGroupDivider(isTablet)
                    val label = slot.label()
                    SettingsChoiceRow(
                        title = label,
                        description = "Where this plays in the pre-show. Choosing a position moves the others along.",
                        options = positions,
                        selectedValue = index + 1,
                        isTablet = isTablet,
                        onSelected = { PreshowSettings.moveTo(slot, it) },
                    )
                    if (slot.isClipSlot) {
                        SettingsSwitchRow(
                            title = "Play: $label",
                            description = "Off by default. With nothing found for it, the rating card and feature presentation draw a simple card instead.",
                            checked = slot in enabled,
                            isTablet = isTablet,
                            onCheckedChange = { PreshowSettings.setEnabled(slot, it) },
                        )
                    }
                }
            }
        }
    }

    item {
        SettingsSection(
            title = "Rating card",
            isTablet = isTablet,
        ) {
            val region by PreshowSettings.ratingRegion.collectAsStateWithLifecycle()
            SettingsGroup(isTablet = isTablet) {
                SettingsChoiceRow(
                    title = "Rating system",
                    description = "The film's certificate is converted to this system. Nothing plays when the film has no certificate.",
                    options = listOf(
                        SettingsChoiceOption(RatingRegion.Bbfc, "BBFC (UK)"),
                        SettingsChoiceOption(RatingRegion.Mpaa, "MPA (US)"),
                    ),
                    selectedValue = region,
                    isTablet = isTablet,
                    onSelected = PreshowSettings::setRatingRegion,
                )
            }
        }
    }

    item {
        SettingsSection(
            title = "Clip sources",
            isTablet = isTablet,
        ) {
            val folder by PreshowSettings.localFolder.collectAsStateWithLifecycle()
            val avid by PreshowSettings.avidUrls.collectAsStateWithLifecycle()
            SettingsGroup(isTablet = isTablet) {
                SettingsTextInputRow(
                    title = "Local folder",
                    description = "Drop clips into the cinema-intro, bumper, rating-bbfc, rating-mpaa and feature-presentation subfolders. Rating clips are named after the certificate, such as 12A.mp4. Blank uses the default folder; local files win over AVID.",
                    value = folder,
                    placeholder = "Default folder",
                    isTablet = isTablet,
                    onSave = PreshowSettings::setLocalFolder,
                )
                listOf(
                    "cinema_intro" to "Cinema intro",
                    "bumper" to "Bumper",
                    "rating_bbfc" to "BBFC (UK)",
                    "rating_mpaa" to "MPA (US)",
                    "feature_presentation" to "Feature presentation",
                ).forEach { (key, label) ->
                    SettingsGroupDivider(isTablet)
                    SettingsTextInputRow(
                        title = "AVID: " + if (key.startsWith("rating")) "Rating card" + " ($label)" else label,
                        description = "An avid.wiki page, or a YouTube link, with embedded videos. Used when no local file matches.",
                        value = avid[key].orEmpty(),
                        placeholder = "https://avid.wiki/…",
                        isTablet = isTablet,
                        onSave = { PreshowSettings.setAvidUrl(key, it) },
                    )
                }
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_preshow_section_trailers),
            isTablet = isTablet,
        ) {
            val trailerCount by PrerollSettings.trailerCount.collectAsStateWithLifecycle()
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsSearchAnchors("preshow-trailers"),
            ) {
                SettingsChoiceRow(
                    title = stringResource(Res.string.settings_preroll_trailers),
                    description = stringResource(Res.string.settings_preroll_trailers_description),
                    options = (0..PREROLL_MAX_TRAILERS).map { count ->
                        SettingsChoiceOption(
                            count,
                            if (count == 0) stringResource(Res.string.settings_preroll_trailers_off) else count.toString(),
                        )
                    },
                    selectedValue = trailerCount,
                    isTablet = isTablet,
                    onSelected = PrerollSettings::setTrailerCount,
                )
                val keepAudioAlive by PreshowSettings.keepAudioAlive.collectAsStateWithLifecycle()
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_preshow_keep_audio_alive),
                    description = stringResource(Res.string.settings_preshow_keep_audio_alive_description),
                    checked = keepAudioAlive,
                    isTablet = isTablet,
                    onCheckedChange = PreshowSettings::setKeepAudioAlive,
                )
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_preshow_section_trivia),
            isTablet = isTablet,
        ) {
            val ai by DiscoverAiSettingsRepository.uiState.collectAsStateWithLifecycle()
            var consentPromptFor by remember { mutableStateOf<AiConsentTarget?>(null) }
            val description = stringResource(Res.string.settings_ai_trivia_enabled_description) +
                if (ai.isConfigured) "" else "\n" + stringResource(Res.string.settings_preshow_trivia_needs_ai)
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsSearchAnchors("ai-trivia-enabled"),
            ) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_ai_trivia_enabled),
                    description = description,
                    checked = ai.triviaEnabled,
                    isTablet = isTablet,
                    onCheckedChange = { wanted ->
                        when {
                            !wanted -> DiscoverAiSettingsRepository.setTriviaEnabled(false)
                            // Consent covers sending film details to the AI provider, so it is only
                            // asked when there is a key to send. Without one the switch just turns
                            // on and the pre-show uses Wikipedia.
                            ai.apiKey.isNotBlank() && !ai.consentGiven -> consentPromptFor = AiConsentTarget.Trivia
                            else -> DiscoverAiSettingsRepository.setTriviaEnabled(true)
                        }
                    },
                )
            }
            AiConsentDialog(target = consentPromptFor, onDismiss = { consentPromptFor = null })
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_theme_song_section),
            isTablet = isTablet,
        ) {
            val triviaTheme by ThemeSongSettings.triviaEnabled.collectAsStateWithLifecycle()
            val detailsTheme by ThemeSongSettings.detailsEnabled.collectAsStateWithLifecycle()
            val themeVolume by ThemeSongSettings.volume.collectAsStateWithLifecycle()
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_theme_song_trivia),
                    description = stringResource(Res.string.settings_theme_song_trivia_description),
                    checked = triviaTheme,
                    isTablet = isTablet,
                    onCheckedChange = ThemeSongSettings::setTriviaEnabled,
                )
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_theme_song_details),
                    description = stringResource(Res.string.settings_theme_song_details_description),
                    checked = detailsTheme,
                    isTablet = isTablet,
                    onCheckedChange = ThemeSongSettings::setDetailsEnabled,
                )
                SettingsChoiceRow(
                    title = stringResource(Res.string.settings_theme_song_volume),
                    description = null,
                    options = listOf(20, 35, 50, 70, 90).map { SettingsChoiceOption(it, "$it%") },
                    selectedValue = listOf(20, 35, 50, 70, 90).minBy { kotlin.math.abs(it - themeVolume) },
                    isTablet = isTablet,
                    onSelected = ThemeSongSettings::setVolume,
                )
            }
        }
    }
}

private fun PreshowSlot.label(): String = when (this) {
    PreshowSlot.CinemaIntro -> "Cinema intro"
    PreshowSlot.Bumper -> "Bumper"
    PreshowSlot.Trailers -> "Trailers"
    PreshowSlot.Trivia -> "Trivia"
    PreshowSlot.RatingCard -> "Rating card"
    PreshowSlot.FeaturePresentation -> "Feature presentation"
}
