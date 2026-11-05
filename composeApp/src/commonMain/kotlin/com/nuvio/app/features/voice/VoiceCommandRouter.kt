package com.nuvio.app.features.voice

/*
 * fork:voice-control - phase 4: what a understood command does, given where the app is. Pure: App.kt
 * gathers [VoiceRouteContext] and carries out the [VoiceAction]; this decides, and is tested.
 */

/** The parts of the app's state a command depends on. */
data class VoiceRouteContext(
    /** The video player is on screen. */
    val playerActive: Boolean,
    /** The music player has something loaded (playing or paused). */
    val musicLoaded: Boolean,
    val youTubeTabEnabled: Boolean,
    val musicTabEnabled: Boolean,
    /** Live TV is in the navigation (an IPTV playlist exists and the tab is shown). */
    val liveTvShown: Boolean,
    val discoverShown: Boolean,
    /** A title's details page is up, with a Play button voice can press ([VoiceScreenTargets.detailsPlay]). */
    val detailsShown: Boolean = false,
    /** The source list is up ([VoiceScreenTargets.sources]): numbers and "play" pick from it. */
    val sourcesShown: Boolean = false,
)

sealed interface VoicePlaybackCommand {
    data object Play : VoicePlaybackCommand
    data object Pause : VoicePlaybackCommand
    data object Next : VoicePlaybackCommand

    /** [deltaMs] forward (positive) or back. */
    data class Seek(val deltaMs: Long) : VoicePlaybackCommand

    data class Speed(val rate: Float) : VoicePlaybackCommand

    /** Accept whatever skip prompt is up (intro, recap, credits). */
    data object SkipSegment : VoicePlaybackCommand
    data object Previous : VoicePlaybackCommand
    data class SeekTo(val positionMs: Long) : VoicePlaybackCommand
    data class VolumeChange(val percent: Int) : VoicePlaybackCommand
    data class VolumeSet(val percent: Int) : VoicePlaybackCommand
    data class Mute(val muted: Boolean) : VoicePlaybackCommand
    data object NextSubtitles : VoicePlaybackCommand
    data object NextAudio : VoicePlaybackCommand
}

/** The speeds the player offers (its own clamp, PlayerScreenRuntimeUi "setPlaybackSpeed"). */
val VOICE_SPEED_RANGE = 0.5f..4f

/** The video player while it is on screen, for spoken play / pause / next. */
interface VoicePlaybackTarget {
    /** Carries out [command]; a message for the overlay when it cannot (no next episode), else null. */
    fun control(command: VoicePlaybackCommand): String?

    /** Leaves the player the way its own Back button does. */
    fun goBack()

    /** Ends playback outright; unlike [goBack], a YouTube video does not go to the mini player. */
    fun close() = goBack()
}

/**
 * What is on screen that voice can act on outside the player. Each is set by the screen while it is
 * shown and cleared when it goes (checked by identity, as [VoicePlayback.player] is).
 */
object VoiceScreenTargets {
    /** The details page's Play button: the same as clicking it (the source list, or straight to playback). */
    @kotlin.concurrent.Volatile
    var detailsPlay: (() -> Unit)? = null

    /** The source list: plays the [number]th (from 1) as listed; a message when there is no such one. */
    @kotlin.concurrent.Volatile
    var sources: ((number: Int) -> String?)? = null

    /** Presses an arrow key or Enter [count] times on whatever has focus; false when the app is not focused. */
    @kotlin.concurrent.Volatile
    var pressKey: ((key: VoiceKey, count: Int) -> Boolean)? = null
}

enum class VoiceKey { Up, Down, Left, Right, Enter }

object VoicePlayback {
    /** Set by the player surface while it is up (PlayerEngine.desktop.kt). */
    @kotlin.concurrent.Volatile
    var player: VoicePlaybackTarget? = null
}

sealed interface VoiceAction {
    /** Switch to a root tab (never [VoiceDestination.Calendar], which is a route). */
    data class OpenTab(val destination: VoiceDestination) : VoiceAction

    data object OpenCalendar : VoiceAction

    /**
     * The main (addon) search for [query]. [confidentQuery] is what the single-match lookup is asked,
     * which carries the type ("Midsommar movie") so a film and a show of one name are told apart.
     */
    data class SearchVideos(val query: String, val confidentQuery: String) : VoiceAction

    data class SearchYouTube(val query: String) : VoiceAction
    data class SearchMusic(val query: String) : VoiceAction

    /** Open a search box, empty and focused. */
    data class FocusSearch(val scope: VoiceSearchScope) : VoiceAction

    data class Player(val command: VoicePlaybackCommand) : VoiceAction
    data class Music(val command: VoicePlaybackCommand) : VoiceAction

