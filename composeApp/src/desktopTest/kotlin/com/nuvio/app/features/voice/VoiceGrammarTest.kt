package com.nuvio.app.features.voice

import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The generated command grammar. Its text is written to build/voice-grammar.gbnf for reading; the
 * real-engine half (needs nuvio_whisper.dll and -Pnuvio.whisperTestModel) checks that whisper's own
 * GBNF parser accepts it and that a held decode comes back with a confidence.
 */
class VoiceGrammarTest {
    private val model = System.getProperty("nuvio.whisperTestModel").orEmpty().takeIf { it.isNotBlank() }?.let(::File)

    @Test
    fun theGrammarIsWritten() {
        val grammar = voiceCommandGrammar(
            VoiceCommandPhrases.None.withPhrase(VoiceCommandKind.Pause, "hold everything")
                .withPhrase(VoiceCommandKind.SearchYouTube, "tube me"),
        )
        File("build").mkdirs()
        File("build/voice-grammar.gbnf").writeText(grammar)
        for (rule in listOf("root ::=", "wake ::=", "cmd ::=", "exact ::=", "search ::=", "custom ::=")) {
            assertTrue(grammar.lineSequence().any { it.startsWith(rule) }, "no $rule")
        }
        // Built from the parser's own tables: a word of every kind of command is in it.
        for (word in listOf("[Rr] [Ii] [Gg] [Hh] [Tt]", "[Pp] [Aa] [Uu] [Ss] [Ee]", "[Ss] [Oo] [Uu] [Rr] [Cc] [Ee]")) {
            assertTrue(word in grammar, "no $word")
        }
    }

    @Test
    fun whisperAcceptsItAndDecodesWithIt() {
        assumeTrue("no -Pnuvio.whisperTestModel", model?.isFile == true)
        assumeTrue("nuvio_whisper.dll not built", WhisperLibrary.resolve() != null)
        WhisperTranscriber.open(model!!).getOrThrow().use { engine ->
            // A second of a tone: nothing a command, so whatever comes out must still fit the grammar.
            val tone = FloatArray(VoiceAudio.SAMPLE_RATE) { i -> (0.2 * kotlin.math.sin(i * 2 * Math.PI * 220 / VoiceAudio.SAMPLE_RATE)).toFloat() }
            val held = engine.transcribeHeld(tone, VOICE_GRAMMAR_COMMAND_RULE, voiceCommandGrammar())
            assertNotNull(held, "whisper's parser rejected the grammar")
            println("tone -> \"${held.text}\" mean=${held.meanLogProb} min=${held.minLogProb}")
        }
    }
}
