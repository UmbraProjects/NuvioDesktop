@file:OptIn(ExperimentalMaterial3Api::class)

package com.spydermahn.nuvioremote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.Normalizer
import kotlinx.coroutines.delay

/** Lower-case, accents and "the " gone, so "Beyoncé" finds "beyonce" and "the beatles" sorts under B. */
private fun searchKey(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase().trim()

private fun matches(query: String, vararg fields: String?): Boolean {
    val words = searchKey(query).split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return true
    val haystack = fields.filterNotNull().joinToString(" ") { searchKey(it) }
    return words.all { it in haystack }
}

// ---------------------------------------------------------------- Shared pieces

/**
 * The top of a library tab, in Nuvio's glass style: an accent label naming where it comes from, the title, and a glass
 * search box. [kicker] is the label ("Your library on NIMILY").
 */
@Composable
private fun LibraryHeader(
    title: String,
    kicker: String,
    query: String,
    onQuery: (String) -> Unit,
    placeholder: String,
    loading: Boolean,
    onRefresh: () -> Unit,
    /** Voice search: a mic in the search box while it is empty. */
    onMic: (() -> Unit)? = null,
) {
    val g = LocalGlass.current
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GlassLabel(kicker)
                Text(title, color = g.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, contentDescription = "Refresh", tint = g.textSecondary) }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(48.dp).glass(RoundedCornerShape(24.dp)).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = g.textSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text(placeholder, color = g.textMuted, fontSize = 15.sp)
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = TextStyle(color = g.text, fontSize = 15.sp, fontFamily = NuvioSans),
                    cursorBrush = SolidColor(g.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, contentDescription = "Clear", tint = g.textSecondary) }
            } else if (onMic != null) {
                IconButton(onClick = onMic) { Icon(Icons.Rounded.Mic, contentDescription = "Search by voice", tint = g.accent) }
            }
        }
    }
}

/** A row of glass pills to narrow a list; scrolls when there are many (a library's sections). */
@Composable
private fun Chips(labels: List<String>, selected: String, onSelect: (String) -> Unit) {
    val g = LocalGlass.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(labels, key = { it }) { label ->
            val on = label == selected
            Box(
                Modifier
                    .height(36.dp)
                    .glass(RoundedCornerShape(18.dp), fill = if (on) g.tint else g.glassFill, edge = if (on) g.tintEdge else g.glassEdge)
                    .clickable { onSelect(label) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) g.accent else Color(0xFFC9CDD2), fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

/** A section label and its count, between the parts of a list ("CONTINUE WATCHING · 3"). */
@Composable
private fun SectionRow(label: String, count: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GlassLabel(label, Modifier.weight(1f))
        if (count != null) GlassTag(count)
    }
}

/** Nothing to show: still loading, the PC could not answer, or the library is simply empty. */
@Composable
private fun EmptyLibrary(loading: Boolean, problem: String?, empty: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when {
            loading -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Asking the PC for its library…", color = Muted)
            }
            problem != null -> {
                Text(problem, color = Muted, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onRetry) { Text("Try again") }
            }
            else -> Text(empty, color = Muted, textAlign = TextAlign.Center)
        }
    }
}

// ---------------------------------------------------------------- Movies & TV

private const val ALL = "All"
private const val UNWATCHED = "Unwatched"

/**
 * The PC's Library (saved titles, its Trakt or Simkl lists, local folders), as posters. Choosing one opens its details
 * on the TV, where the remote picks a source.
 */
