package com.nuvio.app.features.youtubemusic

import co.touchlab.kermit.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/*
 * fork:ytmusic-login
 *
 * Signs in to YouTube Music in two steps, so that Google never sees an automated browser:
 *
 *  1. Google's real login page opens in a throwaway Edge / Chrome / Brave / Chromium window with a
 *     temporary profile and NO debugging port or automation flag. The user signs in (passkeys,
 *     security keys and 2-step prompts all work) and closes the window.
 *  2. The same profile is reopened headless with a debugging port, only to read its cookies, which
 *     the browser decrypts itself, and is then deleted.
 *
 * Why two steps: a browser started with `--remote-debugging-port` is treated by Google as automated
 * and the sign-in is refused with "This browser or app may not be secure". Keeping the port out of
 * the visible window avoids that, and the cookies are persistent (SAPISID lasts a year or more) so
 * they are still in the profile once the window is closed.
 *
 * Why not an embedded web view: Nuvio has no general web view component (Windows uses a private
 * WebView2 bridge for the player controls, macOS a WKWebView), and an embedded Chromium would add a
 * large dependency.
 *
 * Why a temporary profile: the window starts with no cookies so the user picks the account, none of
 * their everyday browsing data is touched, and Chrome 136+ refuses a debugging port on the default
 * profile anyway. The profile is deleted when the process ends.
 */
internal object YouTubeMusicBrowserLogin {
    private val log = Logger.withTag("YouTubeMusicLogin")
    private val json = Json { ignoreUnknownKeys = true }

    private const val LOGIN_URL =
        "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&prompt=select_account" +
            "&continue=https%3A%2F%2Fmusic.youtube.com%2F"
    private const val LOGIN_TIMEOUT_MS = 10 * 60_000L
    private const val DEVTOOLS_PORT_TIMEOUT_MS = 20_000L
    private const val POLL_MS = 1_000L

    /** How long the closed window's helper processes get to finish before the profile is reopened. */
    private const val DESCENDANTS_EXIT_MS = 5_000L

    suspend fun run(): YouTubeMusicLoginOutcome {
        val browser = findBrowser() ?: return YouTubeMusicLoginOutcome.Failed(
            "No Edge, Chrome, Brave or Chromium was found. Install one to sign in to YouTube Music.",
        )
        val profileDir = Files.createTempDirectory("nuvio-ytmusic-login")
        var window: Process? = null
        try {
            // Step 1: the visible sign-in window, with nothing that marks it as automated.
            window = ProcessBuilder(signInCommand(browser, profileDir))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            if (!awaitExit(window, LOGIN_TIMEOUT_MS)) {
                return YouTubeMusicLoginOutcome.Failed("Sign-in timed out.")
            }
            // Let the browser's helper processes finish writing the profile before it is reopened.
            awaitDescendantsExit(window)

            // Step 2: read what the window left in the profile.
            return readSession(browser, profileDir)
        } finally {
            window?.let { stop(it) }
            deleteRecursivelyQuietly(profileDir)
        }
    }

    /** Reopens [profileDir] headless, only to read its YouTube cookies. */
    private suspend fun readSession(browser: String, profileDir: Path): YouTubeMusicLoginOutcome {
        var reader: Process? = null
        var cdp: CdpConnection? = null
        try {
            reader = ProcessBuilder(cookieReadCommand(browser, profileDir))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            val endpoint = awaitDevToolsEndpoint(profileDir, reader)
                ?: return YouTubeMusicLoginOutcome.Failed("The browser could not be reopened to read the session.")
            cdp = CdpConnection.open(endpoint)
            val cookies = cdp.call("Storage.getCookies")["cookies"]?.jsonArray
            val header = cookies?.let(::buildCookieHeader)?.takeIf { sapisidFrom(it) != null }
                ?: return YouTubeMusicLoginOutcome.Failed(
                    "No signed-in YouTube session was found. Sign in, wait for YouTube Music to load, then close the window.",
                )
            return YouTubeMusicLoginOutcome.Success(header)
        } finally {
            runCatching { cdp?.call("Browser.close") }
            cdp?.close()
            reader?.let { stop(it) }
        }
    }

