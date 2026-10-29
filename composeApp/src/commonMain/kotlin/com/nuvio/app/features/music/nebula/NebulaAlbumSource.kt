package com.nuvio.app.features.music.nebula

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.MusicAlbum
import com.nuvio.app.features.music.MusicPlayMode
import com.nuvio.app.features.music.MusicPlayer
import com.nuvio.app.features.music.MusicSegmented
import com.nuvio.app.features.music.library.MusicLibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Where an album's audio comes from. */
internal enum class MusicSource { YouTube, Addon }

/** What the album page knows about the music addon for this album. */
internal sealed interface NebulaOffer {
    data object Checking : NebulaOffer
    data class Available(val match: StoredLookup) : NebulaOffer {
        val matched: Int get() = match.trackIds.count { it != null }
    }
    data object NotFound : NebulaOffer
    data class LookupFailed(val reason: String) : NebulaOffer
}

/**
 * The album page's addon state: the lookup, which source is chosen, and starting playback from the
 * addon. YouTube Music playback stays exactly as it was; this only decides which album the Play,
 * Shuffle and track taps hand to the player.
 */
@Stable
internal class NebulaAlbumController(
    private val album: MusicAlbum,
    private val player: MusicPlayer,
    private val scope: CoroutineScope,
) {
    var offer by mutableStateOf<NebulaOffer>(NebulaOffer.Checking)
        private set
    var chosen by mutableStateOf(MusicSource.YouTube)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var job: Job? = null

    /** True when Play, Shuffle and track taps should use the addon. */
    val active: Boolean get() = chosen == MusicSource.Addon && offer is NebulaOffer.Available

    fun choose(source: MusicSource) {
        chosen = source
        error = null
    }

    suspend fun check() {
        offer = NebulaOffer.Checking
        offer = when (val found = NebulaMusicSource.find(album)) {
            is NebulaAlbumLookup.Found -> NebulaOffer.Available(found.match)
            NebulaAlbumLookup.NotFound -> NebulaOffer.NotFound.also { chosen = MusicSource.YouTube }
            is NebulaAlbumLookup.Failed -> NebulaOffer.LookupFailed(found.reason).also { chosen = MusicSource.YouTube }
        }
    }

    fun play(trackIndex: Int, mode: MusicPlayMode, shuffled: Boolean) {
        val available = offer as? NebulaOffer.Available ?: return
        job?.cancel()
        job = scope.launch {
            error = null
            player.play(NebulaMusicSource.prepare(album, available.match), trackIndex = trackIndex, mode = mode, shuffled = shuffled)
        }
    }
}

/**
 * The controller for [album], or null when no addon link is set (the album page then looks exactly as
 * it did). It looks the album up once the page opens, and again when [retry] changes.
 */
@Composable
internal fun rememberNebulaAlbumController(album: MusicAlbum, player: MusicPlayer, retry: Int): NebulaAlbumController? {
    remember { MusicLibraryRepository.ensureLoaded() }
    val link = MusicLibraryRepository.state.collectAsState().value.settings.nebulaSecretLink.trim()
    val scope = rememberCoroutineScope()
    val controller = remember(album.browseId, link) { NebulaAlbumController(album, player, scope) }
    if (nebulaBaseUrl(link) == null) return null
    LaunchedEffect(controller, retry) { controller.check() }
    return controller
}

/** The source toggle and its status line, under the Play row. */
@Composable
internal fun NebulaSourceRow(controller: NebulaAlbumController, onRetry: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (val offer = controller.offer) {
            NebulaOffer.Checking -> Text("Checking your music addon…", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            NebulaOffer.NotFound -> Text("Not on your music addon.", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            is NebulaOffer.LookupFailed -> Text(
                "Couldn't check your music addon: ${offer.reason} Tap to try again.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.warning,
                modifier = Modifier.clickable(onClick = onRetry),
            )
            is NebulaOffer.Available -> {
                MusicSegmented(
                    options = listOf(MusicSource.YouTube to "YouTube Music", MusicSource.Addon to "Addon · lossless"),
                    selected = controller.chosen,
                    onSelect = controller::choose,
                )
                if (controller.chosen == MusicSource.Addon) {
                    Text(
                        "Play uses your addon copy (${offer.matched} tracks matched).",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            }
        }
        controller.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.warning) }
    }
}
