package com.nuvio.app.features.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What a double-click on the bare video does (desktop, built-in player). */
enum class PlayerDoubleClickAction {
    /** Picture in picture (the YouTube watch page's default view goes to theater first). */
    PictureInPicture,

    /** Fullscreen and windowed in turn, as the player did before picture in picture took it. */
    Fullscreen,
}

/**
 * The double-click setting. Inside picture in picture a double-click always comes back out, whatever
 * this says: there is no fullscreen to toggle in the small window. Stored beside the PiP browse setting.
 */
object PlayerDoubleClick {
    private const val KEY_ACTION = "double_click_action"
    private const val VALUE_FULLSCREEN = "fullscreen"

    private val _action = MutableStateFlow(
        if (PlayerBrowsePipStorage.load(KEY_ACTION) == VALUE_FULLSCREEN) {
            PlayerDoubleClickAction.Fullscreen
        } else {
            PlayerDoubleClickAction.PictureInPicture
        },
    )

    val action: StateFlow<PlayerDoubleClickAction> = _action.asStateFlow()

    fun setAction(action: PlayerDoubleClickAction) {
        if (_action.value == action) return
        _action.value = action
        PlayerBrowsePipStorage.save(KEY_ACTION, if (action == PlayerDoubleClickAction.Fullscreen) VALUE_FULLSCREEN else null)
    }
}
