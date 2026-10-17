package com.nuvio.app.features.details

/**
 * The key two cast lists are merged on: the same person spelled with or without accents, dots or
 * hyphens is one person. Addons and TMDB disagree on exactly that - "Lauren Velez" from an addon and
 * "Lauren Vélez" from TMDB showed Dexter's LaGuerta twice (2026-10-16), and "J.K. Simmons" /
 * "JK Simmons" is the same problem with punctuation.
 *
 * Folds the Latin accents that occur in cast names (common code has no Unicode normaliser), drops
 * everything that is not a letter or digit, and lowercases.
 */
internal fun personNameKey(name: String): String {
    val builder = StringBuilder(name.length)
    for (char in name.trim().lowercase()) {
        val folded = AccentFolds[char]
        when {
            folded != null -> builder.append(folded)
            char.isLetterOrDigit() -> builder.append(char)
        }
    }
    return builder.toString()
}

private val AccentFolds: Map<Char, String> = buildMap {
    fun fold(from: String, to: String) = from.forEach { put(it, to) }
    fold("àáâãäåāăą", "a")
    fold("æ", "ae")
    fold("çćĉċč", "c")
    fold("ďđ", "d")
    fold("èéêëēĕėęě", "e")
    fold("ĝğġģ", "g")
    fold("ĥħ", "h")
    fold("ìíîïĩīĭįı", "i")
    fold("ĵ", "j")
    fold("ķ", "k")
    fold("ĺļľŀł", "l")
    fold("ñńņňŉ", "n")
    fold("òóôõöøōŏő", "o")
    fold("œ", "oe")
    fold("ŕŗř", "r")
    fold("śŝşšș", "s")
    fold("ß", "ss")
    fold("ţťŧț", "t")
    fold("ùúûüũūŭůűų", "u")
    fold("ŵ", "w")
    fold("ýÿŷ", "y")
    fold("źżž", "z")
    fold("þ", "th")
    fold("ð", "d")
}
