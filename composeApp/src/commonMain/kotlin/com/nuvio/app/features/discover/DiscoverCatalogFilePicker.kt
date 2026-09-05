package com.nuvio.app.features.discover

/**
 * The file half of Discover's import/export.
 *
 * Reading and writing live behind the same expect object as the dialogs so a caller never holds a
 * path it then has to do IO with itself — on desktop the chooser and the file access are the same
 * Swing/JVM concern, and splitting them would put platform IO in common code.
 */
internal expect object DiscoverCatalogFilePicker {
    /** Returns the file's text, or null when the user cancelled. Throws only if reading fails. */
    suspend fun openForImport(): String?

    /**
     * Writes [contents] to a file the user chooses. Returns the chosen file's name for the
     * confirmation message, or null when cancelled.
     */
    suspend fun saveExport(suggestedName: String, contents: String): String?
}

/** Turns a row title into something safe to suggest as a filename. */
internal fun discoverCatalogFileName(title: String): String {
    val cleaned = title.trim()
        .map { char -> if (char.isLetterOrDigit() || char == ' ' || char == '-') char else ' ' }
        .joinToString("")
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString("-")
        .lowercase()
    return (cleaned.ifBlank { "discover-row" }).take(60) + ".json"
}

/**
 * `generatedAt` for a fresh export.
 *
 * Formatted by hand rather than through a date library because the field is documentation for a
 * human reading the file — nothing parses it back — and the app's other timestamp helpers all carry
 * timezone and locale behaviour this has no use for.
 */
internal fun discoverExportTimestamp(): String {
    val epochMs = com.nuvio.app.features.watchprogress.WatchProgressClock.nowEpochMs()
    val totalSeconds = epochMs / 1000
    val days = totalSeconds / 86_400
    val secondsOfDay = (totalSeconds % 86_400).toInt()
    var year = 1970
    var remaining = days
    while (true) {
        val length = if (isLeapYear(year)) 366 else 365
        if (remaining < length) break
        remaining -= length
        year++
    }
    val monthLengths = intArrayOf(31, if (isLeapYear(year)) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    var month = 0
    while (remaining >= monthLengths[month]) {
        remaining -= monthLengths[month]
        month++
    }
    val day = remaining.toInt() + 1
    return buildString {
        append(year.toString().padStart(4, '0')); append('-')
        append((month + 1).toString().padStart(2, '0')); append('-')
        append(day.toString().padStart(2, '0')); append('T')
        append((secondsOfDay / 3600).toString().padStart(2, '0')); append(':')
        append((secondsOfDay % 3600 / 60).toString().padStart(2, '0')); append(':')
        append((secondsOfDay % 60).toString().padStart(2, '0')); append('Z')
    }
}

private fun isLeapYear(year: Int): Boolean =
    (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
