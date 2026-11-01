package com.nuvio.app.features.whatsnew

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.preroll.PreshowSettings
import com.nuvio.app.features.themesong.ThemeSongSettings
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository

/**
 * One switch a card can flip. Adding a new one is one entry in [whatsNewToggles] and a key in
 * `WhatsNewActionKeys.kt`; the card screen, the entry checker and the tests all read this table.
 *
 * Nothing here runs until a card is drawn, so the pure checks can read [key] and [page] without
 * starting any setting.
 */
internal class WhatsNewToggle(
    val key: String,
    /** The `SettingsPage` name that holds the switch (what the app's page request takes). */
    val page: String,
    /**
     * True when the page asks for the AI agreement before it lets the switch go on, so a card must
     * open the page instead of flipping the switch behind that step.
     */
    val needsAiConsent: Boolean,
    /** Whether the switch is on now, read inside a composable so the card follows it. */
    val isOn: @Composable () -> Boolean,
    /** Switches the feature on. The caller has already ruled out the consent step. */
    val turnOn: () -> Unit,
)

internal val whatsNewToggles: List<WhatsNewToggle> = listOf(
    WhatsNewToggle(
        key = WHATS_NEW_KEY_PREVIOUSLY_ON,
        page = "Preshow",
        needsAiConsent = true,
        isOn = { PreshowSettings.previouslyOnEnabled.collectAsStateWithLifecycle().value },
        turnOn = { PreshowSettings.setPreviouslyOnEnabled(true) },
    ),
    WhatsNewToggle(
        key = WHATS_NEW_KEY_POST_SHOW,
        page = "Preshow",
        needsAiConsent = true,
        isOn = { PreshowSettings.postShowEnabled.collectAsStateWithLifecycle().value },
        turnOn = { PreshowSettings.setPostShowEnabled(true) },
    ),
    WhatsNewToggle(
        key = WHATS_NEW_KEY_THEME_SONG_DETAILS,
        page = "Preshow",
        needsAiConsent = false,
        isOn = { ThemeSongSettings.detailsEnabled.collectAsStateWithLifecycle().value },
        turnOn = { ThemeSongSettings.setDetailsEnabled(true) },
    ),
    WhatsNewToggle(
        key = WHATS_NEW_KEY_RELATIVE_COUNTDOWN,
        page = "ContinueWatching",
        needsAiConsent = false,
        isOn = {
            ContinueWatchingPreferencesRepository.ensureLoaded()
            val state by ContinueWatchingPreferencesRepository.uiState.collectAsStateWithLifecycle()
            state.relativeAirCountdown
        },
        turnOn = { ContinueWatchingPreferencesRepository.setRelativeAirCountdown(true) },
    ),
)

/** The Settings page for each switch key, for the entry checker and the button rules. */
internal val whatsNewTurnOnPages: Map<String, String>
    get() = whatsNewToggles.associate { it.key to it.page }

internal fun whatsNewToggleFor(key: String): WhatsNewToggle? = whatsNewToggles.firstOrNull { it.key == key }
