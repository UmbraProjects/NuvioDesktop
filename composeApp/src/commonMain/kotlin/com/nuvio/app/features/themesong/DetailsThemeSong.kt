package com.nuvio.app.features.themesong

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import kotlinx.coroutines.delay

/** How long a details screen is open before its theme song is looked up. */
private const val THEME_SONG_DETAILS_DELAY_MS = 1_500L

/**
 * fork:themesong
 *
 * The theme song behind the details screen, as one composable so MetaDetailsScreen carries a single
 * hook line. It yields to anything else that makes sound: a hero trailer with its sound on
 * ([heroTrailerAudible]), and the modals that park the trailer ([modalVisible]). It is looked up
 * after a short pause so flicking through titles does not search for every one.
 */
@Composable
fun DetailsThemeSong(
    metaType: String,
    metaName: String,
    releaseInfo: String?,
    metaId: String,
    deferredWorkAllowed: Boolean,
    leavingDetails: Boolean,
    heroTrailerAudible: Boolean,
    modalVisible: Boolean,
) {
    val enabled by ThemeSongSettings.detailsEnabled.collectAsStateWithLifecycle()
    val volume by ThemeSongSettings.volume.collectAsStateWithLifecycle()
    var source by remember(metaId) { mutableStateOf<TrailerPlaybackSource?>(null) }
    LaunchedEffect(metaId, enabled, deferredWorkAllowed) {
        source = null
        if (!enabled || !deferredWorkAllowed) return@LaunchedEffect
        delay(THEME_SONG_DETAILS_DELAY_MS)
        source = runCatching {
            ThemeSongRepository.resolve(metaType, metaName, releaseInfo, metaId)
        }.getOrNull()
    }
    val toPlay = source.takeIf { enabled && !leavingDetails && !heroTrailerAudible && !modalVisible }
    ThemeSongPlayer(source = toPlay, volume = volume)
}
