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

    /** "skip the intro": whatever skip prompt (intro, recap, credits) the player is offering. */
    data object SkipSegment : VoiceIntent

    /** "stop playback", "close the player": leave the player (not "stop", which pauses). */
    data object StopPlayback : VoiceIntent

    data object PreviousEpisode : VoiceIntent

    /** "go to 20 minutes", "start over" (0). */
    data class SeekTo(val positionMs: Long) : VoiceIntent

    /** "volume up", "louder": by [percent] points (negative: down). */
    data class VolumeChange(val percent: Int) : VoiceIntent

    /** "volume 40", "set the volume to 70 percent". */
    data class VolumeSet(val percent: Int) : VoiceIntent

    data class Mute(val muted: Boolean) : VoiceIntent

    data object NextSubtitles : VoiceIntent
    data object NextAudio : VoiceIntent

    /** "source 3", "the second one": the [number]th (from 1) of what is listed, the source list today. */
    data class Choose(val number: Int) : VoiceIntent

    /** "down", "go right 3", "next row": the arrow key, [count] times. */
    data class Move(val direction: VoiceDirection, val count: Int) : VoiceIntent

    /** "select", "open it": Enter on whatever has focus. */
    data object Select : VoiceIntent

    /** "go right one and select": [steps] carried out in order (never nested, never one step). */
    data class Chain(val steps: List<VoiceIntent>) : VoiceIntent

    /** Not a command. [text] is what was heard, tidied; empty when nothing (or only noise) was. */
    data class Unknown(val text: String) : VoiceIntent
}

enum class VoiceDirection { Up, Down, Left, Right }

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
    // On a title's page these press its Play button; in the source list they take the top source.
    listOf(
        "start playback", "start playing", "start watching", "start it", "watch it", "watch this", "watch now",
        "play this", "play that", "play now", "play the movie", "play the film", "play the show", "play the episode",
    ).forEach { put(it, VoiceIntent.Play) }
    listOf("intro", "recap", "credits", "outro", "opening", "ending", "theme song", "opening credits", "end credits")
        .flatMap { listOf("skip $it", "skip the $it") }
        .plus(listOf("skip this part", "skip this bit", "skip ahead past this"))
        .forEach { put(it, VoiceIntent.SkipSegment) }
    listOf(
        "stop playback", "stop playing", "stop watching", "end playback", "quit playback", "quit", "exit",
        "close the player", "close player", "exit the player", "exit player", "leave the player",
        "stop the video", "stop the movie", "stop the film", "stop the episode", "stop the show",
    ).forEach { put(it, VoiceIntent.StopPlayback) }
    listOf(
        "previous", "previous episode", "last episode", "the episode before", "go back an episode",
        "previous track", "previous song", "previous video",
    ).forEach { put(it, VoiceIntent.PreviousEpisode) }
    listOf(
        "start over", "restart", "restart it", "from the beginning", "start from the beginning",
        "play from the beginning", "go to the beginning", "back to the beginning", "back to the start",
        "go to the start", "rewind to the beginning", "rewind to the start",
    ).forEach { put(it, VoiceIntent.SeekTo(0)) }
    listOf(
        "volume up", "louder", "turn it up", "turn up the volume", "turn the volume up", "increase volume",
        "increase the volume", "raise the volume", "more volume",
    ).forEach { put(it, VoiceIntent.VolumeChange(VOLUME_STEP_PERCENT)) }
    listOf(
        "volume down", "quieter", "softer", "turn it down", "turn down the volume", "turn the volume down",
        "decrease volume", "decrease the volume", "lower the volume", "less volume",
    ).forEach { put(it, VoiceIntent.VolumeChange(-VOLUME_STEP_PERCENT)) }
    listOf("mute", "mute it", "mute the sound", "mute audio", "mute the audio", "sound off")
        .forEach { put(it, VoiceIntent.Mute(true)) }
    listOf("unmute", "unmute it", "sound on", "unmute the sound", "unmute audio")
        .forEach { put(it, VoiceIntent.Mute(false)) }
    listOf(
        "next subtitles", "next subtitle", "change subtitles", "change the subtitles", "switch subtitles",
        "other subtitles", "subtitles", "next subtitle track", "change subtitle track",
    ).forEach { put(it, VoiceIntent.NextSubtitles) }
    listOf(
        "next audio", "next audio track", "change audio", "change the audio", "change audio track",
        "switch audio", "change language", "switch language", "next language", "other audio",
    ).forEach { put(it, VoiceIntent.NextAudio) }
    listOf(
        "select", "select it", "select this", "select that", "enter", "press enter", "open it", "open this",
        "open that", "click", "click it", "click that", "choose this", "choose that", "choose it", "pick this",
        "pick that", "pick it", "confirm",
    ).forEach { put(it, VoiceIntent.Select) }
    put("next row", VoiceIntent.Move(VoiceDirection.Down, 1))
    put("previous row", VoiceIntent.Move(VoiceDirection.Up, 1))
}

