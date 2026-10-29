package com.nuvio.app.features.music

import com.nuvio.app.features.music.library.musicGlass
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.nuvio

/** A one-shot load a screen shows as a spinner, the result, or "couldn't load". */
internal sealed interface MusicLoad<out T> {
    data object Loading : MusicLoad<Nothing>
    data class Loaded<T>(val value: T) : MusicLoad<T>
    data object Failed : MusicLoad<Nothing>
}

/** Runs [load] when [key] or [attempt] changes. Bump [attempt] to retry. A null result is [MusicLoad.Failed]. */
@Composable
internal fun <T : Any> rememberMusicLoad(key: Any, attempt: Int, load: suspend () -> T?): MusicLoad<T> {
    var state by remember(key, attempt) { mutableStateOf<MusicLoad<T>>(MusicLoad.Loading) }
    LaunchedEffect(key, attempt) {
        state = load()?.let { MusicLoad.Loaded(it) } ?: MusicLoad.Failed
    }
    return state
}

/** Cover art, or a note glyph on a flat tile when there is none. */
@Composable
internal fun MusicCover(
    url: String?,
    description: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    circle: Boolean = false,
) {
    val colors = MaterialTheme.nuvio.colors
    Box(
        modifier = modifier
            .size(size)
            .clip(if (circle) CircleShape else RoundedCornerShape(6.dp))
            .background(colors.surfaceElevated),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            NuvioAsyncImage(
                model = url,
                contentDescription = description,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = colors.textMuted)
        }
    }
}

/** A pill-shaped either/or switch: the search kind, audio or video. */
@Composable
internal fun <T> MusicSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val pill = RoundedCornerShape(20.dp)
    Row(modifier = modifier.musicGlass(pill)) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                modifier = Modifier
                    .clip(pill)
                    .background(if (on) colors.accent else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (on) colors.onAccent else colors.textSecondary,
                )
            }
        }
    }
}

/** A centred line of text, with an optional action under it (retry). */
@Composable
internal fun MusicMessage(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.nuvio.colors
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** An album in a list: cover, title, "Album · Artist · 2019". Used by search and the artist page. */
@Composable
internal fun MusicAlbumRow(
    album: MusicAlbumSummary,
    showArtist: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val subtitle = listOfNotNull(
        album.kind,
        album.artists.takeIf { showArtist && it.isNotEmpty() }?.joinToString(", ") { it.name },
        album.year,
    ).joinToString(" · ")
    MusicListRow(modifier = modifier, onClick = onClick) {
        MusicCover(album.thumbnailUrl, album.title, size = 56.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = album.title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            com.nuvio.app.features.music.nebula.AddonBadge(album) // fork:nebula
        }
    }
}

@Composable
internal fun MusicArtistRow(
    artist: MusicArtistSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    MusicListRow(modifier = modifier, onClick = onClick) {
        MusicCover(artist.thumbnailUrl, artist.name, size = 56.dp, circle = true)
        Spacer(Modifier.width(12.dp))
        Text(
            text = artist.name,
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MusicListRow(
    modifier: Modifier,
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
