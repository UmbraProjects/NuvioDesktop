package com.nuvio.app.features.voice

import com.nuvio.app.features.voice.VoiceDirection as Dir
import com.nuvio.app.features.voice.VoicePlaybackCommand as P
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The commands past search / open / play: player extras, picking a source, moving focus. */
class VoiceMoreCommandsTest {
    private val browsing = VoiceRouteContext(
        playerActive = false,
        musicLoaded = false,
        youTubeTabEnabled = true,
        musicTabEnabled = true,
        liveTvShown = true,
        discoverShown = true,
    )
    private val inPlayer = browsing.copy(playerActive = true)
    private val onDetails = browsing.copy(detailsShown = true)
    private val inSources = onDetails.copy(sourcesShown = true)

    private fun assertIntent(expected: VoiceIntent, transcript: String) =
        assertEquals(expected, parseVoiceIntent(transcript), "for \"$transcript\"")

    private fun route(words: String, context: VoiceRouteContext) = routeVoiceIntent(parseVoiceIntent(words), context)

    @Test
    fun playerExtras() {
        assertIntent(VoiceIntent.SkipSegment, "Skip the intro.")
        assertIntent(VoiceIntent.SkipSegment, "skip recap")
        assertIntent(VoiceIntent.SkipSegment, "Skip the credits")
        assertIntent(VoiceIntent.StopPlayback, "Stop playback.")
        assertIntent(VoiceIntent.StopPlayback, "close the player")
        assertIntent(VoiceIntent.PreviousEpisode, "Previous episode.")
        assertIntent(VoiceIntent.SeekTo(0), "Start over.")
        assertIntent(VoiceIntent.SeekTo(20 * 60_000L), "Go to 20 minutes.")
        assertIntent(VoiceIntent.SeekTo(20 * 60_000L), "jump to minute 20")
        assertIntent(VoiceIntent.SeekTo((3600 + 20 * 60) * 1000L), "go to 1 hour 20")
        assertIntent(VoiceIntent.SeekTo(90_000L), "skip to 1 minute 30 seconds")
        assertIntent(VoiceIntent.NextSubtitles, "Change subtitles.")
        assertIntent(VoiceIntent.NextAudio, "change language")
        // "stop" alone still pauses, as before.
        assertIntent(VoiceIntent.Pause, "Stop.")
    }

    @Test
    fun volume() {
        assertIntent(VoiceIntent.VolumeChange(VOLUME_STEP_PERCENT), "Volume up.")
        assertIntent(VoiceIntent.VolumeChange(-VOLUME_STEP_PERCENT), "Turn it down.")
        assertIntent(VoiceIntent.VolumeChange(20), "volume up by 20")
        assertIntent(VoiceIntent.VolumeChange(-5), "turn the volume down 5 percent")
        assertIntent(VoiceIntent.VolumeSet(40), "Volume 40.")
        assertIntent(VoiceIntent.VolumeSet(70), "set the volume to seventy percent")
        assertIntent(VoiceIntent.VolumeSet(100), "volume 130")
        assertIntent(VoiceIntent.Mute(true), "Mute.")
        assertIntent(VoiceIntent.Mute(false), "unmute")
    }

    @Test
    fun pickingByNumber() {
        assertIntent(VoiceIntent.Choose(3), "Source 3.")
        assertIntent(VoiceIntent.Choose(3), "play source three")
        assertIntent(VoiceIntent.Choose(2), "The second one.")
        assertIntent(VoiceIntent.Choose(1), "play the first one")
        assertIntent(VoiceIntent.Choose(4), "number 4")
        assertIntent(VoiceIntent.Choose(5), "select 5")
        assertIntent(VoiceIntent.Choose(2), "pick the 2nd")
        // A number with nothing around it is too likely a misheard word.
        assertIntent(VoiceIntent.Unknown("two"), "two")
    }

    @Test
    fun movingFocus() {
        assertIntent(VoiceIntent.Move(Dir.Down, 1), "Down.")
        assertIntent(VoiceIntent.Move(Dir.Right, 3), "go right 3")
        assertIntent(VoiceIntent.Move(Dir.Down, 2), "move down two rows")
        assertIntent(VoiceIntent.Move(Dir.Up, 1), "previous row")
        assertIntent(VoiceIntent.Move(Dir.Down, 1), "next row")
        assertIntent(VoiceIntent.Move(Dir.Left, 1), "left one")
        assertIntent(VoiceIntent.Select, "Select.")
        assertIntent(VoiceIntent.Select, "open it")
        // Still what they were.
        assertIntent(VoiceIntent.GoBack, "go back")
        assertIntent(VoiceIntent.Open(VoiceDestination.Settings), "go to settings")
    }

    @Test
    fun startPlaybackFromATitle() {
        assertEquals(VoiceAction.PlayTitle, route("Start playback.", onDetails))
        assertEquals(VoiceAction.PlayTitle, route("play", onDetails))
        // With the source list open, "play" takes the top source and a number picks one.
        assertEquals(VoiceAction.PickSource(1), route("play", inSources))
        assertEquals(VoiceAction.PickSource(1), route("start playback", inSources))
        assertEquals(VoiceAction.PickSource(3), route("source 3", inSources))
        assertEquals(VoiceAction.PickSource(2), route("the second one", inSources))
        assertIs<VoiceAction.Refuse>(route("source 3", onDetails))
        // Music in the background does not take "play" away from the title on screen.
        assertEquals(VoiceAction.PlayTitle, route("play", onDetails.copy(musicLoaded = true)))
        assertEquals(VoiceAction.Music(P.Play), route("play", browsing.copy(musicLoaded = true)))
    }

