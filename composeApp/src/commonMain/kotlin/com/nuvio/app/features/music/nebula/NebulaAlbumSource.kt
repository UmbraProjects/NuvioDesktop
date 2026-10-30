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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.delay
import com.nuvio.app.features.library.LibraryClock

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

    /** The album with its tracks pointed at the addon, once the addon has it. Its availability is the addon's. */
    val preparedAlbum: MusicAlbum?
        get() = (offer as? NebulaOffer.Available)?.let { NebulaMusicSource.prepare(album, it.match) }

    /** True while the player holds this album's addon copy, whichever source the switch now shows. */
    val playingFromAddon: Boolean
        get() = player.state.value.let { s -> s.album?.browseId == album.browseId && s.track?.let(NebulaMusicSource::owns) == true }

    /** True when Play, Shuffle and track taps should use the addon. */
    val active: Boolean get() = chosen == MusicSource.Addon && offer is NebulaOffer.Available

    fun choose(source: MusicSource) {
        chosen = source
        error = null
    }

    suspend fun check() {
        offer = NebulaOffer.Checking
        offer = when (val found = NebulaMusicSource.find(album)) {
            is NebulaAlbumLookup.Found -> NebulaOffer.Available(found.match).also {
                // An album YouTube Music has no playable track for is only playable from the addon: start there.
                if (album.tracks.none { t -> t.isAvailable } || MusicLibraryRepository.state.value.settings.preferAddon) chosen = MusicSource.Addon
            }
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
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (val offer = controller.offer) {
            NebulaOffer.Checking -> Text("Checking your music addon…", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            NebulaOffer.NotFound -> Text("Not on your music addon.", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            is NebulaOffer.LookupFailed -> Text(
                "Couldn't check your music addon: ${offer.reason} Tap to try again.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.warning,
                textAlign = TextAlign.Center,
                modifier = Modifier.clickable(onClick = onRetry),
            )
            // The source itself is a button in the album's action row; this only says what it will play.
            is NebulaOffer.Available -> Text(
                if (controller.chosen == MusicSource.Addon) "Playing from your addon, lossless (${offer.matched} tracks matched)."
                else "Your addon has this album lossless (${offer.matched} tracks matched).",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
        controller.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.warning, textAlign = TextAlign.Center) }
        // What the player is waiting on from the addon, or why the last track it asked for didn't come: without this a slow
        // addon was a silent spinner and a refusal only showed as a skip.
        if (controller.active || controller.playingFromAddon) {
            when (val status = NebulaMusicSource.streamStatus) {
                is NebulaStreamStatus.Asking -> {
                    val waited by produceState(0L, status) {
                        while (true) {
                            value = (LibraryClock.nowEpochMs() - status.sinceMs) / 1000
                            delay(1_000)
                        }
                    }
                    Text(
                        "Asking your addon for \u201C${status.trackTitle}\u201D" + (if (waited >= 3) " (${waited} s)…" else "…"),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
                is NebulaStreamStatus.Failed -> Text(
                    "Couldn't play \u201C${status.trackTitle}\u201D from your addon: ${status.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.warning,
                    textAlign = TextAlign.Center,
                )
                null -> Unit
            }
        }
    }
}