/** "volume up" with no amount: the player's own keyboard step is 5, too little to hear from the sofa. */
const val VOLUME_STEP_PERCENT = 10

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
    return parseWords(words, phrases) ?: correctNearMiss(words, phrases)
        ?: parseChain(transcript, phrases) ?: VoiceIntent.Unknown(words.text())
}

/** The pause between two steps of a chain, so the screen can follow (focus moves, a list opens). */
const val VOICE_CHAIN_STEP_MS = 350L

private const val MAX_CHAIN_STEPS = 5

/** Between the steps of a chain: "and", "then", "and then", "after that", or a sentence break whisper wrote. */
private val CHAIN_BREAK = Regex("""\s*(?:[,;.!?]+|\band then\b|\bthen\b|\band\b|\bafter that\b)\s*""", RegexOption.IGNORE_CASE)

/**
 * Several commands said in one go ("go right one and select", "down two, then select"). Only
 * tried when the whole was not one command, so a title with "and" in it stays a search; and every
 * part must be a command, so a sentence that merely contains "and" stays unknown.
 */
private fun parseChain(transcript: String, phrases: VoiceCommandPhrases): VoiceIntent? {
    val parts = transcript.replace(SOUND_TAG, " ").split(CHAIN_BREAK).map { it.trim() }.filter { part ->
        words(part).withoutFillers().size > 0
    }
    if (parts.size < 2 || parts.size > MAX_CHAIN_STEPS) return null
    val steps = parts.map { part ->
        val words = words(part).withoutFillers()
        parseWords(words, phrases) ?: correctNearMiss(words, phrases) ?: return null
    }
    return VoiceIntent.Chain(steps)
}

/** [words] as a command, or null when they are not one (as said; no correction). */
private fun parseWords(words: Words, phrases: VoiceCommandPhrases): VoiceIntent? {
    parseCustom(words, phrases)?.let { return it }
    val builtIn = parseBuiltIn(words) ?: return null
    return if (VoiceCommandKind.of(builtIn) in phrases.builtInOff) null else builtIn
}

private fun parseBuiltIn(words: Words): VoiceIntent? {
    EXACT_COMMANDS[words.matchText()]?.let { return it }
    parseSpeed(words)?.let { return it }
    parseStop(words)?.let { return it }
    parseVolume(words)?.let { return it }
    parseSeekTo(words)?.let { return it }
    parseSeek(words)?.let { return it }
    parseChoose(words)?.let { return it }
    parseMove(words)?.let { return it }
    parseSearch(words)?.let { return it }
    return parseOpen(words)
}

// ---- Volume, jumping to a time, picking by number, moving focus ------------------------------------

private val STOP_VERBS = setOf("stop", "close", "exit", "quit", "end", "leave", "kill", "cancel")
private val STOP_OBJECTS = setOf(
    "playback", "player", "video", "movie", "film", "episode", "show", "stream", "playing", "watching", "youtube",
)

