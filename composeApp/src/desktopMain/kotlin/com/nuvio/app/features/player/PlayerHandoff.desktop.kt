package com.nuvio.app.features.player

import com.nuvio.app.features.player.desktop.DesktopPlayerLaunchShield

internal actual fun coverPlayerClose() {
    DesktopPlayerLaunchShield.holdStillForClose()
}

internal actual fun holdPlayerHandoffShield() {
    // fork:youtube-mini-player - from the corner box over the tab, only the video blacks out.
    DesktopPlayerLaunchShield.holdForHandoff(videoOnly = com.nuvio.app.features.youtube.YouTubeWatchLayout.browsing.value)
}
