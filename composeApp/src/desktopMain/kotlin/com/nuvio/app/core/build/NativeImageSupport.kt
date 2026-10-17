package com.nuvio.app.core.build

import java.io.File

/**
 * GraalVM native-image spike (Docs/Gate0-Performance-Baseline-2026-10-16.md).
 *
 *  - [inImage]: this process is a native image. There is no class loading to warm, so the startup
 *    preloader and the class archive have nothing to do, and `java.home` is unset.
 *  - [tracing]: an ordinary JVM run under `native-image-agent` recording the image's config. The
 *    preloader must stay off here too: its `Class.forName` replay would register every one of its
 *    ~14.6k classes for reflection and drag them all into the image.
 */
internal object NativeImageSupport {

    val inImage: Boolean = System.getProperty("org.graalvm.nativeimage.imagecode") != null

    val tracing: Boolean = System.getProperty("nuvio.nativeImageTracing") != null

    /** `NUVIO_NATIVE_SELFTEST` is set: a `nativeSmoke` launch (plan step 5), not a user's. */
    val selfTestRequested: Boolean = !System.getenv("NUVIO_NATIVE_SELFTEST").isNullOrBlank()

    /**
     * Only with `NUVIO_NATIVE_SELFTEST` set (the `nativeSmoke` gate, plan step 5): exercises what a
     * signed-out launch never reaches, without user input, one line per probe, then
     * `(NativeSelfTest) done passed=N failed=M`, which the smoke script waits for. Each probe stands
     * for a native failure seen before: plugins (QuickJS JNI), backdrop framing (onnxruntime),
     * paste (clipboard), sign-in (reflective serializer lookup), file dialogs (Windows L&F UI
     * classes and icons). Logs counts, never clipboard contents.
     */
    fun startSelfTestIfRequested() {
        if (!selfTestRequested) return
        Thread {
            Thread.sleep(3_000)
            var passed = 0
            var failed = 0
            fun probe(name: String, check: () -> String) {
                runCatching(check)
                    .onSuccess { detail -> passed++; println("Info: (NativeSelfTest) $name ok $detail") }
                    .onFailure { error ->
                        failed++
                        System.err.println("Error: (NativeSelfTest) $name FAILED: $error")
                        error.printStackTrace()
                    }
            }
            probe("clipboard") {
                val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                val flavors = clipboard.getContents(null)?.transferDataFlavors?.size ?: 0
                val hasText = clipboard.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.stringFlavor)
                "flavors=$flavors hasText=$hasText"
            }
            // The updater offers an update on every launch when it cannot identify the build.
            probe("build stamp") { checkNotNull(PackagedBuildInfo.current?.id) { "no build stamp" } }
            // Runtime (reflective) serializer lookup, the path Ktor/Supabase take for request and
            // response bodies; a sign-in failed here with "Serializer for class 'JsonLiteral'".
            // Not JsonLiteral: it has no serializer on the JVM either (Ktor only reaches it after
            // the JsonObject lookup has already failed).
            listOf(
                kotlinx.serialization.json.JsonObject::class.java,
                io.github.jan.supabase.auth.user.UserSession::class.java,
            ).forEach { type ->
                probe("serializer ${type.simpleName}") { kotlinx.serialization.serializer(type).descriptor.serialName }
            }
            // A whole scraper run: the QuickJS DLL from jni/windows_x64, the host bindings, an
            // async result and a logged object (both crossed into Java and failed natively once).
            probe("plugin runtime") {
                val results = kotlinx.coroutines.runBlocking {
                    com.nuvio.app.features.plugins.PluginRuntime.executePlugin(
                        code = """
                            module.exports.getStreams = async function(tmdbId) {
                                console.log("selftest", { id: tmdbId });
                                return [{ title: "selftest " + tmdbId, url: "https://example.invalid/a.mp4" }];
                            };
                        """.trimIndent(),
                        tmdbId = "603",
                        mediaType = "movie",
                        season = null,
                        episode = null,
                        scraperId = "native-selftest",
                    )
                }
                check(results.singleOrNull()?.title == "selftest 603") { "unexpected results: $results" }
                "1 stream"
            }
            // A JS error crossing into Java: quickjs-kt converts an unhandled rejection's Error and
            // probes FindClass("Error") on the way. With -H:+JNIVerboseLookupErrors that failed
            // lookup dumped to a stderr a double-clicked exe does not have, and SVM aborted
            // (2026-10-17). The rejection surfacing as a QuickJsException is expected; passing
            // means the process is still here to print the line.
            probe("plugin js error") {
                val outcome = runCatching { kotlinx.coroutines.runBlocking {
                    com.nuvio.app.features.plugins.PluginRuntime.executePlugin(
                        code = """
                            module.exports.getStreams = async function() {
                                Promise.reject(new Error("selftest rejection"));
                                try { null.boom(); } catch (e) { console.log("caught", e); }
                                return [];
                            };
                        """.trimIndent(),
                        tmdbId = "603",
                        mediaType = "movie",
                        season = null,
                        episode = null,
                        scraperId = "native-selftest-error",
                    )
                } }
                "survived (${outcome.exceptionOrNull()?.javaClass?.simpleName ?: "${outcome.getOrNull()?.size} streams"})"
            }
            probe("backdrop framing models") {
                check(com.nuvio.app.features.home.components.HeroBackdropFramer.modelsAvailable()) {
                    "onnxruntime or the models did not load (see the HeroBackdropFraming warning)"
                }
                "onnxruntime loaded"
            }
            // Builds the chooser the way the app's pickers do (system look and feel first; see
            // LocalDirectoryPicker), classes and icons included, without showing it. The outcome
            // is carried out by hand: an Error thrown on the EDT does not reliably come back out
            // of invokeAndWait (the first smoke run logged "ok" over "no ComponentUI class").
            probe("file chooser") {
                var outcome: Result<String> = Result.failure(IllegalStateException("did not run"))
                javax.swing.SwingUtilities.invokeAndWait {
                    outcome = runCatching {
                        javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName())
                        val chooser = javax.swing.JFileChooser()
                        "${chooser.ui.javaClass.simpleName} filters=${chooser.choosableFileFilters.size}"
                    }
                }
                outcome.getOrThrow()
            }
            println("Info: (NativeSelfTest) done passed=$passed failed=$failed")
        }.apply { name = "nuvio-native-selftest"; isDaemon = true }.start()
    }

    /**
     * First thing in `main`. A native image has no `java.home` and no jpackage launcher passing
     * the skiko and Compose-resources properties, so recreate the packaged layout around the exe:
     * `java.home` = `<exe dir>\runtime` (skiko opens `bin\jawt.dll` there, and the player bridge,
     * engine, updater and backdrop framer all take its parent as the install folder), and the skiko
     * DLL plus Compose resources under `<exe dir>\app`, where jpackage puts them.
     */
    fun configureRuntime() {
        // A nativeSmoke launch runs against a throwaway data directory (stores, logs, the
        // single-instance lock), never the user's profile. An env var because the smoke script
        // sets it per process; native-image -D flags are build-time.
        if (selfTestRequested && System.getProperty("nuvio.desktop.dataDir").isNullOrBlank()) {
            System.getenv("NUVIO_NATIVE_SELFTEST_DATA_DIR")?.takeIf(String::isNotBlank)
                ?.let { System.setProperty("nuvio.desktop.dataDir", it) }
        }
        // ...and signed out: the auth session is stored machine-wide, outside the data directory.
        if (selfTestRequested) com.nuvio.app.core.network.SupabaseProvider.memoryOnlySession = true
        if (!inImage) return
        val exeDir = runCatching {
            ProcessHandle.current().info().command().orElse(null)?.let { File(it).parentFile }
        }.getOrNull() ?: File(System.getProperty("user.dir"))
        captureConsoleForQaBuild(exeDir)
        fun default(key: String, value: String) {
            if (System.getProperty(key).isNullOrBlank()) System.setProperty(key, value)
        }
        // native-image applies -D flags at BUILD time only; the launcher's -D options (renderer,
        // sun.java2d.d3d=false, app version, ...) have to be applied here at run time instead.
        val appDir = File(exeDir, "app")
        runCatching {
            File(appDir, "Nuvio.cfg").readLines()
                .map { it.trim() }
                .filter { it.startsWith("java-options=-D") }
                .map { it.removePrefix("java-options=-D").replace("\$APPDIR", appDir.absolutePath) }
                .forEach { option ->
                    val eq = option.indexOf('=')
                    if (eq > 0) default(option.substring(0, eq), option.substring(eq + 1))
                }
        }
        // skiko switches Swing to the Windows look and feel when it first loads, and that LAF's
        // native theme code (ThemeReader) makes JNI calls the image has no metadata for; whichever
        // thread loads skiko first then poisons FontMgr's static init and every Compose text use
        // after it. Compose draws its own UI, so leave Swing on its default LAF in the image.
        default("skiko.rendering.laf.global", "false")
        // The jpackage launcher sets this to its own exe; PackagedResources (backdrop-framing
        // models, TorrServer), relaunch, logging and the URL scheme all find the install by it.
        runCatching { ProcessHandle.current().info().command().orElse(null) }.getOrNull()
            ?.let { default("jpackage.app-path", it) }
        default("java.home", File(exeDir, "runtime").absolutePath)
        default("skiko.library.path", File(exeDir, "app").absolutePath)
        default("compose.application.resources.dir", File(exeDir, "app/resources").absolutePath)
    }

    /**
     * QA builds (`gradlew createNativeDistributable -Pnuvio.native.qa=true`, plan step 3) are
     * compiled to *report* a missing reflection / JNI / resource registration on the console and
     * carry on, instead of failing somewhere later. The exe is a windowed program with no console,
     * so when the build's `native-qa` marker sits beside it, everything written to stdout and stderr
     * is also copied to `native-qa-console.log` there. Grep that file for "Missing".
     */
    private fun captureConsoleForQaBuild(exeDir: File) {
        if (!File(exeDir, QaMarkerFileName).isFile) return
        runCatching {
            val log = java.io.FileOutputStream(File(exeDir, QaConsoleLogFileName), true)
            fun tee(original: java.io.PrintStream) = java.io.PrintStream(
                object : java.io.OutputStream() {
                    override fun write(b: Int) {
                        original.write(b)
                        synchronized(log) { log.write(b) }
                    }

                    override fun write(b: ByteArray, off: Int, len: Int) {
                        original.write(b, off, len)
                        synchronized(log) { log.write(b, off, len) }
                    }

                    override fun flush() {
                        original.flush()
                        synchronized(log) { log.flush() }
                    }
                },
                true,
                Charsets.UTF_8,
            )
            System.setOut(tee(System.out))
            System.setErr(tee(System.err))
            println("Info: (NativeQa) console captured; build reports missing registrations instead of failing")
        }
    }

    private const val QaMarkerFileName = "native-qa"
    private const val QaConsoleLogFileName = "native-qa-console.log"
}
