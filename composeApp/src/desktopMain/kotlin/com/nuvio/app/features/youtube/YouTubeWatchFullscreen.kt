package com.nuvio.app.features.youtube

import com.nuvio.app.features.player.desktop.desktopAppFullscreenState
import com.nuvio.app.features.player.desktop.toggleDesktopAppFullscreen
import java.awt.Window

/*
 * fork:youtube-watch-page - the player's fullscreen button and Escape while a YouTube tab video can show
 * its watch page (YouTubeWatchLayout): from the page the button makes the video fill the screen (the
 * window fullscreen too, unless it already was); from there the button or Escape goes back to the page,
 * undoing only the fullscreen it made. Anything else is left to the player as before.
 */

/** The HUD's fullscreen button. True when it was taken for the watch page; false leaves it to the player. */
internal fun handleYouTubeFullscreenButton(window: Window?): Boolean {
    if (!YouTubeWatchLayout.available.value) return false
    if (YouTubeWatchLayout.windowed.value) {
        val makeFullscreen = !desktopAppFullscreenState.value
        YouTubeWatchLayout.goFull(madeFullscreen = makeFullscreen)
        if (makeFullscreen) {
            toggleDesktopAppFullscreen(window)
            // Leaving the player from there leaves the fullscreen too, unless the next video takes over.
            YouTubeWatchLayout.onLeftInFullscreen = { restoreWindowAfterPlayer(window) }
        }
        return true
    }
    return leaveYouTubeFull(window)
}

internal actual fun requestYouTubeFullscreen() {
    javax.swing.SwingUtilities.invokeLater { handleYouTubeFullscreenButton(null) }
}

/** Escape. True when it went back to the watch page; false leaves it to the player (leave fullscreen, or close). */
internal fun handleYouTubeEscape(window: Window?): Boolean = leaveYouTubeFull(window)

/** A short wait first: a hand-over to the next video opens its player within about a second and keeps the fullscreen. */
private fun restoreWindowAfterPlayer(window: Window?) {
    javax.swing.Timer(2_000) {
        if (!YouTubeWatchLayout.available.value && YouTubeWatchLayout.madeWindowFullscreen) {
            if (YouTubeWatchLayout.backToPage() && desktopAppFullscreenState.value) toggleDesktopAppFullscreen(window)
        }
    }.apply { isRepeats = false }.start()
}

private fun leaveYouTubeFull(window: Window?): Boolean {
    if (!YouTubeWatchLayout.available.value || YouTubeWatchLayout.windowed.value || !YouTubeWatchLayout.fullFromPage) return false
    if (YouTubeWatchLayout.backToPage() && desktopAppFullscreenState.value) toggleDesktopAppFullscreen(window)
    return true
}
