package com.nuvio.app.features.voice

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.player.desktop.NativeAudioDuck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/** fork:voice-control - see VoiceControl.kt. */
actual object VoiceControlRepository {
    /** In MACHINE_STORE_NAMES: which model and microphone this PC uses is not the account's. */
    internal const val STORE_NAME = "nuvio_voice_control"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MODEL = "model_id"
    private const val KEY_MICROPHONE = "microphone"
    private const val KEY_END_SILENCE = "end_silence_ms"
    private const val KEY_WAKE_WORD = "wake_word"

    /** Heard before speech is detected, kept so a clip does not start a word late. */
    private const val WAKE_PRE_ROLL_MS = 300

    /** Shorter than this is a cough or a click, not "Nuvio" and a command. */
    private const val WAKE_MIN_CLIP_MS = 400

    /** The longest wake-word clip: with music playing every clip runs this long, and each is decoded. */
    private const val WAKE_MAX_CLIP_MS = 6_000

    /** How much of a clip cut off mid-sound also starts the next ("Nuvio, pause" is under 2 s). */
    private const val WAKE_CARRY_MS = 2_500

    /** A carried tail with no sound after it for this long is dropped (it was decoded already). */
    private const val WAKE_CARRY_IDLE_MS = 1_000

    /** After the microphone fails to open, the wake-word listener tries again this much later. */
    private const val WAKE_RETRY_MS = 15_000L

    /** How far the app's own audio goes down while listening, so the microphone hears the person. */
    private const val DUCK_TO = 0.25

    /** A reading of what has been said so far, at most this often. */
    private const val PREVIEW_INTERVAL_MS = 450L

    /** A preview decode slower than this means a slow CPU: the rest of the command gets none. */
    private const val PREVIEW_TOO_SLOW_MS = 700L

    /** How long the overlay shows what was understood (or why not) before it goes. */
    private const val RESULT_SHOWN_MS = 3_500L

    private val log = Logger.withTag("Voice")
    private val store by lazy { DesktopStorage.store(STORE_NAME) }

    /** Not in MACHINE_STORE_NAMES: the phrases someone chose are theirs, so they follow the account. */
    internal const val PHRASES_STORE_NAME = "nuvio_voice_phrases"
    private const val KEY_PHRASES = "phrases"
    private val phrasesStore by lazy { DesktopStorage.store(PHRASES_STORE_NAME) }
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloader = VoiceModelDownloader()

    private val _uiState = MutableStateFlow(VoiceControlUiState())
    actual val uiState: StateFlow<VoiceControlUiState> = _uiState.asStateFlow()

    private val _session = MutableStateFlow<VoiceSessionState>(VoiceSessionState.Idle)
    actual val session: StateFlow<VoiceSessionState> = _session.asStateFlow()

    private val _intents = MutableSharedFlow<VoiceIntent>(extraBufferCapacity = 4)
    actual val intents: SharedFlow<VoiceIntent> = _intents.asSharedFlow()

    private var loaded = false
    private val downloads = mutableMapOf<String, Job>()
    private var testJob: Job? = null
    private var sessionJob: Job? = null
    private var hideJob: Job? = null

    @Volatile
    private var stopRecording = false

    @Volatile
    private var stopListening = false

    private var transcriber: WhisperTranscriber? = null

    /** `<data folder>\models\whisper`: outside the install folder and the account folders. */
    internal val modelsDir: File
        get() = DesktopStorage.rootDir.resolve("models").resolve("whisper").toFile()

    internal fun modelFile(model: VoiceModel) = File(modelsDir, model.fileName)

    @Synchronized
    actual fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val settings = VoiceControlSettings(
            enabled = store.getBoolean(KEY_ENABLED) ?: false,
            modelId = store.getString(KEY_MODEL)?.takeIf { VoiceModels.byId(it) != null } ?: VoiceModels.default.id,
            microphone = store.getString(KEY_MICROPHONE)?.takeIf { it.isNotBlank() },
            endSilenceMs = store.getInt(KEY_END_SILENCE)
                ?.takeIf { it in VoiceActivityDetector.END_SILENCE_OPTIONS_MS }
                ?: VoiceActivityDetector.DEFAULT_END_SILENCE_MS,
            wakeWord = store.getBoolean(KEY_WAKE_WORD) ?: false,
        )
        _uiState.update {
            it.copy(
                settings = settings,
                models = VoiceModels.all.associate { model -> model.id to installedState(model) },
                engineProblem = if (WhisperLibrary.resolve() == null) ENGINE_MISSING else null,
                modelsFolder = modelsDir.absolutePath,
                phrases = loadPhrases(),
            )
        }
        refreshMicrophones()
        warmUp()
        superviseWakeWord()
        // "down", "go right 3", "select": the keystrokes a gamepad sends, so every screen that walks
        // with the arrow keys walks by voice too.
        VoiceScreenTargets.pressKey = { key, count ->
            val code = when (key) {
                VoiceKey.Up -> java.awt.event.KeyEvent.VK_UP
                VoiceKey.Down -> java.awt.event.KeyEvent.VK_DOWN
                VoiceKey.Left -> java.awt.event.KeyEvent.VK_LEFT
                VoiceKey.Right -> java.awt.event.KeyEvent.VK_RIGHT
                VoiceKey.Enter -> java.awt.event.KeyEvent.VK_ENTER
            }
            (0 until count.coerceIn(1, 20)).all { com.nuvio.app.features.input.GamepadKeyInjector.sendKeyCode(code) }
        }
    }

    /** A file of the catalogue's size is trusted; hashing 60 MB at every start is not worth it. */
    private fun installedState(model: VoiceModel): VoiceModelState {
        val file = modelFile(model)
        return if (file.isFile && file.length() == model.sizeBytes) VoiceModelState.Installed else VoiceModelState.NotInstalled
    }

    actual fun setEnabled(enabled: Boolean) {
        store.putBoolean(KEY_ENABLED, enabled)
        updateSettings { it.copy(enabled = enabled) }
        if (enabled) warmUp() else scope.launch { releaseTranscriber() }
    }

    actual fun setModel(modelId: String) {
        if (VoiceModels.byId(modelId) == null) return
        store.putString(KEY_MODEL, modelId)
        updateSettings { it.copy(modelId = modelId) }
        warmUp()
    }

    actual fun setMicrophone(name: String?) {
        store.putString(KEY_MICROPHONE, name)
        updateSettings { it.copy(microphone = name) }
    }

    actual fun setEndSilenceMs(ms: Int) {
        if (ms !in VoiceActivityDetector.END_SILENCE_OPTIONS_MS) return
        store.putInt(KEY_END_SILENCE, ms)
        updateSettings { it.copy(endSilenceMs = ms) }
    }

    actual fun setWakeWord(enabled: Boolean) {
        store.putBoolean(KEY_WAKE_WORD, enabled)
        updateSettings { it.copy(wakeWord = enabled) }
    }

    private fun updateSettings(transform: (VoiceControlSettings) -> VoiceControlSettings) {
        ensureLoaded()
        _uiState.update { it.copy(settings = transform(it.settings)) }
    }

    private fun setModelState(model: VoiceModel, state: VoiceModelState) {
        _uiState.update { it.copy(models = it.models + (model.id to state)) }
    }

    /**
     * While voice control is on, the selected model stays loaded (~150 MB for base.en), so a press
     * starts listening at once and the first command is not 0.3 s slower than the rest.
     */
    private fun warmUp() {
        val state = _uiState.value
        if (!state.ready) return
        scope.launch {
            transcriberFor(modelFile(state.selectedModel))
                .onFailure { log.w(it) { "Could not load the speech model" } }
        }
    }

    @Synchronized
    actual fun download(modelId: String) {
        ensureLoaded()
        val model = VoiceModels.byId(modelId) ?: return
        // One at a time: two 60 MB downloads only make each other slower.
        if (downloads.values.any { it.isActive }) return
        val target = modelFile(model)
        setModelState(model, VoiceModelState.Downloading(VoiceModelDownloader.partFile(target).length(), model.sizeBytes))
        downloads[modelId] = scope.launch {
            try {
                downloader.download(
                    url = model.url,
                    target = target,
                    expectedSize = model.sizeBytes,
                    expectedSha256 = model.sha256,
                    onProgress = { bytes, total -> setModelState(model, VoiceModelState.Downloading(bytes, total)) },
                    onVerifying = { setModelState(model, VoiceModelState.Verifying) },
                )
                log.i { "Downloaded ${model.fileName} into $modelsDir" }
                setModelState(model, VoiceModelState.Installed)
                // The first model downloaded is the one used, whatever was selected before.
                if (!_uiState.value.selectedModelInstalled) setModel(model.id) else warmUp()
            } catch (cancelled: CancellationException) {
                VoiceModelDownloader.discardPartial(target)
                setModelState(model, VoiceModelState.NotInstalled)
                throw cancelled
            } catch (error: Exception) {
                log.w(error) { "Downloading ${model.fileName} failed" }
                setModelState(model, VoiceModelState.Failed(error.message ?: error.javaClass.simpleName))
            }
        }
    }

    @Synchronized
    actual fun cancelDownload(modelId: String) {
        downloads.remove(modelId)?.cancel()
    }

    @Synchronized
    actual fun delete(modelId: String) {
        val model = VoiceModels.byId(modelId) ?: return
        cancelDownload(modelId)
        val file = modelFile(model)
        releaseTranscriber(onlyFor = file)
        file.delete()
        VoiceModelDownloader.discardPartial(file)
        setModelState(model, VoiceModelState.NotInstalled)
    }

    actual fun refreshMicrophones() {
        scope.launch {
            val names = runCatching { VoiceAudio.microphones() }
                .onFailure { log.w(it) { "Listing microphones failed" } }
                .getOrDefault(emptyList())
            _uiState.update { it.copy(microphones = names) }
        }
    }

    actual fun openModelsFolder() {
        val dir = modelsDir.apply { mkdirs() }
        runCatching { ProcessBuilder("explorer.exe", dir.absolutePath).start() }
            .onFailure { log.w(it) { "Could not open $dir" } }
    }

    // ---- Settings test -------------------------------------------------------------------------

    @Synchronized
    actual fun startTest() {
        ensureLoaded()
        if (testJob?.isActive == true || sessionJob?.isActive == true) return
        val state = _uiState.value
        val model = state.selectedModel
        if (!state.selectedModelInstalled) {
            setTest(VoiceTestState.Failed("Download the ${model.label} model first"))
            return
        }
        stopRecording = false
        setTest(VoiceTestState.Recording(level = 0f, elapsedMs = 0))
        testJob = scope.launch {
            try {
                val samples = recordTest(state.settings.microphone) ?: return@launch
                setTest(VoiceTestState.Transcribing)
                val engine = transcriberFor(modelFile(model)).getOrElse { error ->
                    reportEngineFailure(error)
                    setTest(VoiceTestState.Failed(error.message ?: "The speech engine could not start"))
                    return@launch
                }
                val started = System.nanoTime()
                val recognized = recognize(engine, samples, scope = null)
                val decodeMs = (System.nanoTime() - started) / 1_000_000
                val audioMs = samples.size * 1000L / VoiceAudio.SAMPLE_RATE
                log.i { "Test: ${audioMs}ms of audio decoded in ${decodeMs}ms with ${model.id} [${recognized.how}]" }
                setTest(VoiceTestState.Heard(recognized.text, recognized.intent, audioMs, decodeMs))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                log.w(error) { "Voice test failed" }
                setTest(VoiceTestState.Failed(error.message ?: error.javaClass.simpleName))
            } finally {
                // With voice control off nothing else needs the model; with it on it stays for push-to-talk.
                if (!_uiState.value.settings.enabled) releaseTranscriber()
            }
        }
    }

    /** Records until Stop or [VOICE_TEST_MAX_MS]; null (with the test failed) when no microphone opens. */
    private suspend fun recordTest(microphone: String?): FloatArray? {
        val recorder = openRecorder(microphone) { message -> setTest(VoiceTestState.Failed(message)) } ?: return null
        val started = System.currentTimeMillis()
        var lastShown = 0L
        try {
            while (!stopRecording) {
                coroutineContext.ensureActive()
                val elapsed = System.currentTimeMillis() - started
                if (elapsed >= VOICE_TEST_MAX_MS) break
                val chunk = recorder.readChunk()
                if (elapsed - lastShown >= 60) {
                    lastShown = elapsed
                    setTest(VoiceTestState.Recording(VoiceAudio.level(chunk), elapsed))
                }
            }
        } finally {
            recorder.close()
        }
        return recorder.samples()
    }

    actual fun stopTest() {
        when (_uiState.value.test) {
            is VoiceTestState.Recording -> stopRecording = true
            is VoiceTestState.Heard, is VoiceTestState.Failed -> setTest(VoiceTestState.Idle)
            else -> Unit
        }
    }

    private fun setTest(state: VoiceTestState) {
        _uiState.update { it.copy(test = state) }
    }

    // ---- Push-to-talk ----------------------------------------------------------------------------

    @Synchronized
    actual fun toggleListening(scope: VoiceSearchScope?) {
        ensureLoaded()
        when (_session.value) {
            is VoiceSessionState.Listening -> {
                // A second press: the command is over, transcribe it now.
                stopListening = true
                return
            }
            is VoiceSessionState.Transcribing -> return
            else -> Unit
        }
        if (sessionJob?.isActive == true || testJob?.isActive == true) return
        val state = _uiState.value
        val problem = when {
            !state.settings.enabled -> "Voice control is off. Turn it on in Settings > Voice control."
            !state.selectedModelInstalled -> "Download a speech model in Settings > Voice control first."
            else -> state.engineProblem
        }
        if (problem != null) {
            showResult(VoiceSessionState.Failed(problem))
            return
        }
        hideJob?.cancel()
        stopListening = false
        _session.value = VoiceSessionState.Listening(level = 0f, transcript = "", heardSpeech = false, scope = scope)
        val model = state.selectedModel
        val settings = state.settings
        sessionJob = this.scope.launch {
            try {
                listen(model, settings, scope)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                log.w(error) { "Listening failed" }
                showResult(VoiceSessionState.Failed(error.message ?: error.javaClass.simpleName))
            }
        }
    }

    @Synchronized
    actual fun cancelListening() {
        hideJob?.cancel()
        sessionJob?.cancel()
        _session.value = VoiceSessionState.Idle
    }

    private suspend fun listen(model: VoiceModel, settings: VoiceControlSettings, searchScope: VoiceSearchScope?) {
        val engine = transcriberFor(modelFile(model)).getOrElse { error ->
            reportEngineFailure(error)
            showResult(VoiceSessionState.Failed(error.message ?: "The speech engine could not start"))
            return
        }
        val recorder = openRecorder(settings.microphone) { message -> showResult(VoiceSessionState.Failed(message)) }
            ?: return
        val detector = VoiceActivityDetector(endSilenceMs = settings.endSilenceMs)
        val frameRate = recorder.sampleRate
        var preview: Job? = null
        // Live previews only once a decode has shown this model is quick on this PC. A preview
        // still decoding when speech ends holds the final decode up for its whole length (one
        // transcriber, serialised), so a slow model paid its decode time twice per command.
        val previewAllowed = AtomicBoolean(engine.lastDecodeMs?.let { it <= PREVIEW_TOO_SLOW_MS } == true)
        val transcript = AtomicReference("")
        var lastPreviewAt = 0L
        var lastShownAt = 0L
        NativeAudioDuck.duck(DUCK_TO)
        try {
            while (!stopListening) {
                coroutineContext.ensureActive()
                val chunk = recorder.readChunk()
                if (chunk.isEmpty()) continue
                val activity = detector.accept(chunk, (chunk.size * 1000 / frameRate).coerceAtLeast(1))
                if (activity == VoiceActivity.NoSpeech) {
                    showResult(VoiceSessionState.Failed("Didn't hear anything"))
                    return
                }
                if (activity == VoiceActivity.SpeechEnded || activity == VoiceActivity.TooLong) break

                val now = System.currentTimeMillis()
                if (detector.heardSpeech && previewAllowed.get() && preview?.isActive != true &&
                    now - lastPreviewAt >= PREVIEW_INTERVAL_MS
                ) {
                    lastPreviewAt = now
                    val soFar = recorder.samples()
                    preview = scope.launch {
                        val started = System.currentTimeMillis()
                        val text = engine.transcribe(soFar, prompt())
                        if (System.currentTimeMillis() - started > PREVIEW_TOO_SLOW_MS) previewAllowed.set(false)
                        transcript.set(text)
                    }
                }
                if (now - lastShownAt >= 60) {
                    lastShownAt = now
                    _session.value = VoiceSessionState.Listening(VoiceAudio.level(chunk), transcript.get(), detector.heardSpeech, searchScope)
                }
            }
        } finally {
            recorder.close()
            NativeAudioDuck.restore()
        }

        if (!detector.heardSpeech) {
            // Pressed again before saying anything.
            showResult(VoiceSessionState.Failed("Didn't hear anything"))
            return
        }
        _session.value = VoiceSessionState.Transcribing(transcript.get())
        preview?.join()
        val samples = recorder.samples()
        val started = System.nanoTime()
        val recognized = recognize(engine, samples, searchScope)
        val text = recognized.text
        val decodeMs = (System.nanoTime() - started) / 1_000_000
        val intent = recognized.intent
        log.i {
            "Heard ${samples.size * 1000L / VoiceAudio.SAMPLE_RATE}ms in ${decodeMs}ms: ${describeVoiceIntent(intent)}" +
                (if (intent is VoiceIntent.Unknown) " (\"${text.take(120)}\")" else "") + " [${recognized.how}]"
        }
        if (intent is VoiceIntent.Unknown && intent.text.isEmpty()) {
            showResult(VoiceSessionState.Failed("Didn't catch that"))
            return
        }
        showResult(VoiceSessionState.Heard(text, intent))
        if (intent !is VoiceIntent.Unknown) _intents.tryEmit(intent)
    }

    actual fun showOutcome(message: String) = showResult(VoiceSessionState.Failed(message))

    private fun loadPhrases(): VoiceCommandPhrases =
        phrasesStore.getString(KEY_PHRASES)
            ?.let { runCatching { json.decodeFromString(VoiceCommandPhrases.serializer(), it) }.getOrNull() }
            ?: VoiceCommandPhrases.None

    private fun updatePhrases(transform: (VoiceCommandPhrases) -> VoiceCommandPhrases) {
        ensureLoaded()
        val next = transform(_uiState.value.phrases)
        phrasesStore.putString(KEY_PHRASES, json.encodeToString(VoiceCommandPhrases.serializer(), next))
        _uiState.update { it.copy(phrases = next) }
    }

    @Synchronized
    actual fun addPhrase(kind: VoiceCommandKind, phrase: String): String? {
        ensureLoaded()
        VoiceCommandPhrases.problemWith(phrase, kind, _uiState.value.phrases)?.let { return it }
        updatePhrases { it.withPhrase(kind, phrase) }
        return null
    }

    @Synchronized
    actual fun removePhrase(kind: VoiceCommandKind, phrase: String) = updatePhrases { it.withoutPhrase(kind, phrase) }

    @Synchronized
    actual fun setBuiltInPhrases(kind: VoiceCommandKind, enabled: Boolean) = updatePhrases { it.withBuiltIn(kind, enabled) }

    @Synchronized
    actual fun resetPhrases() = updatePhrases { VoiceCommandPhrases.None }

    /** whisper's prompt: the command words, and the person's own so they come out spelled as typed. */
    private fun prompt(): String {
        val own = _uiState.value.phrases.all
        return if (own.isEmpty()) WhisperTranscriber.COMMAND_PROMPT
        else WhisperTranscriber.COMMAND_PROMPT + " " + own.joinToString(". ", postfix = ".")
    }

    /** Shows [state] in the overlay, then hides it. */
    private fun showResult(state: VoiceSessionState) {
        _session.value = state
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(RESULT_SHOWN_MS)
            _session.compareAndSet(state, VoiceSessionState.Idle)
        }
    }

    // ---- Wake word ---------------------------------------------------------------------------------

    /**
     * Runs the wake-word listener whenever it should: switched on, voice control ready, and the
     * microphone not wanted by push-to-talk or the Settings test (each gets it to itself; a result
     * still on screen does not count, so "Nuvio, play" can follow "Nuvio, pause" straight away).
     * A change of model or microphone restarts it with the new one.
     */
    private fun superviseWakeWord() {
        scope.launch {
            combine(_uiState, _session) { ui, session ->
                val micFree = ui.test == VoiceTestState.Idle &&
                    session !is VoiceSessionState.Listening && session !is VoiceSessionState.Transcribing
                if (ui.ready && ui.settings.wakeWord && micFree) ui.selectedModel to ui.settings.microphone else null
            }.distinctUntilChanged().collectLatest { wanted ->
                if (wanted == null) return@collectLatest
                val (model, microphone) = wanted
                while (isActive) {
                    listenForWakeWord(model, microphone)
                    delay(WAKE_RETRY_MS)
                }
            }
        }
    }

    /**
     * Cuts the microphone into utterances with the same detector push-to-talk uses and decodes each
     * one; only one that starts with the name goes any further ([commandAfterWakeWord]). Returns
     * when the microphone or the engine cannot be had; cancelled when it should stop.
     */
    private suspend fun listenForWakeWord(model: VoiceModel, microphone: String?) = coroutineScope {
        val engine = transcriberFor(modelFile(model)).getOrElse { error ->
            reportEngineFailure(error)
            return@coroutineScope
        }
        val recorder = openRecorder(microphone, keep = false) { message -> log.w { "Wake word: $message" } }
            ?: return@coroutineScope
        log.i { "Listening for the wake word with ${model.id}" }
        _uiState.update { it.copy(wakeListening = true) }
        // One clip waiting at most: if decoding falls behind (a film playing loudly), the newest wins.
        val clips = Channel<WakeClip>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val decoder = launch {
            // The command the last clip carried out, so a carried tail that holds it too is not run twice.
            var lastActed: String? = null
            for (clip in clips) {
                try {
                    val acted = onWakeClip(engine, clip.samples, repeatOf = lastActed.takeIf { clip.startsWithCarry })
                    lastActed = acted
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    log.w(error) { "Wake word: decoding failed" }
                }
            }
        }
        try {
            val rate = recorder.sampleRate
            fun newDetector() = VoiceActivityDetector(
                endSilenceMs = _uiState.value.settings.endSilenceMs,
                noSpeechTimeoutMs = Int.MAX_VALUE,
                maxUtteranceMs = WAKE_MAX_CLIP_MS,
            )
            var detector = newDetector()
            val preRoll = ArrayDeque<FloatArray>()
            var preRollMs = 0
            val speech = ArrayList<FloatArray>()
            // After a clip cut off mid-sound (music, a film: no pause to end it at), its last
            // WAKE_CARRY_MS start the next one, so "Nuvio, pause" said across the cut is whole in one
            // of the two. Dropped if the sound stops instead.
            var carrying = false
            var carryIdleMs = 0
            var clipStartsWithCarry = false
            while (isActive) {
                // Suspends once per chunk. collectLatest starts this block undispatched, on the very
                // coroutine that collects the state saying when to stop: a loop of blocking reads that
                // never suspended kept it from seeing push-to-talk start, so the mic was never handed
                // over ("The microphone is busy" after "Nuvio" alone, Nov 5).
                kotlinx.coroutines.yield()
                val chunk = recorder.readChunk()
                if (chunk.isEmpty()) continue
                val chunkMs = (chunk.size * 1000 / rate).coerceAtLeast(1)
                val activity = detector.accept(chunk, chunkMs)
                if (!detector.heardSpeech && carrying) {
                    speech.add(chunk)
                    carryIdleMs += chunkMs
                    if (carryIdleMs > WAKE_CARRY_IDLE_MS) {
                        speech.clear()
                        carrying = false
                        clipStartsWithCarry = false
                    }
                    continue
                }
                if (!detector.heardSpeech) {
                    preRoll.addLast(chunk)
                    preRollMs += chunkMs
                    while (preRollMs > WAKE_PRE_ROLL_MS && preRoll.size > 1) {
                        preRollMs -= preRoll.removeFirst().size * 1000 / rate
                    }
                    continue
                }
                if (speech.isEmpty()) {
                    speech.addAll(preRoll)
                    preRoll.clear()
                    preRollMs = 0
                }
                speech.add(chunk)
                carrying = false
                if (activity == VoiceActivity.SpeechEnded || activity == VoiceActivity.TooLong) {
                    val joined = FloatArray(speech.sumOf { it.size })
                    var at = 0
                    for (part in speech) {
                        part.copyInto(joined, at)
                        at += part.size
                    }
                    if (activity == VoiceActivity.TooLong) {
                        var keptMs = 0
                        val tail = ArrayDeque<FloatArray>()
                        for (part in speech.asReversed()) {
                            if (keptMs >= WAKE_CARRY_MS) break
                            tail.addFirst(part)
                            keptMs += part.size * 1000 / rate
                        }
                        speech.clear()
                        speech.addAll(tail)
                        carrying = true
                        carryIdleMs = 0
                    } else {
                        speech.clear()
                    }
                    detector = newDetector()
                    val sentStartsWithCarry = clipStartsWithCarry
                    clipStartsWithCarry = carrying
                    if (joined.size * 1000L / rate >= WAKE_MIN_CLIP_MS) {
                        clips.trySend(WakeClip(VoiceAudio.resampleTo16k(joined, rate), sentStartsWithCarry))
                    }
                }
            }
        } finally {
            recorder.close()
            clips.close()
            decoder.cancel()
            _uiState.update { it.copy(wakeListening = false) }
        }
    }

    /** What was said is never logged unless it was addressed to Nuvio: the room's talk is not the app's. */
    /** One utterance for the wake-word decoder; [startsWithCarry]: it begins with the last clip's tail. */
    private class WakeClip(val samples: FloatArray, val startsWithCarry: Boolean)

    /**
     * Decodes [clip] and acts on a command addressed to Nuvio. Returns the command acted on (as
     * heard, after the name), null when nothing was. [repeatOf]: the command the clip before carried
     * out, when this one begins with its tail; the same command again is that tail, not a new one.
     */
    private fun onWakeClip(engine: WhisperTranscriber, clip: FloatArray, repeatOf: String? = null): String? {
        // One decode for every clip, held to the wake grammar: free text, then the name, then a
        // command. Talk not meant for Nuvio passes through as said (the grammar can stop before the
        // name), and on noisy clips it finds the name more often than a free decode does (Nov 5: the
        // free decode wrote "New deal. Force." for "Nuvio, pause", the held one "Nuvio, pause.").
        val wakePrompt = prompt() + " " + WAKE_WORD_PROMPT // whisper keeps the end of a long prompt
        val held = if (GRAMMAR_FIRST) engine.transcribeHeld(clip, VOICE_GRAMMAR_WAKE_RULE, grammar(), wakePrompt) else null
        val text = held?.text ?: engine.transcribe(clip, wakePrompt)
        val command = commandAfterWakeWord(text) ?: return null
        if (repeatOf != null && normalizeVoicePhrase(command) == normalizeVoicePhrase(repeatOf)) return null
        val busy = _session.value.let { it is VoiceSessionState.Listening || it is VoiceSessionState.Transcribing }
        if (busy) return null
        if (command.isEmpty()) {
            // The name alone: listen for the command, as a press of the voice key would.
            log.i { "Wake word heard; listening for the command" }
            toggleListening()
            return command
        }
        val (heard, intent, how) = recognizeAfterName(engine, clip, command, held)
        val clipMs = clip.size * 1000L / VoiceAudio.SAMPLE_RATE
        val unknownText = if (intent is VoiceIntent.Unknown) " (\"${heard.take(120)}\")" else ""
        log.i { "Wake word: ${clipMs}ms: ${describeVoiceIntent(intent)}$unknownText [$how]" }
        if (intent is VoiceIntent.Unknown && intent.text.isEmpty()) return null
        showResult(VoiceSessionState.Heard(heard, intent))
        if (intent is VoiceIntent.Unknown) return null
        _intents.tryEmit(intent)
        return command
    }

    // ---- Recognition -------------------------------------------------------------------------------

    /**
     * Commands are decoded held to the command grammar first (see voiceCommandGrammar): whisper can
     * then only write a command, so a near sound becomes the nearest command ("go write one" cannot
     * come out). That reading is used when it parses and whisper was sure enough of it; otherwise (it
     * was not a command, or noise forced into one) the free decode is read as before. A search box's
     * mic is free text, so it skips the held pass. False goes back to the free decode only.
     */
    private const val GRAMMAR_FIRST = true

    /**
     * The lowest mean token log-probability a held reading is trusted at. Measured on noisy command
     * clips (Nov 5, small.en): commands heard right came out between -0.0 and -0.35, a mumbled
     * "pause" forced into "Home" or "Hold on" at -1.0 and below.
     */
    private const val HELD_MIN_MEAN_LOGPROB = -0.6f

    private class Recognized(val text: String, val intent: VoiceIntent, val how: String) {
        operator fun component1() = text
        operator fun component2() = intent
        operator fun component3() = how
    }

    @Volatile
    private var grammarCache: Pair<VoiceCommandPhrases, String>? = null

    /** The command grammar for the person's phrases, built again only when those change. */
    private fun grammar(): String {
        val phrases = _uiState.value.phrases
        grammarCache?.let { (forPhrases, text) -> if (forPhrases == phrases) return text }
        return voiceCommandGrammar(phrases).also { grammarCache = phrases to it }
    }

    private fun WhisperTranscriber.Held.describe() =
        "held \"${text.take(60)}\" ${"%.2f".format(meanLogProb)}"

    /** A push-to-talk utterance (or the Settings test): held first, then free. [scope]: a search box's mic. */
    private fun recognize(engine: WhisperTranscriber, samples: FloatArray, scope: VoiceSearchScope?): Recognized {
        val phrases = _uiState.value.phrases
        var heldNote = ""
        if (GRAMMAR_FIRST && scope == null) {
            val held = engine.transcribeHeld(samples, VOICE_GRAMMAR_COMMAND_RULE, grammar(), prompt())
            if (held != null) {
                val intent = parseVoiceIntent(held.text, phrases)
                if (intent !is VoiceIntent.Unknown && held.meanLogProb >= HELD_MIN_MEAN_LOGPROB) {
                    return Recognized(held.text, intent, held.describe())
                }
                heldNote = held.describe() + " rejected; "
            } else {
                heldNote = "held failed; "
            }
        }
        val text = engine.transcribe(samples, prompt())
        return Recognized(text, scopeVoiceIntent(parseVoiceIntent(text, phrases), scope), heldNote + "free")
    }

    /**
     * What to do with [command], heard after the name in [clip]. [held]: the wake decode it came from,
     * held to the grammar; when what followed the name does not parse there (cut short, or noise), the
     * clip is decoded freely once more and that reading is used. No confidence check: lyrics or
     * dialogue before the name are free text and drag the mean down, and the name was heard.
     */
    private fun recognizeAfterName(
        engine: WhisperTranscriber,
        clip: FloatArray,
        command: String,
        held: WhisperTranscriber.Held?,
    ): Recognized {
        val phrases = _uiState.value.phrases
        val intent = parseVoiceIntent(command, phrases)
        if (held == null || intent !is VoiceIntent.Unknown) {
            return Recognized(command, intent, held?.describe() ?: "free")
        }
        val free = engine.transcribe(clip, prompt() + " " + WAKE_WORD_PROMPT)
        val freeCommand = commandAfterWakeWord(free)?.takeIf { it.isNotEmpty() } ?: command
        return Recognized(freeCommand, parseVoiceIntent(freeCommand, phrases), held.describe() + " did not parse; free")
    }

    // ---- Shared ------------------------------------------------------------------------------------

    /**
     * One recorder at a time. Java Sound on Windows opens a microphone for one line only: a second
     * open while another is recording fails with "format ... not supported", which is what pressing
     * the voice key (or saying the wake word alone) did while the wake-word listener had the mic.
     * Push-to-talk and the test change the state that stops the listener, then wait here the ~20 ms
     * it takes to close its line.
     */
    private val microphoneLock = kotlinx.coroutines.sync.Mutex()

    private const val MICROPHONE_WAIT_MS = 2_000L

    private suspend fun openRecorder(microphone: String?, keep: Boolean = true, onFailure: (String) -> Unit): VoiceAudio.Recorder? {
        if (kotlinx.coroutines.withTimeoutOrNull(MICROPHONE_WAIT_MS) { microphoneLock.lock() } == null) {
            log.w { "The microphone is still in use by another recording" }
            onFailure("The microphone is busy")
            return null
        }
        return try {
            val (line, format) = VoiceAudio.openMicrophone(microphone)
            VoiceAudio.Recorder(line, format, keep, onClose = { microphoneLock.unlock() })
        } catch (error: Exception) {
            microphoneLock.unlock()
            log.w(error) { "Opening the microphone failed" }
            onFailure(error.message ?: "No microphone could be opened")
            null
        }
    }

    private fun reportEngineFailure(error: Throwable) {
        log.w(error) { "Could not start the speech engine" }
        if (error is UnsatisfiedLinkError) _uiState.update { it.copy(engineProblem = engineProblemText(error)) }
    }

    /**
     * Its own lock, not this object's: a model load takes ~0.3 s (longer from a cold disk), and the
     * voice key calls [toggleListening] on the UI thread.
     */
    private val engineLock = Any()

    private fun transcriberFor(file: File): Result<WhisperTranscriber> = synchronized(engineLock) {
        transcriber?.let { if (it.modelFile == file) return Result.success(it) }
        releaseTranscriber()
        WhisperTranscriber.open(file).onSuccess { transcriber = it }
    }

    private fun releaseTranscriber(onlyFor: File? = null): Unit = synchronized(engineLock) {
        val current = transcriber ?: return
        if (onlyFor != null && current.modelFile != onlyFor) return
        current.close()
        transcriber = null
    }

    private const val ENGINE_MISSING =
        "The speech engine (nuvio_whisper.dll) is not installed with this build of Nuvio."

    private fun engineProblemText(error: UnsatisfiedLinkError): String =
        if (WhisperLibrary.resolve() == null) ENGINE_MISSING
        else "The speech engine could not start on this PC (it needs a CPU with AVX2): ${error.message}"
}
