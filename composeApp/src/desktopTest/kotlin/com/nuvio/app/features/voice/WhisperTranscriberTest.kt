package com.nuvio.app.features.voice

import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real engine on real speech: needs nuvio_whisper.dll (:composeApp:buildWindowsWhisper) and a
 * model passed as `-Pnuvio.whisperTestModel=<path to a ggml .bin>`; skipped otherwise. The audio is
 * whisper.cpp's own samples/jfk.wav.
 */
class WhisperTranscriberTest {
    private val model = System.getProperty("nuvio.whisperTestModel").orEmpty().takeIf { it.isNotBlank() }?.let(::File)
    private val audio = System.getProperty("nuvio.whisperTestAudio").orEmpty().takeIf { it.isNotBlank() }?.let(::File)

    @Test
    fun transcribesSpeechAndSilence() {
        assumeTrue("no -Pnuvio.whisperTestModel", model?.isFile == true)
        assumeTrue("no whisper.cpp samples/jfk.wav", audio?.isFile == true)
        assumeTrue("nuvio_whisper.dll not built", WhisperLibrary.resolve() != null)

        WhisperTranscriber.open(model!!).getOrThrow().use { engine ->
            val speech = VoiceAudio.readFile(audio!!)
            val started = System.nanoTime()
            val text = engine.transcribe(speech, prompt = null)
            val ms = (System.nanoTime() - started) / 1_000_000
            println("jfk.wav (${speech.size / 16} ms) -> \"$text\" in $ms ms")
            assertTrue(
                text.lowercase().contains("ask not what your country can do for you"),
                "heard \"$text\"",
            )

            // Silence must not come back as a command, whatever the model writes for it.
            val silence = engine.transcribe(FloatArray(VoiceAudio.SAMPLE_RATE * 2))
            println("2 s of silence -> \"$silence\"")
            assertEquals(VoiceIntent.Unknown(""), parseVoiceIntent(silence))
        }
    }

    @Test
    fun collapsesACommandSaidTwice() {
        assertEquals("Pause.", WhisperTranscriber.collapseRepeatedSentences("Pause. Pause."))
        assertEquals("Play.", WhisperTranscriber.collapseRepeatedSentences("Play. Play. play"))
        assertEquals("Go back.", WhisperTranscriber.collapseRepeatedSentences("Go back. Go back."))
        assertEquals("Search movies for Midsommar.", WhisperTranscriber.collapseRepeatedSentences("Search movies for Midsommar."))
        // Different sentences, or a repeat that is not back to back, stay as heard.
        assertEquals("Pause. Play.", WhisperTranscriber.collapseRepeatedSentences("Pause. Play."))
        assertEquals("Next. Back. Next.", WhisperTranscriber.collapseRepeatedSentences("Next. Back. Next."))
    }
}
