package com.nuvio.app.features.youtubemusic

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Locale

internal actual object YouTubeMusicAuthPlatform {
    // Per-account store (not in MACHINE_STORE_NAMES), and listed in the settings backup's credential
    // stores so a backup leaves it out unless the user asks for credentials.
    private val store = DesktopStorage.store("nuvio_youtube_music_auth")
    private const val COOKIE_KEY = "cookie_header"

    actual fun loadCookie(): String? = store.getString(COOKIE_KEY)?.takeIf { it.isNotBlank() }

    actual fun saveCookie(cookieHeader: String?) = store.putString(COOKIE_KEY, cookieHeader)

    actual fun sha1Hex(input: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it) }

    actual fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1000

    actual suspend fun interactiveLogin(): YouTubeMusicLoginOutcome =
        withContext(Dispatchers.IO) { YouTubeMusicBrowserLogin.run() }

    /**
     * The request a signed-in YouTube page makes from its `accounts.youtube.com/RotateCookiesPage`
     * frame, with the body Google's own pages send. The new `*PSIDTS` values come back as Set-Cookie
     * and reach the saved jar through the request layer (YouTubeMusicAuth.absorbSetCookies).
     */
    actual suspend fun rotateCookies(cookieHeader: String): Int =
        runCatching {
            com.nuvio.app.features.trailer.TrailerExtractionPlatform.performRequest(
                url = "https://accounts.youtube.com/RotateCookies",
                method = "POST",
                headers = mapOf(
                    "cookie" to cookieHeader,
                    "content-type" to "application/json",
                    "origin" to "https://accounts.youtube.com",
                    "referer" to "https://accounts.youtube.com/RotateCookiesPage?origin=https://www.youtube.com&yt_pid=1",
                    "user-agent" to ROTATE_USER_AGENT,
                ),
                body = "[000,\"-0000000000000000000\"]",
                timeoutMillis = 15_000L,
            ).status
        }.getOrDefault(0)

    private const val ROTATE_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
}
