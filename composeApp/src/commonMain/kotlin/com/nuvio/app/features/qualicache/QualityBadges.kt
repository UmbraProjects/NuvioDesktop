package com.nuvio.app.features.qualicache

/** The groups the highlights are bundled into for the per-user visibility toggles. */
enum class QualityBadgeCategory {
    Resolution,
    DynamicRange,
    Audio,
}

/**
 * The six things worth calling out on a release, in left-to-right order.
 *
 * These are *highlights*, not a readout: a 1080p WEB-DL earns nothing and shows nothing. Anything
 * QualiCache reports that is not one of these — every resolution below 4K, a bare source, HDTV — is
 * simply not notable enough to spend a badge on, so the row disappears entirely on ordinary
 * releases and means something when it appears.
 */
enum class QualityHighlight(
    /** Spoken/alt text; there is no visible text form, each highlight is artwork. */
    val label: String,
    val category: QualityBadgeCategory,
) {
    FourKBluRay("4K Blu-ray", QualityBadgeCategory.Resolution),
    FourKWeb("4K WEB-DL", QualityBadgeCategory.Resolution),
    DolbyVision("Dolby Vision", QualityBadgeCategory.DynamicRange),
    Hdr("HDR", QualityBadgeCategory.DynamicRange),
    DolbyAtmos("Dolby Atmos", QualityBadgeCategory.Audio),
    Dts("DTS", QualityBadgeCategory.Audio),
}

/**
 * Resolves raw QualiCache tokens (see its `quality.py`) to the highlights this user has switched
 * on, in enum order.
 *
 * The 4K badge is the resolution *and* the source in one mark, so it needs both tokens: `4K` on its
 * own says nothing about whether the release is a disc or a stream, and there is no artwork that
 * claims neither. REMUX and BLURAY share the disc badge — from a viewer's seat a remux and a good
 * encode of the same disc are the same thing, and QualiCache never reports both.
 *
 * HDR10+ folds into the plain HDR badge deliberately: it is rare in the wild and Windows does not
 * play it back as HDR10+ anyway, so a badge of its own would promise something the player cannot
 * deliver.
 *
 * **At most three badges**: the resolution, the best visual tag, and the best audio tag. Never
 * Dolby Vision *and* HDR, never Atmos *and* DTS — they describe the same release on the same axis,
 * so the lesser one is noise.
 */
fun qualityHighlightsFor(
    tokens: List<String>,
    settings: QualiCacheSettings,
): List<QualityHighlight> {
    val present = tokens.mapTo(mutableSetOf()) { token -> token.trim().uppercase() }
    return buildList {
        if ("4K" in present) {
            when {
                "BLURAY" in present || "REMUX" in present -> add(QualityHighlight.FourKBluRay)
                "WEBDL" in present || "WEBRIP" in present -> add(QualityHighlight.FourKWeb)
            }
        }
        // Best tag per axis, in precedence order. QualiCache already emits at most one of each
        // (`quality.py` walks the same order with if/elif chains), so this is not a correction —
        // it keeps "at most three badges" true *here*, rather than resting on the server's parser
        // never changing shape or on tokens never being merged across releases upstream.
        when {
            "DV" in present -> add(QualityHighlight.DolbyVision)
            "HDR10" in present || "HDR10+" in present -> add(QualityHighlight.Hdr)
        }
        when {
            "ATMOS" in present -> add(QualityHighlight.DolbyAtmos)
            "DTSX" in present -> add(QualityHighlight.Dts)
        }
    }.filter { highlight -> settings.isCategoryEnabled(highlight.category) }
}
