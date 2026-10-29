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
}
