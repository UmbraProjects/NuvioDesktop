package com.nuvio.app.features.voice

/*
 * fork:voice-control
 *
 * What a spoken command asks for. The speech model (whisper.cpp; see Docs/plans/voice-control.md)
 * hands over one finished utterance, written as a sentence ("Search YouTube for cat videos."), and
 * [parseVoiceIntent] turns it into a [VoiceIntent] the app can route: a search in one of the app's
 * search boxes, a tab to open, or a playback command. Pure, so it is tested without audio.
 *
 * Unlike [com.nuvio.app.core.ui.parseVoiceSearchCommand], which reads commands off the end of text
 * that Windows voice typing is still typing into a box, this sees the whole utterance at once and
 * works anywhere in the app.
 */

/** Which search a spoken search runs in. */
enum class VoiceSearchScope { All, Movies, Shows, YouTube, Music }

/** A place in the app a spoken "open ..." goes to. */
enum class VoiceDestination { Home, Search, Discover, Library, LiveTv, Music, YouTube, Calendar, Settings }

sealed interface VoiceIntent {
    /** Run a search for [query] (never blank, in the words' own casing) in [scope]. */
    data class Search(val scope: VoiceSearchScope, val query: String) : VoiceIntent

    /** "search youtube" with nothing after it: open that search box, ready to type in. */
    data class OpenSearch(val scope: VoiceSearchScope) : VoiceIntent

    data class Open(val destination: VoiceDestination) : VoiceIntent

    data object Play : VoiceIntent
    data object Pause : VoiceIntent
    data object Next : VoiceIntent
    data object GoBack : VoiceIntent

    /** Jump [deltaMs] forward (positive) or back (negative): "skip forward 30 seconds", "rewind a minute". */
    data class Seek(val deltaMs: Long) : VoiceIntent

    /** "playback speed 1.5", "normal speed", "double speed". */
    data class Speed(val rate: Float) : VoiceIntent

    /** Not a command. [text] is what was heard, tidied; empty when nothing (or only noise) was. */
    data class Unknown(val text: String) : VoiceIntent
}

private typealias Phrase = List<String>

private fun phrases(vararg words: String): List<Phrase> =
    words.map { it.split(' ') }.sortedByDescending { it.size }

/** Tags whisper writes for sounds rather than words: "[BLANK_AUDIO]", "(music)", "*laughs*". */
private val SOUND_TAG = Regex("""\[[^\]]*]|\([^)]*\)|\*[^*]*\*""")

/** Stripped from both ends of each word, so "for:" is "for" and "'cat videos.'" is "cat videos". */
private const val EDGE_PUNCTUATION = ".,!?;:\"“”‘’'`()[]{}<>…-–—"

private val LEADING_FILLERS = phrases(
    "hey nuvio", "ok nuvio", "okay nuvio", "nuvio", "okay", "ok", "um", "uh",
    "please", "can you", "could you", "would you", "will you", "i want to", "i'd like to",
    "i would like to", "let's",
)
// Only "please": "now" and "for me" end titles ("Apocalypse Now").
private val TRAILING_FILLERS = phrases("please")

/** What whisper writes for silence or room noise, which must not turn into a command or a search. */
private val SILENCE_HALLUCINATIONS = setOf(
    "you", "thank you", "thanks", "thank you very much", "thanks for watching",
    "thank you for watching", "bye", "bye bye", "okay", "ok",
)

private val SEARCH_VERBS = phrases("search", "find", "look up", "look for")

private val SCOPE_WORDS: List<Pair<Phrase, VoiceSearchScope>> = listOf(
    "youtube" to VoiceSearchScope.YouTube,
    "you tube" to VoiceSearchScope.YouTube,
    "movies" to VoiceSearchScope.Movies,
    "movie" to VoiceSearchScope.Movies,
    "films" to VoiceSearchScope.Movies,
    "film" to VoiceSearchScope.Movies,
    "tv shows" to VoiceSearchScope.Shows,
    "tv show" to VoiceSearchScope.Shows,
    "tv series" to VoiceSearchScope.Shows,
    "shows" to VoiceSearchScope.Shows,
    "show" to VoiceSearchScope.Shows,
    "series" to VoiceSearchScope.Shows,
    "television" to VoiceSearchScope.Shows,
    "tv" to VoiceSearchScope.Shows,
    "music" to VoiceSearchScope.Music,
    "songs" to VoiceSearchScope.Music,
    "song" to VoiceSearchScope.Music,
    "everything" to VoiceSearchScope.All,
).map { (words, scope) -> words.split(' ') to scope }.sortedByDescending { it.first.size }

