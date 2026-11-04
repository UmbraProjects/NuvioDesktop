package com.nuvio.app.features.voice

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** fork:voice-control - see VoiceControl.kt. */
actual object VoiceControlRepository {
    /** In MACHINE_STORE_NAMES: which model and microphone this PC uses is not the account's. */
    internal const val STORE_NAME = "nuvio_voice_control"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MODEL = "model_id"
    private const val KEY_MICROPHONE = "microphone"

    private val log = Logger.withTag("Voice")
    private val store by lazy { DesktopStorage.store(STORE_NAME) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloader = VoiceModelDownloader()

    private val _uiState = MutableStateFlow(VoiceControlUiState())
    actual val uiState: StateFlow<VoiceControlUiState> = _uiState.asStateFlow()

    private var loaded = false
    private val downloads = mutableMapOf<String, Job>()
    private var testJob: Job? = null

    @Volatile
    private var stopRecording = false

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
    }

    /** A file of the catalogue's size is trusted; hashing 60 MB at every start is not worth it. */
    private fun installedState(model: VoiceModel): VoiceModelState {
        val file = modelFile(model)
        return if (file.isFile && file.length() == model.sizeBytes) VoiceModelState.Installed else VoiceModelState.NotInstalled
    }

    actual fun setEnabled(enabled: Boolean) {
        store.putBoolean(KEY_ENABLED, enabled)
        updateSettings { it.copy(enabled = enabled) }
    }

    actual fun setModel(modelId: String) {
        if (VoiceModels.byId(modelId) == null) return
        store.putString(KEY_MODEL, modelId)
        updateSettings { it.copy(modelId = modelId) }
    }

    actual fun setMicrophone(name: String?) {
        store.putString(KEY_MICROPHONE, name)
        updateSettings { it.copy(microphone = name) }
    }

    private fun updateSettings(transform: (VoiceControlSettings) -> VoiceControlSettings) {
        ensureLoaded()
        _uiState.update { it.copy(settings = transform(it.settings)) }
    }

    private fun setModelState(model: VoiceModel, state: VoiceModelState) {
        _uiState.update { it.copy(models = it.models + (model.id to state)) }
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
                if (!_uiState.value.selectedModelInstalled) setModel(model.id)
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

    @Synchronized
    actual fun startTest() {
        ensureLoaded()
        if (testJob?.isActive == true) return
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
                val samples = record(state.settings.microphone) ?: return@launch
                setTest(VoiceTestState.Transcribing)
                val engine = transcriberFor(modelFile(model)).getOrElse { error ->
                    log.w(error) { "Could not start the speech engine" }
                    if (error is UnsatisfiedLinkError) _uiState.update { it.copy(engineProblem = engineProblemText(error)) }
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
                // Nothing else uses the model yet (phase 2); keeping ~150 MB resident for the next
                // test is not worth it. Phase 3 keeps it loaded while voice control is enabled.
                releaseTranscriber()
            }
        }
    }

    /** Records until Stop or [VOICE_TEST_MAX_MS]; null (with the test failed) when no microphone opens. */
    private suspend fun record(microphone: String?): FloatArray? {
        val (line, format) = try {
            VoiceAudio.openMicrophone(microphone)
        } catch (error: Exception) {
            log.w(error) { "Opening the microphone failed" }
            setTest(VoiceTestState.Failed(error.message ?: "No microphone could be opened"))
            return null
        }
        val recorder = VoiceAudio.Recorder(line, format)
        val started = System.currentTimeMillis()
        var lastShown = 0L
        try {
            while (!stopRecording) {
                kotlin.coroutines.coroutineContext.ensureActive()
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

    @Synchronized
    private fun transcriberFor(file: File): Result<WhisperTranscriber> {
        transcriber?.let { if (it.modelFile == file) return Result.success(it) }
        releaseTranscriber()
        return WhisperTranscriber.open(file).onSuccess { transcriber = it }
    }

    @Synchronized
    private fun releaseTranscriber(onlyFor: File? = null) {
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
