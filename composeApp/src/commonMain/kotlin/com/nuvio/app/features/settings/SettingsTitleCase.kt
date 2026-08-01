package com.nuvio.app.features.settings

private val settingsUppercaseBrandTokens = setOf(
    "NVIDIA",
    "RTX",
    "SIMKL",
    "TMDB",
    "TVDB",
)

/**
 * Normalizes fully uppercase words used by legacy settings headings without disturbing
 * intentionally mixed-case product names such as MDBList or approved uppercase brand styling.
 */
internal fun settingsTitleCase(value: String): String = buildString(value.length) {
    var tokenStart = 0

    fun appendToken(endExclusive: Int) {
        if (tokenStart >= endExclusive) return
        val token = value.substring(tokenStart, endExclusive)
        val letters = token.filter(Char::isLetter)
        if (token in settingsUppercaseBrandTokens) {
            append(token)
        } else if (letters.isNotEmpty() && letters.all(Char::isUpperCase)) {
            append(
                token.lowercase().replaceFirstChar { first ->
                    if (first.isLetter()) first.titlecase() else first.toString()
                },
            )
        } else {
            append(token)
        }
    }

    value.forEachIndexed { index, character ->
        if (!character.isLetterOrDigit() && character != '\'' && character != '’') {
            appendToken(index)
            append(character)
            tokenStart = index + 1
        }
    }
    appendToken(value.length)
}