/**
 * Scope words that may be followed straight by the query, with no "for": "search movies midsommar".
 * The singular ones are left out because titles start with them ("search show me the money").
 */
private val SCOPES_WITHOUT_FOR = setOf("youtube", "you tube", "movies", "films", "shows", "tv shows", "music", "songs")

private val OPEN_VERBS = phrases(
    "take me to", "navigate to", "switch to", "go to", "bring up", "show me", "open up", "open",
    "show", "launch", "go",
)
private val OPEN_SUFFIXES = setOf("tab", "page", "screen", "section", "menu")

private val DESTINATION_WORDS: List<Pair<Phrase, VoiceDestination>> = listOf(
    "home" to VoiceDestination.Home,
    "search" to VoiceDestination.Search,
    "discover" to VoiceDestination.Discover,
    "library" to VoiceDestination.Library,
    "live tv" to VoiceDestination.LiveTv,
    "live television" to VoiceDestination.LiveTv,
    "tv guide" to VoiceDestination.LiveTv,
    "channels" to VoiceDestination.LiveTv,
    "iptv" to VoiceDestination.LiveTv,
    "music" to VoiceDestination.Music,
    "youtube" to VoiceDestination.YouTube,
    "you tube" to VoiceDestination.YouTube,
    "calendar" to VoiceDestination.Calendar,
    "settings" to VoiceDestination.Settings,
    "setting" to VoiceDestination.Settings,
    "preferences" to VoiceDestination.Settings,
    "options" to VoiceDestination.Settings,
).map { (words, destination) -> words.split(' ') to destination }.sortedByDescending { it.first.size }

/** Whole-utterance commands. */
private val EXACT_COMMANDS: Map<String, VoiceIntent> = buildMap {
    listOf("play", "resume", "unpause", "continue", "play it", "resume playback", "keep playing")
        .forEach { put(it, VoiceIntent.Play) }
    listOf("pause", "stop", "hold on", "pause it", "pause that", "pause playback", "stop it")
        .forEach { put(it, VoiceIntent.Pause) }
    listOf(
        "next", "next one", "next episode", "next track", "next song", "next video", "skip",
        "skip it", "skip this", "play next",
    ).forEach { put(it, VoiceIntent.Next) }
    listOf("back", "go back", "return", "previous page", "go to the previous page", "go to previous page")
        .forEach { put(it, VoiceIntent.GoBack) }
}

/** The utterance as words: [shown] as heard (for the query), [match] lower-cased (for the rules). */
private class Words(val shown: List<String>, val match: List<String>) {
    val size get() = match.size

    fun startsWith(phrase: Phrase, at: Int = 0): Boolean =
        at >= 0 && at + phrase.size <= size && phrase.indices.all { match[at + it] == phrase[it] }

    fun endsWith(phrase: Phrase): Boolean = startsWith(phrase, size - phrase.size)

    fun firstOf(candidates: List<Phrase>, at: Int = 0): Phrase? = candidates.firstOrNull { startsWith(it, at) }

    fun slice(from: Int, to: Int = size) = Words(shown.subList(from, to), match.subList(from, to))

    fun text(): String = shown.joinToString(" ")

    fun matchText(): String = match.joinToString(" ")
}

private fun words(transcript: String): Words {
    val shown = transcript.replace(SOUND_TAG, " ")
        .split(Regex("""\s+"""))
        .map { it.trim { c -> c in EDGE_PUNCTUATION } }
        .filter { it.isNotEmpty() }
    return Words(shown, shown.map { it.lowercase().replace('’', '\'') })
}

private fun Words.withoutFillers(): Words {
    var words = this
    while (true) {
        val lead = words.firstOf(LEADING_FILLERS)
        if (lead != null && lead.size < words.size) {
            words = words.slice(lead.size)
            continue
        }
        val trail = TRAILING_FILLERS.firstOrNull { words.endsWith(it) && it.size < words.size }
        if (trail != null) {
            words = words.slice(0, words.size - trail.size)
            continue
        }
        return words
    }
}

