package com.nuvio.app.features.youtubemusic

import co.touchlab.kermit.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
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

    /**
     * The sign-in's browser profile, kept in the account's folder. Chrome and Edge on Windows bind a
     * Google session to a key the profile holds (device-bound sessions: the `*PSIDRTS` cookies): the
     * short-lived cookie lasts about two hours and only that profile can renew it. Deleting the profile
     * after sign-in, as this used to, left a session that lapsed two hours later every time (Nov 2).
     */
    private fun keptProfileDir(): Path =
        com.nuvio.app.core.storage.DesktopAccountSlots.activeSlotDir().resolve("youtube-browser-profile")

    /** One browser on the profile at a time: a renewal waits for a sign-in, and the other way round. */
    private val profileLock = kotlinx.coroutines.sync.Mutex()

    /** Whether a kept profile exists to renew from (a sign-in made before profiles were kept has none). */
    fun hasKeptProfile(): Boolean = Files.isDirectory(keptProfileDir())

    /** Removes the kept profile: signing out of YouTube Music. */
    fun forgetProfile() {
        deleteRecursivelyQuietly(keptProfileDir())
    }

    /**
     * Renews the session the way the browser itself does: the kept profile is reopened headless on
     * youtube.com, the browser renews its device-bound cookies with its own key while the page loads,
     * and the cookies are read back. Null when there is no kept profile or no signed-in session came back.
     * [previousCookie] tells when the renewed cookie has arrived (its `__Secure-1PSIDTS` changes).
     */
    suspend fun renew(previousCookie: String?): String? = profileLock.withLock {
        val profileDir = keptProfileDir()
        if (!Files.isDirectory(profileDir)) return@withLock null
        val browser = findBrowser() ?: return@withLock null
        releaseProfile(profileDir)
        val before = previousCookie?.let { cookieValue(it, "__Secure-1PSIDTS") }
        var reader: Process? = null
        var cdp: CdpConnection? = null
        try {
            // A kept profile still holds the last run's port file; read before the new one is written it
            // would point at a port nothing listens on.
            Files.deleteIfExists(profileDir.resolve("DevToolsActivePort"))
            reader = ProcessBuilder(cookieReadCommand(browser, profileDir, startUrl = RENEW_URL))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            val endpoint = awaitDevToolsEndpoint(profileDir, reader) ?: run {
                log.w { "Renewal browser gave no DevTools port (alive=${reader.isAlive})" }
                return@withLock null
            }
            cdp = CdpConnection.open(endpoint)
            // The page's first requests carry the renewal; give it until the short-lived cookie changes,
            // or a few seconds past the page loading when it was still fresh and nothing needed renewing.
            val deadline = System.currentTimeMillis() + RENEW_WAIT_MS
            var header: String? = null
            while (System.currentTimeMillis() < deadline) {
                delay(POLL_MS)
                header = cdp.call("Storage.getCookies")["cookies"]?.jsonArray
                    ?.let(::buildCookieHeader)?.takeIf { sapisidFrom(it) != null }
                val now = header?.let { cookieValue(it, "__Secure-1PSIDTS") }
                if (now != null && now != before) break
            }
            header
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w { "Session renewal through the browser failed: ${error.message}" }
            null
        } finally {
            runCatching { cdp?.call("Browser.close") }
            cdp?.close()
            reader?.let { stop(it) }
        }
    }

    suspend fun run(): YouTubeMusicLoginOutcome = profileLock.withLock { signIn() }

    private suspend fun signIn(): YouTubeMusicLoginOutcome {
        val browser = findBrowser() ?: return YouTubeMusicLoginOutcome.Failed(
            "No Edge, Chrome, Brave or Chromium was found. Install one to sign in to YouTube Music.",
        )
        // A fresh profile each sign-in, so the window starts signed out and the user picks the account.
        val profileDir = keptProfileDir()
        // A browser still on the profile would be handed the sign-in window and show nothing (see releaseProfile).
        releaseProfile(profileDir)
        deleteRecursivelyQuietly(profileDir)
        Files.createDirectories(profileDir)
        var window: Process? = null
        var keep = false
        try {
            // Step 1: the visible sign-in window, with nothing that marks it as automated.
            window = ProcessBuilder(signInCommand(browser, profileDir))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            val deadline = System.currentTimeMillis() + LOGIN_TIMEOUT_MS
            if (!awaitExit(window, LOGIN_TIMEOUT_MS)) {
                return YouTubeMusicLoginOutcome.Failed("Sign-in timed out.")
            }
            // Let the browser's helper processes finish writing the profile before it is reopened.
            awaitDescendantsExit(window)
            // The launched process can exit at once while another browser process keeps the window
            // open (a launcher stub, or a hand-off to an instance already running on this profile),
            // so the window is closed only once the profile's lock is released.
            if (profileInUse(profileDir)) {
                log.i { "Browser process exited while the sign-in window is still open; waiting for the profile lock" }
                while (profileInUse(profileDir)) {
                    if (System.currentTimeMillis() >= deadline) {
                        return YouTubeMusicLoginOutcome.Failed("Sign-in timed out.")
                    }
                    delay(POLL_MS)
                }
                // Same grace as above for the processes that actually held the window.
                delay(1_000)
            }

            // Step 2: read what the window left in the profile.
            val outcome = readSession(browser, profileDir)
            // Kept for renewals (see keptProfileDir); a failed sign-in leaves nothing behind.
            keep = outcome is YouTubeMusicLoginOutcome.Success
            return outcome
        } finally {
            window?.let { stop(it) }
            if (!keep) deleteRecursivelyQuietly(profileDir)
        }
    }

    /** How long a browser asked (or made) to close gets to let go of the profile. */
    private const val RELEASE_WAIT_MS = 5_000L

    private const val RENEW_URL = "https://www.youtube.com/"
    private const val RENEW_WAIT_MS = 15_000L

    private fun cookieValue(header: String, name: String): String? =
        header.split(';').map { it.trim() }.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')

    /** Reopens [profileDir] headless, only to read its YouTube cookies. */
    private suspend fun readSession(browser: String, profileDir: Path): YouTubeMusicLoginOutcome {
        var reader: Process? = null
        var cdp: CdpConnection? = null
        try {
            Files.deleteIfExists(profileDir.resolve("DevToolsActivePort"))
            reader = ProcessBuilder(cookieReadCommand(browser, profileDir))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            val endpoint = awaitDevToolsEndpoint(profileDir, reader)
            if (endpoint == null) {
                log.w {
                    "Cookie reader gave no DevTools port (alive=${reader.isAlive}, " +
                        "exit=${if (reader.isAlive) "-" else reader.exitValue()}, profileInUse=${profileInUse(profileDir)})"
                }
                return YouTubeMusicLoginOutcome.Failed("The browser could not be reopened to read the session.")
            }
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

    /**
     * True while a browser still has [profileDir] open. Windows: Chromium holds `lockfile` open with no
     * sharing, so deleting it fails until the browser exits (a stale one is removed, which is harmless).
     * Elsewhere: the `SingletonLock` symlink exists until a clean exit.
     */
    private fun profileInUse(profileDir: Path): Boolean {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return if ("win" in os) {
            runCatching { Files.deleteIfExists(profileDir.resolve("lockfile")) }.isFailure
        } else {
            Files.exists(profileDir.resolve("SingletonLock"), java.nio.file.LinkOption.NOFOLLOW_LINKS)
        }
    }

    private fun stop(process: Process) {
        // Taken first: once the launched process has exited its children can no longer be found from it, and on
        // Windows Edge's first process often exits while the browser it started runs on.
        val tree = process.descendants().toList()
        if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
        tree.filter { it.isAlive }.forEach { it.destroyForcibly() }
    }

    /**
     * Closes any browser still running on [profileDir] before a new one is started on it. Chromium hands a second
     * launch on a profile to the browser already open there, so a hidden renewal or cookie-reading browser left
     * behind (a crash, or one whose processes outlived [stop]) made the sign-in window never appear: the click
     * started nothing visible and the settings page waited on "Sign in in the browser window" (Nov 8). First asked
     * to close through its DevTools port (only the hidden ones have one), then its processes are ended: only those
     * whose command line names this profile.
     */
    private suspend fun releaseProfile(profileDir: Path) {
        if (!Files.isDirectory(profileDir) || !profileInUse(profileDir)) return
        log.w { "The YouTube browser profile is still open in another browser; closing it" }
        runCatching {
            val lines = Files.readAllLines(profileDir.resolve("DevToolsActivePort"))
            val port = lines.getOrNull(0)?.trim()?.toIntOrNull()
            val path = lines.getOrNull(1)?.trim()?.takeIf { it.startsWith("/") }
            if (port != null && path != null) {
                val cdp = CdpConnection.open("ws://127.0.0.1:$port$path")
                try {
                    runCatching { cdp.call("Browser.close") }
                } finally {
                    cdp.close()
                }
            }
        }
        if (awaitProfileReleased(profileDir)) return
        runCatching { endProfileProcesses(profileDir) }.onFailure { log.w { "Could not end the browser on the profile: ${it.message}" } }
        if (!awaitProfileReleased(profileDir)) log.w { "The YouTube browser profile is still in use after closing its browser" }
    }

    private suspend fun awaitProfileReleased(profileDir: Path): Boolean {
        val deadline = System.currentTimeMillis() + RELEASE_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!profileInUse(profileDir)) return true
            delay(250)
        }
        return !profileInUse(profileDir)
    }

    /** Ends every process whose command line names [profileDir] (the browser and its helpers, all launched with it). */
    private fun endProfileProcesses(profileDir: Path) {
        val dir = profileDir.toAbsolutePath().toString()
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val command = if ("win" in os) {
            // Single quotes only: the script goes through Windows' command-line quoting as one argument.
            val quoted = dir.replace("'", "''")
            listOf(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "Get-CimInstance Win32_Process | Where-Object { \$_.ProcessId -ne \$PID -and \$_.CommandLine -and \$_.CommandLine.Contains('$quoted') } | " +
                    "ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }",
            )
        } else {
            listOf("pkill", "-f", "--", "--user-data-dir=$dir")
        }
        val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        if (!process.waitFor(15, TimeUnit.SECONDS)) process.destroyForcibly()
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

    /**
     * The same profile, headless, with a debugging port, used only to read cookies. [startUrl] is the
     * page it opens: blank after sign-in, youtube.com for a renewal (its requests are what renew).
     */
    internal fun cookieReadCommand(browser: String, profileDir: Path, startUrl: String = "about:blank"): List<String> = listOf(
        browser,
        "--user-data-dir=$profileDir",
        // Port 0 = pick a free one; it is written to <profile>/DevToolsActivePort.
        "--remote-debugging-port=0",
        "--headless=new",
        "--disable-gpu",
        "--no-first-run",
        "--no-default-browser-check",
        "--mute-audio",
        startUrl,
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
