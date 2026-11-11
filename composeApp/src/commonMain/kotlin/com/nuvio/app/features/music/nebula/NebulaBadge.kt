package com.nuvio.app.features.music.nebula

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
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
 * fork:nebula - the lossless mark on a release's cover corner: the music addon has this album. An icon in a dark rounded
 * square, sized to the cover it sits on (about a fifth of it, kept readable on a small one and modest on a large one), so
 * it never runs past a small cover. An overlay in the cover's own box, so the tile does not change when the answer arrives.
 */
@Composable
internal fun BoxScope.LosslessPill(alignment: Alignment = Alignment.TopStart) {
    BoxWithConstraints(Modifier.matchParentSize()) {
        val art = minOf(maxWidth, maxHeight)
        val badge = (art * 0.18f).coerceIn(14.dp, 30.dp)
        val inset = (art * 0.05f).coerceIn(3.dp, 8.dp)
        Box(
            Modifier.align(alignment).padding(inset).size(badge)
                .clip(RoundedCornerShape(badge * 0.28f))
                .background(Color.Black.copy(alpha = 0.65f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.HighQuality,
                contentDescription = "Lossless",
                tint = MaterialTheme.nuvio.colors.accent,
                modifier = Modifier.size(badge * 0.74f),
            )
        }
    }
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
