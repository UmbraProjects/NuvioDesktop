package com.nuvio.app.features.music.library

/*
 * How a saved artist was found ([LibraryArtist.foundVia]), as the ladder screen and the artist page's "Where next?" store it:
 * "ladder: Into shoegaze", "ladder: Out from Sting", "Where next from Sting". The genre picker's suggestions read it.
 */

private const val LADDER_PREFIX = "ladder: "
private const val WHERE_NEXT_PREFIX = "Where next from "
private const val OUT_FROM_PREFIX = "Out from "

/** True when the artist was saved from a ladder: the ladder screen's "ladder: ..." label or the artist page's "Where next from ...". */
internal fun foundViaLadder(foundVia: String?): Boolean =
    foundVia != null && (foundVia.startsWith(LADDER_PREFIX.trim()) || foundVia.startsWith(WHERE_NEXT_PREFIX))

/** How the artist was found, without the stored "ladder: " prefix: "Into shoegaze", "Out from Sting", "Where next from Sting". */
internal fun foundViaText(foundVia: String?): String? =
    foundVia?.removePrefix(LADDER_PREFIX.trim())?.trim()?.takeIf { it.isNotEmpty() }

/** The artist a ladder led out from, when the label names one ("Out from Sting", "Where next from Sting"); null for "Into ..." labels. */
internal fun foundViaSource(foundVia: String?): String? {
    val text = foundViaText(foundVia) ?: return null
    val name = when {
        text.startsWith(WHERE_NEXT_PREFIX) -> text.removePrefix(WHERE_NEXT_PREFIX)
        text.startsWith(OUT_FROM_PREFIX) -> text.removePrefix(OUT_FROM_PREFIX)
        else -> return null
    }
    return name.trim().takeIf { it.isNotEmpty() }
}