/** "close playback", "stop the playback", "end this video": any of the verbs with any of the things. */
private fun parseStop(words: Words): VoiceIntent? {
    val tokens = words.match
    if (tokens.size !in 2..4 || tokens[0] !in STOP_VERBS) return null
    var rest = tokens.drop(1)
    if (rest.firstOrNull() in setOf("the", "this", "that")) rest = rest.drop(1)
    if (rest.size >= 2 && rest.last() in setOf("playback", "player")) rest = rest.takeLast(1) // "the youtube player"
    return if (rest.size == 1 && rest[0] in STOP_OBJECTS) VoiceIntent.StopPlayback else null
}

private val VOLUME_SET_PREFIXES = phrases(
    "set the volume to", "set volume to", "change the volume to", "turn the volume to", "volume to", "volume at", "volume",
)
private val PERCENT_WORDS = setOf("percent", "per cent", "%")

private fun parseVolume(words: Words): VoiceIntent? {
    // "volume up by 20", "turn the volume down 5"
    for ((lead, sign) in listOf(phrases("volume up", "turn the volume up", "turn up the volume") to 1,
        phrases("volume down", "turn the volume down", "turn down the volume") to -1)) {
        val prefix = words.firstOf(lead) ?: continue
        var rest = words.slice(prefix.size).match
        if (rest.firstOrNull() == "by") rest = rest.drop(1)
        val amount = percentOf(rest) ?: continue
        return VoiceIntent.VolumeChange(sign * amount)
    }
    val prefix = words.firstOf(VOLUME_SET_PREFIXES) ?: return null
    val percent = percentOf(words.slice(prefix.size).match) ?: return null
    return VoiceIntent.VolumeSet(percent.coerceAtMost(100))
}

/** "40", "forty percent", "70%"; null when the words are not one number (0..150). */
private fun percentOf(tokens: List<String>): Int? {
    var rest = tokens.map { it.removeSuffix("%") }.filter { it.isNotEmpty() }
    if (rest.lastOrNull() in PERCENT_WORDS) rest = rest.dropLast(1)
    if (rest.size >= 2 && rest.takeLast(2) == listOf("per", "cent")) rest = rest.dropLast(2)
    if (rest.isEmpty()) return null
    val (value, used) = parseNumber(rest) ?: return null
    if (used != rest.size) return null
    return value.toInt().takeIf { it in 0..150 }
}

private val SEEK_TO_VERBS = phrases("go to", "jump to", "skip to", "seek to", "move to", "fast forward to", "rewind to", "start at", "play from")

private fun parseSeekTo(words: Words): VoiceIntent? {
    val verb = words.firstOf(SEEK_TO_VERBS) ?: return null
    var rest = words.slice(verb.size).match
    if (rest.firstOrNull() == "the") rest = rest.drop(1)
    if (rest.lastOrNull() == "mark") rest = rest.dropLast(1)
    // "go to minute 20"
    if (rest.size >= 2 && rest[0] in setOf("minute", "min")) {
        val (value, used) = parseNumber(rest.drop(1)) ?: return null
        return if (used == rest.size - 1) VoiceIntent.SeekTo((value * 60_000).toLong()) else null
    }
    if (rest.isEmpty()) return null
    // A bare number is ambiguous ("go to 20"), and "go to two" is more likely a slip than a time.
    if (rest.none { it in DURATION_UNITS }) return null
    val ms = parseDurationMs(rest) ?: return null
    return VoiceIntent.SeekTo(ms)
}

private val CHOOSE_VERBS = phrases("play", "select", "pick", "choose", "open", "start", "use", "take", "go with")
private val CHOOSE_NOUNS = setOf("source", "stream", "option", "number", "item", "result", "link", "one", "choice")
private val ORDINALS = listOf(
    "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth",
    "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth", "seventeenth", "eighteenth",
    "nineteenth", "twentieth",
).withIndex().associate { (i, word) -> word to i + 1 }
private val ORDINAL_DIGITS = Regex("""^(\d+)(st|nd|rd|th)$""")

/**
 * "source 3", "play source three", "number 2", "the second one", "play the first one". A number on
 * its own counts only after a verb ("select 3"): a bare "two" is likelier a misheard word.
 */