    @Test
    fun routingInThePlayer() {
        assertEquals(VoiceAction.Player(P.SkipSegment), route("skip the intro", inPlayer))
        assertEquals(VoiceAction.ClosePlayer, route("stop playback", inPlayer))
        assertEquals(VoiceAction.ClosePlayer, route("close playback", inPlayer))
        assertEquals(VoiceAction.Back, route("go back", inPlayer))
        assertEquals(VoiceAction.Player(P.Previous), route("previous episode", inPlayer))
        assertEquals(VoiceAction.Player(P.SeekTo(0)), route("start over", inPlayer))
        assertEquals(VoiceAction.Player(P.VolumeChange(10)), route("louder", inPlayer))
        assertEquals(VoiceAction.Player(P.VolumeSet(40)), route("volume 40", inPlayer))
        assertEquals(VoiceAction.Player(P.Mute(true)), route("mute", inPlayer))
        assertEquals(VoiceAction.Player(P.NextSubtitles), route("next subtitles", inPlayer))
        assertIs<VoiceAction.Refuse>(route("down", inPlayer))
    }

    @Test
    fun routingWhileBrowsing() {
        assertEquals(VoiceAction.PressKey(VoiceKey.Down, 2), route("down 2", browsing))
        assertEquals(VoiceAction.PressKey(VoiceKey.Enter, 1), route("select", browsing))
        assertIs<VoiceAction.Refuse>(route("skip the intro", browsing))
        assertEquals(VoiceAction.Music(P.VolumeSet(30)), route("volume 30", browsing.copy(musicLoaded = true)))
        assertEquals(VoiceAction.Music(P.Previous), route("previous", browsing.copy(musicLoaded = true)))
    }

    @Test
    fun everyCommandKindHasAPhraseThatParsesBack() {
        // A person's own phrase for each kind must come back as that kind.
        for (kind in VoiceCommandKind.entries) {
            val phrases = VoiceCommandPhrases.None.withPhrase(kind, "zebra command")
            val said = when (kind.takes) {
                VoiceCommandKind.Takes.Nothing -> "zebra command"
                VoiceCommandKind.Takes.Query -> "zebra command cats"
                VoiceCommandKind.Takes.Duration -> "zebra command 30 seconds"
                VoiceCommandKind.Takes.Rate -> "zebra command 1.5"
                VoiceCommandKind.Takes.Number -> "zebra command 3"
            }
            val intent = parseVoiceIntent(said, phrases)
            // StartOver also owns SeekTo, and VolumeUp owns VolumeSet: same kind either way.
            assertEquals(kind, VoiceCommandKind.of(intent), "for $kind")
        }
    }

    /** What was said on Nov 5 and missed, from the log. */
    @Test
    fun phrasesHeardInUse() {
        assertIntent(VoiceIntent.StopPlayback, "close playback")
        assertIntent(VoiceIntent.StopPlayback, "stop the playback")
        assertIntent(VoiceIntent.StopPlayback, "end this video")
        assertIntent(VoiceIntent.StopPlayback, "close the youtube player")
        assertIntent(VoiceIntent.Move(Dir.Right, 1), "go write one")
        assertIntent(VoiceIntent.Move(Dir.Right, 5), "go write 5")
        assertIntent(VoiceIntent.Move(Dir.Right, 5), "go right to five")
        assertIntent(VoiceIntent.Move(Dir.Down, 3), "Go Dan 3")
        // "stop it" still pauses; "stop" with something that is not playback is not a command.
        assertIntent(VoiceIntent.Pause, "stop it")
        assertIntent(VoiceIntent.Unknown("stop the car"), "stop the car")
    }

    @Test
    fun chains() {
        assertIntent(VoiceIntent.Chain(listOf(VoiceIntent.Move(Dir.Right, 1), VoiceIntent.Select)), "go right one and select")
        assertIntent(VoiceIntent.Chain(listOf(VoiceIntent.Move(Dir.Right, 1), VoiceIntent.Select)), "Go write one and select.")
        assertIntent(VoiceIntent.Chain(listOf(VoiceIntent.Move(Dir.Down, 2), VoiceIntent.Select)), "Down two, then select.")
        assertIntent(
            VoiceIntent.Chain(listOf(VoiceIntent.Open(VoiceDestination.Home), VoiceIntent.Move(Dir.Down, 1), VoiceIntent.Select)),
            "Go home. Down. Select.",
        )
        assertIntent(VoiceIntent.Chain(listOf(VoiceIntent.Play, VoiceIntent.Choose(2))), "start playback and then the second one")
        // A title with "and" in it is still one search, not a chain.
        assertIntent(VoiceIntent.Search(VoiceSearchScope.Movies, "Pride and Prejudice"), "search movies for Pride and Prejudice")
        // Every part must be a command.
        assertIntent(VoiceIntent.Unknown("go right and have a nice day"), "go right and have a nice day")
        assertEquals("Right, then Select", describeVoiceIntent(parseVoiceIntent("right and select")))
    }
}
