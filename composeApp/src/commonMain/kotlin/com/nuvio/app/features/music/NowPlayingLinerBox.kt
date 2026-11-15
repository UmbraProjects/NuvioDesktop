package com.nuvio.app.features.music
// FORK UI (Spyder): Nick's Now Playing stage, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.library.MusicLibraryRepository
import com.nuvio.app.features.music.library.musicGlass
import com.nuvio.app.features.music.library.musicKeyFocusRing
import com.nuvio.app.features.music.visualiser.LiveTrackMap
import com.nuvio.app.features.music.visualiser.StageBoxView
import com.nuvio.app.features.music.visualiser.TrackMapLive
import com.nuvio.app.features.music.visualiser.VisualiserStyle
import com.nuvio.app.features.music.visualiser.clockLabel
import com.nuvio.app.features.music.visualiser.nextChorusStartMs
import com.nuvio.app.features.music.visualiser.sectionAt
import com.nuvio.app.features.music.visualiser.songSections
import kotlin.math.roundToInt

/*
 * fork:visualiser - the right-hand glass box of a wide Stage. It is always there and always the same size (its height
 * comes from the album case and the progress bar, measured by the Stage): the liner notes when the album has them, the
 * visualiser otherwise, and a switch at the end of the hint line turns between the two. Nothing outside the glass moves.
 */