private fun parseChoose(words: Words): VoiceIntent? {
    var rest = words.match
    val verb = words.firstOf(CHOOSE_VERBS)
    if (verb != null) rest = rest.drop(verb.size)
    if (rest.firstOrNull() == "the") rest = rest.drop(1)
    if (rest.isEmpty()) return null
    ordinal(rest[0])?.let { n ->
        val tail = rest.drop(1)
        // "the second one", "first source", "the third"
        if (tail.isEmpty() || (tail.size == 1 && tail[0] in CHOOSE_NOUNS)) return VoiceIntent.Choose(n)
        return null
    }
    var hasNoun = false
    if (rest[0] in CHOOSE_NOUNS && rest[0] != "one") {
        rest = rest.drop(1)
        hasNoun = true
    }
    if (!hasNoun && verb == null) return null
    if (rest.firstOrNull() == "number") rest = rest.drop(1)
    val (value, used) = parseNumber(rest) ?: return null
    if (used != rest.size || value < 1 || value > 99 || value != kotlin.math.floor(value)) return null
    return VoiceIntent.Choose(value.toInt())
}

private fun ordinal(token: String): Int? =
    ORDINALS[token] ?: ORDINAL_DIGITS.matchEntire(token)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..99 }

private val MOVE_VERBS = phrases("go", "move", "scroll", "navigate", "jump")
private val MOVE_DIRECTIONS = mapOf(
    "up" to VoiceDirection.Up, "down" to VoiceDirection.Down, "left" to VoiceDirection.Left, "right" to VoiceDirection.Right,
)
private val MOVE_UNITS = setOf("row", "rows", "item", "items", "time", "times", "step", "steps", "place", "places", "space", "spaces", "tile", "tiles")

/** "down", "go right 3", "move down two rows", "left a bit"; [count] 1..20. */
private fun parseMove(words: Words): VoiceIntent? {
    var rest = words.match
    words.firstOf(MOVE_VERBS)?.let { rest = rest.drop(it.size) }
    // "a row down"
    if (rest.size == 3 && rest[0] in setOf("a", "one") && rest[1] in MOVE_UNITS) {
        return MOVE_DIRECTIONS[rest[2]]?.let { VoiceIntent.Move(it, 1) }
    }
    val direction = MOVE_DIRECTIONS[rest.firstOrNull()] ?: return null
    rest = rest.drop(1)
    if (rest.firstOrNull() == "by" || (rest.firstOrNull() == "to" && rest.size >= 2)) rest = rest.drop(1)
    if (rest.lastOrNull() in MOVE_UNITS) rest = rest.dropLast(1)
    if (rest.isEmpty() || rest == listOf("a") || rest == listOf("one")) return VoiceIntent.Move(direction, 1)
    val (value, used) = parseNumber(rest) ?: return null
    if (used != rest.size || value < 1 || value > 20 || value != kotlin.math.floor(value)) return null
    return VoiceIntent.Move(direction, value.toInt())
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
    VoiceCommandKind.Takes.Number -> parseNumber(rest.match)
        ?.takeIf { (value, used) -> used == rest.size && value >= 1 && value <= 99 && value == kotlin.math.floor(value) }
        ?.let { VoiceIntent.Choose(it.first.toInt()) }
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
    // A number with no unit is the unit below the last one said: "1 hour 20" is 1:20:00, "2 minutes 30" 2:30.
    var lastUnitSeconds = 0.0
    while (rest.isNotEmpty()) {
        val (value, used) = parseNumber(rest) ?: return null
        repeat(used) { rest.removeAt(0) }
        val unit = rest.firstOrNull()
        val seconds = when (unit) {
            "second", "seconds", "sec", "secs", "s" -> 1.0
            "minute", "minutes", "min", "mins", "m" -> 60.0
            "hour", "hours", "hr", "hrs", "h" -> 3600.0
            null -> if (lastUnitSeconds >= 3600.0) 60.0 else 1.0
            else -> return null
        }
        if (unit != null) rest.removeAt(0)
        total += value * seconds
        lastUnitSeconds = seconds
        if (rest.firstOrNull() == "and") rest.removeAt(0)
    }
    return if (total > 0) (total * 1000).toLong() else null
}

