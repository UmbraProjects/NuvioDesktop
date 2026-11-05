package com.nuvio.app.features.voice

/*
 * fork:voice-control - the wake word. With it on, everything the microphone picks up is decoded, and
 * only an utterance with "Nuvio" in it is acted on: "Nuvio, pause" runs at once, "Nuvio" alone
 * starts listening for the command. Everything else (people talking, the film) is dropped unread.
 */

/** Seen by whisper before each clip, so the name comes out spelled "Nuvio" rather than "new video". */
const val WAKE_WORD_PROMPT = "Nuvio, pause. Nuvio, search movies for Midsommar. Nuvio, open settings. Nuvio."

/** How whisper also writes the name when the prompt does not win. Close spellings are caught by [isWakeToken]. */
private val WAKE_SPELLINGS = setOf("nuvio", "nuvios", "nuvio's", "neuvio", "nouvio", "nubio", "novio", "nuvia", "nuveo", "nuvo")

private const val WAKE_EDGE_PUNCTUATION = ".,!?;:\"“”‘’'`()[]{}<>…-–—"

/**
 * The command after the wake word in [transcript] ("Nuvio, search movies for Midsommar." ->
 * "search movies for Midsommar."), "" when the name was said alone, null when the utterance does
 * not start with the name (it was not meant for Nuvio).
 */
fun commandAfterWakeWord(transcript: String): String? {
    val tokens = transcript.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
    fun bare(i: Int) = tokens.getOrNull(i)?.trim { it in WAKE_EDGE_PUNCTUATION }?.lowercase()?.replace('’', '\'')
    // The last time the name is said, wherever it is: with music or a film playing there is no pause
    // to end an utterance at, so the clip runs on and the name can be anywhere in it ("…lyrics…
    // Nuvio, pause"). The last one, so "Nuvio… Nuvio, pause" (said again when the first went
    // unanswered) is the command after the second.
    for (at in tokens.indices.reversed()) {
        val name = bare(at) ?: continue
        val nameTokens = when {
            isWakeToken(name) -> 1
            // The name split in two: "No ovio", "new vio" (seen from Windows' Zira voice).
            name in WAKE_SPLIT_STARTS && splitWakeName(name, bare(at + 1)) -> 2
            else -> continue
        }
        return tokens.drop(at + nameTokens).joinToString(" ").trim { it in WAKE_EDGE_PUNCTUATION || it.isWhitespace() }
    }
    return null
}

private val WAKE_SPLIT_STARTS = setOf("no", "new", "nu", "noo", "knew")

/** [first] and a short [second] that together are "nuvio" give or take two letters ("noovio"). */
private fun splitWakeName(first: String, second: String?): Boolean {
    if (second == null || second.length !in 2..5 || !second.all { it.isLetter() }) return false
    return wakeEditDistance(first.removePrefix("k") + second, "nuvio") <= 2
}

/** "nuvio" or a one-letter slip of it ("nuvi", "nuvioo"); not unrelated short words such as "nova". */
private fun isWakeToken(word: String): Boolean {
    if (word in WAKE_SPELLINGS) return true
    if (word.length !in 4..6 || word[0] != 'n') return false
    return wakeEditDistance(word, "nuvio") <= 1
}

private fun wakeEditDistance(a: String, b: String): Int {
    var previous = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val current = IntArray(b.length + 1)
        current[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
        }
        previous = current
    }
    return previous[b.length]
}
