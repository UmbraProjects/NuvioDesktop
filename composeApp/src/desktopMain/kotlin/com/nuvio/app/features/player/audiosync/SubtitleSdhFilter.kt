// Ported from NuvioTV-Reshaped (github.com/DavidVamaiotu/NuvioTV-Reshaped, GPL-3.0),
// app/src/main/java/com/nuvio/tv/ui/screens/player/SubtitleSdhFilter.kt (plain-text part).
package com.nuvio.app.features.player.audiosync

/** Strips SDH annotations ([door slams], (laughs), SPEAKER:) from subtitle text; null when nothing is left. */
internal object SubtitleSdhFilter {
    private val squareBrackets = Regex("\\[[^]]*][ \\t]*")
    // ">>" marks a speaker change and ">>>" a topic change in CEA-608 style
    // captions, which YouTube carries into its own caption tracks.
    private val speakerChevrons = Regex("[<>]{2,}[ \t]*")
    private val parentheses = Regex(
        "(?:\\((?=[A-Za-z0-9 '#.,\\\"\\\\\\-\\r\\n]*\\))(?![0-9]*\\))[^)]*\\)|" +
            "\uFF08(?=[A-Za-z0-9 '#.,\\\"\\\\\\-\\r\\n]*\uFF09)(?![0-9]*\uFF09)[^\uFF09]*\uFF09)[ \\t]*"
    )
    private val speakerLabel = Regex(
        "(?m)^([ \\t]*-[ \\t]*)?(?:[A-Za-z0-9 ()'#.,]+|\\[[^]\\r\\n]*]):(?=\\s|$)[ \\t]*"
    )

    internal fun filterPlainText(text: String): String? {
        // Runs before speakerLabel so that ">> NAME:" loses the chevrons first and
        // is then recognised as a speaker label.
        var filtered = speakerChevrons.replace(text, "")
        filtered = speakerLabel.replace(filtered) { match -> match.groups[1]?.value.orEmpty() }
        filtered = squareBrackets.replace(filtered, "")
        filtered = parentheses.replace(filtered, "")
        return filtered.lines()
            .filter { line -> line.any { !it.isWhitespace() && it != '-' } }
            .joinToString("\n")
            .takeIf(String::isNotBlank)
    }
}
