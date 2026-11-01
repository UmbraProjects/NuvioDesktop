package com.nuvio.app.features.whatsnew

/**
 * A card's "Set it up" closes the cards and opens a Settings page. This remembers where the person
 * was, so the cards come back when they are done there.
 *
 * [page] is the `SettingsPage` name that was opened and [resume] is the card screen as it was
 * (same cards, same card). [reached] turns true once Settings is showing [page]: Settings starts on
 * another page and moves to the requested one a moment later, and that first page must not count
 * as having left.
 */
internal data class WhatsNewReturn(
    val page: String,
    val resume: WhatsNewUiState,
    val reached: Boolean = false,
)

/** What to do after Settings reports the page it is showing. */
internal sealed interface WhatsNewReturnStep {
    /** Keep waiting, with the pending return as given. */
    data class Wait(val pending: WhatsNewReturn) : WhatsNewReturnStep

    /** Bring the cards back as [state]; the return is finished. */
    data class Resume(val state: WhatsNewUiState) : WhatsNewReturnStep
}

/**
 * Settings now shows [currentPage]. The cards come back once the person has moved off the page the
 * card sent them to (Back to the main list, or another page in the sidebar).
 */
internal fun whatsNewReturnOnPage(pending: WhatsNewReturn, currentPage: String): WhatsNewReturnStep = when {
    !pending.reached ->
        WhatsNewReturnStep.Wait(if (currentPage == pending.page) pending.copy(reached = true) else pending)
    currentPage != pending.page -> WhatsNewReturnStep.Resume(pending.resume)
    else -> WhatsNewReturnStep.Wait(pending)
}

/**
 * The person left the Settings tab. The cards come back, whether or not the page was ever reached
 * (a page the app redirects elsewhere would otherwise strand them).
 */
internal fun whatsNewReturnOnTabLeft(pending: WhatsNewReturn): WhatsNewReturnStep =
    WhatsNewReturnStep.Resume(pending.resume)