    data object Back : VoiceAction

    /** "stop playback": the player goes, whatever Back would do there. */
    data object ClosePlayer : VoiceAction

    /** Press the details page's Play button. */
    data object PlayTitle : VoiceAction

    /** Play the [number]th source in the source list. */
    data class PickSource(val number: Int) : VoiceAction

    /** Press [key] [count] times, as the arrow keys / Enter would. */
    data class PressKey(val key: VoiceKey, val count: Int) : VoiceAction

    /** Nothing to do; [message] says why, in the overlay. */
    data class Refuse(val message: String) : VoiceAction
}

/**
 * A command said into a search box's mic ([scope]): plain words are a search there ("cat videos" in
 * the YouTube box), and so is a search that named no scope. Any other command still means what it says.
 */
fun scopeVoiceIntent(intent: VoiceIntent, scope: VoiceSearchScope?): VoiceIntent = when {
    scope == null -> intent
    intent is VoiceIntent.Unknown && intent.text.isNotBlank() -> VoiceIntent.Search(scope, intent.text)
    intent is VoiceIntent.Search && intent.scope == VoiceSearchScope.All -> VoiceIntent.Search(scope, intent.query)
    intent is VoiceIntent.OpenSearch && intent.scope == VoiceSearchScope.All -> VoiceIntent.OpenSearch(scope)
    else -> intent
}

fun routeVoiceIntent(intent: VoiceIntent, context: VoiceRouteContext): VoiceAction {
    // While a video plays, only what controls it (and Back, which leaves it) makes sense: a search or
    // a tab would quietly end the film. Leaving is one "go back" away.
    if (context.playerActive) {
        playerCommand(intent)?.let { return it }
        return when (intent) {
            is VoiceIntent.Speed -> VoiceAction.Refuse("Playback speed goes from 0.5\u00D7 to 4\u00D7")
            VoiceIntent.GoBack -> VoiceAction.Back
            VoiceIntent.StopPlayback -> VoiceAction.ClosePlayer
            is VoiceIntent.Move, VoiceIntent.Select, is VoiceIntent.Choose ->
                VoiceAction.Refuse("In the player, say “skip forward”, “volume up” or “next episode”")
            is VoiceIntent.Unknown -> VoiceAction.Refuse("That isn’t a command I know")
            else -> VoiceAction.Refuse("Say “go back” to leave the player first")
        }
    }
    // The source list is on top of whatever opened it, so it answers first: "play" takes the top
    // source, a number picks one.
    if (context.sourcesShown) {
        when (intent) {
            VoiceIntent.Play -> return VoiceAction.PickSource(1)
            is VoiceIntent.Choose -> return VoiceAction.PickSource(intent.number)
            else -> Unit
        }
    }
    return when (intent) {
        is VoiceIntent.Search -> search(intent.scope, intent.query, context)
        is VoiceIntent.OpenSearch -> tabRefusal(scopeTab(intent.scope), context) ?: VoiceAction.FocusSearch(intent.scope)
        is VoiceIntent.Open -> when (intent.destination) {
            VoiceDestination.Calendar -> VoiceAction.OpenCalendar
            else -> tabRefusal(intent.destination, context) ?: VoiceAction.OpenTab(intent.destination)
        }
        // On a title's page, "play" means that title, even with music in the background.
        VoiceIntent.Play -> when {
            context.detailsShown -> VoiceAction.PlayTitle
            context.musicLoaded -> VoiceAction.Music(VoicePlaybackCommand.Play)
            else -> VoiceAction.Refuse("Nothing is playing")
        }
        VoiceIntent.Pause, VoiceIntent.StopPlayback -> music(VoicePlaybackCommand.Pause, context)
        VoiceIntent.Next -> music(VoicePlaybackCommand.Next, context)
        VoiceIntent.PreviousEpisode -> music(VoicePlaybackCommand.Previous, context)
        is VoiceIntent.Seek -> music(VoicePlaybackCommand.Seek(intent.deltaMs), context)
        is VoiceIntent.SeekTo -> music(VoicePlaybackCommand.SeekTo(intent.positionMs), context)
        is VoiceIntent.VolumeChange -> music(VoicePlaybackCommand.VolumeChange(intent.percent), context)
        is VoiceIntent.VolumeSet -> music(VoicePlaybackCommand.VolumeSet(intent.percent), context)
        is VoiceIntent.Speed ->
            VoiceAction.Refuse(if (context.musicLoaded) "Music plays at normal speed" else "Nothing is playing")
        VoiceIntent.SkipSegment, VoiceIntent.NextSubtitles, VoiceIntent.NextAudio, is VoiceIntent.Mute ->
            VoiceAction.Refuse("That works while a video is playing")
        is VoiceIntent.Choose ->
            VoiceAction.Refuse("Numbers pick from the source list: say “play” on a title first")
        is VoiceIntent.Move -> VoiceAction.PressKey(
            when (intent.direction) {
                VoiceDirection.Up -> VoiceKey.Up
                VoiceDirection.Down -> VoiceKey.Down
                VoiceDirection.Left -> VoiceKey.Left
                VoiceDirection.Right -> VoiceKey.Right
            },
            intent.count,
        )
        VoiceIntent.Select -> VoiceAction.PressKey(VoiceKey.Enter, 1)
        VoiceIntent.GoBack -> VoiceAction.Back
        // App.kt routes each step of a chain on its own.
        is VoiceIntent.Chain -> VoiceAction.Refuse("One command at a time")
        is VoiceIntent.Unknown -> VoiceAction.Refuse("That isn’t a command I know")
    }
}

