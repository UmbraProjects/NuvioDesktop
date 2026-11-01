package com.nuvio.app.features.music

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.discover.DiscoverAiSettingsRepository
import com.nuvio.app.features.music.booklet.AlbumBooklet
import com.nuvio.app.features.music.booklet.AlbumBookletRepository
import com.nuvio.app.features.music.booklet.BookletRequest
import com.nuvio.app.features.music.booklet.BookletSource
import com.nuvio.app.features.music.booklet.BookletTrack
import com.nuvio.app.features.music.booklet.bookletTrackNumber
import com.nuvio.app.features.music.booklet.isBookletAlbum
import com.nuvio.app.features.music.library.musicGlass
import com.nuvio.app.features.music.library.musicKeyFocusRing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

/*
 * fork:booklet - the right-hand glass panel of the Now Playing stage: a CD's inlay booklet. The album's title, a few lines
 * about it, who made it and a note per track, with the page turned to the song that is playing. The wheel, Up and Down
 * scroll it; after a few quiet seconds it turns back to the song.
 */

/** What the panel has to show. */
internal sealed interface BookletUi {
    /** Being looked up (cached sleeve notes show at once; this is only the first time for an album). */
    data object Loading : BookletUi

    /** Nothing to show (not an album, switched off, or Wikipedia has no page): the panel is left out. */
    data object None : BookletUi

    data class Ready(val booklet: AlbumBooklet) : BookletUi
}

/** The lookup for [album], or null for anything a booklet cannot be written for (a mix, a playlist, no named artist). */
internal fun bookletRequestFor(album: MusicAlbum): BookletRequest? {
    if (!isBookletAlbum(album.browseId)) return null
    val artist = album.artists.firstOrNull()?.name?.takeIf { it.isNotBlank() } ?: return null
    return BookletRequest(artist, album.title, album.year, album.tracks.map { BookletTrack(it.title, it.durationSeconds) })
}

/** The booklet for the album playing: from the cache at once, else looked up (once per album) in the background. */
@Composable
internal fun rememberBookletUi(album: MusicAlbum?, enabled: Boolean): BookletUi {
    val request = remember(album?.browseId, album?.tracks?.size, enabled) {
        if (enabled && album != null) bookletRequestFor(album) else null
    }
    // A key added or removed in Settings changes who writes the notes, so it starts a new lookup.
    val aiReady = DiscoverAiSettingsRepository.uiState.collectAsState().value.isConfigured
    val ui by produceState<BookletUi>(BookletUi.Loading, request, aiReady) {
        if (request == null) {
            value = BookletUi.None
            return@produceState
        }
        value = AlbumBookletRepository.peek(request)?.let { BookletUi.Ready(it) } ?: BookletUi.Loading
        val found = AlbumBookletRepository.bookletFor(request)
        value = if (found != null) BookletUi.Ready(found) else BookletUi.None
    }
    return if (request == null) BookletUi.None else ui
}

/** How long the booklet rests where the person left it before turning back to the song playing. */
private const val BOOKLET_IDLE_MS = 6_000L

private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

