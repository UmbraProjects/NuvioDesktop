package com.nuvio.app.features.qualicache

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

// Backs off while QualiCache warms a title up, then gives up: a server that is still pending after
// this long is not going to answer on the timescale someone is looking at the screen, and the next
// visit re-asks anyway. Each delay is longer than the service's pending TTL, so every poll is a
// real request rather than a cache hit.
private val WARMING_RETRY_DELAYS_MS = longArrayOf(3_000L, 6_000L, 12_000L, 24_000L)

/**
 * Looks up the notable qualities for a title and keeps them in sync with the QualiCache settings.
 *
 * Returns an empty list until (and unless) the server answers with something worth badging, so a
 * caller can simply not render the badges — which is the common case, since an ordinary 1080p
 * release earns no badge at all. The lookup itself is cached and de-duplicated by
 * [QualiCacheQualityService], so calling this from a composable that recomposes often is cheap
 * after the first hit.
 *
 * There is no per-surface visibility switch: the Home hero is the only thing that badges anything,
 * so the integration being on *is* "show it there". [QualiCacheQualityService] already returns
 * nothing unless the settings are usable, which is the only gate needed.
 */
@Composable
fun rememberQualityHighlights(
    type: String,
    id: String?,
    releaseDate: String? = null,
): List<QualityHighlight> {
    val settings by remember {
        QualiCacheSettingsRepository.ensureLoaded()
        QualiCacheSettingsRepository.uiState
    }.collectAsStateWithLifecycle()

    // Seeded from the cache rather than starting empty: the badges sit on the same line as the year
    // and runtime, so appearing one frame late reflows that line. A warm hit must be right in the
    // frame the item is first laid out.
    var tokens by remember(type, id, settings) {
        mutableStateOf(QualiCacheQualityService.cached(type, id, settings)?.tokens.orEmpty())
    }
    // Keyed on every input that changes what the server would answer, so correcting a wrong URL or
    // access key re-queries without needing to navigate away and back.
    LaunchedEffect(type, id, settings.baseUrl, settings.accessKey, settings.enabled) {
        // The first request for a title QualiCache has not seen only registers it — the addon
        // query runs server-side and lands seconds later. Asking once would show no badges until
        // some future visit, so warming answers are polled out. Anything else is final.
        for (attempt in 0..WARMING_RETRY_DELAYS_MS.size) {
            val lookup = QualiCacheQualityService.lookup(
                type = type,
                id = id,
                settings = settings,
                releaseDate = releaseDate,
            )
            tokens = lookup.tokens
            if (lookup.status != QualityLookupStatus.Warming) break
            if (attempt == WARMING_RETRY_DELAYS_MS.size) break
            delay(WARMING_RETRY_DELAYS_MS[attempt])
        }
    }

    return remember(tokens, settings) { qualityHighlightsFor(tokens, settings) }
}

/**
 * Whether the quality badges are switched on at all.
 *
 * The hero lays its metadata out differently depending on this: with the badges off there is nothing
 * to make room for, so the year and runtime stay on the genre line where they sat before the badges
 * existed. Deliberately gated on the setting and not on whether *this* title earned a badge —
 * otherwise the year and runtime would hop between two lines as the hero rotates through items.
 */
@Composable
fun rememberQualityBadgesEnabled(): Boolean {
    val settings by remember {
        QualiCacheSettingsRepository.ensureLoaded()
        QualiCacheSettingsRepository.uiState
    }.collectAsStateWithLifecycle()

    return settings.isUsable
}

/**
 * The compact form for a metadata line.
 *
 * Rendered as items in the caller's row rather than as a row of its own, so it can sit beside the
 * year and runtime and add no vertical space of its own.
 */
@Composable
fun QualityInlineBadges(highlights: List<QualityHighlight>) {
    highlights.forEach { highlight ->
        QualityBadgeImage(
            highlight = highlight,
            nominalHeight = QualityBadgeArt.INLINE_HEIGHT,
            // The only surface where the badges sit beside body text on the same line.
            alignWithText = true,
        )
    }
}
