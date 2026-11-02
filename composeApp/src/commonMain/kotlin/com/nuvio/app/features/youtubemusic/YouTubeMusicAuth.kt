package com.nuvio.app.features.youtubemusic

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    }

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
                    _state.value = YouTubeMusicAuthState(signedIn = true)
                    log.i { "Signed in to YouTube Music" }
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
        cookie = null
        loaded = true
        YouTubeMusicAuthPlatform.saveCookie(null)
        _state.value = YouTubeMusicAuthState()
        log.i { "Signed out of YouTube Music" }
    }

    /** A request that carried the saved session was refused (401/403). The session is kept. */
    /**
     * fork:youtube-cookie-upkeep — keeps the saved cookies as a browser would: a value YouTube sends back for a
     * cookie the session already holds replaces the saved one. Google rotates some of them (`SIDCC`,
     * `__Secure-*PSIDCC`, `__Secure-*PSIDTS`) and a copy that never takes the new values is the one that
     * stops being a session. Deletions and cookies the session does not hold are ignored.
     */
    fun applySetCookies(setCookies: List<String>) {
        if (setCookies.isEmpty()) return
        ensureLoaded()
        val updated = synchronized(this) {
            val jar = cookie ?: return
            val merge = mergeSetCookies(jar, setCookies)
            if (merge.updated.isEmpty()) return
            cookie = merge.cookieHeader
            YouTubeMusicAuthPlatform.saveCookie(merge.cookieHeader)
            merge.updated
        }
        log.i { "YouTube refreshed ${updated.size} sign-in cookie(s): ${updated.joinToString()}" }
    }

    fun onSessionRejected() {
        _state.update { if (it.signedIn) it.copy(sessionRejected = true) else it }
    }

    fun onSessionAccepted() {
        _state.update { if (it.sessionRejected) it.copy(sessionRejected = false) else it }
    }

    private fun sapisidHash(sapisid: String, origin: String): String {
        val timestamp = YouTubeMusicAuthPlatform.nowEpochSeconds()
        return "SAPISIDHASH ${timestamp}_${YouTubeMusicAuthPlatform.sha1Hex("$timestamp $sapisid $origin")}"
    }
}

private val SAPISID_NAMES = listOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")

/** The result of [mergeSetCookies]: the new header and the names whose values changed. */
internal data class CookieMerge(val cookieHeader: String, val updated: List<String>)

private val EXPIRED_IN_THE_PAST = Regex("""expires=[^;]*\b(19\d\d|20[01]\d|202[0-5])\b""", RegexOption.IGNORE_CASE)

/**
 * fork:youtube-cookie-upkeep — applies `Set-Cookie` response headers to a `name=value; name=value` header:
 * only names already in it, never a deletion (empty value, `Max-Age=0` or an expiry in the past), never
 * a new name. Order is kept.
 */
internal fun mergeSetCookies(cookieHeader: String, setCookies: List<String>): CookieMerge {
    val entries = cookieHeader.split(';').map { it.trim() }.filter { it.isNotEmpty() }.map { entry ->
        val eq = entry.indexOf('=')
        if (eq <= 0) entry to null else entry.substring(0, eq) to entry.substring(eq + 1)
    }.toMutableList()
    val updated = mutableListOf<String>()
    for (header in setCookies) {
        val first = header.substringBefore(';').trim()
        val eq = first.indexOf('=')
        if (eq <= 0) continue
        val name = first.substring(0, eq).trim()
        val value = first.substring(eq + 1).trim()
        val attributes = header.substringAfter(';', "")
        val deletion = value.isEmpty() ||
            Regex("""max-age\s*=\s*0\b""", RegexOption.IGNORE_CASE).containsMatchIn(attributes) ||
            EXPIRED_IN_THE_PAST.containsMatchIn(attributes)
        if (deletion) continue
        val index = entries.indexOfFirst { it.first == name }
        if (index < 0 || entries[index].second == value) continue
        entries[index] = name to value
        if (name !in updated) updated += name
    }
    val header = entries.joinToString("; ") { (name, value) -> if (value == null) name else "$name=$value" }
    return CookieMerge(header, updated)
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
