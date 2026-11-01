package com.nuvio.app.features.whatsnew

/**
 * How much room a card gets. A visit shows one spotlight card at most; every other card is small.
 * An entry flagged [Spotlight] that is not chosen for the spotlight is drawn small.
 */
internal enum class WhatsNewWeight {
    Spotlight,
    Small,
}

/**
 * What a card's button does. These are plain keys, not references to settings types, so the
 * selection logic and its tests stay pure Kotlin; the screen resolves a key to a real setter or
 * page when it draws the button.
 */
internal sealed interface WhatsNewAction {
    /** No button: the card only tells you about the feature. */
    data object None : WhatsNewAction

    /** Switches the feature on in one step ("Turn it on"). */
    data class TurnOn(val featureKey: String) : WhatsNewAction

    /**
     * Opens a settings page ("Set it up"). For a feature that needs an AI key or consent first, so
     * the button never does nothing.
     */
    data class OpenSettings(val pageKey: String) : WhatsNewAction
}

/**
 * One feature, one card.
 *
 * [number] is what the app remembers, not the app version: the version name and code only move when
 * `DesktopVersion.properties` is edited, so two builds of one version could not be told apart.
 * Numbers only ever go up, one per entry, and are never reused.
 */
internal data class WhatsNewEntry(
    val number: Int,
    val title: String,
    val pitch: String,
    val weight: WhatsNewWeight = WhatsNewWeight.Small,
    val action: WhatsNewAction = WhatsNewAction.None,
)

internal const val WHATS_NEW_MAX_TITLE_LENGTH = 40
internal const val WHATS_NEW_MAX_PITCH_LENGTH = 140

/**
 * Everything wrong with a list of entries, as sentences; empty when the list is fine. Run over the
 * shipped list by a test, so a duplicate number, a forgotten bump or a pitch too long for a card
 * fails CI instead of reaching the screen.
 */
internal fun whatsNewEntryProblems(entries: List<WhatsNewEntry>): List<String> {
    val problems = mutableListOf<String>()
    entries.groupBy { it.number }
        .filterValues { it.size > 1 }
        .keys
        .sorted()
        .forEach { problems += "number $it is used by more than one entry" }
    entries.forEach { entry ->
        val name = "entry ${entry.number}"
        if (entry.number <= 0) problems += "$name: the number must be 1 or more"
        if (entry.title.isBlank()) problems += "$name: the title is blank"
        if (entry.pitch.isBlank()) problems += "$name: the pitch is blank"
        if (entry.title.length > WHATS_NEW_MAX_TITLE_LENGTH) {
            problems += "$name: the title is over $WHATS_NEW_MAX_TITLE_LENGTH characters"
        }
        if (entry.pitch.length > WHATS_NEW_MAX_PITCH_LENGTH) {
            problems += "$name: the pitch is over $WHATS_NEW_MAX_PITCH_LENGTH characters"
        }
        when (val action = entry.action) {
            WhatsNewAction.None -> Unit
            is WhatsNewAction.TurnOn -> when {
                action.featureKey.isBlank() -> problems += "$name: the turn-on key is blank"
                action.featureKey !in whatsNewTurnOnPages ->
                    problems += "$name: the turn-on key '${action.featureKey}' is not one the screen can switch on"
            }
            is WhatsNewAction.OpenSettings ->
                if (action.pageKey.isBlank()) problems += "$name: the settings page key is blank"
        }
    }
    return problems
}