/**
 * What the spoken [transcript] asks for. [phrases] are the person's own (Settings > Voice control >
 * Commands): tried before the built-in ones, and an action whose built-in phrases are turned off
 * answers only to its own.
 */
fun parseVoiceIntent(transcript: String, phrases: VoiceCommandPhrases = VoiceCommandPhrases.None): VoiceIntent {
    val all = words(transcript)
    if (all.size == 0 || all.matchText() in SILENCE_HALLUCINATIONS) return VoiceIntent.Unknown("")
    val words = all.withoutFillers()
    parseCustom(words, phrases)?.let { return it }
    val builtIn = parseBuiltIn(words) ?: return VoiceIntent.Unknown(words.text())
    return if (VoiceCommandKind.of(builtIn) in phrases.builtInOff) VoiceIntent.Unknown(words.text()) else builtIn
}

private fun parseBuiltIn(words: Words): VoiceIntent? {
    EXACT_COMMANDS[words.matchText()]?.let { return it }
    parseSpeed(words)?.let { return it }
    parseSeek(words)?.let { return it }
    parseSearch(words)?.let { return it }
    return parseOpen(words)
}

// ---- The person's own phrases ---------------------------------------------------------------------

/** A phrase as the parser compares it: lower case, word edges' punctuation off. Empty when there are no words. */
fun normalizeVoicePhrase(phrase: String): String = words(phrase).matchText()

private fun parseCustom(words: Words, phrases: VoiceCommandPhrases): VoiceIntent? {
    if (phrases.custom.isEmpty()) return null
    val candidates = phrases.custom.flatMap { (kind, list) ->
        list.map(::normalizeVoicePhrase).filter { it.isNotEmpty() }.map { kind to it.split(' ') }
    }.sortedByDescending { it.second.size }
    for ((kind, phrase) in candidates) {
        if (!words.startsWith(phrase)) continue
        intentFor(kind, words.slice(phrase.size))?.let { return it }
    }
    return null
}

/** [kind] said with [rest] after its phrase; null when [rest] does not fit (words after "pause"). */
private fun intentFor(kind: VoiceCommandKind, rest: Words): VoiceIntent? = when (kind.takes) {
    VoiceCommandKind.Takes.Nothing -> if (rest.size == 0) kind.fixedIntent() else null
    VoiceCommandKind.Takes.Query -> {
        val query = if (rest.startsWith(listOf("for"))) rest.slice(1) else rest
        searchFor(kind.scope!!, query)
    }
    VoiceCommandKind.Takes.Duration -> {
        val ms = if (rest.size == 0) DEFAULT_SEEK_MS else parseDurationMs(rest.match) ?: return null
        VoiceIntent.Seek(if (kind == VoiceCommandKind.SeekBack) -ms else ms)
    }
    VoiceCommandKind.Takes.Rate -> parseRate(rest.match)?.let { VoiceIntent.Speed(it) }
}

// ---- Seek and speed -------------------------------------------------------------------------------

/** "skip forward" with no amount. */
const val DEFAULT_SEEK_MS = 10_000L

private val SEEK_FORWARD = phrases(
    "seek forward", "seek ahead", "skip forward", "skip ahead", "jump forward", "jump ahead", "fast forward",
    "go forward", "move forward", "forward", "ahead", "skip", "seek",
)
private val SEEK_BACK = phrases(
    "seek back", "seek backward", "seek backwards", "skip back", "skip backward", "skip backwards", "jump back",
    "jump backward", "jump backwards", "rewind", "go back", "move back", "back", "backward", "backwards",
)

/** Verbs that mean a seek even with no amount ("rewind"). "back", "go back" and "skip" alone are other commands. */
private val SEEK_WITHOUT_AMOUNT = setOf(
    "seek forward", "seek ahead", "skip forward", "skip ahead", "jump forward", "jump ahead", "fast forward",
    "seek back", "seek backward", "seek backwards", "skip back", "skip backward", "skip backwards", "jump back",
    "jump backward", "jump backwards", "rewind",
)

private fun parseSeek(words: Words): VoiceIntent? {
    for ((verbs, sign) in listOf(SEEK_FORWARD to 1L, SEEK_BACK to -1L)) {
        val verb = words.firstOf(verbs) ?: continue
        var rest = words.slice(verb.size)
        if (rest.startsWith(listOf("by"))) rest = rest.slice(1)
        val ms = when {
            rest.size == 0 -> if (verb.joinToString(" ") in SEEK_WITHOUT_AMOUNT) DEFAULT_SEEK_MS else null
            else -> parseDurationMs(rest.match)
        } ?: continue
        return VoiceIntent.Seek(sign * ms)
    }
    return null
}

