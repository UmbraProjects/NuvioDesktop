package com.nuvio.app.core.ui

import co.touchlab.kermit.Logger
import java.awt.Robot
import java.awt.event.KeyEvent

private val log = Logger.withTag("VoiceTyping")

/**
 * Presses Win+H, Windows 11's voice typing shortcut. Off the UI thread, and after a moment, so the
 * click that asked for it has let go and the search box has taken focus before voice typing opens.
 */
internal actual fun startVoiceTyping(): Boolean = runCatching {
    Thread({
        runCatching {
            Thread.sleep(VOICE_TYPING_DELAY_MS)
            val robot = Robot()
            robot.keyPress(KeyEvent.VK_WINDOWS)
            robot.keyPress(KeyEvent.VK_H)
            robot.keyRelease(KeyEvent.VK_H)
            robot.keyRelease(KeyEvent.VK_WINDOWS)
        }.onFailure { log.w(it) { "voice typing shortcut failed" } }
    }, "nuvio-voice-typing").apply { isDaemon = true }.start()
    true
}.getOrElse {
    log.w(it) { "voice typing unavailable" }
    false
}

private const val VOICE_TYPING_DELAY_MS = 120L
