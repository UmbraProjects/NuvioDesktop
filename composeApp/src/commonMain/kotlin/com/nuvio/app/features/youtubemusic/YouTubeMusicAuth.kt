package com.nuvio.app.features.youtubemusic

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * fork:ytmusic-login
 *
 * Google sign-in for YouTube Music. This is not OAuth: YouTube Music's own web client has no public
 * OAuth scope that covers its InnerTube API, so (like BitChord, SimpMusic, Metrolist and ytmusicapi)
 * the user signs in on Google's real login page and the session cookies are reused. Requests then
 * carry the cookies plus a `SAPISIDHASH` Authorization header derived from the SAPISID cookie.
 *
 * The login window itself is platform code ([YouTubeMusicAuthPlatform.interactiveLogin]); this file
 * is the state, the storage hand-off and the request signing.
 */

/** How an interactive login ended. */
internal sealed interface YouTubeMusicLoginOutcome {
    /** [cookieHeader] is a ready-to-send `Cookie` header value for `*.youtube.com`. */
    data class Success(val cookieHeader: String) : YouTubeMusicLoginOutcome

    /** The user closed the login window before finishing. */
    data object Cancelled : YouTubeMusicLoginOutcome

    data class Failed(val message: String) : YouTubeMusicLoginOutcome
}

internal data class YouTubeMusicAuthState(
    val signedIn: Boolean = false,
    val signingIn: Boolean = false,
    /** The last request made with the saved session was refused; signing in again is the fix. */
    val sessionRejected: Boolean = false,
    val error: String? = null,
    /** Goes up with each completed sign-in, including one over a lapsed session that stays "signed in". */
    val sessionEpoch: Int = 0,
)

internal expect object YouTubeMusicAuthPlatform {
    fun loadCookie(): String?
    fun saveCookie(cookieHeader: String?)
    fun sha1Hex(input: String): String
    fun nowEpochSeconds(): Long

    /**
     * Shows Google's sign-in page in a browser window and suspends until the user lands on
     * music.youtube.com signed in, closes the window, or something breaks. Always cleans up after
     * itself, including when the calling coroutine is cancelled.
     */
    suspend fun interactiveLogin(): YouTubeMusicLoginOutcome

    /** Asks Google to renew the session's short-lived cookies; the HTTP status, 0 when no answer came. */
    suspend fun rotateCookies(cookieHeader: String): Int
}

internal object YouTubeMusicAuth {
    private val log = Logger.withTag("YouTubeMusicAuth")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(YouTubeMusicAuthState())
    val state: StateFlow<YouTubeMusicAuthState> = _state.asStateFlow()

    @Volatile
    private var cookie: String? = null

