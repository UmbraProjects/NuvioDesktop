package com.nuvio.app.features.music
// FORK UI (Spyder): Nick's Now Playing stage, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.SubcomposeLayout
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
import androidx.compose.ui.text.font.FontStyle
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
import com.nuvio.app.features.music.booklet.SongStories
import com.nuvio.app.features.music.booklet.stageEditGroups
import com.nuvio.app.features.music.booklet.SongStory
import com.nuvio.app.features.music.booklet.bookletTrackNumber
import com.nuvio.app.features.music.booklet.coverStats
import com.nuvio.app.features.music.booklet.cursorStep
import com.nuvio.app.features.music.booklet.deckTurn
import com.nuvio.app.features.music.booklet.nextHiddenFact
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
    return BookletRequest(name, album.title, album.year, album.tracks.map { BookletTrack(it.title, it.durationSeconds) }, bookletArtistFrom(artist), album.wikipediaBlurb)
}

/** The booklet for the album playing: from the cache at once, else looked up (once per album) in the background. */
@Composable
internal fun rememberBookletUi(album: MusicAlbum?, artist: LibraryArtist? = null): BookletUi {
    // Settings > Music > Album notes and facts: off, there are no notes to show or look up.
    val notesOn = com.nuvio.app.features.music.library.MusicLibraryRepository.state.collectAsState().value.settings.albumNotes
    val request = remember(notesOn, album?.browseId, album?.tracks?.size, artist?.id, artist?.releases?.size, artist?.beginYear, artist?.channelMismatch) {
        if (album != null && notesOn) bookletRequestFor(album, artist) else null
    }
    // A key added or removed in Settings changes who writes the notes, so it starts a new lookup.
    val aiReady = DiscoverAiSettingsRepository.uiState.collectAsState().value.isConfigured
    // A pin saved or cleared (an edit, an import) changes what is laid over the notes, so they are read again.
    val pinVersion = com.nuvio.app.features.music.booklet.Pins.version.collectAsState().value
    // What is kept shows at once (no frame of "loading" between a pin being saved and the notes being read again).
    val kept = remember(request, aiReady, pinVersion) { request?.let { r -> AlbumBookletRepository.peek(r)?.let { BookletUi.Ready(it, r) } } }
    val ui by produceState<BookletUi>(kept ?: BookletUi.Loading, request, aiReady, pinVersion) {
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

private fun cursorCount(page: LinerPage): Int = when (page) {
    is LinerPage.Guests -> page.guests.size
    is LinerPage.Timeline -> page.timeline.entries.size
    else -> 0
}

private fun initialCursor(page: LinerPage): Int = (page as? LinerPage.Timeline)?.timeline?.currentIndex ?: 0

private fun Modifier.onTap(action: () -> Unit): Modifier = pointerInput(action) { detectTapGestures { action() } }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BookletDeck(
    booklet: AlbumBooklet,
    request: BookletRequest,
    album: MusicAlbum,
    playingVideoId: String?,
    reduced: Boolean,
    onJumpToTrack: (Int) -> Unit,
    onOpenAlbum: (String) -> Unit,
    modifier: Modifier,
    /** fork:visualiser - the Notes / Visualiser switch, at the end of the hint line. */
    switch: @Composable () -> Unit = {},
) {
    val accent = MaterialTheme.nuvio.colors.accent
    val playing = bookletTrackNumber(album.tracks.map { it.videoId }, playingVideoId)
    // fork:story - the song's own story (its Wikipedia article, written up by the AI), from the cache at once, else fetched.
    val songTitle = playing?.let { request.tracks.getOrNull(it - 1)?.title }
    // A pin saved from the Edit dialog changes the story laid over the article, so it is read again (the old story stays up meanwhile).
    val pinVersion = com.nuvio.app.features.music.booklet.Pins.version.collectAsState().value
    val keptStory = remember(request.artist, songTitle, pinVersion) { songTitle?.let { SongStories.peek(request.artist, it) } }
    val story by produceState<SongStory?>(keptStory, request.artist, songTitle, pinVersion) {
        if (songTitle == null) {
            value = null
            return@produceState
        }
        value = SongStories.peek(request.artist, songTitle)
        if (value == null) value = SongStories.load(request.artist, songTitle, backup = true)
    }
    val pages = remember(booklet, request, playing, story) { linerPages(booklet, request, playing, story) }
    var position by remember(request) { mutableStateOf(DeckPosition(0, 0)) }
    var revealed by remember(request) { mutableStateOf(emptySet<Int>()) }
    var lastPlaying by remember(request) { mutableStateOf(playing) }
    var forward by remember { mutableStateOf(true) }
    val page = position.page.coerceIn(0, pages.lastIndex)

    // A new song turns the deck to its own note, when the booklet has one.
    // The story can arrive after the song starts, so a turn that found no Track page yet waits for it.
    var turnPending by remember(request) { mutableStateOf(false) }
    LaunchedEffect(playing, pages.size) {
        if (playing != lastPlaying) {
            lastPlaying = playing
            turnPending = true
        }
        if (turnPending) {
            val at = pages.indexOfLast { it is LinerPage.Track }
            if (at >= 0) {
                turnPending = false
                forward = at >= page
                position = DeckPosition(at, 0)
            }
        }
    }

    /** Turns toward [direction]; false when the deck is already at that end, so the key can move focus on instead. */
    fun turn(direction: Int): Boolean {
        val from = DeckPosition(page, position.cursor)
        val next = deckTurn(from, pages.size, direction)
        if (next == from) return false
        forward = direction > 0
        position = DeckPosition(next.page, initialCursor(pages[next.page]))
        return true
    }

    /** Up or Down through the entries of a guest or timeline page; false elsewhere or at either end, so focus can move on. */
    fun walk(direction: Int): Boolean {
        val from = DeckPosition(page, position.cursor)
        val next = cursorStep(from, cursorCount(pages[page]), direction)
        if (next == from) return false
        position = next
        return true
    }

    fun select() {
        when (val current = pages[page]) {
            is LinerPage.Facts -> nextHiddenFact(current.startIndex, current.facts.size, revealed)?.let { revealed = revealed + it }
            is LinerPage.Guests -> current.guests.getOrNull(position.cursor)?.let { guestTrack(it, album.tracks.size) }?.let { onJumpToTrack(it - 1) }
            is LinerPage.Timeline -> current.timeline.entries.getOrNull(position.cursor)?.browseId?.let(onOpenAlbum)
            else -> Unit
        }
    }

    // The Edit dialog is a window over the stage: the deck underneath does not change while it is open.
    var editing by remember { mutableStateOf(false) }
    val editGroups = remember(pages[page], booklet, request) { stageEditGroups(pages[page], booklet, request) }
    if (editing && editGroups.isNotEmpty()) {
        StageEditDialog(
            title = when (pages[page]) {
                LinerPage.Cover -> "Edit the introduction"
                is LinerPage.Facts -> "Edit these cards"
                is LinerPage.Track -> "Edit what is said about this song"
                else -> "Edit"
            },
            groups = editGroups,
            onDismiss = { editing = false },
        )
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
                        Key.DirectionUp -> walk(-1)
                        Key.DirectionDown -> walk(1)
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
            // fork:stage - each page is shrunk as a whole until it fits the box, so nothing scrolls. A facts page is sized as if every
            // answer were showing, so revealing one never makes the page jump to a smaller size.
            FitToHeight(Modifier.fillMaxSize()) { measuring ->
                when (shown) {
                    LinerPage.Cover -> CoverPage(album, request, booklet.intro, accent)
                    is LinerPage.Facts -> FactsPage(
                        shown,
                        if (measuring) revealed + shown.facts.indices.map { shown.startIndex + it } else revealed,
                        accent,
                    ) { index -> revealed = revealed + index }
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
        LinerHintRow(
            when (pages[page]) {
                is LinerPage.Facts -> "Left and Right turn the page · Select reveals"
                is LinerPage.Guests -> "Up and Down choose · Select plays"
                is LinerPage.Timeline -> "Up and Down choose · Select opens"
                else -> "Left and Right turn the page"
            },
            switch,
            edit = { PinPill("Edit", accent, { editing = true }, enabled = editGroups.isNotEmpty()) },
        )
        Text(
            when (booklet.source) {
                BookletSource.Ai -> "Written by AI · may contain mistakes"
                BookletSource.Wikipedia -> "Text from Wikipedia, CC BY-SA 4.0"
            },
            color = Color.White.copy(alpha = 0.35f),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** The hint under the page dots, with [switch] at its end. Shared by the notes and the visualiser so the two footers match. */
@Composable
internal fun LinerHintRow(hint: String, switch: @Composable () -> Unit, edit: (@Composable () -> Unit)? = null) {
    Row(Modifier.padding(top = 6.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            hint,
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (edit != null) Box(Modifier.padding(start = 8.dp)) { edit() }
        switch()
    }
}

@Composable
internal fun NavPill(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.12f else 0.08f))
            .onTap { if (enabled) onClick() }
            .padding(horizontal = 14.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White.copy(alpha = if (enabled) 0.9f else 0.5f), fontSize = 18.sp)
    }
}

@Composable
internal fun PageLabel(text: String, accent: Color, modifier: Modifier = Modifier) {
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

/** The smallest a notes page is drawn at (of its own size). A page still too tall at this scrolls instead. */
private const val FIT_MIN_SCALE = 0.62f

/**
 * fork:stage - [content] laid out as a column and zoomed out (text, spacing and all, evenly) just enough to fit the height it is
 * given, so a page never needs scrolling. [content] is told when it is only being measured, so it can size itself for its
 * largest state. The zoom is found by halving the gap between a scale that fits and one that does not; a page that does not
 * fit even at [FIT_MIN_SCALE] is drawn at that and scrolls.
 */
@Composable
internal fun FitToHeight(modifier: Modifier = Modifier, content: @Composable (measuring: Boolean) -> Unit) {
    val base = LocalDensity.current
    SubcomposeLayout(modifier) { constraints ->
        val maxHeight = constraints.maxHeight
        val loose = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
        fun heightAt(scale: Float): Int =
            subcompose("measure:$scale") {
                CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
                    Column { content(true) }
                }
            }.maxOfOrNull { it.measure(loose).height } ?: 0
        val scale = when {
            !constraints.hasBoundedHeight || heightAt(1f) <= maxHeight -> 1f
            heightAt(FIT_MIN_SCALE) > maxHeight -> FIT_MIN_SCALE
            else -> {
                var fits = FIT_MIN_SCALE
                var tooBig = 1f
                repeat(5) {
                    val mid = (fits + tooBig) / 2f
                    if (heightAt(mid) <= maxHeight) fits = mid else tooBig = mid
                }
                fits
            }
        }
        val placeables = subcompose("shown") {
            CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content(false) }
            }
        }.map { it.measure(constraints) }
        layout(constraints.maxWidth, if (constraints.hasBoundedHeight) maxHeight else placeables.maxOfOrNull { it.height } ?: 0) {
            placeables.forEach { it.place(0, 0) }
        }
    }
}

@Composable
private fun FactsPage(page: LinerPage.Facts, revealed: Set<Int>, accent: Color, onReveal: (Int) -> Unit) {
    PageLabel("DID YOU KNOW? · ${page.number} OF ${page.total}", accent)
    val shape = RoundedCornerShape(14.dp)
    val next = nextHiddenFact(page.startIndex, page.facts.size, revealed)
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        page.facts.forEachIndexed { i, fact ->
            val index = page.startIndex + i
            val open = index in revealed
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(accent.copy(alpha = if (open) 0.14f else 0.08f))
                    .border(0.5.dp, accent.copy(alpha = if (index == next) 0.8f else 0.35f), shape)
                    .onTap { onReveal(index) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(if (fact.kind == FactKind.Artist) "ARTIST" else "ALBUM", color = accent, fontSize = 11.sp, letterSpacing = 1.2.sp)
                Text(fact.question, color = Color.White, fontFamily = FontFamily.Serif, fontSize = 18.sp, lineHeight = 23.sp)
                if (open) {
                    Text(fact.answer, color = Color.White.copy(alpha = 0.92f), fontSize = 15.sp, lineHeight = 21.sp)
                } else {
                    Text("Select to reveal", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
                }
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
    val story = page.story
    if (story == null) {
        Text(page.note, color = Color.White.copy(alpha = 0.88f), fontSize = 16.sp, lineHeight = 24.sp, modifier = Modifier.padding(top = 12.dp))
        return
    }
    // The song's story: how it was made, what it means, what came after, and the words of the people who were there.
    // The page itself scrolls (the deck's Column above), so this one must not: a scroll inside a scroll is measured without a height and crashes.
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        story.made?.let { StorySection("HOW IT WAS MADE", it, accent) }
        story.meaning?.let { StorySection("WHAT IT MEANS", it, accent) }
        story.after?.let { StorySection("SAMPLED, COVERED, REMEMBERED", it, accent) }
        story.quotes.forEach { quote ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("\u201C${quote.text}\u201D", color = Color.White.copy(alpha = 0.92f), fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 15.sp, lineHeight = 21.sp)
                if (quote.who.isNotBlank()) Text("\u2014 ${quote.who}", color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
            }
        }
        Text(
            if (story.ai) "From Wikipedia, written up by AI \u00B7 may contain mistakes" else "From Wikipedia, CC BY-SA 4.0",
            color = Color.White.copy(alpha = 0.35f),
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun StorySection(label: String, text: String, accent: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.3.sp)
        Text(text, color = Color.White.copy(alpha = 0.88f), fontSize = 14.sp, lineHeight = 20.sp)
    }
}
