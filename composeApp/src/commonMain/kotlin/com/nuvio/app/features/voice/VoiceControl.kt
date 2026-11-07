package com.nuvio.app.features.voice

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * fork:voice-control
 *
 * App-wide voice commands (Docs/plans/voice-control.md). The speech model is whisper.cpp, run
 * on the CPU through nuvio_whisper.dll; the user downloads a model from Settings > Voice control into
 * the data folder's `models\whisper\`, outside the install folder and outside any account's folder,
 * so it survives reinstalls, updates and account switches. Its settings describe the PC
 * (`nuvio_voice_control` is a machine store).
 *
 * Phase 2: the engine, the model download and a "Test" that records from the microphone and shows
 * what was heard and what [parseVoiceIntent] makes of it.
 * Phase 3: push-to-talk ([VoiceControlRepository.toggleListening], from the voice key, the sidebar
 * mic or a controller), [VoiceActivityDetector] to end the utterance, the listening overlay, and
 * [VoiceControlRepository.intents]. Nothing collects the intents yet (phase 4 routes them).
 */

/** A downloadable whisper.cpp model. [sha256] and [sizeBytes] are of the file at [url], which is pinned to a commit. */
data class VoiceModel(
    val id: String,
    val label: String,
    val description: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
)

object VoiceModels {
    /** ggerganov/whisper.cpp on Hugging Face, pinned so the checksums below cannot drift. */
    private const val SOURCE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/"

    val BaseEn = VoiceModel(
        id = "base.en-q5_1",
        label = "Standard",
        description = "Recommended. Gets film, show and song titles right. About half a second per command.",
        fileName = "ggml-base.en-q5_1.bin",
        url = SOURCE + "ggml-base.en-q5_1.bin",
        sizeBytes = 59_721_011L,
        sha256 = "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f",
    )

    val TinyEn = VoiceModel(
        id = "tiny.en-q5_1",
        label = "Fast",
        description = "For slower PCs. Quicker, but mishears unusual titles more often.",
        fileName = "ggml-tiny.en-q5_1.bin",
        url = SOURCE + "ggml-tiny.en-q5_1.bin",
        sizeBytes = 32_166_155L,
        sha256 = "c77c5766f1cef09b6b7d47f21b546cbddd4157886b3b5d6d4f709e91e66c7c2b",
    )

    val SmallEn = VoiceModel(
        id = "small.en-q5_1",
        label = "Accurate",
        description = "Best with strong accents and noisy rooms. Slower than Standard " +
            "(under a second per command) and uses more memory.",
        fileName = "ggml-small.en-q5_1.bin",
        url = SOURCE + "ggml-small.en-q5_1.bin",
        sizeBytes = 190_098_681L,
        sha256 = "bfdff4894dcb76bbf647d56263ea2a96645423f1669176f4844a1bf8e478ad30",
    )

    val all: List<VoiceModel> = listOf(BaseEn, SmallEn, TinyEn)
    val default: VoiceModel = BaseEn

    fun byId(id: String?): VoiceModel? = all.firstOrNull { it.id == id }
}

sealed interface VoiceModelState {
    data object NotInstalled : VoiceModelState
    data class Downloading(val bytes: Long, val totalBytes: Long) : VoiceModelState {
        val fraction: Float get() = if (totalBytes <= 0) 0f else (bytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }
    data object Verifying : VoiceModelState
    data object Installed : VoiceModelState
    data class Failed(val message: String) : VoiceModelState
}

data class VoiceControlSettings(
    val enabled: Boolean = false,
    /** The model voice commands use; one of [VoiceModels.all]. */
    val modelId: String = VoiceModels.default.id,
    /** The microphone's mixer name; null = Windows' default recording device. */
    val microphone: String? = null,
    /** How long a pause ends a command; one of [VoiceActivityDetector.END_SILENCE_OPTIONS_MS]. */
    val endSilenceMs: Int = VoiceActivityDetector.DEFAULT_END_SILENCE_MS,
    /** Listen all the time for "Nuvio ..." (see VoiceWakeWord.kt), besides push-to-talk. */
    val wakeWord: Boolean = false,
)

/** A push-to-talk command, from the press to what it was understood as. Drives the listening overlay. */
sealed interface VoiceSessionState {
    data object Idle : VoiceSessionState