/** [intent] as a command for the video player; null for what is not one. */
private fun playerCommand(intent: VoiceIntent): VoiceAction.Player? {
    val command = when (intent) {
        VoiceIntent.Play -> VoicePlaybackCommand.Play
        VoiceIntent.Pause -> VoicePlaybackCommand.Pause
        VoiceIntent.Next -> VoicePlaybackCommand.Next
        VoiceIntent.PreviousEpisode -> VoicePlaybackCommand.Previous
        VoiceIntent.SkipSegment -> VoicePlaybackCommand.SkipSegment
        is VoiceIntent.Seek -> VoicePlaybackCommand.Seek(intent.deltaMs)
        is VoiceIntent.SeekTo -> VoicePlaybackCommand.SeekTo(intent.positionMs)
        is VoiceIntent.Speed -> if (intent.rate in VOICE_SPEED_RANGE) VoicePlaybackCommand.Speed(intent.rate) else null
        is VoiceIntent.VolumeChange -> VoicePlaybackCommand.VolumeChange(intent.percent)
        is VoiceIntent.VolumeSet -> VoicePlaybackCommand.VolumeSet(intent.percent)
        is VoiceIntent.Mute -> VoicePlaybackCommand.Mute(intent.muted)
        VoiceIntent.NextSubtitles -> VoicePlaybackCommand.NextSubtitles
        VoiceIntent.NextAudio -> VoicePlaybackCommand.NextAudio
        else -> null
    } ?: return null
    return VoiceAction.Player(command)
}

private fun music(command: VoicePlaybackCommand, context: VoiceRouteContext): VoiceAction =
    if (context.musicLoaded) VoiceAction.Music(command) else VoiceAction.Refuse("Nothing is playing")

private fun search(scope: VoiceSearchScope, query: String, context: VoiceRouteContext): VoiceAction {
    tabRefusal(scopeTab(scope), context)?.let { return it }
    return when (scope) {
        VoiceSearchScope.YouTube -> VoiceAction.SearchYouTube(query)
        VoiceSearchScope.Music -> VoiceAction.SearchMusic(query)
        // ConfidentSearch reads a trailing type word ("carrie movie", "carrie tv").
        VoiceSearchScope.Movies -> VoiceAction.SearchVideos(query, "$query movie")
        VoiceSearchScope.Shows -> VoiceAction.SearchVideos(query, "$query tv")
        VoiceSearchScope.All -> VoiceAction.SearchVideos(query, query)
    }
}

private fun scopeTab(scope: VoiceSearchScope): VoiceDestination = when (scope) {
    VoiceSearchScope.YouTube -> VoiceDestination.YouTube
    VoiceSearchScope.Music -> VoiceDestination.Music
    else -> VoiceDestination.Search
}

/** Why [destination] cannot be opened right now; null when it can. */
private fun tabRefusal(destination: VoiceDestination, context: VoiceRouteContext): VoiceAction.Refuse? = when {
    destination == VoiceDestination.YouTube && !context.youTubeTabEnabled ->
        VoiceAction.Refuse("The YouTube tab is off. Turn it on in Settings > YouTube account.")
    destination == VoiceDestination.Music && !context.musicTabEnabled ->
        VoiceAction.Refuse("The Music tab is off. Turn it on in Settings > Music.")
    destination == VoiceDestination.LiveTv && !context.liveTvShown ->
        VoiceAction.Refuse("Live TV needs an IPTV playlist first (Settings > Live TV).")
    destination == VoiceDestination.Discover && !context.discoverShown ->
        VoiceAction.Refuse("Discover is hidden. Show it in Settings > Appearance.")
    else -> null
}
