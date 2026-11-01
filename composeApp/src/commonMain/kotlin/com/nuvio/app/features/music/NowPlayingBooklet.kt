package com.nuvio.app.features.music

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import com.nuvio.app.features.music.booklet.BookletArtist
import com.nuvio.app.features.music.booklet.BookletRelease
import com.nuvio.app.features.music.booklet.BookletRequest
import com.nuvio.app.features.music.booklet.BookletSource
import com.nuvio.app.features.music.booklet.BookletTrack
import com.nuvio.app.features.music.booklet.DeckPosition
import com.nuvio.app.features.music.booklet.FactKind
import com.nuvio.app.features.music.booklet.LinerPage
import com.nuvio.app.features.music.booklet.bookletTrackNumber
import com.nuvio.app.features.music.booklet.coverStats
import com.nuvio.app.features.music.booklet.deckStep
import com.nuvio.app.features.music.booklet.guestTrack
import com.nuvio.app.features.music.booklet.isBookletAlbum
import com.nuvio.app.features.music.booklet.linerPages
import com.nuvio.app.features.music.library.LibraryArtist
import com.nuvio.app.features.music.library.ReleaseKind
import com.nuvio.app.features.music.library.musicGlass
import com.nuvio.app.features.music.library.musicKeyFocusRing

/*
 * fork:booklet - the right-hand glass panel of the Now Playing stage: a CD's liner notes, as a deck of pages to flip
 * through. A cover, trivia cards about the album and the artist (the question first, Select reveals the answer), the
 * guest list, the artist's albums in time, and a note for the song playing. Left and Right turn the page.
 */

/** What the panel has to show. */
internal sealed interface BookletUi {
    /** Being looked up (cached notes show at once; this is only the first time for an album). */
    data object Loading : BookletUi

    /** Nothing to show (not an album, switched off, or Wikipedia has no page): the panel is left out. */
    data object None : BookletUi

    data class Ready(val booklet: AlbumBooklet, val request: BookletRequest) : BookletUi
}

/** What the library knows of [artist] that the cards and the timeline can use; null for a namesake's data or no artist. */
internal fun bookletArtistFrom(artist: LibraryArtist?): BookletArtist? {
    if (artist == null || artist.channelMismatch) return null
    val albums = artist.releases
        .filter { it.kind == ReleaseKind.Album && it.secondaryTypes.isEmpty() }
        .mapNotNull { release ->
            val year = release.year?.take(4)?.toIntOrNull() ?: return@mapNotNull null
            BookletRelease(release.title, year, release.ytBrowseId)
        }
    return BookletArtist(
        type = artist.type,
        beginYear = artist.beginYear,
        country = artist.country ?: artist.area,
        genres = (artist.manualGenres + artist.genres + artist.tags).filter { it.isNotBlank() }.distinct().take(4),
        biography = artist.biography,
        albums = albums,
    )
}

/** The lookup for [album], or null for anything a booklet cannot be written for (a mix, a playlist, no named artist). */
internal fun bookletRequestFor(album: MusicAlbum, artist: LibraryArtist? = null): BookletRequest? {
    if (!isBookletAlbum(album.browseId)) return null
    val name = album.artists.firstOrNull()?.name?.takeIf { it.isNotBlank() } ?: return null
    return BookletRequest(name, album.title, album.year, album.tracks.map { BookletTrack(it.title, it.durationSeconds) }, bookletArtistFrom(artist))
}

/** The booklet for the album playing: from the cache at once, else looked up (once per album) in the background. */
@Composable
internal fun rememberBookletUi(album: MusicAlbum?, enabled: Boolean, artist: LibraryArtist? = null): BookletUi {
    val request = remember(album?.browseId, album?.tracks?.size, enabled, artist?.id, artist?.releases?.size, artist?.beginYear, artist?.channelMismatch) {
        if (enabled && album != null) bookletRequestFor(album, artist) else null
    }
    // A key added or removed in Settings changes who writes the notes, so it starts a new lookup.
    val aiReady = DiscoverAiSettingsRepository.uiState.collectAsState().value.isConfigured
    val ui by produceState<BookletUi>(BookletUi.Loading, request, aiReady) {
        if (request == null) {
            value = BookletUi.None
            return@produceState
        }
        value = AlbumBookletRepository.peek(request)?.let { BookletUi.Ready(it, request) } ?: BookletUi.Loading
        val found = AlbumBookletRepository.bookletFor(request)
        value = if (found != null) BookletUi.Ready(found, request) else BookletUi.None
    }
    return if (request == null) BookletUi.None else ui
}