@Composable
fun VideoLibraryScreen(state: UiState, viewModel: RemoteViewModel, onOpened: () -> Unit) {
    LaunchedEffect(state.link) {
        if (state.link == Link.Connected) {
            viewModel.loadVideo()
            viewModel.loadContinue()
        }
    }
    val library = state.video
    var resumePicked by remember { mutableStateOf<ResumeItem?>(null) }
    // A show opens its season list here (episodes play on the TV from it); a film opens its page on the TV.
    var showPicked by remember { mutableStateOf<LibraryTitle?>(null) }
    val pick: (LibraryTitle) -> Unit = { title ->
        if (title.isShow) {
            showPicked = title
        } else {
            viewModel.openTitle(title)
            onOpened()
        }
    }
    // A long press on a poster: the TV's poster menu (watched, library, playlist...), on the phone.
    val holdTitle: (LibraryTitle) -> Unit = { title ->
        viewModel.openMenu(
            MenuTarget.title(title), title.name, listOfNotNull(if (title.isShow) "Show" else "Film", title.year).joinToString(" · "),
            image = title.poster, imageFallback = title.posterFallback,
        )
    }
    var query by rememberSaveable { mutableStateOf("") }
    var section by rememberSaveable { mutableStateOf(ALL) }

    val sections = remember(library.titles) { library.titles.map { it.section }.distinct() }
    val chips = remember(sections, library.titles) {
        buildList {
            add(ALL)
            addAll(sections)
            if (library.titles.any { it.watched }) add(UNWATCHED)
        }
    }
    // A section that went away (the library changed) shows everything rather than nothing.
    val activeSection = if (section in chips) section else ALL
    val shown = remember(library.titles, query, activeSection) {
        library.titles.filter { title ->
            when (activeSection) {
                ALL -> true
                UNWATCHED -> !title.watched
                else -> title.section == section
            } && matches(query, title.name, title.year)
        }
    }

    Column(Modifier.fillMaxSize()) {
        val voice = rememberVoiceInput("Search Nuvio", onText = { query = it })
        LibraryHeader(
            "Watch",
            "Your library on ${state.current?.name ?: "the PC"}",
            query,
            { query = it },
            "Search your library and addons",
            library.loading,
            onRefresh = { viewModel.loadVideo(force = true) },
            onMic = voice,
        )
        if (chips.size > 2) Chips(chips, activeSection) { section = it }
        // Continue Watching leads the tab, above the library, unless a search or a section is narrowing it.
        val resumes = if (activeSection == ALL && query.isBlank()) state.continueWatching else emptyList()
        // A search also asks Nuvio's catalogs on the PC (what its Search tab finds), once typing pauses.
        LaunchedEffect(query) {
            delay(SEARCH_PAUSE_MS)
            viewModel.searchEverywhere(query)
        }
        val search = state.search?.takeIf { query.isNotBlank() && it.query.equals(query.trim(), ignoreCase = true) }
        val inLibrary = remember(shown) { shown.map { it.key }.toSet() }
        val found = remember(search, inLibrary) { search?.titles.orEmpty().filter { it.key !in inLibrary } }
        if (shown.isEmpty() && resumes.isEmpty() && search == null) {
            EmptyLibrary(
                loading = library.loading && library.titles.isEmpty(),
                problem = library.problem,
                empty = if (library.titles.isEmpty()) "Nothing in the library on the PC yet. Save films and shows there and they show up here."
                else "Nothing matches.",
                onRetry = { viewModel.loadVideo(force = true) },
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 108.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (resumes.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "continue") {
                        ContinueRail(resumes, onHold = { item ->
                            viewModel.openMenu(
                                MenuTarget.resume(item), item.title, item.episode ?: "Continue watching",
                                image = item.still ?: item.poster, imageFallback = item.poster, wide = true,
                            )
                        }) { resumePicked = it }
                    }
                }
                if (shown.isNotEmpty() || search == null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SectionRow(if (activeSection == ALL) "Your library" else activeSection, countLabel(shown.size, "title"))
                    }
                }
                items(shown, key = { it.key }) { title ->
                    PosterCell(title, onHold = { holdTitle(title) }) { pick(title) }
                }
                if (search != null) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "search-header") {
                        Column(Modifier.padding(top = if (shown.isEmpty()) 0.dp else 12.dp)) {
                            SectionRow(
                                if (search.loading) "From your addons · searching…" else "From your addons",
                                if (search.loading && found.isEmpty()) null else countLabel(found.size, "title"),
                            )
                            if (!search.loading && found.isEmpty()) {
                                Text("Nothing more found for \"${search.query}\".", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                    items(found, key = { "search-" + it.key }) { title ->
                        PosterCell(title) { pick(title) }
                    }
                }
            }
        }
    }

    showPicked?.let { show ->
        LaunchedEffect(show.key) { viewModel.loadEpisodes(show) }
        ShowSheet(
            show = show,
            episodes = state.episodes?.takeIf { it.id == show.id },
            onEpisode = { episode ->
                showPicked = null
                viewModel.playEpisode(show, episode)
                onOpened()
            },
            onEpisodeHold = { episode ->
                viewModel.openMenu(
                    MenuTarget.episode(show, episode),
                    listOfNotNull(episode.season?.let { s -> episode.episode?.let { "S$s E$it" } }, episode.title.takeIf { it.isNotBlank() })
                        .joinToString(" · ").ifEmpty { "Episode" },
                    show.name,
                    image = episode.thumbnail, imageFallback = show.poster, wide = true,
                )
            },
            onSeasonHold = { season ->
                viewModel.openMenu(
                    MenuTarget.season(show, season), if (season == 0) "Specials" else "Season $season", show.name,
                    image = show.poster, imageFallback = show.posterFallback,
                )
            },
            onDetails = {
                showPicked = null
                viewModel.openTitle(show)
                onOpened()
            },
            onDismiss = { showPicked = null },
        )
    }

    resumePicked?.let { item ->
        ResumeSheet(
            item = item,
            onDismiss = { resumePicked = null },
            onResume = {
                resumePicked = null
                viewModel.resume(item)
                onOpened()
            },
            onDetails = {
                resumePicked = null
                viewModel.openDetails(item)
                onOpened()
            },
        )
    }

    // The long-press sheets, over whatever is open (the show sheet included).
    state.menu?.let { sheet ->
        ActionSheet(
            sheet = sheet,
            onAction = { action ->
                viewModel.menuAction(action)
                if (action.code in MenuCode.OPENS_ON_TV) {
                    showPicked = null
                    onOpened()
                }
            },
            onDismiss = viewModel::closeMenu,
        )
    }
    state.playlistPick?.let { pick ->
        PlaylistSheet(pick, onPick = { playlist, season -> viewModel.addToPlaylist(playlist, season) }, onDismiss = viewModel::closePlaylists)
    }
    state.recap?.let { recap -> RecapSheet(recap, onDismiss = viewModel::closeRecap) }
}

