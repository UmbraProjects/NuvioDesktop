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
)

sealed interface VoicePlaybackCommand {
    data object Play : VoicePlaybackCommand
    data object Pause : VoicePlaybackCommand
    data object Next : VoicePlaybackCommand

    /** [deltaMs] forward (positive) or back. */
    data class Seek(val deltaMs: Long) : VoicePlaybackCommand

    data class Speed(val rate: Float) : VoicePlaybackCommand
}

/** The speeds the player offers (its own clamp, PlayerScreenRuntimeUi "setPlaybackSpeed"). */
val VOICE_SPEED_RANGE = 0.5f..4f

/** The video player while it is on screen, for spoken play / pause / next. */
interface VoicePlaybackTarget {
    /** Carries out [command]; a message for the overlay when it cannot (no next episode), else null. */
    fun control(command: VoicePlaybackCommand): String?

    /** Leaves the player the way its own Back button does. */
    fun goBack()
}

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
        return when (intent) {
            VoiceIntent.Play -> VoiceAction.Player(VoicePlaybackCommand.Play)
            VoiceIntent.Pause -> VoiceAction.Player(VoicePlaybackCommand.Pause)
            VoiceIntent.Next -> VoiceAction.Player(VoicePlaybackCommand.Next)
            is VoiceIntent.Seek -> VoiceAction.Player(VoicePlaybackCommand.Seek(intent.deltaMs))
            is VoiceIntent.Speed -> if (intent.rate in VOICE_SPEED_RANGE) {
                VoiceAction.Player(VoicePlaybackCommand.Speed(intent.rate))
            } else {
                VoiceAction.Refuse("Playback speed goes from 0.5\u00D7 to 4\u00D7")
            }
            VoiceIntent.GoBack -> VoiceAction.Back
            is VoiceIntent.Unknown -> VoiceAction.Refuse("That isn’t a command I know")
            else -> VoiceAction.Refuse("Say “go back” to leave the player first")
        }
    }
    return when (intent) {
        is VoiceIntent.Search -> search(intent.scope, intent.query, context)
        is VoiceIntent.OpenSearch -> tabRefusal(scopeTab(intent.scope), context) ?: VoiceAction.FocusSearch(intent.scope)
        is VoiceIntent.Open -> when (intent.destination) {
            VoiceDestination.Calendar -> VoiceAction.OpenCalendar
            else -> tabRefusal(intent.destination, context) ?: VoiceAction.OpenTab(intent.destination)
        }
        VoiceIntent.Play, VoiceIntent.Pause, VoiceIntent.Next -> {
            val command = when (intent) {
                VoiceIntent.Play -> VoicePlaybackCommand.Play
                VoiceIntent.Pause -> VoicePlaybackCommand.Pause
                else -> VoicePlaybackCommand.Next
            }
            if (context.musicLoaded) VoiceAction.Music(command) else VoiceAction.Refuse("Nothing is playing")
        }
        is VoiceIntent.Seek ->
            if (context.musicLoaded) VoiceAction.Music(VoicePlaybackCommand.Seek(intent.deltaMs))
            else VoiceAction.Refuse("Nothing is playing")
        is VoiceIntent.Speed ->
            VoiceAction.Refuse(if (context.musicLoaded) "Music plays at normal speed" else "Nothing is playing")
        VoiceIntent.GoBack -> VoiceAction.Back
        is VoiceIntent.Unknown -> VoiceAction.Refuse("That isn’t a command I know")
    }
}

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
