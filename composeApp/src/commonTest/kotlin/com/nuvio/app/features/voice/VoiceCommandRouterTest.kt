package com.nuvio.app.features.voice

import com.nuvio.app.features.voice.VoiceDestination as D
import com.nuvio.app.features.voice.VoicePlaybackCommand as P
import com.nuvio.app.features.voice.VoiceSearchScope as S
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class VoiceCommandRouterTest {
    private val browsing = VoiceRouteContext(
        playerActive = false,
        musicLoaded = false,
        youTubeTabEnabled = true,
        musicTabEnabled = true,
        liveTvShown = true,
        discoverShown = true,
    )
    private val inPlayer = browsing.copy(playerActive = true)

    /** From the spoken words, as the app sees them end to end. */
    private fun route(words: String, context: VoiceRouteContext = browsing) = routeVoiceIntent(parseVoiceIntent(words), context)

    @Test
    fun theFiveExampleCommands() {
        assertEquals(VoiceAction.SearchYouTube("cat videos"), route("Search YouTube for cat videos."))
        assertEquals(VoiceAction.SearchVideos("midsommar", "midsommar movie"), route("search movies for midsommar"))
        assertEquals(VoiceAction.SearchMusic("never gonna give you up"), route("search music for never gonna give you up"))
        assertEquals(VoiceAction.OpenTab(D.Settings), route("Open settings."))
        assertEquals(VoiceAction.OpenTab(D.Library), route("open library"))
    }

    @Test
    fun showsAndEverythingGoToTheMainSearch() {
        assertEquals(VoiceAction.SearchVideos("Severance", "Severance tv"), route("Search shows for Severance."))
        assertEquals(VoiceAction.SearchVideos("Inception", "Inception"), route("Search for Inception."))
        assertEquals(VoiceAction.FocusSearch(S.All), route("Search."))
        assertEquals(VoiceAction.FocusSearch(S.YouTube), route("Search YouTube."))
    }

    @Test
    fun theCalendarIsARouteNotATab() {
        assertEquals(VoiceAction.OpenCalendar, route("open calendar"))
        assertEquals(VoiceAction.OpenTab(D.Home), route("go home"))
    }

    @Test
    fun aTabThatIsOffSaysWhereToTurnItOn() {
        val off = browsing.copy(youTubeTabEnabled = false, musicTabEnabled = false, liveTvShown = false, discoverShown = false)
        listOf(
            "search youtube for cats" to "YouTube",
            "open youtube" to "YouTube",
            "search music for abba" to "Music",
            "open live tv" to "Live TV",
            "open discover" to "Discover",
        ).forEach { (words, name) ->
            val action = assertIs<VoiceAction.Refuse>(route(words, off), words)
            assertEquals(true, name in action.message, action.message)
        }
        // The main search does not depend on any of them.
        assertEquals(VoiceAction.SearchVideos("alien", "alien movie"), route("search movies for alien", off))
    }

    @Test
    fun playbackOutsideThePlayerIsTheMusicPlayer() {
        val music = browsing.copy(musicLoaded = true)
        assertEquals(VoiceAction.Music(P.Pause), route("pause", music))
        assertEquals(VoiceAction.Music(P.Play), route("resume", music))
        assertEquals(VoiceAction.Music(P.Next), route("next song", music))
        assertIs<VoiceAction.Refuse>(route("pause"))
    }

    @Test
    fun inThePlayerOnlyPlaybackAndBackAct() {
        assertEquals(VoiceAction.Player(P.Pause), route("Pause.", inPlayer))
        assertEquals(VoiceAction.Player(P.Play), route("play", inPlayer))
        assertEquals(VoiceAction.Player(P.Next), route("next episode", inPlayer))
        assertEquals(VoiceAction.Back, route("go back", inPlayer))
        // The film is not stopped by a stray search; the message says how to leave.
        val refused = assertIs<VoiceAction.Refuse>(route("search youtube for cats", inPlayer))
        assertEquals(true, "go back" in refused.message)
        assertIs<VoiceAction.Refuse>(route("open settings", inPlayer))
        // Even with music loaded underneath, playback words are for the film.
        assertEquals(VoiceAction.Player(P.Pause), route("pause", inPlayer.copy(musicLoaded = true)))
    }

    @Test
    fun aSearchBoxMicSearchesThereButKeepsCommands() {
        fun boxed(words: String, scope: S) = routeVoiceIntent(scopeVoiceIntent(parseVoiceIntent(words), scope), browsing)
        assertEquals(VoiceAction.SearchYouTube("Cat videos"), boxed("Cat videos.", S.YouTube))
        assertEquals(VoiceAction.SearchYouTube("lofi beats"), boxed("search for lofi beats", S.YouTube))
        assertEquals(VoiceAction.SearchVideos("Midsommar", "Midsommar"), boxed("Midsommar", S.All))
        // A named scope or another command is left as said.
        assertEquals(VoiceAction.SearchMusic("abba"), boxed("search music for abba", S.YouTube))
        assertEquals(VoiceAction.OpenTab(D.Settings), boxed("open settings", S.YouTube))
        // Nothing heard is still nothing.
        assertEquals(VoiceIntent.Unknown(""), scopeVoiceIntent(parseVoiceIntent("Thank you."), S.YouTube))
    }

    @Test
    fun backAndNonsense() {
        assertEquals(VoiceAction.Back, route("back"))
        assertIs<VoiceAction.Refuse>(route("open the pod bay doors"))
    }
}