    /**
     * Recording. [transcript] is a rough reading of what has been said so far (may be empty); [scope]
     * is set when a search box's mic started it, so plain words search there.
     */
    data class Listening(
        val level: Float,
        val transcript: String,
        val heardSpeech: Boolean,
        val scope: VoiceSearchScope? = null,
    ) : VoiceSessionState

    /** The utterance is over; the final reading is being made. */
    data class Transcribing(val transcript: String) : VoiceSessionState

    data class Heard(val text: String, val intent: VoiceIntent) : VoiceSessionState

    data class Failed(val message: String) : VoiceSessionState
}

/** Settings > Voice control > Test: record a command and show what the model heard. */
sealed interface VoiceTestState {
    data object Idle : VoiceTestState
    data class Recording(val level: Float, val elapsedMs: Long) : VoiceTestState
    data object Transcribing : VoiceTestState
    data class Heard(val text: String, val intent: VoiceIntent, val audioMs: Long, val decodeMs: Long) : VoiceTestState
    data class Failed(val message: String) : VoiceTestState
}

data class VoiceControlUiState(
    val settings: VoiceControlSettings = VoiceControlSettings(),
    val models: Map<String, VoiceModelState> = emptyMap(),
    val test: VoiceTestState = VoiceTestState.Idle,
    val microphones: List<String> = emptyList(),
    /** Null when nuvio_whisper.dll is present and loads; otherwise why voice control cannot run. */
    val engineProblem: String? = null,
    /** The person's own command phrases (per account: they are the person's, not the PC's). */
    val phrases: VoiceCommandPhrases = VoiceCommandPhrases.None,
    val modelsFolder: String = "",
    /** The microphone is open, listening for the wake word, right now. */
    val wakeListening: Boolean = false,
    /** The Settings test's recording is being played back. */
    val testPlaying: Boolean = false,
) {
    fun stateOf(model: VoiceModel): VoiceModelState = models[model.id] ?: VoiceModelState.NotInstalled

    val selectedModel: VoiceModel get() = VoiceModels.byId(settings.modelId) ?: VoiceModels.default

    val selectedModelInstalled: Boolean get() = stateOf(selectedModel) == VoiceModelState.Installed

    /** Whether push-to-talk works right now: switched on, with a model and the engine. */
    val ready: Boolean get() = settings.enabled && selectedModelInstalled && engineProblem == null
}

/** The longest a Settings test recording runs; Stop ends it sooner. */
const val VOICE_TEST_MAX_MS = 6_000L

expect object VoiceControlRepository {
    val uiState: StateFlow<VoiceControlUiState>

    /** Reads the settings and which models are on disk; cheap after the first call. */
    fun ensureLoaded()

    fun setEnabled(enabled: Boolean)
    fun setModel(modelId: String)
    fun setMicrophone(name: String?)

    fun download(modelId: String)
    fun cancelDownload(modelId: String)
    fun delete(modelId: String)

    fun refreshMicrophones()
    fun openModelsFolder()

    /** Records up to [VOICE_TEST_MAX_MS] from the chosen microphone, then transcribes it with the selected model. */
    fun startTest()

    /** Ends a test recording early (it is then transcribed), or dismisses a result (and drops its recording). */
    fun stopTest()

    /**
     * Plays back the audio the last test gave the model, exactly as it was decoded (16 kHz mono), or
     * stops a playback under way. Only the test keeps its audio, in memory, until it is dismissed or
     * the next test starts; commands and the wake word never keep theirs.
     */
    fun playTestRecording()

    /** Saves the last test's audio as a WAV in the data folder and shows it in Explorer; the file goes with the result. */
    fun showTestRecording()

    fun setEndSilenceMs(ms: Int)

    /** Turns the "Nuvio ..." wake word on or off; it listens only while voice control is [VoiceControlUiState.ready]. */
    fun setWakeWord(enabled: Boolean)

    val session: StateFlow<VoiceSessionState>

    /** Each command understood ([VoiceIntent.Unknown] is not sent). */
    val intents: SharedFlow<VoiceIntent>

    /**
     * Push-to-talk: starts listening, or, while listening, ends the utterance now. With voice
     * control off or no model it shows why in the overlay instead. [scope]: started from that search
     * box's mic (see [scopeVoiceIntent]).
     */
    fun toggleListening(scope: VoiceSearchScope? = null)

    /** Stops listening and drops what was said (Escape / B), or hides a result. */
    fun cancelListening()

    /** Replaces what the overlay shows with [message] (why a command could not be carried out). */
    fun showOutcome(message: String)

    /** Adds [phrase] to [kind]; the reason it was not added, or null. */
    fun addPhrase(kind: VoiceCommandKind, phrase: String): String?

    fun removePhrase(kind: VoiceCommandKind, phrase: String)

    /** Whether [kind] still answers to its built-in phrases (off = rebound to the person's own). */
    fun setBuiltInPhrases(kind: VoiceCommandKind, enabled: Boolean)

    /** Every action back to its built-in phrases only. */
    fun resetPhrases()
}

/** What a recognised command does, in words, for the overlay and the Settings test. */
fun describeVoiceIntent(intent: VoiceIntent): String = when (intent) {
    is VoiceIntent.Search -> "Search ${scopeName(intent.scope)} for “${intent.query}”"
    is VoiceIntent.OpenSearch ->
        if (intent.scope == VoiceSearchScope.All) "Open Search" else "Open the ${scopeName(intent.scope)} search"
    is VoiceIntent.Open -> "Open ${destinationName(intent.destination)}"
    VoiceIntent.Play -> "Play"
    VoiceIntent.Pause -> "Pause"
    VoiceIntent.Next -> "Next"
    VoiceIntent.GoBack -> "Go back"
    is VoiceIntent.Seek -> (if (intent.deltaMs < 0) "Back " else "Forward ") + spokenDuration(kotlin.math.abs(intent.deltaMs))
    is VoiceIntent.Speed -> "Playback speed ${spokenRate(intent.rate)}\u00D7"
    VoiceIntent.SkipSegment -> "Skip"
    VoiceIntent.StopPlayback -> "Stop playback"
    VoiceIntent.PreviousEpisode -> "Previous episode"
    is VoiceIntent.SeekTo -> if (intent.positionMs == 0L) "Start over" else "Go to ${spokenClock(intent.positionMs)}"
    is VoiceIntent.VolumeChange -> (if (intent.percent < 0) "Volume down " else "Volume up ") + "${kotlin.math.abs(intent.percent)}%"
    is VoiceIntent.VolumeSet -> "Volume ${intent.percent}%"
    is VoiceIntent.Mute -> if (intent.muted) "Mute" else "Unmute"
    VoiceIntent.NextSubtitles -> "Next subtitles"
    VoiceIntent.NextAudio -> "Next audio track"
    is VoiceIntent.Choose -> "Pick number ${intent.number}"
    is VoiceIntent.Move -> intent.direction.name + if (intent.count > 1) " \u00D7${intent.count}" else ""
    VoiceIntent.Select -> "Select"
    is VoiceIntent.Chain -> intent.steps.joinToString(", then ") { describeVoiceIntent(it) }
    is VoiceIntent.Unknown -> "Not a command"
}

private fun spokenDuration(ms: Long): String {
    val seconds = ms / 1000
    return when {
        seconds < 60 -> "$seconds s"
        seconds % 60 == 0L -> "${seconds / 60} min"
        else -> "${seconds / 60} min ${seconds % 60} s"
    }
}

/** 1:05:00, 20:00. */
private fun spokenClock(ms: Long): String {
    val total = ms / 1000
    val (h, m, s) = Triple(total / 3600, (total % 3600) / 60, total % 60)
    fun two(n: Long) = n.toString().padStart(2, '0')
    return if (h > 0) "$h:${two(m)}:${two(s)}" else "$m:${two(s)}"
}

private fun spokenRate(rate: Float): String =
    if (rate == rate.toInt().toFloat()) rate.toInt().toString() else ((rate * 100).toInt() / 100.0).toString()

private fun scopeName(scope: VoiceSearchScope) = when (scope) {
    VoiceSearchScope.All -> "everything"
    VoiceSearchScope.Movies -> "movies"
    VoiceSearchScope.Shows -> "shows"
    VoiceSearchScope.YouTube -> "YouTube"
    VoiceSearchScope.Music -> "music"
}

private fun destinationName(destination: VoiceDestination) = when (destination) {
    VoiceDestination.Home -> "Home"
    VoiceDestination.Search -> "Search"
    VoiceDestination.Discover -> "Discover"
    VoiceDestination.Library -> "Library"
    VoiceDestination.LiveTv -> "Live TV"
    VoiceDestination.Music -> "Music"
    VoiceDestination.YouTube -> "YouTube"
    VoiceDestination.Calendar -> "Calendar"
    VoiceDestination.Settings -> "Settings"
}