private val DURATION_UNITS = setOf(
    "second", "seconds", "sec", "secs", "minute", "minutes", "min", "mins", "hour", "hours", "hr", "hrs",
)

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

// ---- Near misses ----------------------------------------------------------------------------------

/*
 * Whisper mishears a short command word now and then: "playback speed tour", "paws", "serch movies
 * for ...". When nothing matched as said, words are swapped for a command word that sounds like
 * them ([SOUNDS_LIKE]) or is one letter off ([closeCommandWord]), and the result counts only if it
 * then parses as a command. Words that end up inside a search's query are never changed: a title
 * is whatever was said ("search movies for Midsomer" stays Midsomer).
 */

/** Words whisper writes for a command word that sounds the same. */
private val SOUNDS_LIKE: Map<String, List<String>> = mapOf(
    "to" to listOf("two"), "too" to listOf("two"), "tu" to listOf("two"), "tour" to listOf("two"),
    "for" to listOf("four"), "fore" to listOf("four"), "won" to listOf("one"), "wan" to listOf("one"),
    "ate" to listOf("eight"), "tree" to listOf("three"), "free" to listOf("three"), "sicks" to listOf("six"),
    "fife" to listOf("five"), "tin" to listOf("ten"),
    "paws" to listOf("pause"), "pores" to listOf("pause"), "pours" to listOf("pause"), "pose" to listOf("pause"),
    "paused" to listOf("pause"), "pauses" to listOf("pause"), "pals" to listOf("pause"),
    "plate" to listOf("play"), "played" to listOf("play"), "plays" to listOf("play"),
    "necks" to listOf("next"), "nex" to listOf("next"),
    "surge" to listOf("search"), "sirch" to listOf("search"), "church" to listOf("search"),
    "rewine" to listOf("rewind"), "rewound" to listOf("rewind"),
    // Heard in use (Nov 5): "go write one", "Go Dan 3".
    "write" to listOf("right"), "rite" to listOf("right"), "wright" to listOf("right"), "ride" to listOf("right"),
    "dan" to listOf("down"), "dawn" to listOf("down"), "done" to listOf("down"), "town" to listOf("down"),
    "lift" to listOf("left"), "laughed" to listOf("left"), "op" to listOf("up"),
    "role" to listOf("row"), "roll" to listOf("row"), "rose" to listOf("rows"), "rolls" to listOf("rows"),
)

/** Every word a built-in command is made of, the ones a near miss may become. */
private val COMMAND_WORDS: Set<String> = buildSet {
    EXACT_COMMANDS.keys.forEach { addAll(it.split(' ')) }
    listOf(SEARCH_VERBS, OPEN_VERBS, SEEK_FORWARD, SEEK_BACK, SPEED_PREFIXES, SPEED_SUFFIXES).forEach { list ->
        list.forEach { addAll(it) }
    }
    SCOPE_WORDS.forEach { addAll(it.first) }
    DESTINATION_WORDS.forEach { addAll(it.first) }
    addAll(OPEN_SUFFIXES)
    addAll(SMALL_NUMBERS.keys)
    addAll(TENS.keys)
    addAll(listOf("second", "seconds", "minute", "minutes", "normal", "regular", "double", "half", "reset", "point"))
    addAll(listOf("hour", "hours", "volume", "percent", "source", "stream", "option", "number"))
    addAll(MOVE_DIRECTIONS.keys)
    addAll(ORDINALS.keys)
}

private const val MAX_CORRECTED_WORDS = 10

