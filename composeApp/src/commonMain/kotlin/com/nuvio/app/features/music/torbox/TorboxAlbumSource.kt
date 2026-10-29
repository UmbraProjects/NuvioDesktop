package com.nuvio.app.features.music.torbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.music.MusicAlbum
import com.nuvio.app.features.music.MusicPlayMode
import com.nuvio.app.features.music.MusicPlayer
import com.nuvio.app.features.music.MusicSegmented
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Where an album's audio comes from. */
internal enum class MusicSource { YouTube, TorBox }

/** What the album page knows about TorBox for this album. */
internal sealed interface TorboxOffer {
    data object Checking : TorboxOffer
    data class Available(val hit: TorboxSearchHit) : TorboxOffer {
        /** "FLAC", "MP3 320", or a plain "Cached" when the title says nothing about quality. */
        val label: String get() = torboxQualityLabel(hit.title).ifEmpty { "Cached" }
    }
    data object NotCached : TorboxOffer
    data object LookupFailed : TorboxOffer
}

/**
 * The album page's TorBox state: the lookup, which source is chosen, and starting playback from
 * TorBox. Playback from YouTube Music stays exactly as it was; this only decides which album the
 * Play, Shuffle and track taps hand to the player.
 */
@Stable
internal class TorboxAlbumController(
    private val album: MusicAlbum,
    private val player: MusicPlayer,
    private val scope: CoroutineScope,
) {
    var offer by mutableStateOf<TorboxOffer>(TorboxOffer.Checking)
        private set
    var chosen by mutableStateOf(MusicSource.YouTube)
        private set
    var opening by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var matched by mutableStateOf<Int?>(null)
        private set

    private var prepared: MusicAlbum? = null
    private var job: Job? = null

    /** True when Play, Shuffle and track taps should use TorBox. */
    val active: Boolean get() = chosen == MusicSource.TorBox && offer is TorboxOffer.Available

    fun choose(source: MusicSource) {
        chosen = source
        error = null
    }

    suspend fun check() {
        offer = TorboxOffer.Checking
        offer = when (val found = TorboxMusicSource.find(album)) {
            is TorboxAlbumLookup.Found -> TorboxOffer.Available(found.hit)
            TorboxAlbumLookup.NotFound -> TorboxOffer.NotCached.also { chosen = MusicSource.YouTube }
            TorboxAlbumLookup.Failed -> TorboxOffer.LookupFailed.also { chosen = MusicSource.YouTube }
        }
    }

    /** Opens the torrent on first use, then starts the album at [trackIndex]. */
    fun play(trackIndex: Int, mode: MusicPlayMode, shuffled: Boolean) {
        val available = offer as? TorboxOffer.Available ?: return
        job?.cancel()
        job = scope.launch {
            opening = true
            error = null
            val ready = prepared ?: when (val result = TorboxMusicSource.prepare(album, available.hit)) {
                is TorboxPrepared.Ready -> result.album.also { prepared = it; matched = result.matched }
                TorboxPrepared.NoMatch -> { error = "This torrent doesn't line up with the album's tracks."; null }
                TorboxPrepared.Failed -> { error = "Couldn't open this on TorBox."; null }
            }
            opening = false
            if (ready != null) player.play(ready, trackIndex = trackIndex, mode = mode, shuffled = shuffled)
        }
    }
}

/**
 * The controller for [album], or null when no TorBox key is set (the album page then looks exactly
 * as it did). It looks the album up once the page opens, and again when [retry] changes.
 */
@Composable
internal fun rememberTorboxAlbumController(album: MusicAlbum, player: MusicPlayer, retry: Int): TorboxAlbumController? {
    remember { DebridSettingsRepository.ensureLoaded() }
    val key = DebridSettingsRepository.uiState.collectAsState().value.torboxApiKey.trim()
    val scope = rememberCoroutineScope()
    val controller = remember(album.browseId, key) { TorboxAlbumController(album, player, scope) }
    if (key.isEmpty()) return null
    LaunchedEffect(controller, retry) { controller.check() }
    return controller
}

/** The source toggle and its status line, under the Play row. */
@Composable
internal fun TorboxSourceRow(controller: TorboxAlbumController, onRetry: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (val offer = controller.offer) {
            TorboxOffer.Checking -> Text("Checking TorBox…", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            TorboxOffer.NotCached -> Text("Not cached on TorBox.", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            TorboxOffer.LookupFailed -> Text(
                "Couldn't check TorBox. Try again.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.warning,
                modifier = Modifier.clickable(onClick = onRetry),
            )
            is TorboxOffer.Available -> {
                MusicSegmented(
                    options = listOf(MusicSource.YouTube to "YouTube Music", MusicSource.TorBox to "TorBox · ${offer.label}"),
                    selected = controller.chosen,
                    onSelect = controller::choose,
                )
                val line = when {
                    controller.opening -> "Opening on TorBox…"
                    controller.chosen == MusicSource.TorBox -> controller.matched
                        ?.let { "Playing your TorBox copy ($it tracks matched)." }
                        ?: "Play uses your TorBox copy: ${offer.hit.title}"
                    else -> null
                }
                line?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary) }
            }
        }
        controller.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.warning) }
    }
}
