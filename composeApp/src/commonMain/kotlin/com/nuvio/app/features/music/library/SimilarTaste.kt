package com.nuvio.app.features.music.library

import com.nuvio.app.core.ui.collectIsKeyboardFocusedAsState
import com.nuvio.app.core.ui.smoothBorder
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioGlass
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.MusicCover
import com.nuvio.app.features.music.defaultMusicCatalog
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/*
 * fork:music
 *
 * "Similar Taste" on the artist page: who people who listen to this artist also listen to. From
 * Last.fm's similar-artists chart when a Last.fm key is set (built from real listening, so a young
 * singer-songwriter gets her peers, not the genre's founders), else from Deezer's related artists,
 * which needs no key. One request either way, so it shows in a moment instead of waiting behind
 * MusicBrainz. Shown as round portraits under chips: All, the artist's own genres that the similar
 * artists share (from their Last.fm tags), and Library (similar artists already saved).
 */

/** One artist people also listen to: the name, how close Last.fm rates it (0..1, 0 from Deezer), and its genres. */
internal data class SimilarArtist(val name: String, val match: Double, val genres: Set<String> = emptySet())

/** How many similar artists the strip shows. */
private const val SIMILAR_LIMIT = 16

/** Similar artists from `artist.getSimilar`, closest first. Empty for an error answer. */
internal fun parseLastFmSimilar(body: String): List<SimilarArtist> {
    val root = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body) }.getOrNull() as? kotlinx.serialization.json.JsonObject
        ?: return emptyList()
    if (root["error"] != null) return emptyList()
    val list = (root["similarartists"] as? kotlinx.serialization.json.JsonObject)?.get("artist")
    val items = when (list) {
        is kotlinx.serialization.json.JsonArray -> list.mapNotNull { it as? kotlinx.serialization.json.JsonObject }
        is kotlinx.serialization.json.JsonObject -> listOf(list)
        else -> emptyList()
    }
    return items.mapNotNull { item ->
        val name = (item["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val match = (item["match"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
        SimilarArtist(name, match)
    }.distinctBy { genreKey(it.name) }
}

/**
 * The chips over the strip: the artist's own genres, title-cased, that at least one similar artist
 * also carries, most shared first. "All" and "Library" are added by the strip.
 */
internal fun similarTasteGenres(ownGenres: List<String>, similar: List<SimilarArtist>, limit: Int = 5): List<String> =
    ownGenres.map { genre ->
        genre to similar.count { other -> other.genres.any { sameGenre(it, genre) || genreKey(it) == genreKey(genre) } }
    }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }.distinctBy { genreKey(it) }.take(limit)

/** The session's similar-artist lists, by artist name, with their genres filled in as they arrive. */
internal object SimilarTaste {
    private val lock = Mutex()
    private val lists = HashMap<String, List<SimilarArtist>>()

    private val withGenres = HashMap<String, List<SimilarArtist>>()

    /** Who listeners of [name] also play, closest first, without genres yet; null when neither source could be reached. */
    suspend fun forArtist(name: String): List<SimilarArtist>? {
        val key = genreKey(name)
        lock.withLock { withGenres[key] ?: lists[key] }?.let { return it }
        val found = if (LastFmClient.isConfigured) {
            LastFmClient.similarArtists(name, SIMILAR_LIMIT)?.takeIf { it.isNotEmpty() }
        } else {
            null
        } ?: DeezerClient.relatedByName(name)?.map { SimilarArtist(it.name, 0.0) }
        val list = found?.filter { !sameArtistName(it.name, name) }?.take(SIMILAR_LIMIT) ?: return null
        lock.withLock { lists[key] = list }
        return list
    }

    /**
     * [list] (the answer of [forArtist] for [name]) with each artist's genres, for the chips: Last.fm tags cut down to
     * genres, one paced call each, so this comes a few seconds after the portraits. Without a key it is [list] as it is.
     */
    suspend fun withGenres(name: String, list: List<SimilarArtist>): List<SimilarArtist> {
        if (!LastFmClient.isConfigured || list.isEmpty()) return list
        val key = genreKey(name)
        lock.withLock { withGenres[key] }?.let { return it }
        val filled = coroutineScope {
            list.map { artist -> async { artist.copy(genres = LastFmClient.artistTags(artist.name)?.tags?.keys.orEmpty()) } }.awaitAll()
        }
        lock.withLock { withGenres[key] = filled }
        return filled
    }
}

