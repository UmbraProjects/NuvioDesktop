package com.nuvio.app.features.preroll

private enum class Level { All, Pg, Teen, Mature, Adult }

private fun levelOf(code: String): Level? = when (code) {
    "U", "UC", "G", "TV-G", "TV-Y", "ALL", "0" -> Level.All
    "PG", "TV-PG", "TV-Y7", "TV-Y7-FV" -> Level.Pg
    "12", "12A", "PG-13", "13" -> Level.Teen
    "15", "16", "R", "TV-14" -> Level.Mature
    "18", "R18", "NC-17", "X", "TV-MA", "21" -> Level.Adult
    else -> null
}

/**
 * The film's certificate in the chosen rating system, or null when it is unknown or unrecognised.
 *
 * Addons report whatever their source uses (MPA letters, BBFC numbers, TV ratings), so the value is
 * mapped by audience level rather than copied: PG-13 becomes 12A for BBFC, 15 becomes R for MPA.
 */
fun normalizeCertification(raw: String?, region: RatingRegion): String? {
    val code = raw?.trim()?.uppercase()
        ?.removePrefix("RATED ")
        ?.replace(Regex("^(UK|GB|US)[:/ -]+"), "")
        ?.trim()
        ?.takeIf { it.isNotEmpty() } ?: return null
    val level = levelOf(code) ?: return null
    return when (region) {
        RatingRegion.Bbfc -> when (level) {
            Level.All -> "U"
            Level.Pg -> "PG"
            Level.Teen -> "12A"
            Level.Mature -> "15"
            Level.Adult -> "18"
        }
        RatingRegion.Mpaa -> when (level) {
            Level.All -> "G"
            Level.Pg -> "PG"
            Level.Teen -> "PG-13"
            Level.Mature -> "R"
            Level.Adult -> if (code == "TV-MA") "R" else "NC-17"
        }
    }
}

/** Whether [text] (a file name or the words around an embedded video) names exactly [certificate]. */
internal fun mentionsCertificate(text: String, certificate: String): Boolean {
    val pattern = Regex("(?<![a-z0-9])${Regex.escape(certificate.lowercase())}(?![a-z0-9]|-\\d)")
    return pattern.containsMatchIn(text.lowercase())
}