@Composable
internal fun NowPlayingBooklet(
    ui: BookletUi,
    album: MusicAlbum,
    playingVideoId: String?,
    reduced: Boolean,
    onJumpToTrack: (Int) -> Unit,
    onOpenAlbum: (String) -> Unit,
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
            is BookletUi.Ready -> BookletDeck(ui.booklet, ui.request, album, playingVideoId, reduced, onJumpToTrack, onOpenAlbum, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

private fun cursorCount(page: LinerPage): Int = when (page) {
    is LinerPage.Guests -> page.guests.size
    is LinerPage.Timeline -> page.timeline.entries.size
    else -> 0
}

private fun initialCursor(page: LinerPage): Int = (page as? LinerPage.Timeline)?.timeline?.currentIndex ?: 0

private fun Modifier.onTap(action: () -> Unit): Modifier = pointerInput(action) { detectTapGestures { action() } }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookletDeck(
    booklet: AlbumBooklet,
    request: BookletRequest,
    album: MusicAlbum,
    playingVideoId: String?,
    reduced: Boolean,
    onJumpToTrack: (Int) -> Unit,
    onOpenAlbum: (String) -> Unit,
    modifier: Modifier,
) {
    val accent = MaterialTheme.nuvio.colors.accent
    val playing = bookletTrackNumber(album.tracks.map { it.videoId }, playingVideoId)
    val pages = remember(booklet, request, playing) { linerPages(booklet, request, playing) }
    var position by remember(booklet) { mutableStateOf(DeckPosition(0, 0)) }
    var revealed by remember(booklet) { mutableStateOf(emptySet<Int>()) }
    var lastPlaying by remember(booklet) { mutableStateOf(playing) }
    var forward by remember { mutableStateOf(true) }
    val page = position.page.coerceIn(0, pages.lastIndex)

    // A new song turns the deck to its own note, when the booklet has one.
    LaunchedEffect(playing, pages.size) {
        if (playing != lastPlaying) {
            lastPlaying = playing
            val at = pages.indexOfLast { it is LinerPage.Track }
            if (at >= 0) {
                forward = at >= page
                position = DeckPosition(at, 0)
            }
        }
    }

    /** Turns toward [direction]; false when the deck is already at that end, so the key can move focus on instead. */
    fun turn(direction: Int): Boolean {
        val from = DeckPosition(page, position.cursor)
        val next = deckStep(from, pages.size, cursorCount(pages[page]), direction)
        if (next == from) return false
        forward = direction > 0
        position = if (next.page != page) DeckPosition(next.page, initialCursor(pages[next.page])) else next
        return true
    }

    fun select() {
        when (val current = pages[page]) {
            is LinerPage.Fact -> revealed = revealed + current.number
            is LinerPage.Guests -> current.guests.getOrNull(position.cursor)?.let { guestTrack(it, album.tracks.size) }?.let { onJumpToTrack(it - 1) }
            is LinerPage.Timeline -> current.timeline.entries.getOrNull(position.cursor)?.browseId?.let(onOpenAlbum)
            else -> Unit
        }
    }

    Column(modifier) {
        AnimatedContent(
            targetState = page,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .musicKeyFocusRing(RoundedCornerShape(12.dp))
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> turn(-1)
                        Key.DirectionRight -> turn(1)
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter, Key.Spacebar -> {
                            select()
                            true
                        }
                        else -> false
                    }
                }
                .focusable(),
            transitionSpec = {
                if (reduced) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    (slideInHorizontally(tween(260)) { w -> if (forward) w / 5 else -w / 5 } + fadeIn(tween(260))) togetherWith
                        (slideOutHorizontally(tween(180)) { w -> if (forward) -w / 5 else w / 5 } + fadeOut(tween(160)))
                }
            },
            label = "linerPage",
        ) { index ->
            val shown = pages.getOrElse(index) { LinerPage.Cover }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                when (shown) {
                    LinerPage.Cover -> CoverPage(album, request, booklet.intro, accent)
                    is LinerPage.Fact -> FactPage(shown, shown.number in revealed, accent) { revealed = revealed + shown.number }
                    is LinerPage.Guests -> GuestsPage(shown, position.cursor, album.tracks.size, accent) { at ->
                        position = position.copy(cursor = at)
                        shown.guests.getOrNull(at)?.let { guestTrack(it, album.tracks.size) }?.let { onJumpToTrack(it - 1) }
                    }
                    is LinerPage.Timeline -> TimelinePage(shown, position.cursor, request.artist, accent) { at ->
                        position = position.copy(cursor = at)
                        shown.timeline.entries.getOrNull(at)?.browseId?.let(onOpenAlbum)
                    }
                    is LinerPage.Track -> TrackPage(shown, accent)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            NavPill("‹", page > 0) { turn(-1) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                pages.indices.forEach { i ->
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (i == page) accent else Color.White.copy(alpha = 0.25f)))
                }
            }
            NavPill("›", page < pages.lastIndex) { turn(1) }
        }
        Text(
            "Left and Right turn the page · Select reveals",
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp).fillMaxWidth(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            when (booklet.source) {
                BookletSource.Ai -> "Written by AI from Wikipedia"
                BookletSource.Wikipedia -> "Text from Wikipedia, CC BY-SA 4.0"
            },
            color = Color.White.copy(alpha = 0.35f),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun NavPill(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.12f else 0.05f))
            .onTap { if (enabled) onClick() }
            .padding(horizontal = 14.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White.copy(alpha = if (enabled) 0.9f else 0.3f), fontSize = 18.sp)
    }
}