private val SPEED_PREFIXES = phrases(
    "set the playback speed to", "set playback speed to", "set the speed to", "set speed to", "change the speed to",
    "change speed to", "playback speed to", "playback speed", "speed to", "speed", "play at",
)
private val SPEED_SUFFIXES = phrases("times speed", "x speed", "speed", "times", "x")

private fun parseSpeed(words: Words): VoiceIntent? {
    when (words.matchText()) {
        "normal speed", "regular speed", "normal playback speed", "reset speed", "reset the speed", "speed normal" ->
            return VoiceIntent.Speed(1f)
        "double speed" -> return VoiceIntent.Speed(2f)
        "half speed" -> return VoiceIntent.Speed(0.5f)
    }
    words.firstOf(SPEED_PREFIXES)?.let { prefix ->
        return parseRate(words.slice(prefix.size).match)?.let { VoiceIntent.Speed(it) }
    }
    // "1.5x speed", "one and a half times speed": a number first only counts with "speed" after it.
    if (words.size >= 2 && words.endsWith(listOf("speed"))) {
        return parseRate(words.match)?.let { VoiceIntent.Speed(it) }
    }
    return null
}

/** A rate such as "1.5", "1.5x", "one and a half", "two times"; null when the words are not one. */
internal fun parseRate(tokens: List<String>): Float? {
    var rest = tokens
    SPEED_SUFFIXES.firstOrNull { rest.size > it.size && rest.takeLast(it.size) == it }?.let { rest = rest.dropLast(it.size) }
    if (rest.isEmpty()) return null
    val (value, used) = parseNumber(rest) ?: return null
    if (used != rest.size || value <= 0) return null
    return value.toFloat()
}

/** "30 seconds", "a minute", "1 minute 30 seconds", "half a minute", "90"; null when the words are not a duration. */
internal fun parseDurationMs(tokens: List<String>): Long? {
    val rest = tokens.flatMap { it.split('-') }.filter { it.isNotEmpty() }.toMutableList()
    if (rest.isEmpty()) return null
    var total = 0.0
    while (rest.isNotEmpty()) {
        val (value, used) = parseNumber(rest) ?: return null
        repeat(used) { rest.removeAt(0) }
        val unit = rest.firstOrNull()
        val seconds = when (unit) {
            "second", "seconds", "sec", "secs", "s" -> 1.0
            "minute", "minutes", "min", "mins", "m" -> 60.0
            null -> 1.0
            else -> return null
        }
        if (unit != null) rest.removeAt(0)
        total += value * seconds
        if (rest.firstOrNull() == "and") rest.removeAt(0)
    }
    return if (total > 0) (total * 1000).toLong() else null
}

