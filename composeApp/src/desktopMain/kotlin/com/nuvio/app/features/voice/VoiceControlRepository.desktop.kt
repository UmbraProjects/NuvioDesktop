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
        )
        _uiState.update {
            it.copy(
                settings = settings,
                models = VoiceModels.all.associate { model -> model.id to installedState(model) },
                engineProblem = if (WhisperLibrary.resolve() == null) ENGINE_MISSING else null,
                modelsFolder = modelsDir.absolutePath,
            )
        }
        refreshMicrophones()
        warmUp()
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
                val text = engine.transcribe(samples)
                val decodeMs = (System.nanoTime() - started) / 1_000_000
                val audioMs = samples.size * 1000L / VoiceAudio.SAMPLE_RATE
                log.i { "Test: ${audioMs}ms of audio decoded in ${decodeMs}ms with ${model.id}" }
                setTest(VoiceTestState.Heard(text, parseVoiceIntent(text), audioMs, decodeMs))
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
    actual fun toggleListening() {
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
        _session.value = VoiceSessionState.Listening(level = 0f, transcript = "", heardSpeech = false)
        val model = state.selectedModel
        val settings = state.settings
        sessionJob = scope.launch {
            try {
                listen(model, settings)
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

    private suspend fun listen(model: VoiceModel, settings: VoiceControlSettings) {
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
        val previewAllowed = AtomicBoolean(true)
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
                        val text = engine.transcribe(soFar)
                        if (System.currentTimeMillis() - started > PREVIEW_TOO_SLOW_MS) previewAllowed.set(false)
                        transcript.set(text)
                    }
                }
                if (now - lastShownAt >= 60) {
                    lastShownAt = now
                    _session.value = VoiceSessionState.Listening(VoiceAudio.level(chunk), transcript.get(), detector.heardSpeech)
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
        val text = engine.transcribe(samples)
        val decodeMs = (System.nanoTime() - started) / 1_000_000
        val intent = parseVoiceIntent(text)
        log.i { "Heard ${samples.size * 1000L / VoiceAudio.SAMPLE_RATE}ms in ${decodeMs}ms: ${describeVoiceIntent(intent)}" }
        if (intent is VoiceIntent.Unknown && intent.text.isEmpty()) {
            showResult(VoiceSessionState.Failed("Didn't catch that"))
            return
        }
        showResult(VoiceSessionState.Heard(text, intent))
        if (intent !is VoiceIntent.Unknown) _intents.tryEmit(intent)
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

    // ---- Shared ------------------------------------------------------------------------------------

    private fun openRecorder(microphone: String?, onFailure: (String) -> Unit): VoiceAudio.Recorder? =
        try {
            val (line, format) = VoiceAudio.openMicrophone(microphone)
            VoiceAudio.Recorder(line, format)
        } catch (error: Exception) {
            log.w(error) { "Opening the microphone failed" }
            onFailure(error.message ?: "No microphone could be opened")
            null
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