    @Volatile
    private var loaded = false
    private var loginJob: Job? = null

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            cookie = YouTubeMusicAuthPlatform.loadCookie()?.takeIf { sapisidFrom(it) != null }
            _state.update { it.copy(signedIn = cookie != null) }
            loaded = true
        }
        if (cookie != null) startKeepAlive()
    }

    private var keepAliveJob: Job? = null

    @Volatile
    private var lastRotationStatus: Int? = null

    /**
     * Keeps the saved session alive the way a signed-in YouTube tab does: the short-lived
     * `*PSIDTS` cookies are renewed through `RotateCookies` every [ROTATE_INTERVAL_MS]. Without it
     * the copy Nuvio holds went stale and Google answered as signed out, after anything from two
     * to ten hours (Nov 2: signed in 18:58, signed out by 21:17), and the ordinary API answers
     * never renew those cookies (no "refreshed the session cookies" in that time).
     */
    private fun startKeepAlive() {
        synchronized(this) {
            if (keepAliveJob?.isActive == true) return
            keepAliveJob = scope.launch {
                while (true) {
                    val jar = cookie ?: break
                    val status = YouTubeMusicAuthPlatform.rotateCookies(jar)
                    // The first answer and any change; not every renewal.
                    if (status != lastRotationStatus) {
                        if (status in 200..299) {
                            log.i { "Session renewal answered HTTP $status" }
                        } else {
                            log.w { "Session renewal answered HTTP $status; the sign-in may lapse" }
                        }
                        lastRotationStatus = status
                    }
                    delay(ROTATE_INTERVAL_MS)
                }
            }
        }
    }

    private const val ROTATE_INTERVAL_MS = 9 * 60_000L

    val isSignedIn: Boolean
        get() {
            ensureLoaded()
            return cookie != null
        }

    /**
     * Headers that make a request to [origin] (`https://music.youtube.com`) act as the signed-in
     * user, or empty when nobody is signed in. Header names are lower case like the rest of the
     * extractor's headers.
     */
    fun authHeaders(origin: String): Map<String, String> {
        ensureLoaded()
        val jar = cookie ?: return emptyMap()
        val sapisid = sapisidFrom(jar) ?: return emptyMap()
        return mapOf(
            "cookie" to jar,
            "authorization" to sapisidHash(sapisid, origin),
            // First (default) signed-in Google account in the cookie jar.
            "x-goog-authuser" to "0",
            "x-origin" to origin,
        )
    }

    fun signIn() {
        if (loginJob?.isActive == true) return
        _state.update { it.copy(signingIn = true, error = null) }
        loginJob = scope.launch {
            val outcome = try {
                YouTubeMusicAuthPlatform.interactiveLogin()
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.update { it.copy(signingIn = false) }
                throw e
            } catch (e: Exception) {
                log.w(e) { "Login failed" }
                YouTubeMusicLoginOutcome.Failed(e.message ?: "Sign-in failed")
            }
            when (outcome) {
                is YouTubeMusicLoginOutcome.Success -> {
                    cookie = outcome.cookieHeader
                    loaded = true
                    YouTubeMusicAuthPlatform.saveCookie(outcome.cookieHeader)
                    _state.value = YouTubeMusicAuthState(signedIn = true, sessionEpoch = _state.value.sessionEpoch + 1)
                    log.i { "Signed in to YouTube Music" }
                    lastRotationStatus = null
                    startKeepAlive()
                }
                YouTubeMusicLoginOutcome.Cancelled ->
                    _state.update { it.copy(signingIn = false) }
                is YouTubeMusicLoginOutcome.Failed ->
                    _state.update { it.copy(signingIn = false, error = outcome.message) }
            }
        }
    }

    /** Abandons an open login window. */
    fun cancelSignIn() {
        loginJob?.cancel()
    }

    fun signOut() {
        loginJob?.cancel()
        keepAliveJob?.cancel()
        keepAliveJob = null
        cookie = null
        loaded = true
        YouTubeMusicAuthPlatform.saveCookie(null)
        _state.value = YouTubeMusicAuthState()
        log.i { "Signed out of YouTube Music" }
    }

    /** A request that carried the saved session was refused (401/403). The session is kept. */
    fun onSessionRejected() {
        _state.update { if (it.signedIn) it.copy(sessionRejected = true) else it }
    }

    fun onSessionAccepted() {
        _state.update { if (it.sessionRejected) it.copy(sessionRejected = false) else it }
    }

    @Volatile
    private var lastPersistMs = 0L

    /**
     * `Set-Cookie` values from a youtube.com response to a signed request. Google rotates parts of
     * the session (`__Secure-1PSIDTS`, the `*SIDCC` checks) and expects the client to send the new
     * values; a jar frozen at sign-in stops counting as signed in within hours or days, and YouTube
     * then answers as a signed-out visitor rather than refusing. Only cookies already in the jar are
     * updated, and deletions are ignored, so a stray response cannot sign the user out.
     *
     * The `*SIDCC` checks change on almost every response, so they reach storage at most every few
     * minutes; anything else is saved at once.
     */
    fun absorbSetCookies(setCookies: List<String>) {
        if (setCookies.isEmpty()) return
        ensureLoaded()
        val (merged, changed) = synchronized(this) {
            val jar = cookie ?: return
            val result = mergeSetCookies(jar, setCookies)
            if (result.second.isNotEmpty() && sapisidFrom(result.first) != null) cookie = result.first
            result
        }
        if (changed.isEmpty()) return
        val significant = changed.filterNot { it.endsWith("SIDCC") }
        val now = YouTubeMusicAuthPlatform.nowEpochSeconds() * 1000
        if (significant.isNotEmpty() || now - lastPersistMs >= CHECK_COOKIE_PERSIST_MS) {
            lastPersistMs = now
            YouTubeMusicAuthPlatform.saveCookie(merged)
        }
        if (significant.isNotEmpty()) log.i { "YouTube refreshed the session cookies: ${significant.sorted()}" }
    }

    private const val CHECK_COOKIE_PERSIST_MS = 5 * 60_000L

    private fun sapisidHash(sapisid: String, origin: String): String {
        val timestamp = YouTubeMusicAuthPlatform.nowEpochSeconds()
        return "SAPISIDHASH ${timestamp}_${YouTubeMusicAuthPlatform.sha1Hex("$timestamp $sapisid $origin")}"
    }
}

private val SAPISID_NAMES = listOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")

/**
 * [jar] (a `Cookie` header value) with the values from [setCookies] (`Set-Cookie` header values)
 * for names it already holds, in the jar's order; plus the names whose value changed. A cookie being
 * cleared (empty value, `Max-Age=0`, a 1970 expiry) and one for a domain other than youtube.com
 * leave the jar alone.
 */
internal fun mergeSetCookies(jar: String, setCookies: List<String>): Pair<String, List<String>> {
    val entries = jar.split(';').mapNotNull { entry ->
        val name = entry.substringBefore('=').trim()
        if (name.isEmpty()) null else name to entry.substringAfter('=', "").trim()
    }
    val values = LinkedHashMap<String, String>().apply { entries.forEach { (name, value) -> put(name, value) } }
    val changed = LinkedHashSet<String>()
    for (header in setCookies) {
        val parts = header.split(';')
        val name = parts.first().substringBefore('=').trim()
        val value = parts.first().substringAfter('=', "").trim()
        if (name.isEmpty() || value.isEmpty() || name !in values) continue
        val attributes = parts.drop(1).associate { attribute ->
            attribute.substringBefore('=').trim().lowercase() to attribute.substringAfter('=', "").trim()
        }
        val domain = attributes["domain"]?.trimStart('.')?.lowercase()
        if (domain != null && domain != "youtube.com" && !domain.endsWith(".youtube.com")) continue
        if (attributes["max-age"]?.toLongOrNull()?.let { it <= 0 } == true) continue
        if (attributes["expires"]?.contains("1970") == true) continue
        if (values[name] != value) {
            values[name] = value
            changed += name
        }
    }
    return values.entries.joinToString("; ") { (name, value) -> "$name=$value" } to changed.toList()
}

/** The value Google signs requests with, found in a `name=value; name=value` cookie header. */
internal fun sapisidFrom(cookieHeader: String): String? {
    val jar = cookieHeader.split(';').mapNotNull { entry ->
        val name = entry.substringBefore('=').trim()
        val value = entry.substringAfter('=', "").trim()
        if (name.isEmpty() || value.isEmpty()) null else name to value
    }.toMap()
    return SAPISID_NAMES.firstNotNullOfOrNull { jar[it] }
}