@Composable
internal fun NowPlayingBooklet(
    ui: BookletUi,
    album: MusicAlbum,
    playingVideoId: String?,
    reduced: Boolean,
    modifier: Modifier = Modifier,
) {
    if (ui is BookletUi.None) return
    val shape = RoundedCornerShape(18.dp)
    Column(modifier.musicGlass(shape).padding(horizontal = 22.dp, vertical = 18.dp)) {
        when (ui) {
            BookletUi.None -> Unit
            BookletUi.Loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Reading the sleeve notes…", color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp)
            }
            is BookletUi.Ready -> BookletPages(ui.booklet, album, playingVideoId, reduced, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun BookletPages(
    booklet: AlbumBooklet,
    album: MusicAlbum,
    playingVideoId: String?,
    reduced: Boolean,
    modifier: Modifier,
) {
    val accent = MaterialTheme.nuvio.colors.accent
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var touchedAt by remember { mutableLongStateOf(0L) }
    val playing = bookletTrackNumber(album.tracks.map { it.videoId }, playingVideoId)
    val creditItems = if (booklet.credits.isEmpty()) 0 else 1
    // Items before the first track: the heading, the intro, the credits (when there are any) and the "tracks" label.
    val firstTrackItem = 2 + creditItems + 1

    // Turn to the song playing: when it changes, and again once the person has left the booklet alone for a while.
    LaunchedEffect(playing, touchedAt, booklet) {
        val number = playing ?: return@LaunchedEffect
        val wait = BOOKLET_IDLE_MS - (nowMs() - touchedAt)
        if (wait > 0) delay(wait)
        // The first song shows the whole front of the booklet; later ones put their own note at the top.
        val target = if (number == 1) 0 else firstTrackItem + number - 1
        if (reduced) listState.scrollToItem(target) else listState.animateScrollToItem(target)
    }

    Column(modifier) {
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .musicKeyFocusRing(RoundedCornerShape(12.dp))
                .onKeyEvent { event ->
                    val down = event.key == Key.DirectionDown
                    if (event.type == KeyEventType.KeyDown && (down || event.key == Key.DirectionUp)) {
                        touchedAt = nowMs()
                        scope.launch { listState.animateScrollBy(if (down) 240f else -240f) }
                        true
                    } else {
                        false
                    }
                }
                .focusable()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Scroll || event.type == PointerEventType.Press) touchedAt = nowMs()
                        }
                    }
                },
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "head") {
                Column {
                    Text("LINER NOTES", color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        album.title,
                        color = Color.White,
                        fontFamily = FontFamily.Serif,
                        fontSize = 26.sp,
                        lineHeight = 29.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val line = listOfNotNull(album.artists.firstOrNull()?.name, album.year).joinToString(" · ")
                    if (line.isNotBlank()) {
                        Text(line, color = Color.White.copy(alpha = 0.65f), fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
            item(key = "intro") {
                Text(booklet.intro, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(vertical = 8.dp))
            }
            if (booklet.credits.isNotEmpty()) {
                item(key = "credits") {
                    Column(Modifier.padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        BookletLabel("CREDITS")
                        booklet.credits.forEach { credit ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(credit.role, color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp, modifier = Modifier.width(96.dp))
                                Text(credit.names, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            item(key = "tracks") { BookletLabel("TRACKS", Modifier.padding(top = 4.dp)) }
            items(album.tracks.size, key = { "t$it" }) { index ->
                val number = index + 1
                BookletTrackRow(
                    number = number,
                    track = album.tracks[index],
                    note = booklet.trackNotes[number],
                    playing = number == playing,
                    accent = accent,
                )
            }
            item(key = "end") { Spacer(Modifier.height(12.dp)) }
        }
        Text(
            when (booklet.source) {
                BookletSource.Ai -> "Written by AI from Wikipedia"
                BookletSource.Wikipedia -> "Text from Wikipedia, CC BY-SA 4.0"
            },
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun BookletLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, letterSpacing = 1.2.sp, modifier = modifier)
}

@Composable
private fun BookletTrackRow(number: Int, track: MusicTrack, note: String?, playing: Boolean, accent: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (playing) accent.copy(alpha = 0.22f) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(number.toString().padStart(2, '0'), color = if (playing) accent else Color.White.copy(alpha = 0.45f), fontSize = 14.sp, modifier = Modifier.width(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                track.title,
                color = Color.White.copy(alpha = if (playing) 1f else 0.78f),
                fontSize = 16.sp,
                fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (note != null) {
                Text(note, color = Color.White.copy(alpha = if (playing) 0.85f else 0.6f), fontSize = 14.sp, lineHeight = 19.sp)
            }
        }
        if (track.durationSeconds > 0) {
            Text(formatMusicTime(track.durationSeconds * 1_000L), color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
        }
    }
}