@Composable
private fun PageLabel(text: String, accent: Color, modifier: Modifier = Modifier) {
    Text(text, color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, modifier = modifier, maxLines = 1)
}

@Composable
private fun CoverPage(album: MusicAlbum, request: BookletRequest, intro: String, accent: Color) {
    PageLabel("LINER NOTES", accent)
    Spacer(Modifier.height(6.dp))
    Text(album.title, color = Color.White, fontFamily = FontFamily.Serif, fontSize = 26.sp, lineHeight = 29.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    val line = listOfNotNull(album.artists.firstOrNull()?.name, album.year).joinToString(" · ")
    if (line.isNotBlank()) Text(line, color = Color.White.copy(alpha = 0.65f), fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
    if (intro.isNotBlank()) {
        Text(intro, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, lineHeight = 22.sp, maxLines = 8, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
    }
    val stats = coverStats(request, album.year)
    if (stats.isNotEmpty()) {
        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            stats.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (label, value) ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.07f)).padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Text(label, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, maxLines = 1)
                            Text(value, color = Color.White, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun FactPage(page: LinerPage.Fact, revealed: Boolean, accent: Color, onReveal: () -> Unit) {
    val tag = if (page.fact.kind == FactKind.Artist) "ARTIST" else "ALBUM"
    PageLabel("DID YOU KNOW? · ${page.number} OF ${page.total} · $tag", accent)
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(accent.copy(alpha = 0.14f))
            .border(0.5.dp, accent.copy(alpha = 0.5f), shape)
            .onTap(onReveal)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(page.fact.question, color = Color.White, fontFamily = FontFamily.Serif, fontSize = 22.sp, lineHeight = 28.sp)
        if (revealed) {
            Text(page.fact.answer, color = Color.White.copy(alpha = 0.92f), fontSize = 16.sp, lineHeight = 24.sp)
        } else {
            Box(Modifier.clip(CircleShape).background(accent).padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text("Reveal", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuestsPage(page: LinerPage.Guests, cursor: Int, trackCount: Int, accent: Color, onPick: (Int) -> Unit) {
    PageLabel("GUEST LIST", accent)
    Text("Special guests", color = Color.White, fontFamily = FontFamily.Serif, fontSize = 26.sp, modifier = Modifier.padding(top = 6.dp))
    Text("Select a name to hear the song they are on", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
    FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        page.guests.forEachIndexed { i, guest ->
            val track = guestTrack(guest, trackCount)
            val picked = i == cursor
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(if (picked) accent.copy(alpha = 0.3f) else Color.Transparent)
                    .border(0.5.dp, if (picked) accent else Color.White.copy(alpha = 0.4f), CircleShape)
                    .onTap { onPick(i) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(if (track != null) "${guest.name} · $track" else guest.name, color = Color.White, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun TimelinePage(page: LinerPage.Timeline, cursor: Int, artist: String, accent: Color, onPick: (Int) -> Unit) {
    PageLabel("THE ARTIST IN TIME", accent)
    Text("Where it sits", color = Color.White, fontFamily = FontFamily.Serif, fontSize = 26.sp, modifier = Modifier.padding(top = 6.dp))
    Text("$artist's albums, in order", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        page.timeline.entries.forEachIndexed { i, entry ->
            val current = i == page.timeline.currentIndex
            val picked = i == cursor
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (picked) accent.copy(alpha = 0.22f) else Color.Transparent)
                    .onTap { onPick(i) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(if (current) 12.dp else 8.dp).clip(CircleShape).background(if (current) accent else Color.White.copy(alpha = 0.4f)))
                Text(entry.year.toString(), color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, modifier = Modifier.width(44.dp))
                Text(
                    entry.title,
                    color = Color.White.copy(alpha = if (current || picked) 1f else 0.8f),
                    fontSize = 16.sp,
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TrackPage(page: LinerPage.Track, accent: Color) {
    PageLabel("NOW PLAYING · TRACK ${page.number}", accent)
    if (page.title.isNotBlank()) {
        Text(page.title, color = Color.White, fontFamily = FontFamily.Serif, fontSize = 26.sp, lineHeight = 29.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
    Text(page.note, color = Color.White.copy(alpha = 0.88f), fontSize = 16.sp, lineHeight = 24.sp, modifier = Modifier.padding(top = 12.dp))
}
