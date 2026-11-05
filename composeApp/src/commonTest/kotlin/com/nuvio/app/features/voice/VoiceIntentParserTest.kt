package com.nuvio.app.features.voice

import com.nuvio.app.features.voice.VoiceDestination as D
import com.nuvio.app.features.voice.VoiceSearchScope as S
import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceIntentParserTest {

    private fun assertIntent(expected: VoiceIntent, transcript: String) =
        assertEquals(expected, parseVoiceIntent(transcript), "for \"$transcript\"")

    @Test
    fun searchScopeForQuery() {
        assertIntent(VoiceIntent.Search(S.YouTube, "cat videos"), "Search YouTube for cat videos.")
        assertIntent(VoiceIntent.Search(S.Movies, "midsommar"), "search movies for midsommar")
        assertIntent(VoiceIntent.Search(S.Shows, "Severance"), "Search shows for Severance.")
        assertIntent(VoiceIntent.Search(S.Shows, "the bear"), "search tv shows for the bear")
        assertIntent(VoiceIntent.Search(S.Music, "Never Gonna Give You Up"), "Search music for Never Gonna Give You Up.")
        assertIntent(VoiceIntent.Search(S.YouTube, "lofi beats"), "search on youtube for lofi beats")
        assertIntent(VoiceIntent.Search(S.Movies, "Alien"), "Find the movies for Alien")
        assertIntent(VoiceIntent.Search(S.YouTube, "cats"), "look up you tube for cats")
        assertIntent(VoiceIntent.Search(S.Shows, "Dark"), "search show for Dark")
    }

    @Test
    fun theQueryKeepsItsCasingAndInnerPunctuation() {
        assertIntent(VoiceIntent.Search(S.Movies, "Spider-Man"), "Search movies for \"Spider-Man\".")
        assertIntent(VoiceIntent.Search(S.Shows, "Grey's Anatomy"), "Search shows for: Grey's Anatomy!")
        assertIntent(VoiceIntent.Search(S.All, "1917"), "Search for 1917.")
    }

    @Test
    fun scopeAtTheEnd() {
        assertIntent(VoiceIntent.Search(S.YouTube, "cat videos"), "Search for cat videos on YouTube.")
        assertIntent(VoiceIntent.Search(S.Music, "bohemian rhapsody"), "find bohemian rhapsody in music")
        assertIntent(VoiceIntent.Search(S.YouTube, "minecraft"), "search minecraft on the youtube")
        // "on"/"in" + a word that only looks like a scope inside a title is still a title.
        assertIntent(VoiceIntent.Search(S.All, "murder on the orient express"), "search for murder on the orient express")
        assertIntent(VoiceIntent.Search(S.All, "a movie"), "search for a movie")
    }

    @Test
    fun scopeBeforeTheVerb() {
        assertIntent(VoiceIntent.Search(S.YouTube, "cat videos"), "YouTube search cat videos")
        assertIntent(VoiceIntent.Search(S.Music, "abba"), "music search for abba")
    }

    @Test
    fun pluralScopeWithoutFor() {
        assertIntent(VoiceIntent.Search(S.Movies, "midsommar"), "search movies midsommar")
        assertIntent(VoiceIntent.Search(S.YouTube, "cat videos"), "search youtube cat videos")
        assertIntent(VoiceIntent.Search(S.Music, "daft punk"), "search music daft punk")
    }

    @Test
    fun titlesThatStartWithAScopeWordStayTitles() {
        assertIntent(VoiceIntent.Search(S.All, "show me the money"), "search show me the money")
        assertIntent(VoiceIntent.Search(S.All, "series of unfortunate events"), "search series of unfortunate events")
        assertIntent(VoiceIntent.Search(S.All, "movie 43"), "search movie 43")
        assertIntent(VoiceIntent.Search(S.All, "the office"), "search the office")
        assertIntent(VoiceIntent.Search(S.All, "in the mood for love"), "search in the mood for love")
        assertIntent(VoiceIntent.Search(S.All, "Apocalypse Now"), "Search for Apocalypse Now.")
    }

    @Test
    fun searchWithoutScope() {
        assertIntent(VoiceIntent.Search(S.All, "midsommar"), "search midsommar")
        assertIntent(VoiceIntent.Search(S.All, "the dark knight"), "Look for the dark knight")
        assertIntent(VoiceIntent.Search(S.All, "inception"), "find inception")
    }

    @Test
    fun searchWithNothingToSearchOpensTheBox() {
        assertIntent(VoiceIntent.OpenSearch(S.All), "Search.")
        assertIntent(VoiceIntent.OpenSearch(S.All), "search for")
        assertIntent(VoiceIntent.OpenSearch(S.YouTube), "Search YouTube.")
        assertIntent(VoiceIntent.OpenSearch(S.Music), "search music for")
        assertIntent(VoiceIntent.OpenSearch(S.Movies), "search movies")
    }

    @Test
    fun openDestinations() {
        assertIntent(VoiceIntent.Open(D.Settings), "Open settings.")
        assertIntent(VoiceIntent.Open(D.Library), "open library")
        assertIntent(VoiceIntent.Open(D.Library), "Open my library.")
        assertIntent(VoiceIntent.Open(D.Home), "Go home.")
        assertIntent(VoiceIntent.Open(D.Home), "take me to the home screen")
        assertIntent(VoiceIntent.Open(D.YouTube), "Open YouTube.")
        assertIntent(VoiceIntent.Open(D.YouTube), "go to the you tube tab")
        assertIntent(VoiceIntent.Open(D.Calendar), "show calendar")
        assertIntent(VoiceIntent.Open(D.Music), "switch to music")
        assertIntent(VoiceIntent.Open(D.LiveTv), "open live TV")
        assertIntent(VoiceIntent.Open(D.LiveTv), "show me the tv guide")
        assertIntent(VoiceIntent.Open(D.Discover), "open discover page")
        assertIntent(VoiceIntent.Open(D.Search), "open search")
        assertIntent(VoiceIntent.Open(D.Settings), "go to preferences")
        // The destination alone.
        assertIntent(VoiceIntent.Open(D.Settings), "Settings.")
        assertIntent(VoiceIntent.Open(D.Library), "Library")
    }

    @Test
    fun playbackAndBack() {
        assertIntent(VoiceIntent.Play, "Play.")
        assertIntent(VoiceIntent.Play, "resume")
        assertIntent(VoiceIntent.Pause, "Pause.")
        assertIntent(VoiceIntent.Pause, "stop")
        assertIntent(VoiceIntent.Next, "Next.")
        assertIntent(VoiceIntent.Next, "next episode")
        assertIntent(VoiceIntent.Next, "Skip!")
        assertIntent(VoiceIntent.GoBack, "Go back.")
        assertIntent(VoiceIntent.GoBack, "back")
    }

    @Test
    fun fillersAroundTheCommandAreIgnored() {
        assertIntent(VoiceIntent.Search(S.YouTube, "cat videos"), "Hey Nuvio, search YouTube for cat videos.")
        assertIntent(VoiceIntent.Open(D.Settings), "Can you open settings, please?")
        assertIntent(VoiceIntent.Pause, "Okay, pause.")
        assertIntent(VoiceIntent.Search(S.Movies, "midsommar"), "I want to search movies for midsommar")
        assertIntent(VoiceIntent.Play, "Um, play please.")
    }

    @Test
    fun silenceAndNoiseAreNothing() {
        assertIntent(VoiceIntent.Unknown(""), "")
        assertIntent(VoiceIntent.Unknown(""), "   ")
        assertIntent(VoiceIntent.Unknown(""), "[BLANK_AUDIO]")
        assertIntent(VoiceIntent.Unknown(""), "(music)")
        assertIntent(VoiceIntent.Unknown(""), "Thank you.")
        assertIntent(VoiceIntent.Unknown(""), " you")
        assertIntent(VoiceIntent.Unknown(""), "Thanks for watching!")
    }

    @Test
    fun anythingElseIsUnknownWithWhatWasHeard() {
        assertIntent(VoiceIntent.Unknown("Show me the money"), "Show me the money.")
        assertIntent(VoiceIntent.Unknown("Play Never Gonna Give You Up"), "Play Never Gonna Give You Up.")
        assertIntent(VoiceIntent.Unknown("Hey Arnold"), "Hey Arnold!")
        assertIntent(VoiceIntent.Unknown("Open the pod bay doors"), "Open the pod bay doors.")
        assertIntent(VoiceIntent.Unknown("Go back home now"), "Go back home now.")
    }

    @Test
    fun nearMissesAreCorrected() {
        assertIntent(VoiceIntent.Speed(2f), "Playback speed tour.")
        assertIntent(VoiceIntent.Speed(2f), "playback speed to")
        assertIntent(VoiceIntent.Speed(4f), "set speed to for")
        assertIntent(VoiceIntent.Pause, "Paws.")
        assertIntent(VoiceIntent.Pause, "Pauze.")
        assertIntent(VoiceIntent.Play, "Plate.")
        assertIntent(VoiceIntent.Next, "Necks episode.")
        assertIntent(VoiceIntent.Seek(30_000), "skip forward tirty seconds")
        assertIntent(VoiceIntent.Open(D.Settings), "open setings")
        assertIntent(VoiceIntent.Open(D.Library), "open libary")
    }

    @Test
    fun aCorrectionNeverChangesTheTitle() {
        assertIntent(VoiceIntent.Search(S.Movies, "Midsomer"), "Serch movies for Midsomer.")
        assertIntent(VoiceIntent.Search(S.Movies, "Paws"), "Serch moovies for Paws")
        // Already a command as said: nothing is corrected.
        assertIntent(VoiceIntent.Search(S.All, "tour de france"), "search for tour de france")
    }

    @Test
    fun speechThatIsNotNearACommandStaysUnknown() {
        assertIntent(VoiceIntent.Unknown("What a lovely day"), "What a lovely day.")
        assertIntent(VoiceIntent.Unknown("pause the music and dance"), "pause the music and dance")
    }
}