@Composable
internal fun SimilarTasteStrip(artist: LibraryArtist, onOpenArtist: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.nuvio.colors
    val scope = rememberCoroutineScope()
    val catalog = remember { defaultMusicCatalog() }
    // The portraits as soon as the names are in; the genre chips once their tags follow.
    val similar by produceState<List<SimilarArtist>?>(null, artist.name) {
        val names = SimilarTaste.forArtist(artist.name) ?: emptyList()
        value = names
        value = SimilarTaste.withGenres(artist.name, names)
    }
    val libraryState by MusicLibraryRepository.state.collectAsStateWithLifecycle()
    val library = remember(artist.id, libraryState.artists.size) { similarInLibrary(artist, libraryState.artists) }
    val genres = remember(similar, artist.genres) { similarTasteGenres(passportChips(artist).second, similar.orEmpty()) }
    var chip by rememberSaveable(artist.id) { mutableStateOf(CHIP_ALL) }
    // Names being looked up on YouTube Music after a click, and the ones that turned out not to be there.
    val opening = remember(artist.id) { mutableStateMapOf<String, Boolean>() }
    var note by remember(artist.id) { mutableStateOf<String?>(null) }

    if (similar?.isEmpty() == true && library.isEmpty()) return

    // Five portraits wide; the rest are a scroll (or an arrow press) away.
    val rowState = rememberLazyListState()
    LaunchedEffect(chip) { rowState.scrollToItem(0) }
    Column(modifier.width(STRIP_WIDTH + STRIP_ARROW * 2), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Similar Taste", style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
        LazyRow(Modifier.width(STRIP_WIDTH + STRIP_ARROW * 2), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val chips = listOf(CHIP_ALL) + genres + if (library.isNotEmpty()) listOf(CHIP_LIBRARY) else emptyList()
            items(chips, key = { it }) { label ->
                ActionPill(label.titleCase(), selected = chip == label, onClick = { chip = label })
            }
        }
        val shownLibrary = chip == CHIP_LIBRARY
        Row(verticalAlignment = Alignment.CenterVertically) {
        StripArrow(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous", enabled = rowState.canScrollBackward) {
            scope.launch { rowState.animateScrollToItem((rowState.firstVisibleItemIndex - STRIP_ITEMS).coerceAtLeast(0)) }
        }
        LazyRow(
            Modifier.width(STRIP_WIDTH),
            state = rowState,
            horizontalArrangement = Arrangement.spacedBy(STRIP_GAP),
            contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
        ) {
            if (shownLibrary) {
                items(library, key = { "lib:${it.id}" }) { other ->
                    TastePortrait(name = other.name, picture = other.art.thumb, busy = false) { onOpenArtist(other.id) }
                }
            } else {
                val list = similar
                if (list == null) {
                    items(PLACEHOLDERS) { TastePortraitPlaceholder() }
                } else {
                    val filtered = if (chip == CHIP_ALL) list else list.filter { other ->
                        other.genres.any { sameGenre(it, chip) || genreKey(it) == genreKey(chip) }
                    }
                    items(filtered, key = { "sim:${it.name}" }) { other ->
                        val picture by produceState<String?>(null, other.name) { value = LadderArtwork.picture(other.name) }
                        TastePortrait(name = other.name, picture = picture, busy = opening[other.name] == true) {
                            if (opening[other.name] == true) return@TastePortrait
                            opening[other.name] = true
                            scope.launch {
                                val found = rungArtist(catalog, other.name)
                                opening.remove(other.name)
                                if (found != null) onOpenArtist(found.browseId) else note = "${other.name} isn't on YouTube Music"
                            }
                        }
                    }
                }
            }
        }
        StripArrow(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next", enabled = rowState.canScrollForward) {
            scope.launch { rowState.animateScrollToItem(rowState.firstVisibleItemIndex + STRIP_ITEMS) }
        }
        }
        note?.let {
            LaunchedEffect(it) {
                kotlinx.coroutines.delay(3_000)
                note = null
            }
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        }
    }
}

private const val CHIP_ALL = "All"
private const val CHIP_LIBRARY = "Library"
private const val PLACEHOLDERS = 8
private val PORTRAIT = 120.dp
private val PORTRAIT_WIDTH = 140.dp

/** The portrait row shows this many at a time. */
private const val STRIP_ITEMS = 5
private val STRIP_GAP = 18.dp
private val STRIP_WIDTH = PORTRAIT_WIDTH * STRIP_ITEMS + STRIP_GAP * (STRIP_ITEMS - 1) + 8.dp
private val STRIP_ARROW = 40.dp

/** A round arrow beside the portrait row; it fades out at the end it points to. */
@Composable
private fun StripArrow(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    Box(
        Modifier
            .size(STRIP_ARROW)
            .alpha(if (enabled) 1f else 0.25f)
            .clip(CircleShape)
            .musicKeyFocusRing(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = colors.textSecondary, modifier = Modifier.size(28.dp))
    }
}

private fun String.titleCase(): String = split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }

/** A round portrait with the name under it; a spinner over it while its page is being found. */
@Composable
private fun TastePortrait(name: String, picture: String?, busy: Boolean, onOpen: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsKeyboardFocusedAsState()
    Column(
        Modifier
            .width(PORTRAIT_WIDTH)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onOpen),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.smoothBorder(2.dp, if (hovered || focused) colors.accent else NuvioGlass.EdgeColor, CircleShape).padding(3.dp),
            contentAlignment = Alignment.Center,
        ) {
            MusicCover(picture, name, size = PORTRAIT, circle = true)
            if (busy) {
                Box(Modifier.matchParentSize().clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = colors.accent, strokeWidth = 2.dp)
                }
            }
        }
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (hovered || focused) colors.textPrimary else colors.textSecondary,
            textAlign = TextAlign.Center,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TastePortraitPlaceholder() {
    Box(Modifier.width(PORTRAIT_WIDTH), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.padding(3.dp).size(PORTRAIT).clip(CircleShape).musicGlass(CircleShape))
    }
}
