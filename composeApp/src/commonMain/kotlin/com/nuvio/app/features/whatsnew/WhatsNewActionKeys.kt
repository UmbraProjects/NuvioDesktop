package com.nuvio.app.features.whatsnew

/** Keys for `WhatsNewAction.TurnOn`: the features whose switch a card can flip. */
internal const val WHATS_NEW_KEY_PREVIOUSLY_ON = "previously-on"
internal const val WHATS_NEW_KEY_POST_SHOW = "post-show"
internal const val WHATS_NEW_KEY_THEME_SONG_DETAILS = "theme-song-details"
internal const val WHATS_NEW_KEY_RELATIVE_COUNTDOWN = "relative-countdown"

/** The button a card draws for its action. */
internal enum class WhatsNewButton {
    /** Flips the switch in one step. */
    TurnOn,

    /** Opens the Settings page, for a feature that needs a step only that page can take. */
    SetUp,

    /** The feature is already on: a label, not a button. */
    On,
}

/** The Settings page a card's action leads to, or null when it has none. */
internal fun whatsNewSettingsPage(action: WhatsNewAction): String? = when (action) {
    WhatsNewAction.None -> null
    is WhatsNewAction.TurnOn -> whatsNewTurnOnPages[action.featureKey]
    is WhatsNewAction.OpenSettings -> action.pageKey
}

/**
 * What a card's button is right now, or null when it has none.
 *
 * [needsConsent] is true when an AI key is set but the user has not agreed to send the title to it.
 * The Pre-show page asks for that agreement before it lets the switch go on, so a card must not flip
 * the switch behind it: it opens the page instead ([WhatsNewButton.SetUp]). A feature that is
 * already on shows [WhatsNewButton.On] whatever the consent state. A key the screen does not know
 * gets no button rather than one that does nothing.
 */
internal fun whatsNewButtonFor(
    action: WhatsNewAction,
    isOn: Boolean,
    needsConsent: Boolean,
): WhatsNewButton? = when (action) {
    WhatsNewAction.None -> null
    is WhatsNewAction.TurnOn -> when {
        action.featureKey !in whatsNewTurnOnPages -> null
        isOn -> WhatsNewButton.On
        needsConsent -> WhatsNewButton.SetUp
        else -> WhatsNewButton.TurnOn
    }
    is WhatsNewAction.OpenSettings -> WhatsNewButton.SetUp
}
