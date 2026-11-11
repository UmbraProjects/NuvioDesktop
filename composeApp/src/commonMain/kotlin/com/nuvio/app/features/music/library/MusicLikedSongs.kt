package com.nuvio.app.features.music.library

import com.nuvio.app.features.music.MusicCatalog
import com.nuvio.app.features.music.MusicTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * fork:music-account - the heart on the open dock. A song is liked when the account's Liked Music holds it, unless this
 * session has changed it since: [overrides] holds those changes at once, so the heart answers before YouTube Music does,
 * and a refused like is taken back.
 */
internal object MusicLikedSongs {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _overrides = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val overrides: StateFlow<Map<String, Boolean>> = _overrides.asStateFlow()

    /** Whether [videoId] is liked: this session's change if there is one, else whether the account's liked songs hold it. */
    fun isLiked(videoId: String, account: List<MusicTrack>, overrides: Map<String, Boolean>): Boolean =
        overrides[videoId] ?: account.any { it.videoId == videoId }

    /** Flips the like on [videoId], which is [nowLiked] at the moment. */
    fun toggle(catalog: MusicCatalog, videoId: String, nowLiked: Boolean) {
        val want = !nowLiked
        _overrides.update { it + (videoId to want) }
        scope.launch {
            val done = runCatching { catalog.setSongLiked(videoId, want) }.getOrDefault(false)
            if (!done) _overrides.update { it - videoId }
        }
    }
}
