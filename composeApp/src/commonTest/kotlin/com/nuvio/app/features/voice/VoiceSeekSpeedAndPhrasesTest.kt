package com.nuvio.app.features.voice

import com.nuvio.app.features.voice.VoiceSearchScope as S
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class VoiceSeekSpeedAndPhrasesTest {

    private fun assertIntent(expected: VoiceIntent, transcript: String, phrases: VoiceCommandPhrases = VoiceCommandPhrases.None) =
        assertEquals(expected, parseVoiceIntent(transcript, phrases), "for \"$transcript\"")

    @Test
    fun seekForwardAndBackByAnAmount() {
        assertIntent(VoiceIntent.Seek(30_000), "Seek forward 30 seconds.")
        assertIntent(VoiceIntent.Seek(30_000), "skip ahead thirty seconds")
        assertIntent(VoiceIntent.Seek(60_000), "Fast forward a minute.")
        assertIntent(VoiceIntent.Seek(90_000), "jump forward 1 minute 30 seconds")
        assertIntent(VoiceIntent.Seek(45_000), "forward forty five seconds")
        assertIntent(VoiceIntent.Seek(30_000), "Skip 30 seconds.")
        assertIntent(VoiceIntent.Seek(-10_000), "Seek backwards 10 seconds.")
        assertIntent(VoiceIntent.Seek(-30_000), "Go back 30 seconds.")
        assertIntent(VoiceIntent.Seek(-30_000), "rewind half a minute")
        assertIntent(VoiceIntent.Seek(-120_000), "back two minutes")
        assertIntent(VoiceIntent.Seek(-15_000), "Seek back by 15-seconds.")
        assertIntent(VoiceIntent.Seek(20_000), "skip forward 20")
    }

    @Test
    fun aSeekWordAloneUsesTenSecondsButBareCommandsStayThemselves() {
        assertIntent(VoiceIntent.Seek(DEFAULT_SEEK_MS), "Skip forward.")
        assertIntent(VoiceIntent.Seek(-DEFAULT_SEEK_MS), "Rewind.")
        assertIntent(VoiceIntent.Next, "Skip.")
        assertIntent(VoiceIntent.GoBack, "Go back.")
        assertIntent(VoiceIntent.GoBack, "back")
        // Not an amount: not a seek ("skip intro" is the skip prompt's command, VoiceMoreCommandsTest).
        assertIntent(VoiceIntent.SkipSegment, "skip intro")
        assertIntent(VoiceIntent.Unknown("go back a bit"), "go back a bit")
    }

    @Test
    fun playbackSpeed() {
        assertIntent(VoiceIntent.Speed(1.5f), "Playback speed 1.5.")
        assertIntent(VoiceIntent.Speed(1.5f), "set the playback speed to 1.5x")
        assertIntent(VoiceIntent.Speed(1.25f), "speed one point two five")
        assertIntent(VoiceIntent.Speed(1.5f), "speed one and a half")
        assertIntent(VoiceIntent.Speed(2f), "Speed 2 times.")
        assertIntent(VoiceIntent.Speed(1.5f), "1.5x speed")
        assertIntent(VoiceIntent.Speed(1.75f), "play at 1.75")
        assertIntent(VoiceIntent.Speed(1f), "Normal speed.")
        assertIntent(VoiceIntent.Speed(2f), "double speed")
        assertIntent(VoiceIntent.Speed(0.5f), "half speed")
        // "speed" with no number is not a speed (and not anything else either).
        assertIntent(VoiceIntent.Unknown("speed up"), "speed up")
    }

    @Test
    fun numbersAndDurations() {
        assertEquals(35.0 to 2, parseNumber(listOf("thirty", "five")))
        assertEquals(1.5 to 1, parseNumber(listOf("1.5x")))
        assertEquals(2.5 to 4, parseNumber(listOf("two", "and", "a", "half")))
        assertEquals(1.5 to 4, parseNumber(listOf("1", "and", "a", "half")))
        assertNull(parseNumber(listOf("speed")))
        assertEquals(150_000L, parseDurationMs(listOf("two", "and", "a", "half", "minutes")))
        assertEquals(90_000L, parseDurationMs(listOf("one", "minute", "thirty")))
        assertNull(parseDurationMs(listOf("a", "bit")))
    }

    @Test
    fun theirOwnPhrasesComeFirstAndTakeWhatTheActionTakes() {
        val own = VoiceCommandPhrases.None
            .withPhrase(VoiceCommandKind.SearchYouTube, "Find on tube")
            .withPhrase(VoiceCommandKind.Pause, "hold it")
            .withPhrase(VoiceCommandKind.SeekForward, "jump on")
            .withPhrase(VoiceCommandKind.Speed, "go at")
            .withPhrase(VoiceCommandKind.OpenSettings, "the cogs")
        assertIntent(VoiceIntent.Search(S.YouTube, "cat videos"), "Find on tube cat videos.", own)
        assertIntent(VoiceIntent.Search(S.YouTube, "cats"), "find on tube for cats", own)
        assertIntent(VoiceIntent.OpenSearch(S.YouTube), "Find on tube.", own)
        assertIntent(VoiceIntent.Pause, "Hold it!", own)
        assertIntent(VoiceIntent.Seek(30_000), "jump on 30 seconds", own)
        assertIntent(VoiceIntent.Seek(DEFAULT_SEEK_MS), "jump on", own)
        assertIntent(VoiceIntent.Speed(1.5f), "go at 1.5", own)
        assertIntent(VoiceIntent.Open(VoiceDestination.Settings), "The cogs.", own)
        // Words after a phrase that takes none: not that command.
        assertIntent(VoiceIntent.Unknown("hold it there"), "hold it there", own)
        // The built-in phrases still work alongside.
        assertIntent(VoiceIntent.Pause, "pause", own)
        assertIntent(VoiceIntent.Search(S.YouTube, "cats"), "search youtube for cats", own)
    }

    @Test
    fun turningTheBuiltInPhrasesOffRebindsTheAction() {
        val rebound = VoiceCommandPhrases.None
            .withPhrase(VoiceCommandKind.Pause, "hold it")
            .withBuiltIn(VoiceCommandKind.Pause, on = false)
        assertIntent(VoiceIntent.Pause, "hold it", rebound)
        assertIntent(VoiceIntent.Unknown("pause"), "pause", rebound)
        assertIntent(VoiceIntent.Unknown("stop"), "stop", rebound)
        // Only that action.
        assertIntent(VoiceIntent.Play, "play", rebound)
        // A seek has two actions; turning off one leaves the other.
        val noRewind = VoiceCommandPhrases.None.withBuiltIn(VoiceCommandKind.SeekBack, on = false)
        assertIntent(VoiceIntent.Unknown("rewind 10 seconds"), "rewind 10 seconds", noRewind)
        assertIntent(VoiceIntent.Seek(10_000), "skip forward 10 seconds", noRewind)
    }

    @Test
    fun aPhraseCannotBeUsedTwice() {
        val own = VoiceCommandPhrases.None.withPhrase(VoiceCommandKind.Pause, "hold it")
        assertNotNull(VoiceCommandPhrases.problemWith("Hold it.", VoiceCommandKind.Play, own))
        assertNotNull(VoiceCommandPhrases.problemWith("hold it", VoiceCommandKind.Pause, own))
        assertNotNull(VoiceCommandPhrases.problemWith("  ", VoiceCommandKind.Play, own))
        assertNull(VoiceCommandPhrases.problemWith("hold up", VoiceCommandKind.Pause, own))
        assertEquals(VoiceCommandPhrases.None, own.withoutPhrase(VoiceCommandKind.Pause, "hold it"))
    }

    @Test
    fun everyIntentHasItsAction() {
        listOf(
            VoiceIntent.Search(S.Movies, "x") to VoiceCommandKind.SearchMovies,
            VoiceIntent.OpenSearch(S.Music) to VoiceCommandKind.SearchMusic,
            VoiceIntent.Open(VoiceDestination.Calendar) to VoiceCommandKind.OpenCalendar,
            VoiceIntent.Seek(-5) to VoiceCommandKind.SeekBack,
            VoiceIntent.Seek(5) to VoiceCommandKind.SeekForward,
            VoiceIntent.Speed(2f) to VoiceCommandKind.Speed,
        ).forEach { (intent, kind) -> assertEquals(kind, VoiceCommandKind.of(intent)) }
        assertNull(VoiceCommandKind.of(VoiceIntent.Unknown("x")))
    }

    @Test
    fun seekAndSpeedAreRoutedToWhatIsPlaying() {
        val player = VoiceRouteContext(
            playerActive = true, musicLoaded = false, youTubeTabEnabled = true,
            musicTabEnabled = true, liveTvShown = true, discoverShown = true,
        )
        assertEquals(VoiceAction.Player(VoicePlaybackCommand.Seek(30_000)), routeVoiceIntent(VoiceIntent.Seek(30_000), player))
        assertEquals(VoiceAction.Player(VoicePlaybackCommand.Speed(1.5f)), routeVoiceIntent(VoiceIntent.Speed(1.5f), player))
        assertIs<VoiceAction.Refuse>(routeVoiceIntent(VoiceIntent.Speed(8f), player))
        val music = player.copy(playerActive = false, musicLoaded = true)
        assertEquals(VoiceAction.Music(VoicePlaybackCommand.Seek(-10_000)), routeVoiceIntent(VoiceIntent.Seek(-10_000), music))
        assertIs<VoiceAction.Refuse>(routeVoiceIntent(VoiceIntent.Speed(1.5f), music))
        assertIs<VoiceAction.Refuse>(routeVoiceIntent(VoiceIntent.Seek(5_000), music.copy(musicLoaded = false)))
    }
}
