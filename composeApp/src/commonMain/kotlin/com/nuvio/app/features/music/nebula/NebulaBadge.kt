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
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.MusicAlbumSummary
import com.nuvio.app.features.music.library.MusicLibraryRepository
import com.nuvio.app.features.music.library.addonLinks

/**
 * fork:nebula - the "Lossless" pill on a release tile's cover corner: the music addon has this album. An overlay, so a tile
 * looks the same whether it shows or not (nothing moves or resizes when the answer arrives).
 */
@Composable
internal fun LosslessPill(modifier: Modifier = Modifier) {
    Text(
        text = "Lossless",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.nuvio.colors.accent,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * A small "On your addon" note for an album in a list. Nothing at all without an addon link. The check
 * runs only while the row is on screen (a lazy list composes only visible rows, and leaving cancels it),
 * and asks the addon through [NebulaMusicSource.probablyHas], which limits and remembers requests.
 */
@Composable
internal fun AddonBadge(album: MusicAlbumSummary) {
    remember { MusicLibraryRepository.ensureLoaded() }
    val links = MusicLibraryRepository.state.collectAsState().value.settings.addonLinks()
    if (nebulaAddons(links).isEmpty()) return
    var has by remember(album.browseId, links) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(album.browseId, links) { has = NebulaMusicSource.probablyHas(album) }
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
