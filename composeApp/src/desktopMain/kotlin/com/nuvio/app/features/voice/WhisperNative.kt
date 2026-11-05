package com.nuvio.app.features.voice

import java.io.File

/**
 * fork:voice-control - the JNI surface of nuvio_whisper.dll (src/desktopMain/native/whisper-jni).
 * The JNI symbols are named for this exact class; rename both together.
 */
internal object WhisperNative {
    /** A whisper_context for the model at [modelPath]; 0 when it could not be loaded. */
    external fun nativeInit(modelPath: String): Long

    /** The text heard in [samples] (16 kHz mono, -1..1); null when decoding failed. */
    external fun nativeTranscribe(handle: Long, samples: FloatArray, initialPrompt: String?, threads: Int): String?

    /**
     * [samples] decoded with the output held to the GBNF [grammar] from [startRule]:
     * "<mean log-probability>\t<min log-probability>\t<text>"; null when the grammar does not parse
     * or decoding failed.
     */
    external fun nativeTranscribeWithGrammar(
        handle: Long,
        samples: FloatArray,
        initialPrompt: String?,
        threads: Int,
        grammar: String,
        startRule: String,
    ): String?

    external fun nativeFree(handle: Long)

    /** ggml's CPU feature line ("AVX = 1 | AVX2 = 1 | ..."), for the log. */
    external fun nativeSystemInfo(): String
}

/**
 * Finds and loads nuvio_whisper.dll, the same way NuvioEngineLibrary finds nuvio_engine.dll: beside
 * the app runtime first, then the Gradle build output for a dev run; `-Dnuvio.whisper.library` or
 * `NUVIO_WHISPER_LIBRARY` wins over both.
 */
internal object WhisperLibrary {
    const val LIBRARY_FILE_NAME = "nuvio_whisper.dll"

    @Volatile
    private var loaded: Result<File>? = null

    fun resolve(): File? {
        configuredPath()?.let { return it.takeIf(File::isFile) }
        return candidates().firstOrNull(File::isFile)
    }

    /** Loads the DLL once; the failure (missing file, CPU without AVX2, ...) is kept and returned again. */
    @Synchronized
    fun load(): Result<File> {
        loaded?.let { return it }
        val result = runCatching {
            val library = resolve() ?: throw UnsatisfiedLinkError(
                "$LIBRARY_FILE_NAME was not found beside the app; run :composeApp:buildWindowsWhisper",
            )
            System.load(library.absolutePath)
            library
        }
        loaded = result
        return result
    }

    private fun configuredPath(): File? =
        (System.getProperty("nuvio.whisper.library")?.takeIf { it.isNotBlank() }
            ?: System.getenv("NUVIO_WHISPER_LIBRARY")?.takeIf { it.isNotBlank() })
            ?.let(::File)

    private fun candidates(): List<File> {
        val packaged = System.getProperty("java.home")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.parentFile
            ?.resolve(LIBRARY_FILE_NAME)
        if (!com.nuvio.app.core.build.DesktopDevRun.allowsLocalNativeBuilds) return listOfNotNull(packaged)
        return listOfNotNull(
            packaged,
            File("composeApp/build/native/windows/$LIBRARY_FILE_NAME"),
            File("build/native/windows/$LIBRARY_FILE_NAME"),
        )
    }
}