private val SMALL_NUMBERS = listOf(
    "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
    "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
).withIndex().associate { (i, word) -> word to i } + mapOf("a" to 1, "an" to 1)
private val TENS = listOf("twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    .withIndex().associate { (i, word) -> word to (i + 2) * 10 }
private fun smallNumber(token: String?): Int? = token?.let { SMALL_NUMBERS[it] }
private val DIGITS = Regex("""^(\d+(?:[.,]\d+)?)(x|s|m)?$""")

/** A number at the start of [tokens] and how many tokens it took: "30", "1.5x", "forty five", "one point five", "two and a half", "half". */
internal fun parseNumber(tokens: List<String>): Pair<Double, Int>? {
    if (tokens.isEmpty()) return null
    DIGITS.matchEntire(tokens[0])?.let { match ->
        val value = match.groupValues[1].replace(',', '.').toDouble()
        var used = 1
        if (tokens.getOrNull(1) == "and" && tokens.getOrNull(2) == "a" && tokens.getOrNull(3) == "half") {
            return value + 0.5 to used + 3
        }
        return value to used
    }
    var at = 0
    var value: Double
    when {
        tokens[0] == "half" -> {
            // "half a minute", "half speed" (the caller reads what follows)
            return 0.5 to if (tokens.getOrNull(1) == "a" || tokens.getOrNull(1) == "an") 2 else 1
        }
        tokens[0] in TENS -> {
            value = TENS.getValue(tokens[0]).toDouble()
            at = 1
            smallNumber(tokens.getOrNull(1))?.takeIf { it in 1..9 && tokens[1] != "a" && tokens[1] != "an" }?.let {
                value += it
                at = 2
            }
        }
        tokens[0] in SMALL_NUMBERS -> {
            value = SMALL_NUMBERS.getValue(tokens[0]).toDouble()
            at = 1
        }
        else -> return null
    }
    // "one point five", "one point two five"
    if (tokens.getOrNull(at) == "point") {
        var scale = 0.1
        var digits = 0
        while (true) {
            val digit = smallNumber(tokens.getOrNull(at + 1 + digits))?.takeIf { it in 0..9 } ?: break
            if (tokens[at + 1 + digits] == "a" || tokens[at + 1 + digits] == "an") break
            value += digit * scale
            scale /= 10
            digits++
        }
        if (digits == 0) return null
        at += 1 + digits
    }
    // "two and a half"
    if (tokens.getOrNull(at) == "and" && tokens.getOrNull(at + 1) == "a" && tokens.getOrNull(at + 2) == "half") {
        value += 0.5
        at += 3
    }
    return value to at
}

private fun Words.scopeAt(at: Int): Pair<Phrase, VoiceSearchScope>? =
    SCOPE_WORDS.firstOrNull { startsWith(it.first, at) }

private fun searchFor(scope: VoiceSearchScope, query: Words): VoiceIntent =
    if (query.size == 0) VoiceIntent.OpenSearch(scope) else VoiceIntent.Search(scope, query.text())

private fun parseSearch(words: Words): VoiceIntent? {
    // "youtube search cat videos"
    words.scopeAt(0)?.let { (scopeWords, scope) ->
        words.firstOf(SEARCH_VERBS, scopeWords.size)?.let { verb ->
            var at = scopeWords.size + verb.size
            if (words.startsWith(listOf("for"), at)) at++
            return searchFor(scope, words.slice(at))
        }
    }

    val verb = words.firstOf(SEARCH_VERBS) ?: return null
    val afterVerb = words.slice(verb.size)

    // "search [on] youtube for cat videos", "search movies midsommar", "search youtube"
    var at = 0
    if (afterVerb.firstOf(phrases("on", "in"), at) != null) at++
    if (afterVerb.startsWith(listOf("the"), at)) at++
    afterVerb.scopeAt(at)?.let { (scopeWords, scope) ->
        val rest = at + scopeWords.size
        when {
            rest == afterVerb.size -> return VoiceIntent.OpenSearch(scope)
            afterVerb.startsWith(listOf("for"), rest) -> return searchFor(scope, afterVerb.slice(rest + 1))
            scopeWords.joinToString(" ") in SCOPES_WITHOUT_FOR -> return searchFor(scope, afterVerb.slice(rest))
        }
    }

    // "search [for] cat videos on youtube", "search for midsommar"
    val query = if (afterVerb.startsWith(listOf("for"))) afterVerb.slice(1) else afterVerb
    trailingScope(query)?.let { (scope, length) -> return VoiceIntent.Search(scope, query.slice(0, query.size - length).text()) }
    return searchFor(VoiceSearchScope.All, query)
}

/** "... on [the] youtube" at the end of [query], with words before it: the scope and how many words it took. */
private fun trailingScope(query: Words): Pair<VoiceSearchScope, Int>? {
    for ((scopeWords, scope) in SCOPE_WORDS) {
        if (!query.endsWith(scopeWords)) continue
        var length = scopeWords.size
        if (query.startsWith(listOf("the"), query.size - length - 1)) length++
        if (query.firstOf(phrases("on", "in"), query.size - length - 1) == null) continue
        length++
        if (length < query.size) return scope to length
    }
    return null
}

private fun parseOpen(words: Words): VoiceIntent? {
    var at = words.firstOf(OPEN_VERBS)?.size ?: 0
    if (words.firstOf(phrases("the", "my"), at) != null) at++
    val (destinationWords, destination) = DESTINATION_WORDS.firstOrNull { words.startsWith(it.first, at) } ?: return null
    at += destinationWords.size
    if (at < words.size && words.match[at] in OPEN_SUFFIXES) at++
    return if (at == words.size) VoiceIntent.Open(destination) else null
}
