package com.nuvio.app.features.music.potoken

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/*
 * fork:potoken
 *
 * A headless Edge / Chrome that exists to run Google's BotGuard script and nothing else.
 *
 * Why a real browser: BotGuard inspects the environment it runs in and the service answers `GenerateIT`
 * with no integrity token when it does not look like a browser. A jsdom stand-in passes; a hand-written
 * shim in QuickJS does not (measured). Edge ships with Windows, so nothing has to be bundled, and the
 * repo already drives a headless browser this way for the YouTube Music sign-in.
 *
 * The page is served by DevTools interception on a real `https://www.youtube.com/...` URL, so it runs with
 * that origin (as BitChord's WebView does with `loadDataWithBaseURL`), and every other request the page
 * makes is refused: the script cannot reach the network, and Kotlin does the two service calls itself.
 * The profile is a throwaway directory, deleted on close.
 */
internal class BotGuardBrowser private constructor(
    private val process: Process,
    private val profileDir: Path,
    private val cdp: CdpClient,
    private val sessionId: String,
    /** Set when the page threw an error nobody caught: Google's script is in an unknown state. */
    private val pageFailed: AtomicBoolean,
) {
    val isDead: Boolean get() = cdp.isClosed || !process.isAlive || pageFailed.get()

    /** Evaluates [expression] in the page, awaits a returned promise, and returns its string value. */
    suspend fun evaluate(expression: String, timeoutMs: Long): String {
        val reply = cdp.call(
            method = "Runtime.evaluate",
            params = buildJsonObject {
                put("expression", expression)
                put("awaitPromise", true)
                put("returnByValue", true)
            },
            sessionId = sessionId,
            timeoutMs = timeoutMs,
        )
        reply["exceptionDetails"]?.let { details ->
            val exception = details.jsonObject["exception"]?.jsonObject
            val text = exception?.get("description")?.jsonPrimitive?.contentOrNull
                ?: exception?.get("value")?.jsonPrimitive?.contentOrNull
                ?: details.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                ?: "script error"
            throw PoTokenException("The page failed: ${text.lineSequence().first().take(300)}")
        }
        return reply["result"]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull
            ?: throw PoTokenException("The page returned no value")
    }

    fun close() {
        runCatching { cdp.close() }
        // Off the caller's thread: a browser can take a moment to let go of its files.
        Thread({ stop(process, profileDir) }, "potoken-browser-cleanup").apply { isDaemon = true }.start()
    }

    companion object {
        private val log = Logger.withTag("PoTokenBrowser")

        const val PAGE_URL = "https://www.youtube.com/__nuvio_potoken"
        private const val DEVTOOLS_PORT_TIMEOUT_MS = 20_000L
        private const val PAGE_LOAD_TIMEOUT_MS = 15_000L

        /** Extra command-line switches, space separated. Tests use it for `--no-sandbox` in a container. */
        private fun extraArguments(): List<String> =
            System.getProperty("nuvio.potoken.browserArgs").orEmpty().split(' ').filter { it.isNotBlank() }

        fun command(browser: String, profileDir: Path): List<String> = listOf(
            browser,
            "--user-data-dir=$profileDir",
            // Port 0 = pick a free one; it is written to <profile>/DevToolsActivePort.
            "--remote-debugging-port=0",
            "--headless=new",
            "--disable-gpu",
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-extensions",
            "--disable-sync",
            "--disable-background-networking",
            "--disable-component-update",
            "--mute-audio",
        ) + extraArguments() + "about:blank"

        suspend fun launch(browserPath: String): BotGuardBrowser {
            val profileDir = Files.createTempDirectory("nuvio-potoken")
            var process: Process? = null
            var cdp: CdpClient? = null
            try {
                process = ProcessBuilder(command(browserPath, profileDir))
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
                val endpoint = awaitDevToolsEndpoint(profileDir, process)
                    ?: throw PoTokenException("The browser did not open a debugging port")
                val client = CdpClient.connect(endpoint).also { cdp = it }

                val target = client.call(
                    "Target.createTarget",
                    buildJsonObject { put("url", "about:blank") },
                )["targetId"]?.jsonPrimitive?.contentOrNull ?: throw PoTokenException("No tab was created")
                val session = client.call(
                    "Target.attachToTarget",
                    buildJsonObject { put("targetId", target); put("flatten", true) },
                )["sessionId"]?.jsonPrimitive?.contentOrNull ?: throw PoTokenException("Could not attach to the tab")

                // Serve our page on the YouTube origin; refuse everything else.
                val loaded = CompletableDeferred<Unit>()
                val pageFailed = AtomicBoolean(false)
                client.onEvent { event ->
                    if (event.sessionId != session) return@onEvent
                    when (event.method) {
                        "Page.loadEventFired" -> loaded.complete(Unit)
                        "Runtime.exceptionThrown" -> {
                            // BitChord does the same for "Uncaught" console errors: replace the page at once
                            // rather than find out from a mint that times out.
                            val text = event.params["exceptionDetails"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                            log.w { "Uncaught error in the BotGuard page: $text" }
                            pageFailed.set(true)
                        }
                        "Fetch.requestPaused" -> answerRequest(client, session, event.params)
                    }
                }
                client.call(
                    "Fetch.enable",
                    buildJsonObject { put("patterns", JsonArray(listOf(buildJsonObject { put("urlPattern", "*") }))) },
                    session,
                )
                client.call("Page.enable", sessionId = session)
                client.call("Runtime.enable", sessionId = session)
                // New-style headless Chrome says "HeadlessChrome" in its user agent. BitChord's WebView presents a
                // plain Chrome one, so do the same: the browser's own string with the "Headless" taken out.
                val ownAgent = runCatching {
                    client.call("Browser.getVersion")["userAgent"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                client.call(
                    "Emulation.setUserAgentOverride",
                    buildJsonObject {
                        put("userAgent", ownAgent?.replace("HeadlessChrome", "Chrome") ?: PoTokenProtocol.USER_AGENT)
                    },
                    session,
                )
                client.call("Page.navigate", buildJsonObject { put("url", PAGE_URL) }, session)
                withTimeout(PAGE_LOAD_TIMEOUT_MS) { loaded.await() }

                return BotGuardBrowser(process, profileDir, client, session, pageFailed)
            } catch (error: Throwable) {
                runCatching { cdp?.close() }
                process?.let { stop(it, profileDir) } ?: deleteQuietly(profileDir)
                if (error is CancellationException) throw error
                throw error as? PoTokenException ?: PoTokenException("The browser could not be started: ${error.message}", error)
            }
        }

        private suspend fun answerRequest(client: CdpClient, session: String, params: JsonObject) {
            val requestId = params["requestId"]?.jsonPrimitive?.contentOrNull ?: return
            val url = params["request"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
            try {
                if (url == PAGE_URL) {
                    client.call(
                        "Fetch.fulfillRequest",
                        buildJsonObject {
                            put("requestId", requestId)
                            put("responseCode", 200)
                            put(
                                "responseHeaders",
                                JsonArray(listOf(buildJsonObject { put("name", "Content-Type"); put("value", "text/html; charset=utf-8") })),
                            )
                            put("body", Base64.getEncoder().encodeToString(BotGuardPage.HTML.toByteArray(Charsets.UTF_8)))
                        },
                        session,
                    )
                } else {
                    client.call(
                        "Fetch.failRequest",
                        buildJsonObject { put("requestId", requestId); put("errorReason", "BlockedByClient") },
                        session,
                    )
                }
            } catch (error: PoTokenException) {
                log.d { "Could not answer a page request: ${error.message}" }
            }
        }

        private suspend fun awaitDevToolsEndpoint(profileDir: Path, process: Process): String? {
            val file = profileDir.resolve("DevToolsActivePort")
            val deadline = System.currentTimeMillis() + DEVTOOLS_PORT_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline && process.isAlive) {
                val lines = runCatching { Files.readAllLines(file) }.getOrNull().orEmpty()
                val port = lines.getOrNull(0)?.trim()?.toIntOrNull()
                val path = lines.getOrNull(1)?.trim()?.takeIf { it.startsWith("/") }
                if (port != null && path != null) return "ws://127.0.0.1:$port$path"
                delay(100)
            }
            return null
        }

        private fun stop(process: Process, profileDir: Path) {
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                runCatching { process.descendants().forEach { it.destroyForcibly() } }
                process.destroyForcibly()
                process.waitFor(3, TimeUnit.SECONDS)
            }
            deleteQuietly(profileDir)
        }

        private fun deleteQuietly(dir: Path) {
            // The browser can hold files for a moment after it exits.
            repeat(5) {
                runCatching {
                    Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
                }
                if (!Files.exists(dir)) return
                Thread.sleep(300)
            }
            log.w { "Could not remove the temporary browser profile $dir" }
        }
    }
}

/**
 * Where an installed Edge / Chrome / Brave / Chromium is. Edge comes first on Windows because it ships
 * with the system. `-Dnuvio.potoken.browser=<path>` overrides, then the sign-in's `nuvio.youtubeMusic.browser`.
 */
internal object BrowserLocator {
    fun find(): String? {
        for (property in listOf("nuvio.potoken.browser", "nuvio.youtubeMusic.browser")) {
            System.getProperty(property)?.takeIf { Files.isExecutable(Path.of(it)) }?.let { return it }
        }
        return candidates().firstOrNull { Files.isExecutable(Path.of(it)) }
    }

    internal fun candidates(
        os: String = System.getProperty("os.name").orEmpty().lowercase(),
        env: (String) -> String? = System::getenv,
    ): List<String> = when {
        "win" in os -> {
            val roots = listOfNotNull(env("ProgramFiles"), env("ProgramFiles(x86)"), env("LOCALAPPDATA"))
            listOf(
                "Microsoft\\Edge\\Application\\msedge.exe",
                "Google\\Chrome\\Application\\chrome.exe",
                "BraveSoftware\\Brave-Browser\\Application\\brave.exe",
            ).flatMap { relative -> roots.map { root -> "$root\\$relative" } }
        }
        "mac" in os || "darwin" in os -> listOf(
            "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser",
            "/Applications/Chromium.app/Contents/MacOS/Chromium",
        )
        else -> listOf(
            "microsoft-edge", "microsoft-edge-stable", "google-chrome", "google-chrome-stable",
            "brave-browser", "chromium", "chromium-browser",
        ).mapNotNull { name ->
            env("PATH").orEmpty().split(java.io.File.pathSeparatorChar)
                .filter { it.isNotBlank() }
                .map { Path.of(it, name) }
                .firstOrNull { Files.isExecutable(it) }
                ?.toString()
        }
    }
}

/** The page the browser serves itself: BotGuard's VM, loaded and driven through three functions. */
internal object BotGuardPage {
    /** Runs the challenge's script and program; resolves to the botguard response string. */
    fun runExpression(challenge: PoTokenProtocol.Challenge): String {
        val argument = buildJsonObject {
            put("script", challenge.script)
            put("program", challenge.program)
            put("globalName", challenge.globalName)
        }
        return "__pot.run($argument)"
    }

    /** Builds the minter from the integrity token (web-safe base64). Resolves to "ok". */
    fun initExpression(integrityTokenWebSafe: String): String = "__pot.init(${JsonPrimitive(integrityTokenWebSafe)})"

    /** Resolves to the PO token (web-safe base64) bound to [identifier]. */
    fun mintExpression(identifier: String): String = "__pot.mint(${JsonPrimitive(identifier)})"

    const val HTML = """<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><title></title><script>
(function () {
  var b64u8 = function (s) {
    var r = atob(s.replace(/-/g, '+').replace(/_/g, '/').replace(/\./g, '='));
    var u = new Uint8Array(r.length);
    for (var i = 0; i < r.length; i++) u[i] = r.charCodeAt(i);
    return u;
  };
  var u8b64url = function (u) {
    var s = '';
    for (var i = 0; i < u.length; i++) s += String.fromCharCode(u[i]);
    return btoa(s).replace(/\+/g, '-').replace(/\//g, '_');
  };
  var within = function (ms, what, start) {
    return new Promise(function (resolve, reject) {
      var t = setTimeout(function () { reject(new Error(what + ' timed out')); }, ms);
      start(function (v) { clearTimeout(t); resolve(v); }, function (e) { clearTimeout(t); reject(e); });
    });
  };
  window.__pot = {
    out: [],
    minter: null,
    run: function (c) {
      var self = this;
      (0, eval)(c.script);
      var vm = window[c.globalName];
      if (!vm || !vm.a) return Promise.reject(new Error('BotGuard VM missing'));
      return within(10000, 'BotGuard setup', function (resolve) {
        vm.a(c.program, function (snapshot) { resolve(snapshot); }, true, undefined, function () {}, [[], []],
             undefined, false, [function () {}, function () {}, function () {}, function () {}, function () {}]);
      }).then(function (snapshot) {
        return within(10000, 'BotGuard snapshot', function (resolve) {
          snapshot(function (response) { resolve(response); }, [undefined, undefined, self.out, undefined]);
        });
      });
    },
    init: function (integrityToken) {
      var self = this;
      var factory = self.out[0];
      if (typeof factory !== 'function') return Promise.reject(new Error('BotGuard gave no minter'));
      return Promise.resolve(factory(b64u8(integrityToken))).then(function (minter) {
        if (typeof minter !== 'function') throw new Error('BotGuard minter is not a function');
        self.minter = minter;
        return 'ok';
      });
    },
    mint: function (identifier) {
      if (!this.minter) return Promise.reject(new Error('minter not ready'));
      return Promise.resolve(this.minter(new TextEncoder().encode(identifier))).then(function (bytes) {
        if (!(bytes instanceof Uint8Array)) throw new Error('minter returned ' + Object.prototype.toString.call(bytes));
        return u8b64url(bytes);
      });
    }
  };
})();
</script></head><body></body></html>
"""
}