/**
 * A show's seasons and episodes, from the TV's details: pick a season, tap an episode and the TV plays it (its source
 * list comes up on the remote), as clicking the episode on the TV's details page does.
 */
@Composable
private fun ShowSheet(
    show: LibraryTitle,
    episodes: ServerLine.Episodes?,
    onEpisode: (EpisodeInfo) -> Unit,
    onEpisodeHold: (EpisodeInfo) -> Unit,
    onSeasonHold: (Int) -> Unit,
    onDetails: () -> Unit,
    onDismiss: () -> Unit,
) {
    val g = LocalGlass.current
    val seasons = remember(episodes) {
        episodes?.episodes.orEmpty().map { it.season ?: 1 }.distinct().sortedWith(compareBy({ it == 0 }, { it }))
    }
    // Opens on the first season with an episode not yet watched through, as the TV's details page does.
    var season by remember(episodes) {
        mutableStateOf(
            episodes?.episodes.orEmpty().firstOrNull { (it.season ?: 1) != 0 && it.progress < 0.95f }?.season
                ?: seasons.firstOrNull { it != 0 } ?: seasons.firstOrNull() ?: 1,
        )
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF14151A),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RemoteImage(show.poster, show.posterFallback, show.name, Modifier.size(width = 64.dp, height = 96.dp).clip(RoundedCornerShape(10.dp)))
                Spacer(Modifier.size(16.dp))
                Column(Modifier.weight(1f)) {
                    GlassLabel(listOfNotNull("Show", show.year).joinToString(" · "))
                    DisplayTitle(show.name, size = 22, maxLines = 2)
                }
            }
            Spacer(Modifier.height(14.dp))
            ActionButton("Details on the TV", Icons.Rounded.Info, modifier = Modifier.fillMaxWidth(), onClick = onDetails)
            when {
                episodes == null -> Row(Modifier.padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                    Text("Asking the TV for the episodes…", color = g.textSecondary)
                }
                !episodes.found || episodes.episodes.isEmpty() -> Text(
                    "The TV couldn't list this show's episodes. Details on the TV opens it there.",
                    color = g.textSecondary,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
                else -> {
                    if (seasons.size > 1) {
                        LazyRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(seasons) { s ->
                                GlassTag(
                                    if (s == 0) "Specials" else "Season $s",
                                    accent = s == season,
                                    onLongClick = { onSeasonHold(s) },
                                    onClick = { season = s },
                                )
                            }
                        }
                    }
                    Text(
                        if (seasons.size > 1) "Hold an episode or a season for more: watched, playlist, recap."
                        else "Hold an episode for more: watched, playlist, recap.",
                        color = g.textMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp),
                    )
                    val shown = episodes.episodes.filter { (it.season ?: 1) == season }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(shown, key = { it.videoId }) { episode ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .tapOrHold(onHold = { onEpisodeHold(episode) }) { onEpisode(episode) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(width = 112.dp, height = 63.dp).clip(RoundedCornerShape(8.dp))) {
                                    RemoteImage(episode.thumbnail, show.poster, "E${episode.episode ?: ""}", Modifier.fillMaxSize())
                                    if (episode.progress > 0f) {
                                        ProgressLine(episode.progress, Modifier.align(Alignment.BottomCenter).padding(horizontal = 6.dp, vertical = 5.dp))
                                    }
                                }
                                Spacer(Modifier.size(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        listOfNotNull(episode.episode?.let { "E$it" }, episode.title.takeIf { it.isNotBlank() }).joinToString(" · "),
                                        color = if (episode.progress >= 0.95f) g.textMuted else g.text,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val note = when {
                                        episode.progress >= 0.95f -> "Watched"
                                        episode.progress > 0f -> "${(episode.progress * 100).toInt()}% watched"
                                        else -> episode.released
                                    }
                                    note?.let { Text(it, color = g.textSecondary, fontSize = 12.sp, maxLines = 1) }
                                }
                                if (episode.progress >= 0.95f) {
                                    Icon(Icons.Rounded.CheckCircle, contentDescription = "Watched", tint = Color(0xFF6FD08C), modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Continue Watching: landscape cards with the progress under each, newest first. */
@Composable
private fun ContinueRail(resumes: List<ResumeItem>, onHold: (ResumeItem) -> Unit, onPick: (ResumeItem) -> Unit) {
    val g = LocalGlass.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionRow("Continue watching", countLabel(resumes.size, "title"))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(resumes, key = { it.key }) { item ->
                Column(Modifier.size(width = 220.dp, height = 176.dp).clip(RoundedCornerShape(12.dp)).tapOrHold(onHold = { onHold(item) }) { onPick(item) }) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                        RemoteImage(
                            url = item.still ?: item.poster,
                            fallback = item.poster,
                            placeholder = item.title,
                            modifier = Modifier.fillMaxSize().glass(RoundedCornerShape(12.dp), fill = Color.Transparent),
                        )
                        ProgressLine(item.progress, Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp, vertical = 8.dp))
                    }
                    Text(item.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    Text(
                        listOfNotNull(item.episode, item.timeLeft).joinToString(" · ").ifEmpty { "${(item.progress * 100).toInt()}% watched" },
                        fontSize = 12.sp,
                        color = g.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** A thin bar: how far through, in the accent, over a faint track. */
@Composable
private fun ProgressLine(progress: Float, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    Box(modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.25f))) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0.02f, 1f)).height(4.dp).background(g.accent))
    }
}

/** One Continue Watching title: Resume on the TV (the same source, from where it stopped), or its details. */
@Composable
private fun ResumeSheet(item: ResumeItem, onDismiss: () -> Unit, onResume: () -> Unit, onDetails: () -> Unit) {
    val g = LocalGlass.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF14151A),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RemoteImage(
                    url = item.poster ?: item.still,
                    fallback = item.still,
                    placeholder = item.title,
                    modifier = Modifier.size(width = 72.dp, height = 108.dp).clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.size(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    GlassLabel(if (item.episode != null) "Continue the show" else "Continue the film")
                    DisplayTitle(item.title, size = 22, maxLines = 2)
                    item.episode?.let { Text(it, color = g.accent, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            Spacer(Modifier.height(16.dp))
            ProgressLine(item.progress)
            Spacer(Modifier.height(6.dp))
            Text(
                listOfNotNull(
                    if (item.durationMs > 0L) "${formatClock(item.positionMs)} of ${formatClock(item.durationMs)}" else "${(item.progress * 100).toInt()}% watched",
                    item.timeLeft,
                ).joinToString(" · "),
                color = g.textSecondary,
                fontSize = 13.sp,
            )
            item.lastSource?.let {
                Text("Last played from $it", color = g.textMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(20.dp))
            ActionButton("Resume on the TV", Icons.Rounded.PlayArrow, primary = true, modifier = Modifier.fillMaxWidth(), onClick = onResume)
            Spacer(Modifier.height(10.dp))
            ActionButton("Details on the TV", Icons.Rounded.Info, modifier = Modifier.fillMaxWidth(), onClick = onDetails)
        }
    }
}

private fun formatClock(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@Composable
private fun PosterCell(title: LibraryTitle, onHold: (() -> Unit)? = null, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(10.dp)).tapOrHold(onHold = onHold, onTap = onClick)) {
        Box {
            RemoteImage(
                url = title.poster,
                fallback = title.posterFallback,
                placeholder = title.name,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).glass(RoundedCornerShape(12.dp), fill = Color.Transparent),
            )
            if (title.watched) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = "Watched",
                    tint = Color(0xFF6FD08C),
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).clip(CircleShape).background(Color(0xCC0E0F14)),
                )
            }
        }
        Text(title.name, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        title.year?.let { Text(it, fontSize = 12.sp, color = Muted, maxLines = 1) }
    }
}

