package com.nuvio.app.features.youtubemusic

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * fork:ytmusic-login
 *
 * Google refuses to sign in a browser that was started with a debugging port ("This browser or app
 * may not be secure"), so the visible window must never carry one; only the headless reader of the
 * finished profile may.
 */
class YouTubeMusicBrowserLoginTest {
    private val profile = Path.of("profile")

    @Test
    fun theSignInWindowCarriesNoAutomationSignals() {
        val command = YouTubeMusicBrowserLogin.signInCommand("browser", profile)

        assertEquals("browser", command.first())
        assertTrue(
            command.none { it.startsWith("--remote-debugging") || it == "--enable-automation" || it.startsWith("--headless") },
            "sign-in window must look like an ordinary browser: $command",
        )
        assertTrue(command.any { it == "--user-data-dir=$profile" })
        assertTrue(command.any { it.startsWith("--app=https://accounts.google.com/") }, "$command")
    }

    @Test
    fun theCookieReaderIsHeadlessOnTheSameProfile() {
        val command = YouTubeMusicBrowserLogin.cookieReadCommand("browser", profile)

        assertTrue(command.any { it == "--user-data-dir=$profile" })
        assertTrue(command.any { it == "--remote-debugging-port=0" })
        assertTrue(command.any { it == "--headless=new" })
        assertTrue(command.none { it.startsWith("--app=") }, "the reader opens no sign-in page: $command")
    }
}
