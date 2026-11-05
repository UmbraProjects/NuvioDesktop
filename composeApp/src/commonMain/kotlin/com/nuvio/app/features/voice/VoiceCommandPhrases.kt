package com.nuvio.app.features.voice

import kotlinx.serialization.Serializable

/*
 * fork:voice-control - every action a voice command can be, so Settings can list them and the
 * person can give each one phrases of their own (one they remember, or one the model hears better
 * in their accent), and turn its built-in phrases off to rebind it.
 */

enum class VoiceCommandKind(
    val label: String,
    val group: String,
    /** How the built-in phrases go, for the Commands list ("…" is the part said each time). */
    val examples: String,
    val takes: Takes,
    val scope: VoiceSearchScope? = null,
    val destination: VoiceDestination? = null,
) {
    SearchAll("Search everything", GROUP_SEARCH, "search for …, find …, look up …", Takes.Query, scope = VoiceSearchScope.All),
    SearchMovies("Search movies", GROUP_SEARCH, "search movies for …, find … in films", Takes.Query, scope = VoiceSearchScope.Movies),
    SearchShows("Search shows", GROUP_SEARCH, "search shows for …, search tv for …", Takes.Query, scope = VoiceSearchScope.Shows),
    SearchYouTube("Search YouTube", GROUP_SEARCH, "search youtube for …, … on youtube", Takes.Query, scope = VoiceSearchScope.YouTube),
    SearchMusic("Search music", GROUP_SEARCH, "search music for …, search songs for …", Takes.Query, scope = VoiceSearchScope.Music),

    OpenHome("Open Home", GROUP_OPEN, "go home, open home", Takes.Nothing, destination = VoiceDestination.Home),
    OpenSearch("Open Search", GROUP_OPEN, "open search", Takes.Nothing, destination = VoiceDestination.Search),
    OpenLibrary("Open Library", GROUP_OPEN, "open library, open my library", Takes.Nothing, destination = VoiceDestination.Library),
    OpenDiscover("Open Discover", GROUP_OPEN, "open discover", Takes.Nothing, destination = VoiceDestination.Discover),
    OpenLiveTv("Open Live TV", GROUP_OPEN, "open live tv, open the tv guide", Takes.Nothing, destination = VoiceDestination.LiveTv),
    OpenMusic("Open Music", GROUP_OPEN, "open music", Takes.Nothing, destination = VoiceDestination.Music),
    OpenYouTube("Open YouTube", GROUP_OPEN, "open youtube", Takes.Nothing, destination = VoiceDestination.YouTube),
    OpenCalendar("Open Calendar", GROUP_OPEN, "open calendar", Takes.Nothing, destination = VoiceDestination.Calendar),
    OpenSettings("Open Settings", GROUP_OPEN, "open settings, preferences", Takes.Nothing, destination = VoiceDestination.Settings),

    Play("Play", GROUP_PLAYBACK, "play, resume, continue", Takes.Nothing),
    Pause("Pause", GROUP_PLAYBACK, "pause, stop, hold on", Takes.Nothing),
    Next("Next", GROUP_PLAYBACK, "next, next episode, skip", Takes.Nothing),
    SeekForward("Seek forward", GROUP_PLAYBACK, "skip forward 30 seconds, fast forward a minute", Takes.Duration),
    SeekBack("Seek back", GROUP_PLAYBACK, "rewind 10 seconds, go back 30 seconds", Takes.Duration),
    Speed("Playback speed", GROUP_PLAYBACK, "playback speed 1.5, normal speed, double speed", Takes.Rate),
    GoBack("Go back", GROUP_PLAYBACK, "go back, back", Takes.Nothing),
    SkipSegment("Skip intro", GROUP_PLAYBACK, "skip the intro, skip the recap, skip the credits", Takes.Nothing),
    StopPlayback("Stop playback", GROUP_PLAYBACK, "stop playback, close the player, stop watching", Takes.Nothing),
    PreviousEpisode("Previous episode", GROUP_PLAYBACK, "previous episode, last episode", Takes.Nothing),
    StartOver("Start over", GROUP_PLAYBACK, "start over, from the beginning; go to 20 minutes", Takes.Nothing),
    VolumeUp("Volume up", GROUP_PLAYBACK, "volume up, louder, volume up by 20, volume 40", Takes.Nothing),
    VolumeDown("Volume down", GROUP_PLAYBACK, "volume down, quieter, turn it down", Takes.Nothing),
    Mute("Mute", GROUP_PLAYBACK, "mute, sound off", Takes.Nothing),
    Unmute("Unmute", GROUP_PLAYBACK, "unmute, sound on", Takes.Nothing),
    NextSubtitles("Next subtitles", GROUP_PLAYBACK, "next subtitles, change subtitles", Takes.Nothing),
    NextAudio("Next audio track", GROUP_PLAYBACK, "next audio, change language", Takes.Nothing),

    MoveUp("Move up", GROUP_NAVIGATE, "up, go up 2, previous row", Takes.Nothing),
    MoveDown("Move down", GROUP_NAVIGATE, "down, go down 3 rows, next row", Takes.Nothing),
    MoveLeft("Move left", GROUP_NAVIGATE, "left, go left 2", Takes.Nothing),
    MoveRight("Move right", GROUP_NAVIGATE, "right, go right 4", Takes.Nothing),
    Select("Select", GROUP_NAVIGATE, "select, open it, enter", Takes.Nothing),
    Choose("Pick by number", GROUP_NAVIGATE, "source 3, the second one, play the first one", Takes.Number),
    ;

    /** What follows the phrase: nothing, the search words, an amount of time, or a speed. */
    enum class Takes { Nothing, Query, Duration, Rate, Number }

    /** The intent of a phrase that takes nothing. */
    fun fixedIntent(): VoiceIntent = when (this) {
        Play -> VoiceIntent.Play
        Pause -> VoiceIntent.Pause
        Next -> VoiceIntent.Next
        GoBack -> VoiceIntent.GoBack
        SkipSegment -> VoiceIntent.SkipSegment
        StopPlayback -> VoiceIntent.StopPlayback
        PreviousEpisode -> VoiceIntent.PreviousEpisode
        StartOver -> VoiceIntent.SeekTo(0)
        VolumeUp -> VoiceIntent.VolumeChange(VOLUME_STEP_PERCENT)
        VolumeDown -> VoiceIntent.VolumeChange(-VOLUME_STEP_PERCENT)
        Mute -> VoiceIntent.Mute(true)
        Unmute -> VoiceIntent.Mute(false)
        NextSubtitles -> VoiceIntent.NextSubtitles
        NextAudio -> VoiceIntent.NextAudio
        MoveUp -> VoiceIntent.Move(VoiceDirection.Up, 1)
        MoveDown -> VoiceIntent.Move(VoiceDirection.Down, 1)
        MoveLeft -> VoiceIntent.Move(VoiceDirection.Left, 1)
        MoveRight -> VoiceIntent.Move(VoiceDirection.Right, 1)
        Select -> VoiceIntent.Select
        else -> VoiceIntent.Open(destination ?: error("$this takes more than a phrase"))
    }

    /** What the person adds after one of their own phrases, for the Commands list. */
    val usage: String
        get() = when (takes) {
            Takes.Nothing -> "Say just the phrase."
            Takes.Query -> "Say the phrase, then what to search for."
            Takes.Duration -> "Say the phrase, then how far (“30 seconds”); on its own it is 10 seconds."
            Takes.Rate -> "Say the phrase, then the speed (“1.5”)."
            Takes.Number -> "Say the phrase, then the number (“3”)."
        }

    companion object {
        /** The action [intent] is; null for [VoiceIntent.Unknown]. */
        fun of(intent: VoiceIntent): VoiceCommandKind? = when (intent) {
            is VoiceIntent.Search -> ofScope(intent.scope)
            is VoiceIntent.OpenSearch -> ofScope(intent.scope)
            is VoiceIntent.Open -> entries.first { it.destination == intent.destination }
            VoiceIntent.Play -> Play
            VoiceIntent.Pause -> Pause
            VoiceIntent.Next -> Next
            VoiceIntent.GoBack -> GoBack
            is VoiceIntent.Seek -> if (intent.deltaMs < 0) SeekBack else SeekForward
            is VoiceIntent.Speed -> Speed
            VoiceIntent.SkipSegment -> SkipSegment
            VoiceIntent.StopPlayback -> StopPlayback
            VoiceIntent.PreviousEpisode -> PreviousEpisode
            is VoiceIntent.SeekTo -> StartOver
            is VoiceIntent.VolumeChange -> if (intent.percent < 0) VolumeDown else VolumeUp
            is VoiceIntent.VolumeSet -> VolumeUp
            is VoiceIntent.Mute -> if (intent.muted) Mute else Unmute
            VoiceIntent.NextSubtitles -> NextSubtitles
            VoiceIntent.NextAudio -> NextAudio
            is VoiceIntent.Move -> when (intent.direction) {
                VoiceDirection.Up -> MoveUp
                VoiceDirection.Down -> MoveDown
                VoiceDirection.Left -> MoveLeft
                VoiceDirection.Right -> MoveRight
            }
            VoiceIntent.Select -> Select
            is VoiceIntent.Choose -> Choose
            is VoiceIntent.Chain, is VoiceIntent.Unknown -> null
        }

        private fun ofScope(scope: VoiceSearchScope) = entries.first { it.scope == scope }
    }
}

