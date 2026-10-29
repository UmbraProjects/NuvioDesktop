package com.nuvio.app.features.music.nebula

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.MusicAlbumSummary
import com.nuvio.app.features.music.library.MusicLibraryRepository

/**
 * A small "On your addon" note for an album in a list. Nothing at all without an addon link. The check
 * runs only while the row is on screen (a lazy list composes only visible rows, and leaving cancels it),
 * and asks the addon through [NebulaMusicSource.probablyHas], which limits and remembers requests.
 */
@Composable
internal fun AddonBadge(album: MusicAlbumSummary) {
    remember { MusicLibraryRepository.ensureLoaded() }
    val link = MusicLibraryRepository.state.collectAsState().value.settings.nebulaSecretLink
    if (nebulaBaseUrl(link) == null) return
    var has by remember(album.browseId, link) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(album.browseId, link) { has = NebulaMusicSource.probablyHas(album) }
    if (has == true) {
        Text(
            text = "Lossless on your addon",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.nuvio.colors.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
