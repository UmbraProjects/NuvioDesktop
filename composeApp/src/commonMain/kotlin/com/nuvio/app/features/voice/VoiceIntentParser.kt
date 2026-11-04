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

/** What the spoken [transcript] asks for. */
fun parseVoiceIntent(transcript: String): VoiceIntent {
    val all = words(transcript)
    if (all.size == 0 || all.matchText() in SILENCE_HALLUCINATIONS) return VoiceIntent.Unknown("")
    val words = all.withoutFillers()
    EXACT_COMMANDS[words.matchText()]?.let { return it }
    parseSearch(words)?.let { return it }
    parseOpen(words)?.let { return it }
    return VoiceIntent.Unknown(words.text())
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
