package com.nuvio.app.features.music

/** Whether the app window is in (OS) fullscreen right now. */
expect fun isAppFullscreen(): Boolean

/** Puts the app window into or out of fullscreen. A no-op when it already is. */
expect fun setAppFullscreen(on: Boolean)
