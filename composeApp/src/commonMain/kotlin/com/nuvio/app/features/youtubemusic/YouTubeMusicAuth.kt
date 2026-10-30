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

/** The value Google signs requests with, found in a `name=value; name=value` cookie header. */
internal fun sapisidFrom(cookieHeader: String): String? {
    val jar = cookieHeader.split(';').mapNotNull { entry ->
        val name = entry.substringBefore('=').trim()
        val value = entry.substringAfter('=', "").trim()
        if (name.isEmpty() || value.isEmpty()) null else name to value
    }.toMap()
    return SAPISID_NAMES.firstNotNullOfOrNull { jar[it] }
}