private const val GROUP_SEARCH = "Search"
private const val GROUP_OPEN = "Open"
private const val GROUP_PLAYBACK = "Playback"
private const val GROUP_NAVIGATE = "Navigate"

/** The person's own phrases, by action (stored by [VoiceCommandKind.name]). */
@Serializable
data class VoiceCommandPhrases(
    val custom: Map<VoiceCommandKind, List<String>> = emptyMap(),
    /** Actions that answer only to their own phrases ("rebound"). */
    val builtInOff: Set<VoiceCommandKind> = emptySet(),
) {
    fun phrasesFor(kind: VoiceCommandKind): List<String> = custom[kind].orEmpty()

    /** The action that already has [phrase] (compared as the parser compares it), if any. */
    fun ownerOf(phrase: String): VoiceCommandKind? {
        val wanted = normalizeVoicePhrase(phrase)
        return custom.entries.firstOrNull { (_, list) -> list.any { normalizeVoicePhrase(it) == wanted } }?.key
    }

    fun withPhrase(kind: VoiceCommandKind, phrase: String) =
        copy(custom = custom + (kind to (phrasesFor(kind) + phrase.trim()).distinct()))

    fun withoutPhrase(kind: VoiceCommandKind, phrase: String): VoiceCommandPhrases {
        val left = phrasesFor(kind) - phrase
        return copy(custom = if (left.isEmpty()) custom - kind else custom + (kind to left))
    }

    fun withBuiltIn(kind: VoiceCommandKind, on: Boolean) =
        copy(builtInOff = if (on) builtInOff - kind else builtInOff + kind)

    /** Every phrase of the person's own, for whisper's prompt (so it spells them as they were typed). */
    val all: List<String> get() = custom.values.flatten()

    companion object {
        val None = VoiceCommandPhrases()

        /** Why [phrase] cannot be added to [kind]; null when it can. */
        fun problemWith(phrase: String, kind: VoiceCommandKind, current: VoiceCommandPhrases): String? {
            val normalized = normalizeVoicePhrase(phrase)
            if (normalized.isEmpty()) return "Type a few words"
            if (normalized.split(' ').size > 6) return "Keep it to six words or fewer"
            current.ownerOf(phrase)?.let { owner ->
                return if (owner == kind) "Already one of this command's phrases" else "Already used by “${owner.label}”"
            }
            return null
        }
    }
}