/** How long typing has to pause before the catalogs on the PC are searched. */
private const val SEARCH_PAUSE_MS = 600L

private fun countLabel(count: Int, noun: String) = if (count == 1) "1 $noun" else "$count ${noun}s"

// ---------------------------------------------------------------- Music

private const val ALBUMS = "Albums"
private const val ARTISTS = "Artists"
private const val STARRED = "Starred"

/**
 * The PC's music library: every album of every artist in it, or the artists A to Z. An album plays, shuffles or joins
 * the queue on the TV; an artist plays as a mix of their albums or as YouTube Music's radio.
 */
@Composable
fun MusicLibraryScreen(state: UiState, viewModel: RemoteViewModel, onPlayed: () -> Unit) {
    LaunchedEffect(state.link) { if (state.link == Link.Connected) viewModel.loadMusic() }
    val library = state.music
    var query by rememberSaveable { mutableStateOf("") }
    var view by rememberSaveable { mutableStateOf(ALBUMS) }
    var kind by rememberSaveable { mutableStateOf(ALL) }
    var openArtistId by rememberSaveable { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<LibraryAlbum?>(null) }

    val artistsById = remember(library.artists) { library.artists.associateBy { it.id } }
    val openArtist = openArtistId?.let(artistsById::get)

    if (openArtist != null) {
        BackHandler { openArtistId = null }
        ArtistPage(
            artist = openArtist,
            albums = library.albums.filter { it.artistId == openArtist.id },
            onBack = { openArtistId = null },
            onAlbum = { picked = it },
            onAction = { action ->
                viewModel.artist(openArtist, action)
                onPlayed()
            },
        )
    } else {
        Column(Modifier.fillMaxSize()) {
            val voice = rememberVoiceInput("Search your music", onText = { query = it })
            LibraryHeader(
                "Music",
                "Your music on ${state.current?.name ?: "the PC"}",
                query,
                { query = it },
                "Search artists and albums",
                library.loading,
                onRefresh = { viewModel.loadMusic(force = true) },
                onMic = voice,
            )
            PillTabs(
                items = listOf(ALBUMS, ARTISTS),
                selected = view,
                label = { it },
                onSelect = { view = it },
                height = 38.dp,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
            )
            when {
                library.albums.isEmpty() && library.artists.isEmpty() -> EmptyLibrary(
                    loading = library.loading,
                    problem = library.problem,
                    empty = "No artists in the music library on the PC yet. Add some from the Music tab there.",
                    onRetry = { viewModel.loadMusic(force = true) },
                )
                view == ARTISTS -> ArtistList(
                    artists = library.artists.filter { matches(query, it.name) },
                    albumCounts = remember(library.albums) { library.albums.groupingBy { it.artistId }.eachCount() },
                    onOpen = { openArtistId = it.id },
                )
                else -> {
                    val kinds = remember(library.albums) {
                        buildList {
                            add(ALL)
                            addAll(library.albums.map { it.kind }.distinct().sortedBy(::kindOrder))
                            if (library.albums.any { it.starred }) add(STARRED)
                        }
                    }
                    val activeKind = if (kind in kinds) kind else ALL
                    val shown = library.albums.filter { album ->
                        when (activeKind) {
                            ALL -> true
                            STARRED -> album.starred
                            else -> album.kind == kind
                        } && matches(query, album.title, artistsById[album.artistId]?.name, album.year)
                    }
                    if (kinds.size > 2) Chips(kinds, activeKind) { kind = it }
                    AlbumGrid(shown, artistsById, header = if (activeKind == ALL) "All albums" else activeKind, count = countLabel(shown.size, "album")) { picked = it }
                }
            }
        }
    }

    picked?.let { album ->
        LaunchedEffect(album.browseId) { viewModel.loadAlbumTracks(album) }
        AlbumSheet(
            album = album,
            artist = artistsById[album.artistId],
            tracks = state.albumTracks?.takeIf { it.browseId == album.browseId },
            onTrack = { index ->
                viewModel.playTrack(album, index)
                picked = null
                onPlayed()
            },
            onDismiss = { picked = null },
            onAction = { action ->
                viewModel.album(album, action)
                picked = null
                if (action != AlbumAction.Queue) onPlayed()
            },
            onArtist = { artist ->
                picked = null
                openArtistId = artist.id
            },
        )
    }
}

