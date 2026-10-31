package com.nuvio.app.features.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioGlass
import com.nuvio.app.core.ui.NuvioModalDialog
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.discover.DiscoverAiSettingsRepository
import com.nuvio.app.features.preroll.PREROLL_MAX_TRAILERS
import com.nuvio.app.features.preroll.PREVIOUSLY_ON_BREAK_CHOICES
import com.nuvio.app.features.preroll.PrerollSettings
import com.nuvio.app.features.preroll.PreshowSettings
import com.nuvio.app.features.preroll.PreshowSlot
import com.nuvio.app.features.preroll.RatingRegion
import com.nuvio.app.features.themesong.ThemeSongSettings
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_done
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
import nuvio.composeapp.generated.resources.settings_playback_hud_button_move_down
import nuvio.composeapp.generated.resources.settings_playback_hud_button_move_up
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Section titles, shared with the sidebar's jump list (DesktopSettingsSidebarSections). */
internal const val PRESHOW_SECTION_RUNNING_ORDER = "Running order"
internal const val PRESHOW_SECTION_RATING_CARD = "Rating card"
internal const val PRESHOW_SECTION_CLIP_SOURCES = "Clip sources"
internal const val PRESHOW_SECTION_PREVIOUSLY_ON = "Previously on"
internal const val PRESHOW_SECTION_POST_SHOW = "Post-show"

/** Trailers come back at this count when switched on from the running order after being off. */
private const val TRAILERS_WHEN_SWITCHED_ON = 2

/**
 * Pre-show: everything that plays between pressing play and the film starting, then the extras around it
 * (the "previously on" recap before an episode, post-show cards when the credits roll, theme songs).
 *
 * What plays, and in which order, is one list in one dialog: every part has its switch beside it. The sections
 * below it only hold each part's details (how many trailers, which rating system, where clips come from).
 */
