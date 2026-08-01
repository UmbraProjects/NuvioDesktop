package com.nuvio.app.features.locallibrary

/**
 * Decomposes Unicode text so title matching can ignore diacritics without damaging non-Latin
 * scripts. On desktop this uses the JDK's Unicode normalizer.
 */
internal expect fun decomposeUnicodeForTitleMatching(value: String): String

internal fun normalizeLocalMatchTitle(value: String): String =
    decomposeUnicodeForTitleMatching(value)
        .lowercase()
        .filterNot { it.category in combiningMarkCategories }
        .map { if (it.isLetterOrDigit()) it else ' ' }
        .joinToString("")
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ")

private val combiningMarkCategories = setOf(
    CharCategory.NON_SPACING_MARK,
    CharCategory.COMBINING_SPACING_MARK,
    CharCategory.ENCLOSING_MARK,
)