@Composable
internal fun NowPlayingLinerBox(
    bookletUi: BookletUi,
    album: MusicAlbum?,
    playingVideoId: String?,
    reduced: Boolean,
    live: LiveTrackMap?,
    positionMs: Long,
    playing: Boolean,
    durationMs: Long,
    lyricLines: List<LyricLine>?,
    coverUrl: String?,
    onJumpToTrack: (Int) -> Unit,
    onOpenAlbum: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = MusicLibraryRepository.state.collectAsStateWithLifecycle().value.settings
    // Notes are on offer while they load or once found; "no notes" (not an album, switched off, none written) leaves the visualiser.
    val notesOnOffer = album != null && bookletUi !is BookletUi.None
    val showNotes = notesOnOffer && settings.stageBoxView == StageBoxView.Notes
    val choose: (StageBoxView) -> Unit = { view ->
        if (view != settings.stageBoxView) MusicLibraryRepository.updateSettings(settings.copy(stageBoxView = view))
    }
    val switch: @Composable () -> Unit = { BoxViewSwitch(showNotes, notesOnOffer, choose) }
    Column(modifier.musicGlass(RoundedCornerShape(18.dp)).padding(horizontal = 22.dp, vertical = 18.dp)) {
        when {
            showNotes && bookletUi is BookletUi.Ready && album != null -> BookletDeck(
                bookletUi.booklet, bookletUi.request, album, playingVideoId, reduced, onJumpToTrack, onOpenAlbum,
                Modifier.weight(1f).fillMaxWidth(), switch,
            )
            showNotes -> {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Reading the sleeve notes…", color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp)
                }
                LinerHintRow("", switch)
                Text(" ", fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            }
            else -> VisualiserDeck(
                style = settings.visualiserStyle,
                onStyle = { style -> MusicLibraryRepository.updateSettings(settings.copy(visualiserStyle = style)) },
                live = live,
                positionMs = positionMs,
                playing = playing,
                durationMs = durationMs,
                lyricLines = lyricLines,
                coverUrl = coverUrl,
                reduced = reduced,
                switch = switch,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

private fun Modifier.onTap(action: () -> Unit): Modifier = pointerInput(action) { detectTapGestures { action() } }

/** Notes | Visualiser. Select or Left and Right flip it; Notes is dimmed when the album has none. */
@Composable
private fun BoxViewSwitch(showNotes: Boolean, notesOnOffer: Boolean, onChoose: (StageBoxView) -> Unit) {
    val accent = MaterialTheme.nuvio.colors.accent
    val shape = CircleShape
    fun flip() {
        if (notesOnOffer) onChoose(if (showNotes) StageBoxView.Visualiser else StageBoxView.Notes)
    }
    Row(
        Modifier
            .padding(start = 8.dp)
            .musicKeyFocusRing(shape)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter, Key.Spacebar -> { flip(); true }
                    Key.DirectionLeft -> if (!showNotes && notesOnOffer) { onChoose(StageBoxView.Notes); true } else false
                    Key.DirectionRight -> if (showNotes) { onChoose(StageBoxView.Visualiser); true } else false
                    else -> false
                }
            }
            .focusable()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.08f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Segment("Notes", selected = showNotes, enabled = notesOnOffer, accent = accent) { onChoose(StageBoxView.Notes) }
        Segment("Visualiser", selected = !showNotes, enabled = true, accent = accent) { onChoose(StageBoxView.Visualiser) }
    }
}

@Composable
private fun Segment(text: String, selected: Boolean, enabled: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (selected) accent else Color.Transparent)
            .onTap { if (enabled) onClick() }
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(
            text,
            color = Color.White.copy(alpha = if (selected) 1f else if (enabled) 0.7f else 0.3f),
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun VisualiserDeck(
    style: VisualiserStyle,
    onStyle: (VisualiserStyle) -> Unit,
    live: LiveTrackMap?,
    positionMs: Long,
    playing: Boolean,
    durationMs: Long,
    lyricLines: List<LyricLine>?,
    coverUrl: String?,
    reduced: Boolean,
    switch: @Composable () -> Unit,
    modifier: Modifier,
) {
    val accent = MaterialTheme.nuvio.colors.accent
    val styles = VisualiserStyle.entries
    val index = styles.indexOf(style)
    val position = rememberSmoothPositionMs(positionMs, playing)
    val sections = remember(lyricLines, durationMs) { songSections(lyricLines.orEmpty(), durationMs) }
    val map = live?.map
    // The subtitle changes at most once a second, so only that recomposes.
    val second by remember { derivedStateOf { position.value / 1_000L } }
    val subtitle = remember(style, second, sections, map, live?.lufs) {
        val now = second * 1_000L
        when (style) {
            VisualiserStyle.SongMap -> {
                val part = sectionAt(sections, now)?.kind?.label
                val next = nextChorusStartMs(sections, now)?.let { "Chorus in ${clockLabel(it - now)}" }
                listOfNotNull(part, next).joinToString(" · ").ifEmpty { "The shape of this song" }
            }
            VisualiserStyle.Halo -> "Reacting to the bass"
            VisualiserStyle.TwinVu -> "Left and right level"
            // fork:visualiser - OK (or a click on the grid) moves beat one along, for a song the network reads a beat out.
            VisualiserStyle.BeatGrid -> map?.beat?.let { if (it.barShift == 0) "Kick, snare, hats and melody · OK moves beat one" else "Beat one moved by hand · OK moves it again" }
                ?: if (map?.complete == true) "No steady beat" else if (map != null && map.frames > 0) "Listening for the beat" else "Finding the beat"
        }
    }

    fun moveBeatOne(): Boolean {
        if (style != VisualiserStyle.BeatGrid || map?.beat == null) return false
        val id = live?.loadId ?: return false
        TrackMapLive.shiftBar(id, 1)
        return true
    }

    fun turn(direction: Int): Boolean {
        val next = index + direction
        if (next !in styles.indices) return false
        onStyle(styles[next])
        return true
    }

    Column(modifier) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .musicKeyFocusRing(RoundedCornerShape(12.dp))
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> turn(-1)
                        Key.DirectionRight -> turn(1)
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> moveBeatOne()
                        else -> false
                    }
                }
                .focusable(),
        ) {
            PageLabel("VISUALISER", accent)
            Text(
                style.title,
                color = Color.White,
                fontFamily = FontFamily.Serif,
                fontSize = 26.sp,
                lineHeight = 29.sp,
                maxLines = 1,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                subtitle,
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 14.dp).onTap { moveBeatOne() }) {
                if (reduced) {
                    VisualiserView(style, live, position, durationMs, sections, coverUrl, accent, reduced, Modifier.fillMaxSize())
                } else {
                    Crossfade(targetState = style, animationSpec = tween(260), label = "visualiserStyle") { shown ->
                        VisualiserView(shown, live, position, durationMs, sections, coverUrl, accent, reduced, Modifier.fillMaxSize())
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            NavPill("‹", index > 0) { turn(-1) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                styles.indices.forEach { i ->
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (i == index) accent else Color.White.copy(alpha = 0.25f)))
                }
            }
            NavPill("›", index < styles.lastIndex) { turn(1) }
        }
        LinerHintRow("Left and Right change the view", switch)
        Text(
            when {
                live?.unavailable == true && map == null -> "This track can't be analysed on this computer"
                map == null -> "Analysing this track…"
                live?.analysing == true -> "Analysing this track · ready to ${clockLabel(map.analysedMs)}"
                else -> "Measured on this track"
            },
            color = Color.White.copy(alpha = 0.35f),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