private fun correctNearMiss(words: Words, phrases: VoiceCommandPhrases): VoiceIntent? {
    if (words.size == 0 || words.size > MAX_CORRECTED_WORDS) return null
    val vocabulary = COMMAND_WORDS + phrases.custom.values.flatten().flatMap { normalizeVoicePhrase(it).split(' ') }
    // Every single-word swap, the likeliest first: a sound-alike, then by letters changed.
    val swaps = words.match.indices.flatMap { at ->
        val word = words.match[at]
        SOUNDS_LIKE[word].orEmpty().map { Triple(at, it, 0) } +
            closeCommandWord(word, vocabulary)?.let { listOf(Triple(at, it.first, it.second)) }.orEmpty()
    }.sortedBy { it.third }
    // Two words off ("serch moovies for ..."): every word's best swap at once, the query put back as said.
    val everySwap by lazy { everyBestSwap(words, swaps, phrases) }
    for ((at, replacement, _) in swaps) {
        val corrected = words.replaced(mapOf(at to replacement))
        val intent = parseWords(corrected, phrases) ?: continue
        if (intent is VoiceIntent.Search && at in queryRange(corrected, intent)) continue
        // "serch moovies for x" fixed one word at a time reads as a search of everything for
        // "moovies for x"; fixing both finds the scope that was meant.
        if (intent is VoiceIntent.Search && intent.scope == VoiceSearchScope.All) {
            (everySwap as? VoiceIntent.Search)?.takeIf { it.scope != VoiceSearchScope.All }?.let { return it }
        }
        return intent
    }
    return everySwap
}

private fun everyBestSwap(words: Words, swaps: List<Triple<Int, String, Int>>, phrases: VoiceCommandPhrases): VoiceIntent? {
    // Only words that are not command words already: "for" stays "for" here, not the number.
    val best = swaps.filter { words.match[it.first] !in COMMAND_WORDS }
        .groupBy { it.first }.mapValues { (_, list) -> list.first().second }
    if (best.size < 2) return null
    val corrected = words.replaced(best)
    val intent = parseWords(corrected, phrases) ?: return null
    if (intent !is VoiceIntent.Search) return intent
    val range = queryRange(corrected, intent)
    return if (range.isEmpty()) intent else intent.copy(query = words.shown.subList(range.first, range.last + 1).joinToString(" "))
}

private fun Words.replaced(swaps: Map<Int, String>): Words =
    Words(shown.mapIndexed { i, w -> swaps[i] ?: w }, match.mapIndexed { i, w -> swaps[i] ?: w })

/** Where [search]'s query sits in [words]; empty when it cannot be found. */
private fun queryRange(words: Words, search: VoiceIntent.Search): IntRange {
    val query = search.query.split(' ')
    for (start in (words.size - query.size) downTo 0) {
        if (words.shown.subList(start, start + query.size) == query) return start until start + query.size
    }
    return IntRange.EMPTY
}

/**
 * The command word [word] is most likely a slip for, and how many letters differ: same first letter
 * (whisper keeps the sound a word starts with), one letter off for a short word, two for a long one.
 * Null for a word that already is one, a short word, or nothing close.
 */
private fun closeCommandWord(word: String, vocabulary: Set<String>): Pair<String, Int>? {
    if (word.length < 4 || word in vocabulary || !word.all { it.isLetter() }) return null
    val allowed = if (word.length >= 7) 2 else 1
    return vocabulary.asSequence()
        .filter { it.length >= 3 && it[0] == word[0] && kotlin.math.abs(it.length - word.length) <= allowed }
        .map { it to editDistance(word, it) }
        .filter { it.second <= allowed }
        .minWithOrNull(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first })
}