internal fun LazyListScope.preshowSettingsContent(isTablet: Boolean) {
    item {
        SettingsSection(title = PRESHOW_SECTION_RUNNING_ORDER, isTablet = isTablet) {
            val order by PreshowSettings.order.collectAsStateWithLifecycle()
            val enabled by PreshowSettings.enabledSlots.collectAsStateWithLifecycle()
            val trailerCount by PrerollSettings.trailerCount.collectAsStateWithLifecycle()
            val ai by DiscoverAiSettingsRepository.uiState.collectAsStateWithLifecycle()
            var editing by remember { mutableStateOf(false) }
            var consentPromptFor by remember { mutableStateOf<AiConsentTarget?>(null) }
            val on = order.filter { it.isOn(enabled, trailerCount, ai.triviaEnabled) }
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsSearchAnchors("preshow-lineup", "ai-trivia-enabled"),
            ) {
                SettingsNavigationRow(
                    title = "What plays, and in what order",
                    description = if (on.isEmpty()) {
                        "Nothing is switched on, so films start straight away."
                    } else {
                        on.joinToString("  →  ") { it.label() }
                    },
                    isTablet = isTablet,
                    trailingContent = {
                        Text(
                            text = "${on.size} of ${order.size} on",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.nuvio.colors.textMuted,
                        )
                    },
                    onClick = { editing = true },
                )
            }
            if (editing) {
                PreshowRunningOrderDialog(
                    order = order,
                    isOn = { it.isOn(enabled, trailerCount, ai.triviaEnabled) },
                    triviaNeedsAi = !ai.isConfigured,
                    onToggle = { slot, wanted ->
                        when (slot) {
                            PreshowSlot.Trailers -> PrerollSettings.setTrailerCount(if (wanted) TRAILERS_WHEN_SWITCHED_ON else 0)
                            PreshowSlot.Trivia -> when {
                                !wanted -> DiscoverAiSettingsRepository.setTriviaEnabled(false)
                                // Consent covers sending film details to the AI provider, so it is only asked when there
                                // is a key to send. It is a dialog of its own, so this one closes for it.
                                ai.apiKey.isNotBlank() && !ai.consentGiven -> {
                                    editing = false
                                    consentPromptFor = AiConsentTarget.Trivia
                                }
                                else -> DiscoverAiSettingsRepository.setTriviaEnabled(true)
                            }
                            else -> PreshowSettings.setEnabled(slot, wanted)
                        }
                    },
                    onDismiss = { editing = false },
                )
            }
            AiConsentDialog(target = consentPromptFor, onDismiss = { consentPromptFor = null })
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_preshow_section_trailers),
            isTablet = isTablet,
        ) {
            val trailerCount by PrerollSettings.trailerCount.collectAsStateWithLifecycle()
            val keepAudioAlive by PreshowSettings.keepAudioAlive.collectAsStateWithLifecycle()
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
                SettingsGroupDivider(isTablet)
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
        SettingsSection(title = PRESHOW_SECTION_RATING_CARD, isTablet = isTablet) {
            val region by PreshowSettings.ratingRegion.collectAsStateWithLifecycle()
            SettingsGroup(isTablet = isTablet) {
                SettingsChoiceRow(
                    title = "Rating system",
                    description = "The film's age rating is shown in this system's style. A film with no rating skips the card.",
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
        SettingsSection(title = PRESHOW_SECTION_CLIP_SOURCES, isTablet = isTablet) {
            val folder by PreshowSettings.localFolder.collectAsStateWithLifecycle()
            val avid by PreshowSettings.avidUrls.collectAsStateWithLifecycle()
            SettingsGroup(isTablet = isTablet) {
                SettingsTextInputRow(
                    title = "Local folder",
                    description = "Your own clips for the cinema intro, bumper, rating card and feature presentation, in subfolders " +
                        "named cinema-intro, bumper, rating-bbfc, rating-mpaa and feature-presentation. Name rating clips after " +
                        "the rating, such as 12A.mp4 or PG-13.mp4. Leave blank for the default folder. A clip here is used before " +
                        "any link below.",
                    value = folder,
                    placeholder = "Default folder",
                    isTablet = isTablet,
                    onSave = PreshowSettings::setLocalFolder,
                )
                listOf(
                    "cinema_intro" to "Cinema intro clips",
                    "bumper" to "Bumper clips",
                    "rating_bbfc" to "BBFC rating clips",
                    "rating_mpaa" to "MPA rating clips",
                    "feature_presentation" to "Feature presentation clips",
                ).forEach { (key, title) ->
                    SettingsGroupDivider(isTablet)
                    SettingsTextInputRow(
                        title = title,
                        description = "An avid.wiki page or YouTube link to take clips from when the local folder has none. " +
                            "Separate several links with spaces or commas and one clip is picked from all of them.",
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
        SettingsSection(title = PRESHOW_SECTION_PREVIOUSLY_ON, isTablet = isTablet) {
            val ai by DiscoverAiSettingsRepository.uiState.collectAsStateWithLifecycle()
            val enabled by PreshowSettings.previouslyOnEnabled.collectAsStateWithLifecycle()
            val breakOnly by PreshowSettings.previouslyOnBreakOnly.collectAsStateWithLifecycle()
            val breakHours by PreshowSettings.previouslyOnBreakHours.collectAsStateWithLifecycle()
            var consentPromptFor by remember { mutableStateOf<AiConsentTarget?>(null) }
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = "Previously on cards",
                    description = "Before an episode, cards recap the last episode you watched, then a short quiz. " +
                        "With your AI key they are written from the episode's overview; without one you get the overview itself, with no quiz.",
                    checked = enabled,
                    isTablet = isTablet,
                    onCheckedChange = { wanted ->
                        when {
                            !wanted -> PreshowSettings.setPreviouslyOnEnabled(false)
                            ai.apiKey.isNotBlank() && !ai.consentGiven -> consentPromptFor = AiConsentTarget.PreviouslyOn
                            else -> PreshowSettings.setPreviouslyOnEnabled(true)
                        }
                    },
                )
                if (enabled) {
                    SettingsGroupDivider(isTablet)
                    SettingsSwitchRow(
                        title = "Only after a break",
                        description = "Skip the cards when you watched the last episode recently, such as when binging.",
                        checked = breakOnly,
                        isTablet = isTablet,
                        onCheckedChange = PreshowSettings::setPreviouslyOnBreakOnly,
                    )
                    if (breakOnly) {
                        SettingsGroupDivider(isTablet)
                        SettingsChoiceRow(
                            title = "Break length",
                            description = "Show the cards when it has been at least this long since you watched the last episode.",
                            options = PREVIOUSLY_ON_BREAK_CHOICES.map { SettingsChoiceOption(it, breakLabel(it)) },
                            selectedValue = breakHours,
                            isTablet = isTablet,
                            onSelected = PreshowSettings::setPreviouslyOnBreakHours,
                        )
                    }
                }
            }
            AiConsentDialog(target = consentPromptFor, onDismiss = { consentPromptFor = null })
        }
    }

    item {
        SettingsSection(title = PRESHOW_SECTION_POST_SHOW, isTablet = isTablet) {
            val ai by DiscoverAiSettingsRepository.uiState.collectAsStateWithLifecycle()
            val postShow by PreshowSettings.postShowEnabled.collectAsStateWithLifecycle()
            var consentPromptFor by remember { mutableStateOf<AiConsentTarget?>(null) }
            val description = "When a film's credits start, the video moves to a corner and cards look back at what you " +
                "just watched: what the ending meant, themes, and a short quiz. Also runs on season and series finales. " +
                "With your AI key the cards are written for the title; without one, films use their English Wikipedia " +
                "article, and finales are skipped."
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = "Post-show cards",
                    description = description,
                    checked = postShow,
                    isTablet = isTablet,
                    onCheckedChange = { wanted ->
                        when {
                            !wanted -> PreshowSettings.setPostShowEnabled(false)
                            // Same rule as the trivia switch: consent is only asked when there is a key to send.
                            ai.apiKey.isNotBlank() && !ai.consentGiven -> consentPromptFor = AiConsentTarget.PostShow
                            else -> PreshowSettings.setPostShowEnabled(true)
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
                SettingsGroupDivider(isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_theme_song_details),
                    description = stringResource(Res.string.settings_theme_song_details_description),
                    checked = detailsTheme,
                    isTablet = isTablet,
                    onCheckedChange = ThemeSongSettings::setDetailsEnabled,
                )
                SettingsGroupDivider(isTablet)
                SettingsChoiceRow(
                    title = stringResource(Res.string.settings_theme_song_volume),
                    description = null,
                    options = THEME_VOLUME_CHOICES.map { SettingsChoiceOption(it, "$it%") },
                    selectedValue = THEME_VOLUME_CHOICES.minBy { kotlin.math.abs(it - themeVolume) },
                    isTablet = isTablet,
                    onSelected = ThemeSongSettings::setVolume,
                )
            }
        }
    }
}

private val THEME_VOLUME_CHOICES = listOf(20, 35, 50, 70, 90)

/**
 * The running order: every part of the pre-show, top to bottom in the order it plays, each with its switch.
 * Rows move by dragging or with the arrows (which the keyboard and a gamepad can reach); a part that is off keeps
 * its place, so switching it back on puts it where it was.
 */
@Composable
private fun PreshowRunningOrderDialog(
    order: List<PreshowSlot>,
    isOn: (PreshowSlot) -> Boolean,
    triviaNeedsAi: Boolean,
    onToggle: (PreshowSlot, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val hapticFeedback = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val slot = order.getOrNull(from.index) ?: return@rememberReorderableLazyListState
        if (to.index !in order.indices) return@rememberReorderableLazyListState
        PreshowSettings.moveTo(slot, to.index + 1)
        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    NuvioModalDialog(
        onDismissRequest = onDismiss,
        title = "Pre-show running order",
        subtitle = "Plays top to bottom after you press play, then the film starts. Drag a row or use the arrows to move it; " +
            "the switch turns it on or off. Escape skips the whole pre-show.",
        maxWidth = 680.dp,
        actions = {
            SettingsGlassButton(text = stringResource(Res.string.action_done), onClick = onDismiss, accent = true)
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
            state = listState,
        ) {
            itemsIndexed(order, key = { _, slot -> slot.key }) { index, slot ->
                ReorderableItem(reorderState, key = slot.key) { isDragging ->
                    val elevation by animateDpAsState(if (isDragging) 4.dp else 0.dp)
                    val on = isOn(slot)
                    Surface(
                        shadowElevation = elevation,
                        // Clear so the rows sit on the dialog's glass; the row being dragged takes the glass night
                        // as an opaque base so it reads above the rows it passes.
                        color = if (isDragging) NuvioGlass.night(colors.background, colors.accent) else Color.Transparent,
                    ) {
                        Column {
                            if (index > 0) SettingsGroupDivider(isTablet = false)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DragIndicator,
                                    contentDescription = null,
                                    tint = colors.textMuted,
                                    modifier = with(this@ReorderableItem) {
                                        Modifier
                                            .draggableHandle(
                                                onDragStarted = { hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress) },
                                            )
                                            .padding(end = 8.dp)
                                            .size(20.dp)
                                    },
                                )
                                Column(
                                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = "${index + 1}.  ${slot.label()}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (on) colors.textPrimary else colors.textMuted,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        text = slot.description(triviaNeedsAi),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textMuted,
                                    )
                                }
                                IconButton(onClick = { PreshowSettings.moveTo(slot, index) }, enabled = index > 0) {
                                    Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = stringResource(Res.string.settings_playback_hud_button_move_up))
                                }
                                IconButton(onClick = { PreshowSettings.moveTo(slot, index + 2) }, enabled = index < order.lastIndex) {
                                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(Res.string.settings_playback_hud_button_move_down))
                                }
                                SettingsSquareSwitch(
                                    checked = on,
                                    onCheckedChange = { onToggle(slot, it) },
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Trailers and trivia keep their own switches elsewhere (a trailer count, the AI trivia flag); the clips use the slot set. */
private fun PreshowSlot.isOn(enabledClips: Set<PreshowSlot>, trailerCount: Int, triviaEnabled: Boolean): Boolean = when (this) {
    PreshowSlot.Trailers -> trailerCount > 0
    PreshowSlot.Trivia -> triviaEnabled
    else -> this in enabledClips
}

private fun PreshowSlot.label(): String = when (this) {
    PreshowSlot.CinemaIntro -> "Cinema intro"
    PreshowSlot.Bumper -> "Sound system trailer (bumper)"
    PreshowSlot.Trailers -> "Trailers"
    PreshowSlot.Trivia -> "Trivia"
    PreshowSlot.RatingCard -> "Rating card"
    PreshowSlot.FeaturePresentation -> "Feature presentation"
}

private fun PreshowSlot.description(triviaNeedsAi: Boolean): String = when (this) {
    PreshowSlot.CinemaIntro ->
        "A cinema chain's opening ident, the kind that plays as the lights go down. Needs a clip: add a local folder " +
            "or link under Clip sources, or nothing plays."
    PreshowSlot.Bumper ->
        "A short demo of a cinema sound system, like THX's Deep Note or Dolby Atmos. Comes with three THX and three " +
            "Dolby Atmos trailers; set your own under Clip sources."
    PreshowSlot.Trailers ->
        "Trailers for similar titles, from More Like This. Choose how many under Trailers."
    PreshowSlot.Trivia ->
        "Facts about the film over its artwork, then a short quiz." + if (triviaNeedsAi) {
            " No AI provider is set up, so the facts come from Wikipedia and there is no quiz; set one under Discover, then AI."
        } else {
            " Written by your AI provider, or from Wikipedia when it is unavailable."
        }
    PreshowSlot.RatingCard ->
        "The film's age rating on a full-screen card, like the BBFC or MPA card before a film in the cinema. Uses " +
            "a clip for the rating when you have one, otherwise draws the card. Choose the system under Rating card."
    PreshowSlot.FeaturePresentation ->
        "The \"our feature presentation\" ident that leads straight into the film. Uses a clip when you have one, " +
            "otherwise draws a simple title card."
}

private fun breakLabel(hours: Int): String = when (hours) {
    24 -> "1 day"
    72 -> "3 days"
    168 -> "1 week"
    336 -> "2 weeks"
    else -> "$hours hours"
}
