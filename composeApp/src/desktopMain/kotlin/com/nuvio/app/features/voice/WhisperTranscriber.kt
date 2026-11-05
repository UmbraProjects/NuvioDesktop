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
        val started = System.nanoTime()
        val text = WhisperNative.nativeTranscribe(handle, samples, prompt, decodeThreads())?.trim().orEmpty()
        lastDecodeMs = (System.nanoTime() - started) / 1_000_000
        return collapseRepeatedSentences(text)
    }

    /** A decode held to a grammar: what came out and how sure whisper was of it. */
    class Held(val text: String, val meanLogProb: Float, val minLogProb: Float)

    /**
     * [samples] decoded with the output held to [grammar] (GBNF) from [startRule]; null when the
     * grammar does not parse or decoding failed. Times like [transcribe].
     */
    @Synchronized
    fun transcribeHeld(samples: FloatArray, startRule: String, grammar: String, prompt: String? = COMMAND_PROMPT): Held? {
        check(handle != 0L) { "transcriber is closed" }
        if (samples.isEmpty()) return null
        val started = System.nanoTime()
        val raw = WhisperNative.nativeTranscribeWithGrammar(handle, samples, prompt, decodeThreads(), grammar, startRule)
        lastDecodeMs = (System.nanoTime() - started) / 1_000_000
        raw ?: return null
        val parts = raw.split('\t', limit = 3)
        if (parts.size < 3) return null
        return Held(
            text = collapseRepeatedSentences(parts[2].trim()),
            meanLogProb = parts[0].toFloatOrNull() ?: return null,
            minLogProb = parts[1].toFloatOrNull() ?: return null,
        )
    }

    /**
     * How long the last decode took, null before the first. It is what decides whether live
     * previews can run without holding up the final decode (see the listen loop).
     */
    @Volatile
    var lastDecodeMs: Long? = null
        private set

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
         * "Pause. Pause." -> "Pause.": whisper's typical slip on a short clip is to say the command
         * again, and nobody gives one command twice in one breath. Only whole sentences repeated
         * back to back go; anything else is left as heard.
         */
        internal fun collapseRepeatedSentences(text: String): String {
            val sentences = Regex("""[^.!?]+[.!?]*""").findAll(text).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()
            if (sentences.size < 2) return text
            fun key(sentence: String) = sentence.trimEnd('.', '!', '?').trim().lowercase()
            val kept = sentences.filterIndexed { i, sentence -> i == 0 || key(sentence) != key(sentences[i - 1]) }
            return if (kept.size == sentences.size) text else kept.joinToString(" ")
        }

        /**
         * Seen by the decoder as text that came before the utterance, so the command words and the
         * tab names come out spelled the way [parseVoiceIntent] expects ("YouTube", not "You Tube").
         */
        const val COMMAND_PROMPT =
            "Search YouTube for cat videos. Search movies for Midsommar. Search music for a song. " +
                "Open settings. Open library. Go home. Play. Pause. Next. Go back. Go right one. Go left two. " +
                "Go down three rows. Up. Select. Source 3. Skip the intro. Stop playback. Volume up. Volume down. " +
                "Mute. Start over. Go to 20 minutes. Next subtitles. Previous episode."

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