private fun editDistance(a: String, b: String): Int {
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

// ---- The command grammar ---------------------------------------------------------------------------

/*
 * What whisper may write when it is held to commands (WhisperNative.nativeTranscribeWithGrammar): a
 * GBNF grammar built from the same tables the parser reads, so it covers exactly what the parser
 * understands and grows with it, and with the person's own phrases. "go write one" cannot come out:
 * "write" is in no command, so whisper has to pick the nearest thing that is ("go right one").
 *
 * Search queries are the one free part (a title cannot be listed), allowed only after a search verb.
 * The text it gives is parsed by [parseVoiceIntent] like any other, so this decides only what can be
 * heard, never what it means.
 */

/** The rule a push-to-talk command starts at. */
const val VOICE_GRAMMAR_COMMAND_RULE = "root"

/** The rule for a wake-word clip: anything, then the name, then a command (or nothing). */
const val VOICE_GRAMMAR_WAKE_RULE = "wake"

private val SPEED_WORDS = listOf(
    "normal speed", "regular speed", "normal playback speed", "reset speed", "reset the speed", "speed normal",
    "double speed", "half speed",
)
private val VOLUME_STEP_PREFIXES = listOf(
    "volume up", "turn the volume up", "turn up the volume", "volume down", "turn the volume down", "turn down the volume",
)

/** The grammar for [phrases]' owner: every built-in command, their own phrases, chains of them. */
fun voiceCommandGrammar(phrases: VoiceCommandPhrases = VoiceCommandPhrases.None): String {
    fun list(vararg groups: Iterable<String>): Expr = trie(groups.flatMap { it }.map { it.split(' ') })
    fun list(phrases: List<Phrase>): Expr = trie(phrases)
    val rules = linkedMapOf<String, String>()
    fun rule(name: String, body: String) { rules[name] = body }

    // Whisper's text starts with a space; a command may be several, said in one go.
    rule("root", "\" \" (filler \",\"? \" \")? chain end")
    rule("wake", "\" \" (pre \" \")* name ([,.!] \" \"? | \" \") (chain end | end)")
    rule("pre", "[^ ]+")
    rule("name", "[Nn] \"uvio\"")
    rule("chain", "cmd (sep cmd)*")
    rule("sep", "[,.;]? \" \" ((" + word("and then") + " | " + word("and") + " | " + word("then") + ") \",\"? \" \")?")
    rule("end", "[.!?]*")
    rule("filler", list(listOf("please", "can you", "could you", "okay", "ok", "hey")).text)
    rule(
        "cmd",
        listOf("exact", "stop", "speed", "volume", "seekto", "seek", "choose", "move", "open", "search")
            .plus(if (phrases.custom.isEmpty()) emptyList() else listOf("custom"))
            .joinToString(" | "),
    )

    rule("exact", list(EXACT_COMMANDS.keys).text)
    rule("stop", "${list(STOP_VERBS).text} \" \" (${list(listOf("the", "this", "that")).text} \" \")? ${list(STOP_OBJECTS).text} (\" \" ${list(listOf("player", "playback")).text})?")

    // Numbers as whisper writes them: "30", "1.5", "1.5x", "70%", "thirty", "one point five", "two and a half".
    rule("num", "[0-9]+ ([.,] [0-9]+)? [x%]? | numword ([ -] numword)*")
    rule("numword", list(SMALL_NUMBERS.keys, TENS.keys, listOf("half", "point", "and")).text)
    rule("unit", list(DURATION_UNITS).text)
    rule("duration", "num (\" \" unit)? (\" \" (${word("and")} \" \")? num (\" \" unit)?)*")
    rule("seek", "${list(SEEK_FORWARD + SEEK_BACK).text} (\" \" (${word("by")} \" \")? duration)?")
    rule("seekto", "${list(SEEK_TO_VERBS).text} \" \" (${word("the")} \" \")? (duration | ${list(listOf("minute", "min")).text} \" \" num) (\" \" ${word("mark")})?")
    rule("rate", "num (\" \" ${list(listOf("times", "x")).text})?")
    rule("speed", "${list(SPEED_PREFIXES).text} \" \" rate | rate \" \" ${list(SPEED_SUFFIXES).text} | ${list(SPEED_WORDS).text}")
    rule("pct", list(listOf("percent", "per cent")).text)
    rule(
        "volume",
        "${list(VOLUME_STEP_PREFIXES).text} \" \" (${word("by")} \" \")? num (\" \" pct)? | " +
            "${list(VOLUME_SET_PREFIXES).text} \" \" num (\" \" pct)?",
    )
    rule("ordinal", "${list(ORDINALS.keys).text} | [0-9]+ (\"st\" | \"nd\" | \"rd\" | \"th\")")
    rule(
        "choose",
        "(${list(CHOOSE_VERBS).text} \" \")? (${word("the")} \" \")? " +
            "(ordinal (\" \" ${list(CHOOSE_NOUNS).text})? | ${list(CHOOSE_NOUNS - "one").text} \" \" (${word("number")} \" \")? num | num)",
    )
    rule("dir", list(MOVE_DIRECTIONS.keys).text)
    rule(
        "move",
        "(${list(MOVE_VERBS).text} \" \")? dir (\" \" (${list(listOf("by", "to")).text} \" \")? num (\" \" ${list(MOVE_UNITS).text})?)? | " +
            "${list(listOf("a", "one")).text} \" \" ${list(MOVE_UNITS).text} \" \" dir",
    )
    rule(
        "open",
        "(${list(OPEN_VERBS).text} \" \")? (${list(listOf("the", "my")).text} \" \")? " +
            "${list(DESTINATION_WORDS.map { it.first }).text} (\" \" ${list(OPEN_SUFFIXES).text})?",
    )
    // The free part: the query, after a search verb (and a scope) only.
    rule("free", "[^.!?]+")
    val scope = list(SCOPE_WORDS.map { it.first }).text
    val verb = list(SEARCH_VERBS).text
    rule(
        "search",
        "$verb (\" \" (${list(listOf("on", "in")).text} \" \")? (${word("the")} \" \")? $scope)? (\" \" ${word("for")})? (\" \" free)? | " +
            "$scope \" \" $verb (\" \" ${word("for")})? \" \" free",
    )
    if (phrases.custom.isNotEmpty()) {
        val alternatives = phrases.custom.flatMap { (kind, list) ->
            val slot = when (kind.takes) {
                VoiceCommandKind.Takes.Nothing -> ""
                VoiceCommandKind.Takes.Query -> " (\" \" ${word("for")})? \" \" free"
                VoiceCommandKind.Takes.Duration -> " (\" \" duration)?"
                VoiceCommandKind.Takes.Rate -> " \" \" rate"
                VoiceCommandKind.Takes.Number -> " \" \" num"
            }
            list.map(::normalizeVoicePhrase).filter { it.isNotEmpty() }.map { trie(listOf(it.split(' '))).text + slot }
        }
        rule("custom", alternatives.joinToString(" | ").ifEmpty { "\"\"" })
    }
    return rules.entries.joinToString("\n") { (name, body) -> "$name ::= $body" }
}

/** A grammar expression: [text] is GBNF. */
private class Expr(val text: String)

/**
 * [phrases] as one alternation, shared beginnings written once ("go back" / "go home" / "go to":
 * "go" then the three), so the decoder tracks a handful of open paths rather than one per phrase.
 */
private fun trie(phrases: List<List<String>>): Expr {
    val clean = phrases.map { words -> words.filter { it.isNotEmpty() } }.distinct()
    fun build(group: List<List<String>>): String {
        val alternatives = group.filter { it.isNotEmpty() }.groupBy { it.first() }.map { (first, rest) ->
            val tails = rest.map { it.drop(1) }
            val nonEmpty = tails.filter { it.isNotEmpty() }
            when {
                nonEmpty.isEmpty() -> word(first)
                nonEmpty.size == tails.size -> "${word(first)} \" \" ${build(nonEmpty)}"
                else -> "${word(first)} (\" \" ${build(nonEmpty)})?"
            }
        }
        return if (alternatives.size == 1) alternatives.single() else "(" + alternatives.joinToString(" | ") + ")"
    }
    return Expr(build(clean))
}

/**
 * One word, any letter in either case: whisper capitalises the first word, and writes "YouTube" and
 * "TV" its own way. Anything but a letter is written as it is.
 */
private fun word(text: String): String {
    val out = StringBuilder()
    var literal = StringBuilder()
    fun flushLiteral() {
        if (literal.isEmpty()) return
        if (out.isNotEmpty()) out.append(' ')
        out.append('"').append(literal.toString().replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
        literal = StringBuilder()
    }
    for (c in text) {
        if (c.isLetter() && c.lowercaseChar() != c.uppercaseChar()) {
            flushLiteral()
            if (out.isNotEmpty()) out.append(' ')
            out.append('[').append(c.uppercaseChar()).append(c.lowercaseChar()).append(']')
        } else {
            literal.append(c)
        }
    }
    flushLiteral()
    return out.toString()
}