    /** True once [process] has exited; false on timeout. Cancelling the caller throws. */
    private suspend fun awaitExit(process: Process, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            coroutineContext.ensureActive()
            if (!process.isAlive) return true
            delay(POLL_MS)
        }
        coroutineContext.ensureActive()
        return false
    }

    private suspend fun awaitDescendantsExit(process: Process) {
        val deadline = System.currentTimeMillis() + DESCENDANTS_EXIT_MS
        while (System.currentTimeMillis() < deadline && process.descendants().anyMatch { it.isAlive }) {
            delay(200)
        }
    }

    private fun stop(process: Process) {
        if (!process.waitFor(3, TimeUnit.SECONDS)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }

    private fun awaitDevToolsEndpoint(profileDir: Path, process: Process): String? {
        val file = profileDir.resolve("DevToolsActivePort")
        val deadline = System.currentTimeMillis() + DEVTOOLS_PORT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline && process.isAlive) {
            val lines = runCatching { Files.readAllLines(file) }.getOrNull().orEmpty()
            val port = lines.getOrNull(0)?.trim()?.toIntOrNull()
            val path = lines.getOrNull(1)?.trim()?.takeIf { it.startsWith("/") }
            if (port != null && path != null) return "ws://127.0.0.1:$port$path"
            Thread.sleep(200)
        }
        return null
    }

    /** The visible window: no debugging port, no automation flag. */
    internal fun signInCommand(browser: String, profileDir: Path): List<String> = listOf(
        browser,
        "--user-data-dir=$profileDir",
        "--no-first-run",
        "--no-default-browser-check",
        "--disable-sync",
        "--window-size=520,780",
        "--app=$LOGIN_URL",
    )

    /** The same profile, headless, with a debugging port, used only to read cookies. */
    internal fun cookieReadCommand(browser: String, profileDir: Path): List<String> = listOf(
        browser,
        "--user-data-dir=$profileDir",
        // Port 0 = pick a free one; it is written to <profile>/DevToolsActivePort.
        "--remote-debugging-port=0",
        "--headless=new",
        "--disable-gpu",
        "--no-first-run",
        "--no-default-browser-check",
        "about:blank",
    )

    // ---- Browser discovery ---------------------------------------------------------------------

    private fun findBrowser(): String? {
        System.getProperty("nuvio.youtubeMusic.browser")?.takeIf { Files.isExecutable(Path.of(it)) }?.let { return it }
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val candidates = when {
            "win" in os -> windowsCandidates()
            "mac" in os || "darwin" in os -> listOf(
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
                "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser",
                "/Applications/Chromium.app/Contents/MacOS/Chromium",
            )
            else -> listOf(
                "google-chrome", "google-chrome-stable", "microsoft-edge", "microsoft-edge-stable",
                "brave-browser", "chromium", "chromium-browser",
            ).mapNotNull { onPath(it) }
        }
        return candidates.firstOrNull { Files.isExecutable(Path.of(it)) }
    }

    private fun windowsCandidates(): List<String> {
        val roots = listOfNotNull(
            System.getenv("ProgramFiles"),
            System.getenv("ProgramFiles(x86)"),
            System.getenv("LOCALAPPDATA"),
        )
        // Edge ships with Windows, so it goes first.
        val relative = listOf(
            "Microsoft\\Edge\\Application\\msedge.exe",
            "Google\\Chrome\\Application\\chrome.exe",
            "BraveSoftware\\Brave-Browser\\Application\\brave.exe",
        )
        return relative.flatMap { rel -> roots.map { root -> "$root\\$rel" } }
    }

    private fun onPath(name: String): String? =
        System.getenv("PATH").orEmpty().split(java.io.File.pathSeparatorChar)
            .asSequence()
            .filter { it.isNotBlank() }
            .map { Path.of(it, name) }
            .firstOrNull { Files.isExecutable(it) }
            ?.toString()

    private fun deleteRecursivelyQuietly(dir: Path) {
        // The browser can hold files for a moment after it exits, so retry a few times.
        repeat(5) {
            runCatching {
                Files.walk(dir).use { stream ->
                    stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
            if (!Files.exists(dir)) return
            Thread.sleep(300)
        }
        log.w { "Could not remove temporary login profile $dir" }
    }

    // ---- Cookies --------------------------------------------------------------------------------

    /**
     * A `Cookie` header value for requests to music.youtube.com, from DevTools' `Storage.getCookies`
     * output: every cookie whose domain is youtube.com or a subdomain. When a name appears on both
     * `.youtube.com` and a more specific host, the more specific one wins, as in a browser.
     */
    internal fun buildCookieHeader(cookies: JsonArray): String {
        val byName = LinkedHashMap<String, Pair<String, String>>() // name -> (domain, value)
        for (element in cookies) {
            val cookie = element.jsonObject
            val name = cookie["name"]?.jsonPrimitive?.contentOrNull ?: continue
            val value = cookie["value"]?.jsonPrimitive?.contentOrNull ?: continue
            val domain = cookie["domain"]?.jsonPrimitive?.contentOrNull?.trimStart('.')?.lowercase() ?: continue
            if (domain != "youtube.com" && !domain.endsWith(".youtube.com")) continue
            val existing = byName[name]
            if (existing == null || domain.length > existing.first.length) byName[name] = domain to value
        }
        return byName.entries.joinToString("; ") { (name, domainAndValue) -> "$name=${domainAndValue.second}" }
    }

    // ---- Minimal DevTools protocol client -------------------------------------------------------

    /** One browser-level DevTools connection. Calls are synchronous and meant for the IO dispatcher. */
    internal class CdpConnection private constructor() : WebSocket.Listener {
        private val ids = AtomicInteger(0)
        private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
        private val buffer = StringBuilder()

        @Volatile
        private var socket: WebSocket? = null

        fun call(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject {
            val ws = socket ?: throw IOException("DevTools connection is closed")
            val id = ids.incrementAndGet()
            val future = CompletableFuture<JsonObject>()
            pending[id] = future
            val message = buildJsonObject {
                put("id", id)
                put("method", method)
                put("params", params)
            }
            try {
                ws.sendText(message.toString(), true).get(10, TimeUnit.SECONDS)
                return future.get(10, TimeUnit.SECONDS)
            } finally {
                pending.remove(id)
            }
        }

        fun close() {
            socket?.abort()
            socket = null
            failAll(IOException("DevTools connection closed"))
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buffer.append(data)
            if (last) {
                val text = buffer.toString()
                buffer.setLength(0)
                runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()?.let { message ->
                    val id = message["id"]?.jsonPrimitive?.int
                    if (id != null) {
                        val future = pending[id]
                        val error = message["error"]
                        when {
                            future == null -> Unit
                            error != null -> future.completeExceptionally(IOException("DevTools error: $error"))
                            else -> future.complete(message["result"]?.jsonObject ?: JsonObject(emptyMap()))
                        }
                    }
                }
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            failAll(IOException("DevTools connection closed"))
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            failAll(error)
        }

        private fun failAll(error: Throwable) {
            pending.values.forEach { it.completeExceptionally(error) }
        }

        companion object {
            fun open(url: String): CdpConnection {
                val connection = CdpConnection()
                connection.socket = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .buildAsync(URI(url), connection)
                    .get(10, TimeUnit.SECONDS)
                return connection
            }
        }
    }
}