private fun kindOrder(kind: String): Int = when (kind) {
    "Album" -> 0
    "EP" -> 1
    "Single" -> 2
    "Live" -> 3
    "Compilation" -> 4
    else -> 5
}

@Composable
private fun AlbumGrid(
    albums: List<LibraryAlbum>,
    artistsById: Map<String, LibraryArtistInfo>,
    header: String?,
    count: String? = null,
    showArtist: Boolean = true,
    top: (@Composable () -> Unit)? = null,
    onClick: (LibraryAlbum) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (top != null) item(span = { GridItemSpan(maxLineSpan) }) { top() }
        if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { SectionRow(header, count) }
        items(albums, key = { it.artistId + "|" + it.browseId }) { album ->
            Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable { onClick(album) }) {
                Box {
                    RemoteImage(
                        url = album.cover,
                        fallback = null,
                        placeholder = album.title,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)),
                    )
                    if (album.starred) {
                        Icon(
                            Icons.Rounded.Star,
                            contentDescription = "Starred",
                            tint = Color(0xFFF2C14E),
                            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp),
                        )
                    }
                }
                Text(album.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                val line = listOfNotNull(
                    if (showArtist) artistsById[album.artistId]?.name else null,
                    album.year,
                    album.kind.takeIf { it != "Album" },
                ).joinToString(" · ")
                if (line.isNotEmpty()) Text(line, fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ArtistList(artists: List<LibraryArtistInfo>, albumCounts: Map<String, Int>, onOpen: (LibraryArtistInfo) -> Unit) {
    if (artists.isEmpty()) {
        EmptyLibrary(loading = false, problem = null, empty = "No artists match.", onRetry = {})
        return
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
        items(artists, key = { it.id }) { artist ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(artist) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RemoteImage(
                    url = artist.photo,
                    fallback = null,
                    placeholder = artist.name.take(1),
                    modifier = Modifier.size(52.dp).clip(CircleShape),
                )
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(artist.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(countLabel(albumCounts[artist.id] ?: 0, "release"), fontSize = 13.sp, color = Muted)
                }
                if (artist.starred) Icon(Icons.Rounded.Star, contentDescription = "Starred", tint = Color(0xFFF2C14E), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ArtistPage(
    artist: LibraryArtistInfo,
    albums: List<LibraryAlbum>,
    onBack: () -> Unit,
    onAlbum: (LibraryAlbum) -> Unit,
    onAction: (ArtistAction) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            DisplayTitle(artist.name, size = 24, maxLines = 1)
        }
        AlbumGrid(
            albums = albums.sortedWith(compareBy<LibraryAlbum> { kindOrder(it.kind) }.thenByDescending { it.year ?: "" }),
            artistsById = emptyMap(),
            header = "Discography",
            count = countLabel(albums.size, "release"),
            showArtist = false,
            top = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RemoteImage(
                        url = artist.photo,
                        fallback = null,
                        placeholder = artist.name.take(1),
                        modifier = Modifier.size(88.dp).clip(CircleShape),
                    )
                    Spacer(Modifier.size(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton("Shuffle all", Icons.Rounded.Shuffle, primary = true) { onAction(ArtistAction.Mix) }
                        ActionButton("Radio", Icons.Rounded.Radio) { onAction(ArtistAction.Radio) }
                    }
                }
            },
            onClick = onAlbum,
        )
    }
}

@Composable
private fun ActionButton(label: String, icon: ImageVector, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // Nuvio's glass buttons: the main one tinted in the accent (as "Play the song" on the TV), the others plain glass.
    val g = LocalGlass.current
    Row(
        modifier
            .height(if (primary) 52.dp else 46.dp)
            .glass(RoundedCornerShape(26.dp), fill = if (primary) g.tint else g.glassFill, edge = if (primary) g.tintEdge else g.glassEdge)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = g.text, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(label, color = g.text, fontSize = if (primary) 16.sp else 14.sp, fontWeight = if (primary) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun AlbumSheet(
    album: LibraryAlbum,
    artist: LibraryArtistInfo?,
    /** The album's songs, once the PC has sent them; null while asking. */
    tracks: ServerLine.AlbumTracks?,
    onTrack: (index: Int) -> Unit,
    onDismiss: () -> Unit,
    onAction: (AlbumAction) -> Unit,
    onArtist: (LibraryArtistInfo) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF14151A),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RemoteImage(
                    url = album.cover,
                    fallback = null,
                    placeholder = album.title,
                    modifier = Modifier.size(96.dp).clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.size(16.dp))
                Column(Modifier.weight(1f)) {
                    GlassLabel(listOfNotNull(album.kind, album.year).joinToString(" · "))
                    DisplayTitle(album.title, size = 22, maxLines = 2)
                    artist?.let { Text(it.name, color = LocalGlass.current.accent, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            Spacer(Modifier.height(20.dp))
            ActionButton("Play on the TV", Icons.Rounded.PlayArrow, primary = true, modifier = Modifier.fillMaxWidth()) { onAction(AlbumAction.Play) }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("Shuffle", Icons.Rounded.Shuffle, modifier = Modifier.weight(1f)) { onAction(AlbumAction.Shuffle) }
                ActionButton("Add to queue", Icons.AutoMirrored.Rounded.QueueMusic, modifier = Modifier.weight(1f)) { onAction(AlbumAction.Queue) }
            }
            if (artist != null) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { onArtist(artist) }) { Text("More by ${artist.name}") }
            }
            // The songs: tap one to play the album from it, as clicking a song on the album page does on the TV.
            Spacer(Modifier.height(8.dp))
            GlassLabel(if (tracks == null) "Songs · asking the TV…" else "Songs · ${tracks.tracks.size}")
            val g = LocalGlass.current
            if (tracks != null && tracks.tracks.isEmpty()) {
                Text("The TV couldn't fetch this album's songs.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).padding(top = 8.dp)) {
                items(tracks?.tracks.orEmpty().size) { index ->
                    val track = tracks!!.tracks[index]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = track.upcoming) { onTrack(index) }
                            .padding(horizontal = 6.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${index + 1}", color = g.textMuted, fontSize = 13.sp, modifier = Modifier.width(28.dp))
                        Text(
                            track.title,
                            color = if (track.upcoming) g.text else g.textMuted,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (track.seconds > 0) Text(formatTime(track.seconds * 1000L), color = g.textSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
