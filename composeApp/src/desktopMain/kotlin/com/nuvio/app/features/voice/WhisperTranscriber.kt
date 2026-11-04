package com.nuvio.app.features.voice

import co.touchlab.kermit.Logger
import java.io.File

/**
 * fork:voice-control - one loaded whisper model, kept in memory between commands (a load is
 * ~0.3 s for base.en). Calls are serialised: a whisper_context is not safe to share between
 * concurrent decodes.
 */
internal class WhisperTranscriber private constructor(val modelFile: File, private var handle: Long) : AutoCloseable {

    /** What was said in [samples] (16 kHz mono, -1..1), trimmed; empty for nothing or a failed decode. */
    @Synchronized
    fun transcribe(samples: FloatArray, prompt: String? = COMMAND_PROMPT): String {
        check(handle != 0L) { "transcriber is closed" }
        if (samples.isEmpty()) return ""
        return WhisperNative.nativeTranscribe(handle, samples, prompt, decodeThreads())?.trim().orEmpty()
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            WhisperNative.nativeFree(handle)
            handle = 0L
        }
    }

    companion object {
        private val log = Logger.withTag("Voice")

        /**
         * Seen by the decoder as text that came before the utterance, so the command words and the
         * tab names come out spelled the way [parseVoiceIntent] expects ("YouTube", not "You Tube").
         */
        const val COMMAND_PROMPT =
            "Search YouTube for cat videos. Search movies for Midsommar. Search music for a song. " +
                "Open settings. Open library. Go home. Play. Pause. Next. Go back."

        /** Loads [modelFile]; the failure says why (no DLL, unreadable model). */
        fun open(modelFile: File): Result<WhisperTranscriber> = WhisperLibrary.load().mapCatching {
            if (!modelFile.isFile) error("${modelFile.name} is not downloaded")
            val handle = WhisperNative.nativeInit(modelFile.absolutePath)
            if (handle == 0L) error("${modelFile.name} could not be loaded; delete it and download it again")
            log.i { "Loaded ${modelFile.name}; ${WhisperNative.nativeSystemInfo().trim()}" }
            WhisperTranscriber(modelFile, handle)
        }

        /** Half the logical cores, 2..4: past 4 whisper's small models stop getting faster, and the UI keeps a core. */
        fun decodeThreads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
    }
}
