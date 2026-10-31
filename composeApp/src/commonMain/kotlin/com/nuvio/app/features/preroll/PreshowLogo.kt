package com.nuvio.app.features.preroll

import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.withTimeoutOrNull

/** How long a pre-show trailer waits for a title's logo before it settles for the text title. */
private const val LOGO_LOOKUP_TIMEOUT_MS = 3_000L

private val IMDB_ID = Regex("^tt\\d+$")

/**
 * The logo to show over a pre-show trailer in place of the big white title text.
 *
 * Trailer candidates are catalogue cards, and a catalogue card rarely carries a logo, so the overlay used to
 * fall back to the title as text. In order: the card's own logo, the logo in the title's metadata
 * ([detailsLogo]), then the Metahub logo for an IMDb id. Null means there is none and the text title stays.
 */
internal fun preshowLogoFor(subject: MetaPreview, detailsLogo: String?): String? {
    subject.logo?.takeIf { it.isNotBlank() }?.let { return it }
    detailsLogo?.takeIf { it.isNotBlank() }?.let { return it }
    val id = subject.id.trim()
    return if (IMDB_ID.matches(id)) "https://images.metahub.space/logo/medium/$id/img" else null
}

/**
 * This card with a logo filled in from metadata. Uses what is already cached, otherwise waits briefly for the
 * fetch; a slow or failed lookup still gets the Metahub fallback rather than holding the trailer up.
 */
internal suspend fun MetaPreview.withMetadataLogo(): MetaPreview {
    if (!logo.isNullOrBlank()) return this
    val details = MetaDetailsRepository.peekAny(type, id)
        ?: withTimeoutOrNull(LOGO_LOOKUP_TIMEOUT_MS) {
            runCatching { MetaDetailsRepository.fetch(type, id) }.getOrNull()
        }
    val found = preshowLogoFor(this, details?.logo) ?: return this
    return copy(logo = found)
}
